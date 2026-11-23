/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.impl.msc.SocialPostParser
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A page of a peeked room's posts, and the token for the next (older) page. */
data class PeekedPage(val posts: List<FeedPost>, val nextToken: String?)

/**
 * "Peeking": reading a world-readable profile or group without joining (following) it, like Haven Desktop does.
 * Events come straight from the server (`/event`, `/relations`, `/messages`), since the SDK only has timelines for
 * joined rooms. Peeked posts can be read, reposted and quoted; liking, reacting and replying need a follow.
 */
@SingleIn(SessionScope::class)
@Inject
class SocialPeek(
    private val matrixClient: MatrixClient,
    private val httpApi: SocialHttpApi,
) {
    private val profiles = mutableMapOf<String, Pair<String?, String?>>()
    private val profilesMutex = Mutex()

    /** One post, or a failure if the room can't be peeked (not world-readable) or the event doesn't exist. */
    suspend fun post(roomId: RoomId, eventId: String): Result<FeedPost?> =
        httpApi.getRoomEvent(roomId.value, eventId).map { toFeedPost(roomId, it) }

    /** A thread: its root and every reply (`m.thread` relations), oldest first. */
    suspend fun thread(roomId: RoomId, rootId: String): Result<List<FeedPost>> = runCatching {
        val root = httpApi.getRoomEvent(roomId.value, rootId).getOrThrow()
        val replies = mutableListOf<JSONObject>()
        var from: String? = null
        var pages = 0
        do {
            val page = httpApi.relations(roomId.value, rootId, "m.thread", from).getOrThrow()
            val chunk = page.optJSONArray("chunk")
            for (i in 0 until (chunk?.length() ?: 0)) chunk!!.optJSONObject(i)?.let(replies::add)
            from = page.optString("next_batch").takeIf { it.isNotEmpty() }
        } while (from != null && ++pages < 10)
        (listOf(root) + replies).mapNotNull { toFeedPost(roomId, it) }.sortedBy { it.timestamp }
    }

    /** A page of a room's top-level posts, newest first. */
    suspend fun roomPage(roomId: RoomId, from: String?): Result<PeekedPage> =
        httpApi.roomMessages(roomId.value, from).map { page ->
            val chunk = page.optJSONArray("chunk")
            val posts = (0 until (chunk?.length() ?: 0))
                .mapNotNull { chunk!!.optJSONObject(it)?.let { event -> toFeedPost(roomId, event) } }
                .filter { !it.isThreadReply }
            PeekedPage(posts, page.optString("end").takeIf { it.isNotEmpty() && (chunk?.length() ?: 0) > 0 })
        }

    private suspend fun toFeedPost(roomId: RoomId, event: JSONObject): FeedPost? {
        if (event.optJSONObject("unsigned")?.has("redacted_because") == true) return null
        val post = SocialPostParser.parse(event.toString()) ?: return null
        val sender = event.optString("sender").takeIf { it.startsWith("@") } ?: return null
        val eventId = event.optString("event_id").takeIf { it.isNotEmpty() } ?: return null
        val (name, avatar) = profileOf(sender)
        return FeedPost(
            key = roomId.value + "|" + eventId,
            roomId = roomId,
            eventId = EventId(eventId),
            transactionId = null,
            sender = UserId(sender),
            senderName = name,
            senderAvatarUrl = avatar,
            timestamp = event.optLong("origin_server_ts"),
            isOwn = sender == matrixClient.sessionId.value,
            post = post,
            contentJson = event.optJSONObject("content")?.toString(),
            // For "View source".
            originalJson = event.toString(),
            likeCount = 0,
            likedByMe = false,
            repostCount = 0,
            repostedByMe = false,
            reactions = emptyList(),
            replyCount = post.bundledReplyCount,
            peeked = true,
        )
    }

    /** A user's current display name and avatar URL, cached for the session. */
    suspend fun profileOf(userId: String): Pair<String?, String?> {
        profilesMutex.withLock { profiles[userId]?.let { return it } }
        val user = matrixClient.getProfile(UserId(userId)).getOrNull()
        val result = user?.displayName to user?.avatarUrl
        profilesMutex.withLock { profiles[userId] = result }
        return result
    }
}
