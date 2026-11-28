/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.messages.impl.timeline.factories.event

import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.ThumbnailInfo
import io.element.android.libraries.matrix.api.media.VideoInfo
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EmoteMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.EventTimelineItem
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.api.timeline.item.event.MessageType
import io.element.android.libraries.matrix.api.timeline.item.event.NoticeMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import org.json.JSONObject
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * Haven: MSC4501 posts sent with their own event type (`org.matrix.msc4501.social.post`, and MSC3639's older ones) have
 * the same content as `m.room.message` (msgtype, body, formatted_body, media), but the SDK doesn't know the type, so it
 * has no message for them. This reads that content from the event's JSON, so such a post is a message everywhere a
 * message is: shown as one in the room, and with the same actions (copying, forwarding, ...).
 *
 * The latest edit's `m.new_content` is used when the post has been edited.
 */
internal object HavenSocialPostContent {
    private val POST_TYPES = setOf(
        "org.matrix.msc4501.social.post",
        "org.matrix.msc3639.social.post",
        "org.matrix.msc3639.social.comment",
    )

    // MSC4501: a post's own text when its `body` is a fallback (e.g. a repost's permalink)
    private const val SOCIAL_BODY = "org.matrix.msc4501.social.body"
    private const val SOCIAL_FORMATTED_BODY = "org.matrix.msc4501.social.formatted_body"

    /** Whether [type] is an MSC4501 (or MSC3639) post event type. */
    fun isPostType(type: String?): Boolean = type in POST_TYPES

    /** The message [item] is, when it's such a post; null for anything else. */
    fun messageContent(item: EventTimelineItem): MessageContent? = runCatching {
        val debugInfo = item.timelineItemDebugInfoProvider()
        val event = debugInfo.originalJson?.let(::JSONObject) ?: return null
        if (!isPostType(event.optString("type"))) return null
        val original = event.optJSONObject("content") ?: return null
        val edited = debugInfo.latestEditedJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("content")
            ?.optJSONObject("m.new_content")
        MessageContent(
            body = bodyOf(edited ?: original),
            inReplyTo = null,
            isEdited = edited != null,
            threadInfo = null,
            type = messageType(edited ?: original),
        )
    }.onFailure { Timber.w(it, "Couldn't read the post %s", item.eventId) }.getOrNull()

    private fun bodyOf(content: JSONObject): String =
        if (content.has(SOCIAL_BODY)) content.optString(SOCIAL_BODY) else content.optString("body")

    private fun formattedOf(content: JSONObject): FormattedBody? = when {
        content.has(SOCIAL_FORMATTED_BODY) -> FormattedBody(MessageFormat.HTML, content.optString(SOCIAL_FORMATTED_BODY))
        content.has(SOCIAL_BODY) -> null
        content.optString("format") == "org.matrix.custom.html" && content.has("formatted_body") ->
            FormattedBody(MessageFormat.HTML, content.optString("formatted_body"))
        else -> null
    }

    private fun messageType(content: JSONObject): MessageType {
        val body = bodyOf(content)
        val formatted = formattedOf(content)
        val source = mediaSource(content)
        val info = content.optJSONObject("info")
        // A media message's body is its caption when it has a filename of its own
        val filename = content.optString("filename").takeIf { it.isNotEmpty() } ?: body
        val caption = body.takeIf { content.has("filename") && it != filename }
        return when (content.optString("msgtype")) {
            "m.notice" -> NoticeMessageType(body, formatted)
            "m.emote" -> EmoteMessageType(body, formatted)
            "m.image" -> if (source == null) TextMessageType(body, formatted) else ImageMessageType(
                filename = filename,
                caption = caption,
                formattedCaption = formatted.takeIf { caption != null },
                source = source,
                info = info?.let {
                    ImageInfo(
                        height = it.optLongOrNull("h"),
                        width = it.optLongOrNull("w"),
                        mimetype = it.optStringOrNull("mimetype"),
                        size = it.optLongOrNull("size"),
                        thumbnailInfo = thumbnailInfo(it),
                        thumbnailSource = thumbnailSource(it),
                        blurhash = blurhash(it),
                    )
                },
            )
            "m.video" -> if (source == null) TextMessageType(body, formatted) else VideoMessageType(
                filename = filename,
                caption = caption,
                formattedCaption = formatted.takeIf { caption != null },
                source = source,
                info = info?.let {
                    VideoInfo(
                        duration = it.optLongOrNull("duration")?.milliseconds,
                        height = it.optLongOrNull("h"),
                        width = it.optLongOrNull("w"),
                        mimetype = it.optStringOrNull("mimetype"),
                        size = it.optLongOrNull("size"),
                        thumbnailInfo = thumbnailInfo(it),
                        thumbnailSource = thumbnailSource(it),
                        blurhash = blurhash(it),
                    )
                },
            )
            "m.audio" -> if (source == null) TextMessageType(body, formatted) else AudioMessageType(
                filename = filename,
                caption = caption,
                formattedCaption = formatted.takeIf { caption != null },
                source = source,
                info = info?.let {
                    AudioInfo(
                        duration = it.optLongOrNull("duration")?.milliseconds,
                        size = it.optLongOrNull("size"),
                        mimetype = it.optStringOrNull("mimetype"),
                    )
                },
            )
            "m.file" -> if (source == null) TextMessageType(body, formatted) else FileMessageType(
                filename = filename,
                caption = caption,
                formattedCaption = formatted.takeIf { caption != null },
                source = source,
                info = info?.let {
                    FileInfo(
                        mimetype = it.optStringOrNull("mimetype"),
                        size = it.optLongOrNull("size"),
                        thumbnailInfo = thumbnailInfo(it),
                        thumbnailSource = thumbnailSource(it),
                    )
                },
            )
            else -> TextMessageType(body, formatted)
        }
    }

    /** The media: `url`, or the encrypted `file` (with its keys). */
    private fun mediaSource(content: JSONObject): MediaSource? {
        content.optStringOrNull("url")?.let { return MediaSource(it) }
        val file = content.optJSONObject("file") ?: return null
        return file.optStringOrNull("url")?.let { MediaSource(it, file.toString()) }
    }

    private fun thumbnailSource(info: JSONObject): MediaSource? {
        info.optStringOrNull("thumbnail_url")?.let { return MediaSource(it) }
        val file = info.optJSONObject("thumbnail_file") ?: return null
        return file.optStringOrNull("url")?.let { MediaSource(it, file.toString()) }
    }

    private fun thumbnailInfo(info: JSONObject): ThumbnailInfo? = info.optJSONObject("thumbnail_info")?.let {
        ThumbnailInfo(
            height = it.optLongOrNull("h"),
            width = it.optLongOrNull("w"),
            mimetype = it.optStringOrNull("mimetype"),
            size = it.optLongOrNull("size"),
        )
    }

    private fun blurhash(info: JSONObject): String? = info.optStringOrNull("blurhash") ?: info.optStringOrNull("xyz.amorgan.blurhash")

    private fun JSONObject.optStringOrNull(key: String): String? = optString(key).takeIf { has(key) && it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key)) (opt(key) as? Number)?.toLong() else null
}
