/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.msc

import io.element.android.libraries.matrix.ui.formattedname.FormattedName
import io.element.android.libraries.matrix.ui.formattedname.formattedDisplayNameIn
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
    /** MSC4144 per-message profile (`com.beeper.per_message_profile`), e.g. a bridged post's real author. */
    val perMessageProfile: PerMessageProfile? = null,
)

/** MSC4144, read like Haven Desktop's PerMessageProfile.ts. */
data class PerMessageProfile(
    val id: String,
    val displayname: String?,
    /** An mxc:// URL, or null when not set (or not an mxc:// URL). */
    val avatarUrl: String?,
    /** `avatar_url` set to "": explicitly no avatar. */
    val avatarRemoved: Boolean,
    /** Haven: the MSC4589 formatted display name, if it has one (checked against [displayname] where it's shown). */
    val formattedDisplayname: FormattedName? = null,
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
            (
                relation.contentInline ||
                    isPermalinkTo(rawBody.trim(), relation.roomId, relation.eventId) ||
                    // MSC4501's body fields present but empty: no commentary, whatever the stock body says (e.g. a
                    // bridge's "🔁 X boosted Y's post" line for ordinary timelines).
                    hasEmptyBodyOverride(content)
                )
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

    /**
     * Applies MSC4501's rule: `org.matrix.msc4501.social.body` / `social.formatted_body`, when present (even empty), are
     * shown in place of `body` / `formatted_body`. Senders (e.g. bridges) use them to leave out what's only there for
     * ordinary room timelines, like a "⤵️ Reply to X's post:" header line.
     *
     * With only `social.body`, the stock `formatted_body` isn't used either: it's the timeline version of the text
     * (header included), which `social.body` replaces.
     *
     * Without either field, the older `software.haven.remove_header` flag (from before them, still honoured like Haven
     * Desktop does) removes the stock fields' first line.
     */
    fun parseContent(content: JSONObject, eventType: String? = null, inlineRepost: Boolean = false): PostContent {
        val hasOverride = content.has(Msc4501.BODY) || content.has(Msc4501.FORMATTED_BODY)
        var body = if (content.has(Msc4501.BODY)) content.optString(Msc4501.BODY) else content.optString("body")
        var formattedBody = when {
            content.has(Msc4501.FORMATTED_BODY) -> content.optString(Msc4501.FORMATTED_BODY)
            content.has(Msc4501.BODY) -> null
            content.optString("format") == "org.matrix.custom.html" -> content.optStringOrNull("formatted_body")
            else -> null
        }
        if (!hasOverride && content.optBoolean(Msc4501.REMOVE_HEADER, false)) {
            formattedBody = formattedBody?.replace(LEADING_HTML_LINE, "")
            // Not a bare permalink: that's a repost's fallback, not a header.
            if (!isBarePermalink(body)) body = body.replace(LEADING_TEXT_LINE, "")
        }
        val msgtype = content.optStringOrNull("msgtype")
        val media = parseMedia(content, msgtype, eventType)
        // Not post text: a bare event permalink in the stock body is only the fallback MSC4501 puts there for clients
        // that don't know MSC4501 (e.g. a repost's body), and an inline repost of media has the outer event's own
        // filler (permalink or filename) as its stock body/formatted_body, like Haven Desktop treats them. The
        // m.social.* fields are always real text.
        if (!hasOverride && ((inlineRepost && media != null) || isBarePermalink(body))) {
            body = ""
            formattedBody = null
        }
        return PostContent(
            msgtype = msgtype,
            body = stripReplyFallback(body),
            formattedBody = formattedBody?.let(::stripHtmlReplyFallback),
            media = media,
            // An inline repost's outer content is the reposter's own event: its per-message profile is theirs.
            perMessageProfile = if (inlineRepost) null else parsePerMessageProfile(content),
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
        // A `file` object is only encrypted media (EncryptedFile) when it carries the key: some senders put a plain
        // {url, name, mimetype} there (Haven Desktop reads it that way too), which must load as ordinary media.
        val file = content.optJSONObject("file")
        val encryptedFile = file?.takeIf { it.isEncryptedFile() }
        val info = content.optJSONObject("info")
        val thumbnailFile = info?.optJSONObject("thumbnail_file")
        return PostMedia(
            kind = kind,
            url = file?.optStringOrNull("url") ?: content.optStringOrNull("url"),
            encryptedFileJson = encryptedFile?.toString(),
            mimetype = info?.optStringOrNull("mimetype"),
            width = info?.optInt("w", 0)?.takeIf { it > 0 },
            height = info?.optInt("h", 0)?.takeIf { it > 0 },
            filename = content.optStringOrNull("filename") ?: content.optString("body").takeIf { !isBarePermalink(it) },
            thumbnailUrl = info?.optStringOrNull("thumbnail_url") ?: thumbnailFile?.optStringOrNull("url"),
            thumbnailFileJson = thumbnailFile?.takeIf { it.isEncryptedFile() }?.toString(),
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
            contentInline -> parseContent(JSONObject(outerContent.toString()).apply { remove(Msc4501.RELATES_TO) }, inlineRepost = true)
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

    private fun parsePerMessageProfile(content: JSONObject): PerMessageProfile? {
        val raw = content.optJSONObject("com.beeper.per_message_profile") ?: return null
        val id = raw.optStringOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
        val avatar = raw.optStringOrNull("avatar_url")
        return PerMessageProfile(
            id = id,
            displayname = raw.optStringOrNull("displayname")?.takeIf { it.isNotEmpty() },
            avatarUrl = avatar?.takeIf { it.startsWith("mxc://") },
            avatarRemoved = avatar == "",
            formattedDisplayname = formattedDisplayNameIn(raw, raw.optStringOrNull("displayname")),
        )
    }

    /** MSC4501's body fields present, and both empty: the sender says there's no text to show as a post. */
    private fun hasEmptyBodyOverride(content: JSONObject): Boolean =
        (content.has(Msc4501.BODY) || content.has(Msc4501.FORMATTED_BODY)) &&
            content.optString(Msc4501.BODY).isBlank() &&
            content.optString(Msc4501.FORMATTED_BODY).isBlank()

    // software.haven.remove_header: the first line, as bridges send it (a top-level <p> and following <br>s in HTML; a
    // line and following blank lines in plain text), like Haven Desktop's stripLeadingHeaderLine.
    private val LEADING_HTML_LINE = Regex("""^\s*<p>.*?</p>\s*(<br\s*/?>\s*)*""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val LEADING_TEXT_LINE = Regex("""^[^\n]*\n(?:[ \t]*\n)*""")

    /** True when [text] is nothing but an event permalink (matrix.to or matrix: URI). */
    fun isBarePermalink(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() } && parsePermalink(trimmed) != null
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

/** An EncryptedFile (Matrix spec): has the key and IV needed to decrypt the media. */
internal fun JSONObject.isEncryptedFile() = has("key") && has("iv") && has("hashes")
