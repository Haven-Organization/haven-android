/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProviderFactory
import io.element.android.libraries.mediaupload.api.MediaSenderRoomTimelineFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import org.json.JSONObject
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

/** A file picked in the post composer (one per post, like Haven Desktop). */
data class PostAttachment(val uri: android.net.Uri, val mimeType: String)

/** Liking, reacting or replying to a post in a room the user doesn't follow: they need to follow (join) it first. */
class FollowRequiredException(val roomId: RoomId, val eventId: String?, val via: List<String>) : Exception("Follow to interact")

/** Sends posts and interactions, following MSC4501 and matching Haven Desktop. */
@SingleIn(SessionScope::class)
@Inject
class SocialActions(
    private val matrixClient: MatrixClient,
    private val repository: SocialRepository,
    private val timelineStore: SocialTimelineStore,
    private val http: SocialHttpApi,
    private val mediaSenderFactory: MediaSenderRoomTimelineFactory,
    private val mediaOptimizationConfigProviderFactory: MediaOptimizationConfigProviderFactory,
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
        crossPostReply(parent, body)
    }

    /** MSC4501 "Cross-posted reply": best effort, never fails the reply itself (already sent). */
    suspend fun crossPostReply(parent: FeedPost, body: String) {
        val profileRoomId = (repository.ownProfile.value as? OwnProfileState.Linked)?.link?.roomId
        if (crossPostReplies && profileRoomId != null && profileRoomId != parent.roomId) {
            runCatching {
                val profileRoom = timelineStore.joinedRoom(profileRoomId) ?: return@runCatching
                val content = SocialContentBuilder.crossPostedReply(body, reference(parent))
                profileRoom.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, content.toString()).getOrThrow()
            }.onFailure { Timber.w(it, "Couldn't cross-post reply to profile") }
        }
    }



    /**
     * A quote post from the post composer, to the user's profile. With an attachment, the media message is built here
     * (it has to carry MSC4501's `relates_to` too), which needs a plain upload: only possible in unencrypted rooms.
     */
    suspend fun publishQuote(original: FeedPost, markdown: String, attachment: PostAttachment?): Result<Unit> {
        if (attachment == null) return quotePost(original, markdown)
        return runCatching {
            val roomId = ownProfileRoomId()
            val room = timelineStore.joinedRoom(roomId) ?: throw SocialActionException("Not a member of this room")
            if (room.info().isEncrypted != false) {
                throw SocialActionException("Attachments on quote posts aren't supported in encrypted rooms yet")
            }
            val sender = mediaSenderFactory.create(matrixClient, room, Timeline.Mode.Live)
            try {
                val config = mediaOptimizationConfigProviderFactory.create(matrixClient).get()
                val info = sender.preProcessMedia(attachment.uri, attachment.mimeType, config).getOrThrow()
                val content = uploadedMediaContent(info, markdown)
                content.put(Msc4501.RELATES_TO, SocialContentBuilder.quotePost("", reference(original)).getJSONObject(Msc4501.RELATES_TO))
                room.sendRaw(SocialContentBuilder.POST_EVENT_TYPE, content.toString()).getOrThrow()
            } finally {
                sender.cleanUp()
            }
            sendRepostReaction(original)
        }
    }


    /** A media message's content for pre-processed media, uploaded unencrypted (see [publishQuote]). */
    private suspend fun uploadedMediaContent(info: MediaUploadInfo, caption: String): JSONObject {
        suspend fun upload(file: java.io.File, mimeType: String?): String =
            matrixClient.uploadMedia(mimeType ?: "application/octet-stream", file.readBytes()).getOrThrow()
        val infoJson = JSONObject()
        val (msgtype, mimeType) = when (info) {
            is MediaUploadInfo.Image -> {
                infoJson.putOpt("w", info.imageInfo.width).putOpt("h", info.imageInfo.height).putOpt("size", info.imageInfo.size)
                    .putOpt("blurhash", info.imageInfo.blurhash)
                info.thumbnailFile?.let { thumb ->
                    infoJson.put("thumbnail_url", upload(thumb, info.imageInfo.thumbnailInfo?.mimetype ?: "image/jpeg"))
                    info.imageInfo.thumbnailInfo?.let { t ->
                        infoJson.put("thumbnail_info", JSONObject().putOpt("w", t.width).putOpt("h", t.height).putOpt("size", t.size).putOpt("mimetype", t.mimetype))
                    }
                }
                "m.image" to info.imageInfo.mimetype
            }
            is MediaUploadInfo.Video -> {
                infoJson.putOpt("w", info.videoInfo.width).putOpt("h", info.videoInfo.height).putOpt("size", info.videoInfo.size)
                    .putOpt("duration", info.videoInfo.duration?.inWholeMilliseconds).putOpt("blurhash", info.videoInfo.blurhash)
                info.thumbnailFile?.let { thumb ->
                    infoJson.put("thumbnail_url", upload(thumb, info.videoInfo.thumbnailInfo?.mimetype ?: "image/jpeg"))
                    info.videoInfo.thumbnailInfo?.let { t ->
                        infoJson.put("thumbnail_info", JSONObject().putOpt("w", t.width).putOpt("h", t.height).putOpt("size", t.size).putOpt("mimetype", t.mimetype))
                    }
                }
                "m.video" to info.videoInfo.mimetype
            }
            is MediaUploadInfo.Audio -> {
                infoJson.putOpt("size", info.audioInfo.size).putOpt("duration", info.audioInfo.duration?.inWholeMilliseconds)
                "m.audio" to info.audioInfo.mimetype
            }
            is MediaUploadInfo.VoiceMessage -> {
                infoJson.putOpt("size", info.audioInfo.size).putOpt("duration", info.audioInfo.duration?.inWholeMilliseconds)
                "m.audio" to info.audioInfo.mimetype
            }
            is MediaUploadInfo.AnyFile -> {
                infoJson.putOpt("size", info.fileInfo.size)
                "m.file" to info.fileInfo.mimetype
            }
        }
        mimeType?.let { infoJson.put("mimetype", it) }
        val filename = info.file.name
        return JSONObject()
            .put("msgtype", msgtype)
            .put("body", caption.trim().ifEmpty { filename })
            .put("filename", filename)
            .put("url", upload(info.file, mimeType))
            .put("info", infoJson)
    }

    /** Likes and reposts shown before the server confirms them. */
    val optimistic = OptimisticPosts()

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

    /**
     * Edits [post] (one the SDK can't edit: sent with MSC4501's own event type) to [text]: an `m.replace` of the same
     * event type, whose `m.new_content` is the post's content with the new text, keeping the rest (its msgtype, what it
     * quotes or reposts).
     */
    suspend fun editPost(post: FeedPost, text: String): Result<Unit> = runCatching {
        val eventId = post.eventId ?: throw SocialActionException("This post isn't sent yet")
        val room = timelineStore.joinedRoom(post.roomId) ?: throw followRequired(post)
        val newContent = post.contentJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject().put("msgtype", "m.text")
        newContent.remove("m.relates_to")
        newContent.remove("m.new_content")
        if (newContent.has(Msc4501.BODY)) {
            // The post's own text is its MSC4501 body (its body is a fallback, e.g. a link)
            newContent.put(Msc4501.BODY, text)
            newContent.remove(Msc4501.FORMATTED_BODY)
        } else {
            newContent.put("body", text)
            newContent.remove("format")
            newContent.remove("formatted_body")
        }
        if (!newContent.has("msgtype")) newContent.put("msgtype", "m.text")
        val edit = JSONObject()
            .put("msgtype", newContent.optString("msgtype"))
            .put("body", "* $text")
            .put("m.new_content", newContent)
            .put("m.relates_to", JSONObject().put("rel_type", "m.replace").put("event_id", eventId.value))
        room.sendRaw(post.post.eventType, edit.toString()).getOrThrow()
    }

    /** Likes or unlikes a post: a 👍 reaction (MSC4501 "Liking"). */
    suspend fun toggleLike(post: FeedPost): Result<Unit> = toggleReaction(post, Msc4501.LIKE_KEY)

    /**
     * Adds the user's [key] reaction to [post], or removes it if they already reacted with it. Done on the room directly
     * (the SDK's toggle only works for events loaded in an open timeline, and the feed doesn't keep one per room): the
     * user's existing reactions are looked up on the server, so it's right whatever the feed has loaded.
     */
    suspend fun toggleReaction(post: FeedPost, key: String): Result<Unit> = runCatching {
        val eventId = post.eventId
        if (eventId == null) {
            // Not sent yet: only its timeline knows it.
            val timeline = timelineStore.timeline(post.roomId) ?: throw followRequired(post)
            timeline.toggleReaction(key, post.eventOrTransactionId()).getOrThrow()
            return@runCatching
        }
        val room = timelineStore.joinedRoom(post.roomId) ?: throw followRequired(post)
        val mine = ownReactions(post.roomId, eventId.value, key)
        if (mine.isEmpty()) {
            room.sendRaw("m.reaction", SocialContentBuilder.reaction(eventId.value, key).toString()).getOrThrow()
        } else {
            mine.forEach { http.redact(post.roomId.value, it).getOrThrow() }
        }
    }

    /** The user's own [key] reactions to [eventId] (any variation selector), from the server. */
    private suspend fun ownReactions(roomId: RoomId, eventId: String, key: String): List<String> {
        val me = matrixClient.sessionId.value
        val wanted = key.replace("\uFE0F", "")
        val found = mutableListOf<String>()
        var from: String? = null
        repeat(MAX_RELATION_PAGES) {
            val page = http.relations(roomId.value, eventId, "m.annotation", from).getOrThrow()
            val chunk = page.optJSONArray("chunk")
            for (i in 0 until (chunk?.length() ?: 0)) {
                val event = chunk?.optJSONObject(i) ?: continue
                if (event.optString("type") != "m.reaction" || event.optString("sender") != me) continue
                val reactionKey = event.optJSONObject("content")?.optJSONObject("m.relates_to")?.optString("key") ?: continue
                if (reactionKey.replace("\uFE0F", "") == wanted) found += event.optString("event_id")
            }
            from = page.optString("next_batch").takeIf { it.isNotEmpty() } ?: return found
        }
        return found
    }

    suspend fun delete(post: FeedPost): Result<Unit> = runCatching {
        val eventId = post.eventId
        if (eventId != null) {
            if (timelineStore.joinedRoom(post.roomId) == null) throw SocialActionException("Not a member of this room")
            http.redact(post.roomId.value, eventId.value).getOrThrow()
        } else {
            // Not sent yet: cancelled through its timeline.
            val timeline = timelineStore.timeline(post.roomId) ?: throw SocialActionException("Not a member of this room")
            timeline.redactEvent(post.eventOrTransactionId(), reason = null).getOrThrow()
        }
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

private const val MAX_RELATION_PAGES = 10
