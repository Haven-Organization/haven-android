/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.msc

import org.json.JSONArray
import org.json.JSONObject

/** A post referenced by a repost, quote post or cross-posted reply. */
data class PostReference(
    val eventId: String,
    val roomId: String,
    val sender: String,
    val displayname: String?,
    val via: List<String>,
    /** The referenced post's full `content` JSON at reference time (MSC4501 recommends embedding it). */
    val contentJson: String?,
)

/**
 * Builds the event contents Haven sends, following MSC4501 and matching Haven Desktop.
 *
 * Posts are sent as `m.room.message` (MSC4501 phase 1, Haven Desktop's default), so every Matrix client can read them.
 */
object SocialContentBuilder {
    /** MSC4501 phase 1: post as `m.room.message`. */
    const val POST_EVENT_TYPE = "m.room.message"

    /** A text post; [html] its formatting and custom emoji, when it has any. */
    fun post(body: String, html: String? = null): JSONObject = JSONObject()
        .put("msgtype", "m.text")
        .put("body", body)
        .apply { if (html != null) put("format", "org.matrix.custom.html").put("formatted_body", html) }

    /** A reply: an `m.thread` relation to the thread root, with `m.in_reply_to` the post being replied to (MSC4501 "Posts"). */
    fun reply(body: String, threadRootId: String, parentId: String): JSONObject = post(body)
        .put(
            "m.relates_to",
            JSONObject()
                .put("rel_type", "m.thread")
                .put("event_id", threadRootId)
                .put("is_falling_back", false)
                .put("m.in_reply_to", JSONObject().put("event_id", parentId)),
        )

    /** MSC4501 "Cross-posted reply": a copy of a reply, posted to the replier's own profile for their followers. */
    fun crossPostedReply(body: String, parent: PostReference, html: String? = null): JSONObject = post(body, html)
        .put(Msc4501.RELATES_TO, relatesTo(Msc4501.REL_TYPE_REPLY, parent))

    /**
     * MSC4501 "Reposts": the outer body is only a permalink to the reposted post (so clients without MSC4501 show a
     * link), and the original is embedded in `relates_to.content`.
     */
    fun repost(permalink: String, original: PostReference): JSONObject = post(permalink)
        .put(Msc4501.RELATES_TO, relatesTo(Msc4501.REL_TYPE_REPOST, original))

    /** MSC4501 "Quote Posts": the outer body is the commentary, the original is embedded in `relates_to`. */
    fun quotePost(commentary: String, original: PostReference, html: String? = null): JSONObject = post(commentary, html)
        .put(Msc4501.RELATES_TO, relatesTo(Msc4501.REL_TYPE_REPOST, original))

    /** An `m.reaction` annotation: 👍 is a like (MSC4501 "Liking"), 🔁 counts a repost (MSC4501 "Repost counts"). */
    fun reaction(eventId: String, key: String): JSONObject = JSONObject()
        .put(
            "m.relates_to",
            JSONObject()
                .put("rel_type", "m.annotation")
                .put("event_id", eventId)
                .put("key", key),
        )

    private fun relatesTo(relType: String, ref: PostReference): JSONObject {
        val block = JSONObject()
            .put("rel_type", relType)
            .put("event_id", ref.eventId)
            .put("room_id", ref.roomId)
            .put("sender", ref.sender)
        if (ref.via.isNotEmpty()) block.put("via", JSONArray(ref.via))
        ref.displayname?.takeIf { it.isNotEmpty() }?.let { block.put("displayname", it) }
        ref.contentJson?.let { json -> runCatching { JSONObject(json) }.getOrNull() }?.let { block.put("content", it) }
        return block
    }
}
