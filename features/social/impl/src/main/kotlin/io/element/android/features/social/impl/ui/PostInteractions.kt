/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryEvent
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryState
import io.element.android.features.messages.impl.timeline.components.reactionsummary.ReactionSummaryView
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.data.FollowRequiredException
import io.element.android.features.social.impl.data.OwnProfileState
import io.element.android.features.social.impl.data.SocialRoom
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.hide
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the post composer screen is for (see PostComposerScreen). */
sealed interface ComposeMode {
    /** A new post, to a room picked from [SocialDeps.repository]'s postable rooms. */
    data class NewPost(val defaultRoomId: RoomId?) : ComposeMode
    data class Reply(val parent: FeedPost) : ComposeMode
    data class Quote(val original: FeedPost) : ComposeMode
}

/** Pending dialogs and sheets for post interactions on a screen. */
class PostInteractionState {
    var reactTo by mutableStateOf<FeedPost?>(null)

    /** The post and reaction key whose senders are shown (the stock reaction summary sheet). */
    var reactionsOf by mutableStateOf<Pair<FeedPost, String>?>(null)
    var confirmDelete by mutableStateOf<FeedPost?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun rememberPostInteractionState() = remember { PostInteractionState() }

/** The standard post callbacks: interactions go through [SocialDeps.actions], navigation through [navigator]. */
@Composable
fun rememberPostCallbacks(deps: SocialDeps, navigator: SocialNavigator, state: PostInteractionState): PostCallbacks {
    val scope = rememberCoroutineScope()
    return remember(deps, navigator, state) {
        // Like Haven Desktop: replying, liking and reacting need a follow; reposting and quoting work on peeked posts.
        fun requireFollow(post: FeedPost): Boolean {
            if (!post.peeked) return false
            val via = listOf(post.roomId.value.substringAfter(':', ""), post.sender.value.substringAfter(':', ""))
                .filter { it.isNotEmpty() }
                .distinct()
            navigator.openFollow(post.roomId, post.eventId?.value, via)
            return true
        }
        val onError: (Throwable) -> Unit = { error ->
            if (error is FollowRequiredException) {
                navigator.openFollow(error.roomId, error.eventId, error.via)
            } else {
                state.error = error.message ?: error.toString()
            }
        }
        PostCallbacks(
            onOpenPost = { post ->
                post.eventId?.let {
                    // The post screen shows it right away instead of waiting for its thread to load.
                    deps.timelineStore.rememberOpenedPost(post)
                    navigator.openThread(post.roomId, it.value)
                }
            },
            onOpenUser = navigator::openUser,
            onOpenReference = { relation -> navigator.openThread(RoomId(relation.roomId), relation.eventId, relation.via) },
            onReply = { post -> if (!requireFollow(post)) navigator.openComposer(ComposeMode.Reply(post)) },
            // Shown at once; undone (with an error) only if sending fails.
            onRepost = { post ->
                if (!deps.actions.optimistic.isRepostPending(post)) {
                    scope.runAction(onError) { deps.actions.optimistic.repost(post) { deps.actions.repost(post) } }
                }
            },
            onQuote = { navigator.openComposer(ComposeMode.Quote(it)) },
            onLike = { post ->
                // [post] is as shown, including a pending like; one like at a time per post.
                if (!requireFollow(post) && !deps.actions.optimistic.isLikePending(post)) {
                    scope.runAction(onError) { deps.actions.optimistic.like(post, liked = !post.likedByMe) { deps.actions.toggleLike(post) } }
                }
            },
            onReact = { post -> if (!requireFollow(post)) state.reactTo = post },
            onToggleReaction = { post, key -> if (!requireFollow(post)) scope.runAction(onError) { deps.actions.toggleReaction(post, key) } },
            onShowReactions = { post, key -> state.reactionsOf = post to key },
            onDelete = { state.confirmDelete = it },
            onViewInRoom = { navigator.openRoomTimeline(it.roomId) },
            onViewSource = { post ->
                post.eventId?.let { eventId ->
                    deps.timelineStore.rememberOpenedPost(post)
                    navigator.openSource(post.roomId, eventId.value)
                }
            },
            onOpenEvent = { roomId, eventId -> navigator.openThread(roomId, eventId) },
            onOpenMedia = { post, media ->
                // The stock viewer needs the room open; for peeked posts, following comes first.
                if (!navigator.openMedia(post, media) && !requireFollow(post)) {
                    state.error = "Couldn't open this media"
                }
            },
        )
    }
}

private fun CoroutineScope.runAction(onError: (Throwable) -> Unit, action: suspend () -> Result<Unit>) {
    launch {
        action().onFailure(onError)
    }
}

private fun CoroutineScope.runAction(state: PostInteractionState, action: suspend () -> Result<Unit>) =
    runAction({ state.error = it.message ?: it.toString() }, action)

/** The sheets and dialogs driven by [PostInteractionState]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostInteractionOverlays(deps: SocialDeps, state: PostInteractionState) {
    val scope = rememberCoroutineScope()
    state.reactTo?.let { post ->
        val presenter = remember { deps.emojiPickerPresenterFactory.create(deps.getRecentEmojis) }
        val pickerState = presenter.present()
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        // Like the stock reaction sheet: above the keyboard when it's open (searching, or a freeform reaction).
        ModalBottomSheet(
            onDismissRequest = { state.reactTo = null },
            sheetState = sheetState,
            scrollable = false,
            modifier = Modifier.imePadding(),
        ) {
            // The stock emoji picker used for reacting to messages.
            deps.emojiPickerRenderer.Render(
                state = pickerState,
                onSelectEmoji = { emoji ->
                    sheetState.hide(scope) {
                        state.reactTo = null
                        scope.runAction(state) { deps.actions.toggleReaction(post, emoji.unicode) }
                    }
                },
                onSelectCustomEmoji = { key ->
                    sheetState.hide(scope) {
                        state.reactTo = null
                        scope.runAction(state) { deps.actions.toggleReaction(post, key) }
                    }
                },
                // Like Desktop's reaction picker: the user's own reactions are marked (picking one removes it).
                selectedEmojis = post.reactions.filter { it.byMe }.map { it.key }.toPersistentSet(),
                modifier = Modifier.fillMaxSize(),
                // Custom emoji from the room's and the favorite packs: the reaction's key is the image's mxc:// URL
                roomId = post.roomId,
                onSelectPackImage = { image ->
                    sheetState.hide(scope) {
                        state.reactTo = null
                        scope.runAction(state) { deps.actions.toggleReaction(post, image.url) }
                    }
                },
            )
        }
    }
    state.reactionsOf?.let { (post, key) ->
        ReactionsSummarySheet(deps, post, key, onDismiss = { state.reactionsOf = null })
    }
    state.confirmDelete?.let { post ->
        ConfirmationDialog(
            title = stringResource(R.string.social_delete_post),
            content = stringResource(R.string.social_delete_post_confirm),
            submitText = stringResource(R.string.social_delete),
            destructiveSubmit = true,
            onSubmitClick = {
                state.confirmDelete = null
                scope.runAction(state) { deps.actions.delete(post) }
            },
            onDismiss = { state.confirmDelete = null },
        )
    }
    state.error?.let { message ->
        ErrorDialog(content = message, onSubmit = { state.error = null })
    }
}

/** Rooms the user can post to: their own profile first, then joined groups, by name (like Haven Desktop). */
fun postableRooms(rooms: List<SocialRoom>, ownProfile: OwnProfileState): List<SocialRoom> {
    val ownProfileId = (ownProfile as? OwnProfileState.Linked)?.link?.roomId
    return rooms
        .filter { it.roomId == ownProfileId || it.kind == SocialRoomKind.Group }
        .sortedWith(compareBy<SocialRoom> { it.roomId != ownProfileId }.thenBy { it.info.name.orEmpty().lowercase() })
}

@Composable
internal fun RoomPicker(rooms: List<SocialRoom>, selected: RoomId?, ownProfileId: RoomId?, onSelect: (RoomId) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedRoom = rooms.firstOrNull { it.roomId == selected }
    Box {
        Row(
            modifier = Modifier.clickable { expanded = true }.padding(vertical = 4.dp),
        ) {
            Text(
                text = stringResource(R.string.social_post_to) + " " + (selectedRoom?.let { roomLabel(it, ownProfileId) } ?: "…"),
                style = ElementTheme.typography.fontBodyMdMedium,
                color = ElementTheme.colors.textActionAccent,
            )
            Icon(CompoundIcons.ChevronDown(), contentDescription = null, tint = ElementTheme.colors.iconSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (rooms.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.social_no_postable_rooms)) }, onClick = { expanded = false }, enabled = false)
            }
            rooms.forEach { room ->
                DropdownMenuItem(
                    text = { Text(roomLabel(room, ownProfileId)) },
                    onClick = {
                        expanded = false
                        onSelect(room.roomId)
                    },
                )
            }
        }
    }
}

