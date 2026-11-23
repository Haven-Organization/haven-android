/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** How the feed gets a room's posts. */
sealed interface FeedRoom {
    /** Loaded from the server's raw events (posts newest first, reply counts not applied yet). */
    data class Loaded(val state: RoomFeedState) : FeedRoom

    /** Needs the SDK's timeline: the room is encrypted, or the server has no sliding sync. */
    data object NeedsTimeline : FeedRoom
}

/**
 * The feed's posts straight from the server, without an SDK timeline per room: the latest posts of every feed room
 * come in one simplified sliding sync request (MSC4186, on a connection of its own), which then long-polls for new
 * ones while the feed is shown, and older posts are loaded with `/messages`.
 *
 * An SDK timeline keeps all of a room's loaded events mapped in memory and processes every update for as long as it's
 * open; with the feed's many rooms that added up until the app ran out of memory. Here only the events themselves are
 * kept, and edits, reactions (MSC4501 likes and reposts), redactions and senders' names are applied to them directly.
 *
 * Encrypted rooms still go through the SDK ([FeedRoom.NeedsTimeline]), which decrypts them.
 */
@SingleIn(SessionScope::class)
@Inject
class FeedSync(
    private val http: SocialHttpApi,
    private val matrixClient: MatrixClient,
) {
    private class Member(val name: String?, val avatarUrl: String?)

    private class RoomData {
        /** Oldest first. */
        var events: List<JSONObject> = emptyList()
        val eventIds = HashSet<String>()

        // Edits (latest per post) and deletions seen live, kept even when the post isn't among [events] (e.g. a post
        // the feed shows from its cache, older than what's loaded): they still apply to it.
        val liveEdits = HashMap<String, JSONObject>()
        val redactedIds = HashSet<String>()
        var prevBatch: String? = null
        var reachedStart = false
        var encrypted = false
        val members = HashMap<String, Member>()
        val parsed = HashMap<String, ParsedPost>()
    }

    private val mutex = Mutex()

    // One sync request at a time: two with the same `pos` would fork the server's view of the connection.
    private val syncMutex = Mutex()

    // Bumped by [clear], so a response to a request sent before is dropped.
    private var generation = 0
    private val data = HashMap<RoomId, RoomData>()
    private var pos: String? = null
    private var unsupported = false
    private val paginating = java.util.Collections.synchronizedSet(HashSet<RoomId>())

    private val _rooms = MutableStateFlow<Map<RoomId, FeedRoom>>(emptyMap())
    val rooms: StateFlow<Map<RoomId, FeedRoom>> = _rooms.asStateFlow()

    /** Loads the latest posts of the [roomIds] not loaded yet, in one request. */
    suspend fun load(roomIds: Collection<RoomId>) {
        if (roomIds.none { it !in _rooms.value }) return
        // Already syncing (the feed is open): that request loads them.
        if (!syncMutex.tryLock()) return
        try {
            syncLocked(roomIds, timeoutMs = 0)
        } finally {
            syncMutex.unlock()
        }
    }

    /** Loads [roomIds], then keeps them up to date (new posts, likes, edits, deletions) until cancelled. */
    suspend fun runLive(roomIds: Collection<RoomId>) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val waitForNews = pos != null && roomIds.all { it in _rooms.value }
            val ok = syncMutex.withLock { syncLocked(roomIds, timeoutMs = if (waitForNews) LIVE_TIMEOUT_MS else 0) }
            if (ok) {
                failures = 0
            } else {
                if (unsupported) return
                // Back off on errors (offline, server trouble): 2s, 4s, ... up to a minute.
                delay(minOf(60_000L, 2_000L shl minOf(failures++, 5)))
            }
        }
    }

    /** Loads older posts of [roomId]. Returns whether it may have more. */
    suspend fun paginate(roomId: RoomId): Boolean {
        val from = mutex.withLock { data[roomId]?.takeIf { !it.reachedStart }?.prevBatch } ?: return false
        if (!paginating.add(roomId)) return true
        try {
            val json = http.roomMessages(roomId.value, from, limit = PAGE_SIZE, lazyLoadMembers = true)
                .onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "Couldn't load older posts of %s", roomId) }
                .getOrNull() ?: return true
            return withContext(Dispatchers.Default) {
                mutex.withLock {
                    val room = data[roomId] ?: return@withLock false
                    // Reset meanwhile (a gap, see applySync): this page belongs to the old stretch.
                    if (room.prevBatch != from) return@withLock true
                    // Newest first: reversed to oldest first.
                    val chunk = json.optJSONArray("chunk").objects().asReversed()
                    // Older member events only fill in senders not known yet: the newer name and avatar win.
                    json.optJSONArray("state").objects().forEach { room.applyState(it, older = true) }
                    val older = chunk.filter { e -> e.optString("event_id").let { it.isNotEmpty() && room.eventIds.add(it) } }
                    older.forEach { room.applyState(it, older = true) }
                    room.events = older + room.events
                    room.prevBatch = json.optString("end").takeIf { it.isNotEmpty() }
                    room.reachedStart = room.prevBatch == null || chunk.isEmpty()
                    publish(roomId, room)
                    !room.reachedStart
                }
            }
        } finally {
            paginating.remove(roomId)
        }
    }

    /** Forgets everything (the next load starts a new sync connection). */
    suspend fun clear() = mutex.withLock {
        data.clear()
        pos = null
        generation++
        _rooms.value = emptyMap()
    }

    /** One sliding sync request (holding [syncMutex]). Returns false on failure. */
    private suspend fun syncLocked(roomIds: Collection<RoomId>, timeoutMs: Long): Boolean {
        if (unsupported) {
            markNeedsTimeline(roomIds)
            return false
        }
        val subscriptions = JSONObject()
        roomIds.forEach { subscriptions.put(it.value, SUBSCRIPTION) }
        val body = JSONObject()
            .put("conn_id", CONN_ID)
            .put("room_subscriptions", subscriptions)
        val (requestPos, requestGeneration) = mutex.withLock { pos to generation }
        val json = http.slidingSync(body, requestPos, timeoutMs).getOrElse { error ->
            if (error is CancellationException) throw error
            when {
                error is SocialHttpException && error.errcode == "M_UNKNOWN_POS" -> {
                    // The server dropped the connection: start a new one (each room's latest posts come again).
                    mutex.withLock { if (generation == requestGeneration) pos = null }
                }
                error is SocialHttpException && (error.statusCode == 404 || error.errcode == "M_UNRECOGNIZED") -> {
                    Timber.tag(SocialRepository.LOG_TAG).w("No simplified sliding sync on the server: the feed uses room timelines")
                    unsupported = true
                    markNeedsTimeline(roomIds)
                }
                else -> Timber.tag(SocialRepository.LOG_TAG).w(error, "Feed sync failed")
            }
            return false
        }
        withContext(Dispatchers.Default) {
            mutex.withLock {
                // Cleared meanwhile (Social was closed): this response belongs to the old connection.
                if (generation != requestGeneration) return@withLock
                pos = json.optString("pos").takeIf { it.isNotEmpty() }
                val rooms = json.optJSONObject("rooms")
                rooms?.keys()?.forEach { key ->
                    val roomId = runCatching { RoomId(key) }.getOrNull() ?: return@forEach
                    val room = data.getOrPut(roomId) { RoomData() }
                    room.applySync(rooms.getJSONObject(key))
                    publish(roomId, room)
                }
                // A room the server sent nothing for (e.g. no longer joined) shouldn't hold the feed in "loading".
                roomIds.filter { it !in data }.forEach { roomId ->
                    val room = RoomData().apply { reachedStart = true }
                    data[roomId] = room
                    publish(roomId, room)
                }
            }
        }
        return true
    }

    private fun markNeedsTimeline(roomIds: Collection<RoomId>) {
        _rooms.update { current -> current + roomIds.filter { current[it] !is FeedRoom.NeedsTimeline }.associateWith { FeedRoom.NeedsTimeline } }
    }

    private fun RoomData.applySync(room: JSONObject) {
        room.optJSONArray("required_state").objects().forEach { applyState(it) }
        val isNew = events.isEmpty()
        val timeline = room.optJSONArray("timeline").objects()
        val redacted = HashSet<String>()
        val added = timeline.filter { e ->
            if (e.optString("type") == "m.room.redaction") {
                (e.optJSONObject("content")?.optString("redacts")?.takeIf { it.isNotEmpty() } ?: e.optString("redacts"))
                    .takeIf { it.isNotEmpty() }?.let { redacted += it }
            }
            applyState(e)
            e.optJSONObject("content")?.optJSONObject("m.relates_to")
                ?.takeIf { it.optString("rel_type") == "m.replace" }
                ?.optString("event_id")?.takeIf { it.isNotEmpty() }
                ?.let { target ->
                    val previous = liveEdits[target]
                    if (previous == null || previous.optLong("origin_server_ts") <= e.optLong("origin_server_ts")) liveEdits[target] = e
                }
            e.optString("event_id").let { it.isNotEmpty() && eventIds.add(it) }
        }
        redactedIds += redacted
        val gap = !isNew && room.optBoolean("limited") && added.isNotEmpty()
        if (gap) {
            // More happened than the server sent (or it's a new connection): what's between the old events and these is
            // missing, so the old ones are dropped. The feed stays one unbroken stretch of the room, and the counts of
            // every post shown are complete; older posts load again from the new token as the user scrolls.
            events = added.filter { it.optString("event_id") !in redacted }
            eventIds.clear()
            events.forEach { eventIds += it.optString("event_id") }
            parsed.clear()
        } else {
            events = (events + added).filter { it.optString("event_id") !in redacted }
            eventIds.removeAll(redacted)
        }
        if (isNew || gap) {
            prevBatch = room.optString("prev_batch").takeIf { it.isNotEmpty() }
            reachedStart = prevBatch == null
        }
    }

    private fun RoomData.applyState(event: JSONObject, older: Boolean = false) {
        when (event.optString("type")) {
            "m.room.encryption" -> encrypted = true
            "m.room.encrypted" -> encrypted = true
            "m.room.member" -> {
                val userId = event.optString("state_key").takeIf { it.isNotEmpty() } ?: return
                if (older && userId in members) return
                val content = event.optJSONObject("content") ?: return
                members[userId] = Member(
                    name = content.optString("displayname").takeIf { it.isNotEmpty() && !content.isNull("displayname") },
                    avatarUrl = content.optString("avatar_url").takeIf { it.isNotEmpty() && !content.isNull("avatar_url") },
                )
            }
        }
    }

    private fun publish(roomId: RoomId, room: RoomData) {
        val entry = if (room.encrypted) FeedRoom.NeedsTimeline else FeedRoom.Loaded(room.toState(roomId))
        _rooms.update { it + (roomId to entry) }
    }

    private fun RoomData.toState(roomId: RoomId): RoomFeedState {
        val me = matrixClient.sessionId.value
        val edits = HashMap<String, JSONObject>()
        // Target event -> its reactions.
        val reactions = HashMap<String, MutableList<RawReaction>>()
        events.forEach { e ->
            val relatesTo = e.optJSONObject("content")?.optJSONObject("m.relates_to") ?: return@forEach
            val target = relatesTo.optString("event_id").takeIf { it.isNotEmpty() } ?: return@forEach
            when {
                e.optString("type") == "m.reaction" && relatesTo.optString("rel_type") == "m.annotation" -> {
                    val key = relatesTo.optString("key").takeIf { it.isNotEmpty() } ?: return@forEach
                    reactions.getOrPut(target) { mutableListOf() } += RawReaction(key, e.optString("sender"), e.optLong("origin_server_ts"))
                }
                relatesTo.optString("rel_type") == "m.replace" -> {
                    // The latest edit wins (events are oldest first).
                    edits[target] = e
                }
            }
        }
        val posts = events.asReversed().mapNotNull { e -> toPost(roomId, e, edits, reactions, me) }
        return RoomFeedState(
            posts = posts,
            oldestLoaded = events.minOfOrNull { it.optLong("origin_server_ts") },
            hasMore = !reachedStart && prevBatch != null,
            edits = liveEdits.mapValues { it.value.toString() },
            redacted = redactedIds.toSet(),
        )
    }

    private fun RoomData.toPost(
        roomId: RoomId,
        event: JSONObject,
        edits: Map<String, JSONObject>,
        reactions: Map<String, List<RawReaction>>,
        me: String,
    ): FeedPost? {
        val eventId = event.optString("event_id").takeIf { it.isNotEmpty() } ?: return null
        if (event.optJSONObject("unsigned")?.has("redacted_because") == true) return null
        val relType = event.optJSONObject("content")?.optJSONObject("m.relates_to")?.optString("rel_type")
        if (relType == "m.replace" || event.optString("type") == "m.reaction") return null
        val sender = event.optString("sender")
        // Only the author's own edits count.
        val edit = edits[eventId]?.takeIf { it.optString("sender") == sender }
        val parsed = parsed.getOrPut(eventId + "|" + edit?.optString("event_id").orEmpty()) {
            ParsedPost.of(event.toString(), edit?.toString())
        }
        val post = parsed.post ?: return null
        // De-duplicated by sender, like Haven Desktop; 👍 / 👍️ and 🔁 / 🔁️ merged.
        val summaries = reactionSummaries(reactions[eventId].orEmpty(), me)
        val like = summaries.likes()
        val repost = summaries.reposts()
        val member = members[sender]
        return runCatching {
            FeedPost(
                key = roomId.value + "|" + eventId,
                roomId = roomId,
                eventId = EventId(eventId),
                transactionId = null,
                sender = UserId(sender),
                senderName = member?.name,
                senderAvatarUrl = member?.avatarUrl,
                timestamp = event.optLong("origin_server_ts"),
                isOwn = sender == me,
                post = post,
                contentJson = parsed.contentJson,
                originalJson = parsed.originalJson,
                latestEditJson = parsed.latestEditJson,
                likeCount = like?.count ?: 0,
                likedByMe = like?.byMe ?: false,
                repostCount = repost?.count ?: 0,
                repostedByMe = repost?.byMe ?: false,
                reactions = summaries,
                // The thread's size from the server: only a fallback, see SocialTimelineStore.withReplyCounts.
                replyCount = post.bundledReplyCount,
            )
        }.getOrNull()
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private companion object {
        const val CONN_ID = "haven-social-feed"
        // Enough that busy rooms (where reactions, edits and member events fill the latest events) don't hold the
        // first screen of the feed back to their last few minutes.
        const val TIMELINE_LIMIT = 50
        const val PAGE_SIZE = 100
        const val LIVE_TIMEOUT_MS = 20_000L

        val SUBSCRIPTION: JSONObject
            get() = JSONObject()
                .put("timeline_limit", TIMELINE_LIMIT)
                .put(
                    "required_state",
                    JSONArray()
                        .put(JSONArray().put("m.room.encryption").put(""))
                        // The members who sent the events returned.
                        .put(JSONArray().put("m.room.member").put("\$LAZY")),
                )
    }
}
