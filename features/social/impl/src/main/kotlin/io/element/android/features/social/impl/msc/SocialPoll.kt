/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.msc

import io.element.android.libraries.matrix.api.poll.PollAnswer
import io.element.android.libraries.matrix.api.poll.PollKind
import org.json.JSONArray
import org.json.JSONObject

/** A poll's question and answers, from its start event's content (stable `m.poll.start` or unstable MSC3381). */
data class PollStart(
    val question: String,
    val answers: List<PollAnswer>,
    val kind: PollKind,
    /** At least 1. */
    val maxSelections: Int,
)

/**
 * Reads poll start content and poll responses from raw event JSON, so polls work the same whatever the post came from
 * (live timeline, feed sync, peek, cache), without the SDK's timeline aggregation.
 */
object SocialPollParser {
    const val START = "m.poll.start"
    const val START_UNSTABLE = "org.matrix.msc3381.poll.start"
    const val RESPONSE = "m.poll.response"
    const val RESPONSE_UNSTABLE = "org.matrix.msc3381.poll.response"
    const val END = "m.poll.end"
    const val END_UNSTABLE = "org.matrix.msc3381.poll.end"

    private const val STABLE_POLL_KEY = "m.poll"
    private const val STABLE_SELECTIONS_KEY = "m.selections"
    private const val UNSTABLE_TEXT_KEY = "org.matrix.msc1767.text"
    private const val UNSTABLE_MESSAGE_KEY = "org.matrix.msc1767.message"

    /** The spec's limit on the number of answers. */
    private const val MAX_ANSWERS = 20

    fun isStartEventType(type: String?) = type == START || type == START_UNSTABLE
    fun isResponseEventType(type: String?) = type == RESPONSE || type == RESPONSE_UNSTABLE
    fun isEndEventType(type: String?) = type == END || type == END_UNSTABLE

    /** The poll in a poll start event's [content], or null when it isn't one (or has no usable answers). */
    fun parseStart(content: JSONObject): PollStart? {
        content.optJSONObject(STABLE_POLL_KEY)?.let { poll ->
            return parsePoll(
                poll = poll,
                answerIdKey = "m.id",
                isDisclosed = poll.optString("kind") == "m.disclosed",
            )
        }
        content.optJSONObject(START_UNSTABLE)?.let { poll ->
            return parsePoll(
                poll = poll,
                answerIdKey = "id",
                isDisclosed = poll.optString("kind") == "org.matrix.msc3381.poll.disclosed",
            )
        }
        return null
    }

    /**
     * The answer ids selected in a poll response event's [content], or null when it has no selections field at all
     * (e.g. redacted). An empty list is a valid response: it spoils the sender's vote.
     */
    fun parseResponseSelections(content: JSONObject): List<String>? {
        val array = content.optJSONArray(STABLE_SELECTIONS_KEY)
            ?: content.optJSONObject(RESPONSE_UNSTABLE)?.optJSONArray("answers")
            ?: return null
        return (0 until array.length()).mapNotNull { array.opt(it) as? String }
    }

    private fun parsePoll(poll: JSONObject, answerIdKey: String, isDisclosed: Boolean): PollStart? {
        val question = poll.optJSONObject("question")?.let(::textOf).orEmpty()
        val answersJson = poll.optJSONArray("answers") ?: return null
        val seen = mutableSetOf<String>()
        val answers = (0 until answersJson.length())
            .mapNotNull { answersJson.optJSONObject(it) }
            .mapNotNull { answer ->
                val id = answer.optStringOrNull(answerIdKey)?.takeIf { it.isNotEmpty() && seen.add(it) } ?: return@mapNotNull null
                PollAnswer(id = id, text = textOf(answer).orEmpty())
            }
            .take(MAX_ANSWERS)
        if (answers.isEmpty()) return null
        return PollStart(
            question = question,
            answers = answers,
            kind = if (isDisclosed) PollKind.Disclosed else PollKind.Undisclosed,
            maxSelections = poll.optInt("max_selections", 1).coerceAtLeast(1),
        )
    }

    /**
     * The plain text of an extensible event text block: stable `m.text` (an array of representations, plain text
     * preferred), unstable `org.matrix.msc1767.text` (a string) or `org.matrix.msc1767.message` (an array), or `body`.
     */
    private fun textOf(block: JSONObject): String? {
        block.optJSONArray("m.text")?.let(::plainTextOf)?.let { return it }
        block.optStringOrNull(UNSTABLE_TEXT_KEY)?.let { return it }
        block.optJSONArray(UNSTABLE_MESSAGE_KEY)?.let(::plainTextOf)?.let { return it }
        return block.optStringOrNull("body")
    }

    private fun plainTextOf(representations: JSONArray): String? {
        val items = (0 until representations.length()).mapNotNull { representations.optJSONObject(it) }
        val plain = items.firstOrNull { (it.optStringOrNull("mimetype") ?: "text/plain") == "text/plain" } ?: items.firstOrNull()
        return plain?.optStringOrNull("body")
    }
}
