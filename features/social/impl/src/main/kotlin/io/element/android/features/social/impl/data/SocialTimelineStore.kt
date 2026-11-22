/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.features.social.impl.msc.SocialPost
import io.element.android.features.social.impl.msc.SocialPostParser
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.EventThreadInfo
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.RedactedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber

/** One room's part of the feed. */
data class RoomFeedState(
    val posts: List<FeedPost>,
    /** Timestamp of the oldest loaded event (any type), or null if nothing is loaded. */
    val oldestLoaded: Long?,
    /** Whether the room has older history that isn't loaded yet. */
    val hasMore: Boolean,
)

/**
 * The merged feed, and how many of its rooms have loaded so far.
 *
 * [posts] only goes down to [cutoff]: the point every room with more history has loaded back to. Anything older
 * could have gaps (other rooms' posts from that time aren't loaded yet), so it's held back until those rooms load more.
 */
data class FeedSnapshot(
    val posts: List<FeedPost>,
    val loadedRooms: Int,
    val totalRooms: Int,
    /** Posts older than this are held back; 0 when every room's whole history is loaded. */
    val cutoff: Long = 0,
    /** Rooms with more history, most limiting first (the ones to load more from to extend the feed). */
    val limitingRooms: List<RoomId> = emptyList(),
) {
    val isLoading get() = loadedRooms < totalRooms
    val hasMore get() = limitingRooms.isNotEmpty()
}

data class ReactionSummary(val key: String, val count: Int, val byMe: Boolean)

/** A post as shown in a feed, profile, group or thread. */
data class FeedPost(
    val key: String,
    val roomId: RoomId,
    val eventId: EventId?,
    val transactionId: TransactionId?,
    val sender: UserId,
    val senderName: String?,
    val senderAvatarUrl: String?,
    val timestamp: Long,
    val isOwn: Boolean,
    val post: SocialPost,
    /** The event's own content JSON, embedded when this post is reposted, quoted or replied to. */
    val contentJson: String?,
    val likeCount: Int,
    val likedByMe: Boolean,
    val repostCount: Int,
    val repostedByMe: Boolean,
    /** Other reactions (MSC4501 likes and repost counts are shown on their own buttons). */
    val reactions: List<ReactionSummary>,
    val replyCount: Int,
    /** Read without following the room (peeked): liking, reacting and replying need a follow first. */
    val peeked: Boolean = false,
) {
    val isThreadReply get() = post.threadRootId != null
}

/**
 * Keeps one live timeline per social room for the session, and turns timeline items into [FeedPost]s.
 *
 * Posts are parsed from each event's raw JSON (`m.room.message` and MSC4501 posts alike), because MSC4501's fields
 * (`relates_to`, `m.social.body`) aren't part of the SDK's typed content.
 */
