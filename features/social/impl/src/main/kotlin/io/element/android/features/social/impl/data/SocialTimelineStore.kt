/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import kotlinx.coroutines.flow.first
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.impl.msc.SocialPost
import io.element.android.features.social.impl.msc.SocialPostParser
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.TransactionId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
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
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/** One room's part of the feed. */
data class RoomFeedState(
    val posts: List<FeedPost>,
    /** Timestamp of the oldest loaded event (any type), or null if nothing is loaded. */
    val oldestLoaded: Long?,
    /** Whether the room has older history that isn't loaded yet. */
    val hasMore: Boolean,
    /** Latest edit (event JSON) per post, seen live: also applied to posts shown from the feed cache. */
    val edits: Map<String, String> = emptyMap(),
    /** Posts deleted live: also taken out of the posts shown from the feed cache. */
    val redacted: Set<String> = emptySet(),
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
    /**
     * All reactions, 👍 and 🔁 included (their counts are also in [likeCount] / [repostCount], shown on the Like and
     * Repost buttons; the reactions shelf leaves them out, see [isActionBarReaction]).
     */
    val reactions: List<ReactionSummary>,
    val replyCount: Int,
    /** Read without following the room (peeked): liking, reacting and replying need a follow first. */
    val peeked: Boolean = false,
    /** The event's raw JSON and its latest edit's, to save the post in the feed cache ([FeedCache]). */
    val originalJson: String? = null,
    val latestEditJson: String? = null,
    /** Shown from the feed cache until the room's own timeline has loaded. */
    val fromCache: Boolean = false,
) {
    /** Stable across app restarts (unlike [key]), so a cached post and its live copy are the same list item. */
    val listKey get() = eventId?.let { roomId.value + "|" + it.value } ?: key

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
    private val feedCache: FeedCache,
    private val feedSync: FeedSync,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) {
    private val rooms = java.util.concurrent.ConcurrentHashMap<RoomId, JoinedRoom>()

    // One lock per room, so the feed's rooms open in parallel instead of one after another.
    private val roomLocks = java.util.concurrent.ConcurrentHashMap<RoomId, Mutex>()
    private fun lockOf(roomId: RoomId) = roomLocks.getOrPut(roomId) { Mutex() }
    private val parseCache = mutableMapOf<String, ParsedPost>()

    suspend fun joinedRoom(roomId: RoomId): JoinedRoom? = rooms[roomId] ?: lockOf(roomId).withLock {
        rooms[roomId]?.let { return@withLock it }
        // Only rooms the SDK has synced: for others, Element X's room factory waits for the room list (holding a lock
        // that would also block opening ordinary rooms). Social rooms get subscribed by SocialRepository.
        if (matrixClient.getRoomInfo(roomId).getOrNull() == null) return@withLock null
        matrixClient.getJoinedRoom(roomId)?.also { rooms[roomId] = it }
    }

    private val socialTimelines = java.util.concurrent.ConcurrentHashMap<RoomId, Timeline>()

    /**
     * The room's live timeline including thread replies (Element X's own live timeline hides them; Haven Desktop's feed
     * shows them). Falls back to the stock live timeline if it can't be created.
     */
    suspend fun timeline(roomId: RoomId): Timeline? {
        socialTimelines[roomId]?.let { return it }
        val room = joinedRoom(roomId) ?: return null
        return lockOf(roomId).withLock {
            socialTimelines[roomId] ?: room.createTimeline(CreateTimelineParams.HavenLiveWithThreads)
                .onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "Couldn't create a timeline with threads for %s", roomId) }
                .getOrNull()
                ?.also { socialTimelines[roomId] = it }
                ?: room.liveTimeline
        }
    }

    // Posts the user just opened from a list, so the post screen can show them at once (see SocialThreadScreen).
    private val openedPosts = java.util.concurrent.ConcurrentHashMap<String, FeedPost>()

    fun rememberOpenedPost(post: FeedPost) {
        val eventId = post.eventId ?: return
        if (openedPosts.size > 50) openedPosts.clear()
        openedPosts[post.roomId.value + "|" + eventId.value] = post
    }

    fun openedPost(roomId: RoomId, eventId: String): FeedPost? = openedPosts[roomId.value + "|" + eventId]

    /** The room if its timeline is already open (no waiting). */
    fun cachedJoinedRoom(roomId: RoomId): JoinedRoom? = rooms[roomId]

    /**
     * The SDK's item for [eventId], when [roomId]'s timeline is open and has it loaded (nothing is loaded for this): e.g.
     * for a screen that can then show the post at once.
     */
    suspend fun loadedTimelineEvent(roomId: RoomId, eventId: EventId): MatrixTimelineItem.Event? {
        val timeline = socialTimelines[roomId] ?: return null
        val items = kotlinx.coroutines.withTimeoutOrNull(500) { timeline.timelineItems.first() } ?: return null
        return items.firstNotNullOfOrNull { (it as? MatrixTimelineItem.Event)?.takeIf { item -> item.eventId == eventId } }
    }

    /** Any room whose timeline is open, e.g. for a screen that needs a room to run in but not a particular one. */
    fun anyCachedJoinedRoom(): JoinedRoom? = rooms.values.firstOrNull()

    /** Drops a room's timeline, e.g. after leaving it. */
    suspend fun forget(roomId: RoomId) = lockOf(roomId).withLock {
        socialTimelines.remove(roomId)?.let { runCatching { it.close() } }
        rooms.remove(roomId)?.destroy()
    }

    // Social screens (flows) currently open: their rooms' timelines stay open meanwhile.
    private val users = java.util.concurrent.atomic.AtomicInteger(0)

    /** A Social flow opened: keep the rooms' timelines open until it's [released][release]. */
    fun acquire() {
        users.incrementAndGet()
    }

    /**
     * A Social flow closed. Once none is left, closes every timeline Social opened: an open timeline keeps receiving and
     * mapping its room's updates in the background, and with the feed's many rooms (each paginated back) that kept
     * growing until the app ran out of memory. The next visit starts from the cached feed, so it still opens at once.
     */
    fun release() {
        if (users.decrementAndGet() > 0) return
        users.set(0)
        // Removed from the maps first, so nothing picks up a timeline that's being closed.
        val timelines = socialTimelines.keys.mapNotNull { socialTimelines.remove(it) }
        val joined = rooms.keys.mapNotNull { rooms.remove(it) }
        timelines.forEach { runCatching { it.close() } }
        joined.forEach { runCatching { it.destroy() } }
        synchronized(parseCache) { parseCache.clear() }
        sessionScope.launch { feedSync.clear() }
        Timber.tag(SocialRepository.LOG_TAG).d("Closed %d social timelines", timelines.size)
    }

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
    /**
     * @param roomsComplete false while the feed's rooms are still being found (just after the app starts, they come
     * from the server): [roomIds] may be partial, so all of last time's cached posts are shown meanwhile.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun feed(roomIds: List<RoomId>, roomsComplete: Boolean = true): Flow<FeedSnapshot> {
        if (roomIds.isEmpty() && !roomsComplete) {
            // The feed's rooms aren't known yet (just after the app starts, they come from the server): show last
            // time's feed meanwhile, still loading.
            return flow {
                emit(FeedSnapshot(feedCache.posts(), loadedRooms = 0, totalRooms = 1))
            }.flowOn(Dispatchers.Default)
        }
        if (roomIds.isEmpty()) return flowOf(FeedSnapshot(emptyList(), loadedRooms = 0, totalRooms = 0))
        val perRoom = roomIds.map { roomId ->
            feedSync.rooms
                .map { it[roomId] }
                .distinctUntilChanged()
                .flatMapLatest { room ->
                    when (room) {
                        // null = this room hasn't loaded yet
                        null -> flowOf(null)
                        is FeedRoom.Loaded -> flowOf(room.state.copy(posts = withReplyCounts(room.state.posts)))
                        // Encrypted (or no sliding sync on the server): the SDK's timeline decrypts it.
                        FeedRoom.NeedsTimeline -> flow { emitAll(roomFeedState(roomId)) }
                    }
                }
                .map { roomId to it }
        }
        // Merging and sorting many rooms is real work: keep it off the main thread.
        return channelFlow {
            // The rooms' latest posts in one request, then new ones as they come, for as long as the feed is shown.
            launch { feedSync.runLive(roomIds) }
            val roomIdSet = roomIds.toSet()
            // Last time's newest posts, shown right away and replaced room by room as each room loads.
            val cached = feedCache.posts().filter { !roomsComplete || it.roomId in roomIdSet }
            perRoom.merge()
                .scan(emptyMap<RoomId, RoomFeedState?>()) { acc, (roomId, state) -> acc + (roomId to state) }
                .map { byRoom -> snapshotOf(byRoom, roomIds.size, cached) }
                .collect { send(it) }
        }
            .conflate()
            .flowOn(Dispatchers.Default)
    }

    /** Saves the newest posts of a fully loaded feed for next time. */
    suspend fun saveFeed(snapshot: FeedSnapshot) {
        if (!snapshot.isLoading && snapshot.posts.isNotEmpty()) feedCache.save(snapshot.posts)
    }

    private fun snapshotOf(byRoom: Map<RoomId, RoomFeedState?>, totalRooms: Int, cached: List<FeedPost>): FeedSnapshot {
        val loaded = byRoom.filterValues { it != null }.mapValues { it.value!! }
        // Rooms with more history, by how far back they're loaded (rooms with nothing loaded yet don't hold the feed
        // back: their posts appear as they arrive).
        val limiting = loaded.filter { it.value.hasMore && it.value.oldestLoaded != null }
            .entries.sortedByDescending { it.value.oldestLoaded }
        val cutoff = limiting.firstOrNull()?.value?.oldestLoaded ?: 0L
        val live = loaded.values.flatMap { it.posts }.filter { it.timestamp >= cutoff }
        val liveKeys = live.mapTo(HashSet()) { it.listKey }
        // A cached post stays until its room has loaded back to it: from then on the room's live posts are the truth
        // (a deleted post disappears). The cache was complete when saved, so it isn't held back by the cutoff.
        val fromCache = cached.filter { post ->
            if (post.listKey in liveKeys) return@filter false
            val room = byRoom[post.roomId]
            if (post.eventId?.value in room?.redacted.orEmpty()) return@filter false
            val oldestLoaded = room?.oldestLoaded
            // Room not loaded yet (or nothing in its timeline yet), or the post is older than what it has loaded.
            oldestLoaded == null || post.timestamp < oldestLoaded
        }.map { post -> withLiveEdit(post, byRoom[post.roomId]?.edits?.get(post.eventId?.value)) }
        val posts = (live + fromCache).sortedByDescending { it.timestamp }
        return FeedSnapshot(
            posts = posts,
            loadedRooms = loaded.size,
            totalRooms = totalRooms,
            cutoff = cutoff,
            limitingRooms = limiting.map { it.key },
        )
    }

    /** [post] (from the feed cache) with an edit made since it was cached, if any (by its author only). */
    private fun withLiveEdit(post: FeedPost, editJson: String?): FeedPost {
        editJson ?: return post
        val edit = runCatching { JSONObject(editJson) }.getOrNull() ?: return post
        if (edit.optString("sender") != post.sender.value) return post
        val current = post.latestEditJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (current != null && current.optLong("origin_server_ts") >= edit.optLong("origin_server_ts")) return post
        val parsed = ParsedPost.of(post.originalJson ?: return post, editJson)
        return post.copy(post = parsed.post ?: return post, contentJson = parsed.contentJson, latestEditJson = editJson)
    }

    /** A room's posts, how far back it's loaded, and whether it has more. */
    private suspend fun roomFeedState(roomId: RoomId): Flow<RoomFeedState> {
        val timeline = timeline(roomId) ?: return flowOf(RoomFeedState(emptyList(), null, false))
        val content = timeline.timelineItems
            .conflate()
            .map { items ->
                val events = items.filterIsInstance<MatrixTimelineItem.Event>()
                val posts = withReplyCounts(
                    items.asReversed().mapNotNull { item ->
                        (item as? MatrixTimelineItem.Event)?.let { toFeedPost(roomId, it) }
                    }
                )
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
            withReplyCounts(
                items.asReversed().mapNotNull { item ->
                    (item as? MatrixTimelineItem.Event)?.let { toFeedPost(roomId, it) }
                }
            )
        }
        .flowOn(Dispatchers.Default)

    /** A post the feed has loaded from the server (with its reactions), if any. */
    fun feedPost(roomId: RoomId, eventId: String): FeedPost? =
        (feedSync.rooms.value[roomId] as? FeedRoom.Loaded)?.state?.posts?.firstOrNull { it.eventId?.value == eventId }

    /** Loads the feed rooms' latest posts ahead of time (one request), so the Feed is ready when Social opens. */
    suspend fun prefetchFeed(roomIds: List<RoomId>) = feedSync.load(roomIds)

    /** Haven: checks the feed rooms for new posts now (pull to refresh). */
    suspend fun refreshFeed(roomIds: List<RoomId>) = feedSync.refreshNow(roomIds)

    /** Loads older posts of a feed room: from the server, or its timeline if the feed uses one for it. */
    suspend fun paginateFeed(roomId: RoomId): Boolean = when (feedSync.rooms.value[roomId]) {
        is FeedRoom.Loaded -> feedSync.paginate(roomId)
        FeedRoom.NeedsTimeline -> paginate(roomId)
        null -> true
    }

    /** Loads older posts of [roomId]. Returns whether the room may have more (false at the start, or on failure). */
    suspend fun paginate(roomId: RoomId): Boolean = withContext(Dispatchers.Default) {
        // Off the main thread: opening a room's timeline and paginating call into the SDK, and the list's loading
        // spinner has to keep animating meanwhile.
        val timeline = timeline(roomId) ?: return@withContext false
        timeline.paginate(Timeline.PaginationDirection.BACKWARDS).map { reachedStart -> !reachedStart }.getOrDefault(false)
    }

    private fun toFeedPost(roomId: RoomId, item: MatrixTimelineItem.Event): FeedPost? {
        val event = item.event
        val content = event.content
        if (content is RedactedContent) return null
        val parsed = parse(item.uniqueId.value, event) ?: return null
        val post = parsed.post ?: return null
        val reactions = reactionSummaries(
            event.reactions.flatMap { reaction ->
                reaction.senders.map { RawReaction(reaction.key, it.senderId.value, it.timestamp) }
            },
            me = matrixClient.sessionId.value,
        )
        val like = reactions.likes()
        val repost = reactions.reposts()
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
            contentJson = parsed.contentJson,
            originalJson = parsed.originalJson,
            latestEditJson = parsed.latestEditJson,
            likeCount = like?.count ?: 0,
            likedByMe = like?.byMe ?: false,
            repostCount = repost?.count ?: 0,
            repostedByMe = repost?.byMe ?: false,
            reactions = reactions,
            // The thread's size, if this is a thread root: only a fallback, see withReplyCounts.
            replyCount = maxOf(threadReplies ?: 0, post.bundledReplyCount),
        )
    }

    /**
     * Haven Desktop's reply counts (replyCountFor): a post's direct replies among the loaded posts, i.e. the posts whose
     * immediate parent (`m.in_reply_to`, else the `m.thread` event) is that post. A reply to a reply counts on the reply
     * it answers, not on the thread root. When none are loaded, a thread root keeps its thread size from the server.
     */
    private fun withReplyCounts(posts: List<FeedPost>): List<FeedPost> {
        val counts = HashMap<String, Int>()
        posts.forEach { p -> (p.post.inReplyToId ?: p.post.threadRootId)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        return posts.map { p ->
            val local = p.eventId?.let { counts[it.value] } ?: 0
            if (local > 0 && local != p.replyCount) p.copy(replyCount = local) else p
        }
    }

    private fun parse(uniqueId: String, event: EventTimelineItem): ParsedPost? {
        // The event id too: a post being sent keeps its item's id and content once the server has it, but only then has
        // the event JSON a post is read from
        val cacheKey = uniqueId + "|" + event.eventId?.value + "|" + event.content.hashCode()
        synchronized(parseCache) { parseCache[cacheKey]?.let { return it } }
        val debugInfo = runCatching { event.timelineItemDebugInfoProvider() }
            .onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "No debug info for %s", event.eventId) }
            .getOrNull() ?: return null
        val result = ParsedPost.of(debugInfo.originalJson, debugInfo.latestEditedJson)
        // Without its JSON (not sent yet) it's read again later, not remembered as not being a post
        if (debugInfo.originalJson == null) return result
        synchronized(parseCache) {
            if (parseCache.size > 5000) parseCache.clear()
            parseCache[cacheKey] = result
        }
        return result
    }
}

/** A post parsed from its event's raw JSON (and its latest edit's). */
class ParsedPost(
    val post: SocialPost?,
    /** The content to embed when the post is reposted, quoted or replied to. */
    val contentJson: String?,
    val originalJson: String?,
    val latestEditJson: String?,
) {
    companion object {
        fun of(originalJson: String?, latestEditJson: String?): ParsedPost {
            val post = SocialPostParser.parse(originalJson, latestEditJson)
            val contentJson = latestEditJson
                ?.let { runCatching { JSONObject(it).optJSONObject("content")?.optJSONObject("m.new_content") }.getOrNull() }
                ?.toString()
                ?: originalJson?.let { runCatching { JSONObject(it).optJSONObject("content") }.getOrNull() }?.toString()
            return ParsedPost(post, contentJson, originalJson, latestEditJson)
        }
    }
}
