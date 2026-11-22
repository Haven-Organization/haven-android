/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.msc

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** The renderable part of a post's content (or of an embedded, referenced post). */
data class PostContent(
    val msgtype: String?,
    /** Plain text, after applying MSC4501's `m.social.body` override. */
    val body: String,
    /** HTML, after applying MSC4501's `m.social.formatted_body` override, or null. */
    val formattedBody: String?,
    val media: PostMedia?,
)

data class PostMedia(
    val kind: Kind,
    /** mxc:// URL, for unencrypted media. */
    val url: String?,
    /** JSON of the `file` object, for encrypted media. */
    val encryptedFileJson: String?,
    val mimetype: String?,
    val width: Int?,
    val height: Int?,
    val filename: String?,
    /** Thumbnail mxc:// URL (unencrypted) and `thumbnail_file` JSON (encrypted), e.g. a video's poster frame. */
    val thumbnailUrl: String? = null,
    val thumbnailFileJson: String? = null,
    val blurhash: String? = null,
    /** Milliseconds, for video and audio. */
    val durationMs: Long? = null,
    val size: Long? = null,
) {
    enum class Kind { Image, Video, Audio, File, Sticker }
}

/** An MSC4501 `m.social.relates_to` block. */
data class PostRelation(
    val relType: RelType,
    val eventId: String,
    val roomId: String,
    val sender: String,
    val displayname: String?,
    val via: List<String>,
    /** The embedded copy of the referenced post (`content`), or the outer content when `content_inline` is true. */
    val content: PostContent?,
    val contentInline: Boolean,
) {
    enum class RelType { Repost, Reply, Unknown }
}

/** A post (an `m.room.message`, MSC4501 post, sticker or poll) parsed from its raw event JSON. */
data class SocialPost(
    val eventType: String,
    val content: PostContent,
    val relation: PostRelation?,
    /** Root of the `m.thread` this post replies in, if any. */
    val threadRootId: String?,
    /** Direct parent (`m.in_reply_to`), if any. */
    val inReplyToId: String?,
    /** A plain repost (no commentary): don't render the outer body, only "X reposted Y's post" and the original. */
    val isPlainRepost: Boolean,
    /** Thread reply count bundled by the server, when known. */
    val bundledReplyCount: Int,
    val isEdited: Boolean,
) {
    val isRepost get() = relation?.relType == PostRelation.RelType.Repost
    val isQuotePost get() = isRepost && !isPlainRepost
    val isCrossPostedReply get() = relation?.relType == PostRelation.RelType.Reply
}

object SocialPostParser {
    /**
     * @param originalJson the full original event JSON.
     * @param latestEditJson the full JSON of the latest `m.replace` edit, if any; its `m.new_content` is used.
     */
    fun parse(originalJson: String?, latestEditJson: String? = null): SocialPost? {
        val event = originalJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val type = event.optString("type")
        if (!Msc4501.isFeedEventType(type)) return null
        val originalContent = event.optJSONObject("content") ?: return null
        val editedContent = latestEditJson
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("content")
            ?.optJSONObject("m.new_content")
        // An edit's m.new_content replaces the content, but relations (thread, MSC4501 relates_to) stay those of the original.
        val content = editedContent ?: originalContent
        val relatesTo = originalContent.optJSONObject("m.relates_to")
        val threadRootId = relatesTo?.takeIf { it.optString("rel_type") == "m.thread" }?.optStringOrNull("event_id")
        val inReplyToId = relatesTo?.optJSONObject("m.in_reply_to")?.optStringOrNull("event_id")
        val relation = (content.optJSONObject(Msc4501.RELATES_TO) ?: originalContent.optJSONObject(Msc4501.RELATES_TO))
            ?.let { parseRelation(it, outerContent = content) }
        val rawBody = content.optString("body")
        val isPlainRepost = relation?.relType == PostRelation.RelType.Repost &&
            (relation.contentInline || isPermalinkTo(rawBody.trim(), relation.roomId, relation.eventId))
        val bundledReplyCount = event.optJSONObject("unsigned")
            ?.optJSONObject("m.relations")
            ?.optJSONObject("m.thread")
            ?.optInt("count", 0) ?: 0
        return SocialPost(
            eventType = type,
            content = parseContent(content, eventType = type),
            relation = relation,
            threadRootId = threadRootId,
            inReplyToId = inReplyToId,
            isPlainRepost = isPlainRepost,
            bundledReplyCount = bundledReplyCount,
            isEdited = editedContent != null,
        )
    }

    /** Applies MSC4501's rule: `m.social.body`/`formatted_body`, when present (even empty), replace `body`/`formatted_body`. */
    fun parseContent(content: JSONObject, eventType: String? = null): PostContent {
        val body = if (content.has(Msc4501.BODY)) content.optString(Msc4501.BODY) else content.optString("body")
        val formattedBody = when {
            content.has(Msc4501.FORMATTED_BODY) -> content.optString(Msc4501.FORMATTED_BODY)
            content.optString("format") == "org.matrix.custom.html" -> content.optStringOrNull("formatted_body")
            else -> null
        }
        val msgtype = content.optStringOrNull("msgtype")
        return PostContent(
            msgtype = msgtype,
            body = stripReplyFallback(body),
            formattedBody = formattedBody?.let(::stripHtmlReplyFallback),
            media = parseMedia(content, msgtype, eventType),
        )
    }