@SingleIn(SessionScope::class)
@Inject
class SocialTimelineStore(
    private val matrixClient: MatrixClient,
) {
    private val rooms = mutableMapOf<RoomId, JoinedRoom>()
    private val mutex = Mutex()
    private val parseCache = mutableMapOf<String, Pair<SocialPost?, String?>>()

    suspend fun joinedRoom(roomId: RoomId): JoinedRoom? = mutex.withLock {
        rooms[roomId]?.let { return@withLock it }
        // Only rooms the SDK has synced: for others, Element X's room factory waits for the room list (holding a lock
        // that would also block opening ordinary rooms). Social rooms get subscribed by SocialRepository.
        if (matrixClient.getRoomInfo(roomId).getOrNull() == null) return@withLock null
        matrixClient.getJoinedRoom(roomId)?.also { rooms[roomId] = it }
    }

    suspend fun timeline(roomId: RoomId): Timeline? = joinedRoom(roomId)?.liveTimeline

    /** The room if its timeline is already open (no waiting). */
    fun cachedJoinedRoom(roomId: RoomId): JoinedRoom? = rooms[roomId]

    /** Drops a room's timeline, e.g. after leaving it. */
    suspend fun forget(roomId: RoomId) = mutex.withLock { rooms.remove(roomId)?.destroy() }

    /** Live posts of one room, newest first. */
    suspend fun posts(roomId: RoomId): Flow<List<FeedPost>> {
        val timeline = timeline(roomId) ?: return flowOf(emptyList())
        return postsOf(roomId, timeline)
    }

    /**
     * Live posts of several rooms merged into one feed, newest first (MSC4501 "Feeds").
     * Progressive: each room's posts show up as soon as that room's timeline loads, without waiting for the others.
     * Complete: see [FeedSnapshot.cutoff].
     */
    fun feed(roomIds: List<RoomId>): Flow<FeedSnapshot> {
        if (roomIds.isEmpty()) return flowOf(FeedSnapshot(emptyList(), loadedRooms = 0, totalRooms = 0))
        val perRoom = roomIds.map { roomId ->
            flow { emitAll(roomFeedState(roomId)) }
                .map<RoomFeedState, Pair<RoomId, RoomFeedState?>> { roomId to it }
                // null = this room hasn't loaded yet
                .onStart { emit(roomId to null) }
        }
        // Merging and sorting many rooms is real work: keep it off the main thread.
        return perRoom.merge()
            .scan(emptyMap<RoomId, RoomFeedState?>()) { acc, (roomId, state) -> acc + (roomId to state) }
            .map { byRoom -> snapshotOf(byRoom, roomIds.size) }
            .conflate()
            .flowOn(Dispatchers.Default)
    }

    private fun snapshotOf(byRoom: Map<RoomId, RoomFeedState?>, totalRooms: Int): FeedSnapshot {
        val loaded = byRoom.filterValues { it != null }.mapValues { it.value!! }
        // Rooms with more history, by how far back they're loaded (rooms with nothing loaded yet don't hold the feed
        // back: their posts appear as they arrive).
        val limiting = loaded.filter { it.value.hasMore && it.value.oldestLoaded != null }
            .entries.sortedByDescending { it.value.oldestLoaded }
        val cutoff = limiting.firstOrNull()?.value?.oldestLoaded ?: 0L
        val posts = loaded.values.flatMap { it.posts }
            .filter { it.timestamp >= cutoff }
            .sortedByDescending { it.timestamp }
        return FeedSnapshot(
            posts = posts,
            loadedRooms = loaded.size,
            totalRooms = totalRooms,
            cutoff = cutoff,
            limitingRooms = limiting.map { it.key },
        )
    }

    /** A room's posts, how far back it's loaded, and whether it has more. */
    private suspend fun roomFeedState(roomId: RoomId): Flow<RoomFeedState> {
        val timeline = timeline(roomId) ?: return flowOf(RoomFeedState(emptyList(), null, false))
        val content = timeline.timelineItems
            .conflate()
            .map { items ->
                val events = items.filterIsInstance<MatrixTimelineItem.Event>()
                val posts = items.asReversed().mapNotNull { item ->
                    (item as? MatrixTimelineItem.Event)?.let { toFeedPost(roomId, it) }
                }
                posts to events.minOfOrNull { it.event.timestamp }
            }
            .flowOn(Dispatchers.Default)
        return combine(content, timeline.backwardPaginationStatus) { (posts, oldest), status ->
            RoomFeedState(posts, oldest, status.hasMoreToLoad)
        }
    }

    /** Turns any timeline's items (live or a thread's) into posts, newest first. */
    fun postsOf(roomId: RoomId, timeline: Timeline): Flow<List<FeedPost>> = timeline.timelineItems
        .conflate()
        .map { items ->
            // Each post is parsed from its raw JSON (a call into the SDK per event): never on the main thread.
            items.asReversed().mapNotNull { item ->
                (item as? MatrixTimelineItem.Event)?.let { toFeedPost(roomId, it) }
            }
        }
        .flowOn(Dispatchers.Default)

    /** Loads older posts of [roomId]. Returns whether the room may have more (false at the start, or on failure). */
    suspend fun paginate(roomId: RoomId): Boolean {
        val timeline = timeline(roomId) ?: return false
        return timeline.paginate(Timeline.PaginationDirection.BACKWARDS).map { reachedStart -> !reachedStart }.getOrDefault(false)
    }

    private fun toFeedPost(roomId: RoomId, item: MatrixTimelineItem.Event): FeedPost? {
        val event = item.event
        val content = event.content
        if (content is RedactedContent) return null
        val (post, contentJson) = parse(item.uniqueId.value, event) ?: return null
        if (post == null) return null
        val me = matrixClient.sessionId
        // Reactions are de-duplicated by sender, like Haven Desktop.
        val reactions = event.reactions.map { reaction ->
            val senders = reaction.senders.map { it.senderId }.distinct()
            ReactionSummary(reaction.key, senders.size, me in senders)
        }
        val like = reactions.firstOrNull { it.key == Msc4501.LIKE_KEY }
        val repost = reactions.firstOrNull { it.key == Msc4501.REPOST_KEY }
        val threadReplies = ((content as? MessageContent)?.threadInfo as? EventThreadInfo.ThreadRoot)?.summary?.numberOfReplies?.toInt()
        val profile = event.senderProfile as? ProfileDetails.Ready
        return FeedPost(
            key = roomId.value + "|" + item.uniqueId.value,
            roomId = roomId,
            eventId = event.eventId,
            transactionId = event.transactionId,
            sender = event.sender,
            senderName = profile?.displayName,
            senderAvatarUrl = profile?.avatarUrl,
            timestamp = event.timestamp,
            isOwn = event.isOwn,
            post = post,
            contentJson = contentJson,
            likeCount = like?.count ?: 0,
            likedByMe = like?.byMe ?: false,
            repostCount = repost?.count ?: 0,
            repostedByMe = repost?.byMe ?: false,
            reactions = reactions.filter { it.key != Msc4501.LIKE_KEY && it.key != Msc4501.REPOST_KEY },
            replyCount = maxOf(threadReplies ?: 0, post.bundledReplyCount),
        )
    }

    private fun parse(uniqueId: String, event: EventTimelineItem): Pair<SocialPost?, String?>? {
        val cacheKey = uniqueId + "|" + event.content.hashCode()
        synchronized(parseCache) { parseCache[cacheKey]?.let { return it } }
        val debugInfo = runCatching { event.timelineItemDebugInfoProvider() }
            .onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "No debug info for %s", event.eventId) }
            .getOrNull() ?: return null
        val post = SocialPostParser.parse(debugInfo.originalJson, debugInfo.latestEditedJson)
        val contentJson = debugInfo.latestEditedJson
            ?.let { runCatching { JSONObject(it).optJSONObject("content")?.optJSONObject("m.new_content") }.getOrNull() }
            ?.toString()
            ?: debugInfo.originalJson?.let { runCatching { JSONObject(it).optJSONObject("content") }.getOrNull() }?.toString()
        val result = post to contentJson
        synchronized(parseCache) {
            if (parseCache.size > 5000) parseCache.clear()
            parseCache[cacheKey] = result
        }
        return result
    }
}
