/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import android.net.Uri
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.features.social.impl.msc.PostReference
import io.element.android.features.social.impl.msc.SocialContentBuilder
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.timeline.item.event.EventOrTransactionId
import timber.log.Timber

class SocialActionException(message: String) : Exception(message)

/** Liking, reacting or replying to a post in a room the user doesn't follow: they need to follow (join) it first. */
class FollowRequiredException(val roomId: RoomId, val eventId: String?, val via: List<String>) : Exception("Follow to interact")

/** Sends posts and interactions, following MSC4501 and matching Haven Desktop. */
@SingleIn(SessionScope::class)
@Inject
class SocialActions(
    private val matrixClient: MatrixClient,
    private val repository: SocialRepository,
    private val timelineStore: SocialTimelineStore,
) {
    /** Haven Desktop's "Cross-post replies to your profile" setting defaults to on. */
    var crossPostReplies: Boolean = true

    suspend fun post(roomId: RoomId, body: String): Result<Unit> = runCatching {
        val room = timelineStore.joinedRoom(roomId) ?: throw SocialActionException("Not a member of this room")
        room.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, SocialContentBuilder.post(body).toString()).getOrThrow()
    }

    /** Replies in [parent]'s thread, then cross-posts the reply to the user's own profile (MSC4501 "Cross-posted reply"). */
    suspend fun reply(parent: FeedPost, body: String): Result<Unit> = runCatching {
        val parentId = parent.eventId ?: throw SocialActionException("Post not sent yet")
        val rootId = parent.post.threadRootId ?: parentId.value
        val room = timelineStore.joinedRoom(parent.roomId) ?: throw followRequired(parent)
        room.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, SocialContentBuilder.reply(body, rootId, parentId.value).toString()).getOrThrow()

        val profileRoomId = (repository.ownProfile.value as? OwnProfileState.Linked)?.link?.roomId
        if (crossPostReplies && profileRoomId != null && profileRoomId != parent.roomId) {
            runCatching {
                val profileRoom = timelineStore.joinedRoom(profileRoomId) ?: return@runCatching
                val content = SocialContentBuilder.crossPostedReply(body, reference(parent))
                profileRoom.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, content.toString()).getOrThrow()
            }.onFailure { Timber.w(it, "Couldn't cross-post reply to profile") }
        }
    }

    /** Reposts [original] to the user's own profile, and counts it with a 🔁 reaction (MSC4501 "Repost counts"). */
    suspend fun repost(original: FeedPost): Result<Unit> = runCatching {
        val profileRoomId = ownProfileRoomId()
        val ref = reference(original)
        val permalink = permalinkFor(original) ?: throw SocialActionException("Couldn't make a link to this post")
        val profileRoom = timelineStore.joinedRoom(profileRoomId) ?: throw SocialActionException("Your profile room isn't joined")
        profileRoom.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, SocialContentBuilder.repost(permalink, ref).toString()).getOrThrow()
        sendRepostReaction(original)
    }

    /** A quote post: [commentary] plus the embedded original, posted to [targetRoomId] (the user's profile by default). */
    suspend fun quotePost(original: FeedPost, commentary: String, targetRoomId: RoomId? = null): Result<Unit> = runCatching {
        val roomId = targetRoomId ?: ownProfileRoomId()
        val room = timelineStore.joinedRoom(roomId) ?: throw SocialActionException("Not a member of this room")
        room.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, SocialContentBuilder.quotePost(commentary, reference(original)).toString()).getOrThrow()
        sendRepostReaction(original)
    }

    /** Likes or unlikes a post: a 👍 reaction (MSC4501 "Liking"). */
    suspend fun toggleLike(post: FeedPost): Result<Unit> = toggleReaction(post, Msc4501.LIKE_KEY)

    suspend fun toggleReaction(post: FeedPost, key: String): Result<Unit> = runCatching {
        val timeline = timelineStore.timeline(post.roomId) ?: throw followRequired(post)
        timeline.toggleReaction(key, post.eventOrTransactionId()).getOrThrow()
    }

    suspend fun delete(post: FeedPost): Result<Unit> = runCatching {
        val timeline = timelineStore.timeline(post.roomId) ?: throw SocialActionException("Not a member of this room")
        timeline.redactEvent(post.eventOrTransactionId(), reason = null).getOrThrow()
    }

    private suspend fun sendRepostReaction(original: FeedPost) {
        // Only possible where the user can react in the original's room; otherwise the count just isn't incremented.
        val eventId = original.eventId ?: return
        val room = timelineStore.joinedRoom(original.roomId) ?: return
        room.sendRaw("m.reaction", SocialContentBuilder.reaction(eventId.value, Msc4501.REPOST_KEY).toString())
            .onFailure { Timber.w(it, "Couldn't send repost count reaction") }
    }

    private fun ownProfileRoomId(): RoomId = when (val state = repository.ownProfile.value) {
        is OwnProfileState.Linked -> state.link.roomId
        OwnProfileState.Loading -> throw SocialActionException("Still loading your profile")
        else -> throw SocialActionException("Create a profile first")
    }

    private suspend fun permalinkFor(post: FeedPost): String? {
        val eventId = post.eventId ?: return null
        val room = timelineStore.joinedRoom(post.roomId)
        room?.getPermalinkFor(eventId)?.getOrNull()?.let { return it }
        // Not followed (peeked): build the matrix.to link ourselves, routed through the servers we know of.
        val via = viaFor(post).joinToString("&") { "via=" + Uri.encode(it) }
        return "https://matrix.to/#/" + Uri.encode(post.roomId.value) + "/" + Uri.encode(eventId.value) + (if (via.isNotEmpty()) "?$via" else "")
    }

    /** Routing servers for a room we may not be in: the room ID's server and the author's server. */
    private fun viaFor(post: FeedPost): List<String> =
        listOf(post.roomId.value.substringAfter(':', ""), post.sender.value.substringAfter(':', ""))
            .filter { it.isNotEmpty() }
            .distinct()

    private fun followRequired(post: FeedPost) = FollowRequiredException(post.roomId, post.eventId?.value, viaFor(post))

    private suspend fun reference(post: FeedPost): PostReference {
        val eventId = post.eventId ?: throw SocialActionException("Post not sent yet")
        val via = permalinkFor(post)?.let { link ->
            runCatching { Uri.parse(link.replace("#/", "")).getQueryParameters("via") }.getOrNull()
        }.orEmpty().ifEmpty { viaFor(post) }
        return PostReference(
            eventId = eventId.value,
            roomId = post.roomId.value,
            sender = post.sender.value,
            displayname = post.senderName,
            via = via,
            contentJson = post.contentJson,
        )
    }
}

fun FeedPost.eventOrTransactionId(): EventOrTransactionId =
    EventOrTransactionId.from(eventId, transactionId)