    private fun parseMedia(content: JSONObject, msgtype: String?, eventType: String?): PostMedia? {
        val kind = when {
            eventType == "m.sticker" -> PostMedia.Kind.Sticker
            msgtype == "m.image" -> PostMedia.Kind.Image
            msgtype == "m.video" -> PostMedia.Kind.Video
            msgtype == "m.audio" -> PostMedia.Kind.Audio
            msgtype == "m.file" -> PostMedia.Kind.File
            else -> return null
        }
        val file = content.optJSONObject("file")
        val info = content.optJSONObject("info")
        return PostMedia(
            kind = kind,
            url = file?.optStringOrNull("url") ?: content.optStringOrNull("url"),
            encryptedFileJson = file?.toString(),
            mimetype = info?.optStringOrNull("mimetype"),
            width = info?.optInt("w", 0)?.takeIf { it > 0 },
            height = info?.optInt("h", 0)?.takeIf { it > 0 },
            filename = content.optStringOrNull("filename") ?: content.optString("body"),
            thumbnailUrl = info?.optStringOrNull("thumbnail_url"),
            thumbnailFileJson = info?.optJSONObject("thumbnail_file")?.toString(),
            blurhash = info?.optStringOrNull("xyz.amorgan.blurhash"),
            durationMs = info?.optLong("duration", 0)?.takeIf { it > 0 },
            size = info?.optLong("size", 0)?.takeIf { it > 0 },
        )
    }

    private fun parseRelation(block: JSONObject, outerContent: JSONObject): PostRelation? {
        val eventId = block.optStringOrNull("event_id") ?: return null
        val roomId = block.optStringOrNull("room_id") ?: return null
        val relType = when (block.optString("rel_type")) {
            Msc4501.REL_TYPE_REPOST -> PostRelation.RelType.Repost
            Msc4501.REL_TYPE_REPLY -> PostRelation.RelType.Reply
            else -> PostRelation.RelType.Unknown
        }
        // content_inline is only meaningful for reposts (MSC4501 "Cross-room post references").
        val contentInline = relType == PostRelation.RelType.Repost && block.optBoolean("content_inline", false)
        val embedded = when {
            contentInline -> parseContent(JSONObject(outerContent.toString()).apply { remove(Msc4501.RELATES_TO) })
            else -> block.optJSONObject("content")?.let { parseContent(it) }
        }
        return PostRelation(
            relType = relType,
            eventId = eventId,
            roomId = roomId,
            sender = block.optString("sender"),
            displayname = block.optStringOrNull("displayname"),
            via = block.optJSONArray("via").toStringList(),
            content = embedded,
            contentInline = contentInline,
        )
    }

    /** True when [text] is a matrix.to or matrix: permalink to [eventId] in [roomId] (MSC4501 "Reposts"). */
    fun isPermalinkTo(text: String, roomId: String, eventId: String): Boolean {
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        val (linkRoom, linkEvent) = parsePermalink(text) ?: return false
        return linkRoom == roomId && linkEvent == eventId
    }

    /** Returns (room id or alias, event id) for an event permalink. */
    fun parsePermalink(text: String): Pair<String, String>? {
        return when {
            text.startsWith("https://matrix.to/#/") -> {
                val path = text.removePrefix("https://matrix.to/#/").substringBefore('?')
                val parts = path.split('/').map { Uri.decode(it) }
                if (parts.size >= 2 && parts[1].startsWith("$")) parts[0] to parts[1] else null
            }
            text.startsWith("matrix:") -> {
                val parts = text.removePrefix("matrix:").substringBefore('?').split('/').map { Uri.decode(it) }
                // matrix:roomid/<id>/e/<event> or matrix:r/<alias>/e/<event>
                if (parts.size >= 4 && parts[2] == "e") {
                    val room = when (parts[0]) {
                        "roomid" -> "!" + parts[1]
                        "r" -> "#" + parts[1]
                        else -> return null
                    }
                    room to "$" + parts[3]
                } else {
                    null
                }
            }
            else -> null
        }
    }

    /** Removes a legacy rich-reply fallback ("> <@user> text" lines, then a blank line) from a plain body. */
    private fun stripReplyFallback(body: String): String {
        if (!body.startsWith("> ")) return body
        val lines = body.lines()
        val firstNonQuote = lines.indexOfFirst { !it.startsWith(">") }
        if (firstNonQuote < 0) return body
        return lines.drop(firstNonQuote).dropWhile { it.isBlank() }.joinToString("\n")
    }

    private fun stripHtmlReplyFallback(html: String): String {
        return html.replace(Regex("(?s)<mx-reply>.*?</mx-reply>"), "")
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }
}
