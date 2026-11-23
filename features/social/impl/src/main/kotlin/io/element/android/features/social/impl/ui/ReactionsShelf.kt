/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.element.android.features.messages.impl.timeline.components.TimelineItemReactionsView
import io.element.android.features.messages.impl.timeline.model.AggregatedReaction
import io.element.android.features.messages.impl.timeline.model.AggregatedReactionSender
import io.element.android.features.messages.impl.timeline.model.TimelineItemReactions
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.data.ReactionSummary
import io.element.android.features.social.impl.data.isActionBarReaction
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import kotlinx.collections.immutable.toImmutableList

/**
 * A post's reactions shelf, between its content and its action bar, like Haven Desktop's SocialReactionsRow: the stock
 * message reaction pills (Element X's own [TimelineItemReactionsView], with its "you reacted" highlight, tap to toggle,
 * add-reaction pill, and show more / less), not a reimplementation.
 *
 * 👍 and 🔁 have their own Like and Repost buttons showing their counts, so they're left out of the shelf, except in
 * developer mode, where every reaction is shown (as on Desktop).
 */
@Composable
fun ReactionsShelf(
    post: FeedPost,
    deps: SocialDeps,
    callbacks: PostCallbacks,
    modifier: Modifier = Modifier,
) {
    val developerModeFlow = remember(deps) { deps.appPreferencesStore.isDeveloperModeEnabledFlow() }
    val developerMode by developerModeFlow.collectAsState(initial = false)
    val shown = post.reactions.filter { developerMode || !isActionBarReaction(it.key) }
    if (shown.isEmpty()) return
    val me = deps.matrixClient.sessionId
    val reactionsState = remember(shown, me) { TimelineItemReactions(shown.map { it.toAggregated(me, deps.dateFormatter) }.toImmutableList()) }
    TimelineItemReactionsView(
        reactionsState = reactionsState,
        isOutgoing = false,
        // Reacting needs a follow (Desktop disables the pills otherwise): tapping one on a peeked post offers to follow.
        userCanSendReaction = !post.peeked,
        onReactionClick = { key -> callbacks.onToggleReaction(post, key) },
        onReactionLongClick = { key -> callbacks.onShowReactions(post, key) },
        onMoreReactionsClick = { callbacks.onReact(post) },
        modifier = modifier.padding(top = 8.dp),
    )
}

/** [post]'s reactions for the stock reaction summary sheet (who reacted, and when), developer mode or not. */
fun reactionsForSummary(post: FeedPost, me: UserId, dateFormatter: DateFormatter, users: Map<UserId, MatrixUser> = emptyMap()) =
    post.reactions.map { it.toAggregated(me, dateFormatter, users) }

private fun ReactionSummary.toAggregated(me: UserId, dateFormatter: DateFormatter, users: Map<UserId, MatrixUser> = emptyMap()) =
    AggregatedReaction(
        currentUserId = me,
        key = key,
        senders = senders.sortedByDescending { it.timestamp }.map { sender ->
            AggregatedReactionSender(
                senderId = sender.userId,
                timestamp = sender.timestamp,
                sentTime = dateFormatter.format(sender.timestamp, DateFormatterMode.TimeOrDate),
                user = users[sender.userId],
            )
        }.toImmutableList(),
    )
