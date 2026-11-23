/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.libraries.matrix.api.core.UserId

/** Who reacted, and when (for the stock reaction summary sheet). */
data class ReactionSender(val userId: UserId, val timestamp: Long)

/** One reaction key on a post: each sender counted once, like Haven Desktop. */
data class ReactionSummary(
    val key: String,
    val count: Int,
    val byMe: Boolean,
    val senders: List<ReactionSender> = emptyList(),
)

/** One `m.reaction` event (or the SDK's view of one). */
data class RawReaction(val key: String, val sender: String, val timestamp: Long)

/**
 * The reaction key as counted: some clients and bridges send 👍 and 🔁 with a variation selector (U+FE0F), which is
 * still a like / repost (MSC4501). Other keys are kept as sent.
 */
fun reactionKey(key: String): String {
    val bare = key.replace("️", "")
    return if (bare == Msc4501.LIKE_KEY || bare == Msc4501.REPOST_KEY) bare else key
}

/** A post's reactions, grouped by key in order of first use, each sender counted once per key. */
fun reactionSummaries(reactions: List<RawReaction>, me: String): List<ReactionSummary> {
    val byKey = LinkedHashMap<String, LinkedHashMap<String, Long>>()
    reactions.sortedBy { it.timestamp }.forEach { reaction ->
        byKey.getOrPut(reactionKey(reaction.key)) { LinkedHashMap() }.putIfAbsent(reaction.sender, reaction.timestamp)
    }
    return byKey.mapNotNull { (key, senders) ->
        val list = senders.mapNotNull { (sender, ts) -> runCatching { ReactionSender(UserId(sender), ts) }.getOrNull() }
        ReactionSummary(key = key, count = senders.size, byMe = me in senders, senders = list).takeIf { it.count > 0 }
    }
}

/** The 👍 likes among [reactions] (shown on the Like button). */
fun List<ReactionSummary>.likes(): ReactionSummary? = firstOrNull { it.key == Msc4501.LIKE_KEY }

/** The 🔁 repost counts among [reactions] (shown on the Repost button). */
fun List<ReactionSummary>.reposts(): ReactionSummary? = firstOrNull { it.key == Msc4501.REPOST_KEY }

/** Whether [key] has its own action-bar button (Like, Repost) instead of a pill in the reactions shelf. */
fun isActionBarReaction(key: String): Boolean = key == Msc4501.LIKE_KEY || key == Msc4501.REPOST_KEY