@Composable
private fun roomLabel(room: SocialRoom, ownProfileId: RoomId?): String =
    if (room.roomId == ownProfileId) stringResource(R.string.social_your_profile) else room.info.name ?: room.roomId.value

/** "just now", "5m", "3h", then a short date, like Haven Desktop. */
fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestamp
    return when {
        diff < DateUtils.MINUTE_IN_MILLIS -> "just now"
        diff < DateUtils.HOUR_IN_MILLIS -> "${diff / DateUtils.MINUTE_IN_MILLIS}m"
        diff < DateUtils.DAY_IN_MILLIS -> "${diff / DateUtils.HOUR_IN_MILLIS}h"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timestamp))
    }
}

fun formatFullTime(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

/**
 * Who reacted to [post], and when: the stock reaction summary sheet (as for messages in rooms), opened on [key]'s tab.
 * Senders' names and avatars fill in as their profiles load.
 */
@Composable
private fun ReactionsSummarySheet(deps: SocialDeps, post: FeedPost, key: String, onDismiss: () -> Unit) {
    val eventId = post.eventId ?: return
    val me = deps.matrixClient.sessionId
    var users by remember(post) { mutableStateOf(emptyMap<UserId, MatrixUser>()) }
    LaunchedEffect(post) {
        post.reactions.flatMap { it.senders }.map { it.userId }.distinct().forEach { userId ->
            launch {
                val (name, avatarUrl) = deps.peek.profileOf(userId.value)
                users = users + (userId to MatrixUser(userId = userId, displayName = name, avatarUrl = avatarUrl))
            }
        }
    }
    val reactions = reactionsForSummary(post, me, deps.dateFormatter, users).toImmutableList()
    if (reactions.none { it.key == key }) return
    ReactionSummaryView(
        state = ReactionSummaryState(
            target = ReactionSummaryState.Summary(reactions = reactions, selectedKey = key, selectedEventId = eventId),
            eventSink = { event -> if (event is ReactionSummaryEvent.Clear) onDismiss() },
        ),
    )
}
