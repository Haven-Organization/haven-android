/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the composer sheet is for. */
sealed interface ComposeMode {
    /** A new post, to a room picked from [SocialDeps.repository]'s postable rooms. */
    data class NewPost(val defaultRoomId: RoomId?) : ComposeMode
    data class Reply(val parent: FeedPost) : ComposeMode
    data class Quote(val original: FeedPost) : ComposeMode
}

/** Pending dialogs and sheets for post interactions on a screen. */
class PostInteractionState {
    var compose by mutableStateOf<ComposeMode?>(null)
    var reactTo by mutableStateOf<FeedPost?>(null)
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
            onOpenPost = { post -> post.eventId?.let { navigator.openThread(post.roomId, it.value) } },
            onOpenUser = navigator::openUser,
            onOpenReference = { relation -> navigator.openThread(RoomId(relation.roomId), relation.eventId, relation.via) },
            onReply = { post -> if (!requireFollow(post)) state.compose = ComposeMode.Reply(post) },
            onRepost = { post -> scope.runAction(onError) { deps.actions.repost(post) } },
            onQuote = { state.compose = ComposeMode.Quote(it) },
            onLike = { post -> if (!requireFollow(post)) scope.runAction(onError) { deps.actions.toggleLike(post) } },
            onReact = { post -> if (!requireFollow(post)) state.reactTo = post },
            onToggleReaction = { post, key -> if (!requireFollow(post)) scope.runAction(onError) { deps.actions.toggleReaction(post, key) } },
            onDelete = { state.confirmDelete = it },
            onViewInRoom = { navigator.openRoomTimeline(it.roomId) },
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
    state.compose?.let { mode ->
        ComposeSheet(deps = deps, mode = mode, onDismiss = { state.compose = null }, onError = { state.error = it })
    }
    state.reactTo?.let { post ->
        val presenter = remember { deps.emojiPickerPresenterFactory.create(deps.getRecentEmojis) }
        val pickerState = presenter.present()
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        ModalBottomSheet(onDismissRequest = { state.reactTo = null }, sheetState = sheetState, scrollable = false) {
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
                selectedEmojis = persistentSetOf(),
                modifier = Modifier.fillMaxSize(),
            )
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposeSheet(
    deps: SocialDeps,
    mode: ComposeMode,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val rooms by deps.repository.socialRooms.collectAsState()
    val ownProfile by deps.repository.ownProfile.collectAsState()
    val postable = postableRooms(rooms, ownProfile)
    val ownProfileId = (ownProfile as? OwnProfileState.Linked)?.link?.roomId
    val target: MutableState<RoomId?> = remember { mutableStateOf((mode as? ComposeMode.NewPost)?.defaultRoomId) }
    LaunchedEffect(postable, ownProfileId) {
        if (mode is ComposeMode.NewPost && target.value == null) target.value = ownProfileId ?: postable.firstOrNull()?.roomId
    }
    val title = when (mode) {
        is ComposeMode.NewPost -> stringResource(R.string.social_new_post)
        is ComposeMode.Reply -> stringResource(R.string.social_reply_to, nameOf(mode.parent))
        is ComposeMode.Quote -> stringResource(R.string.social_quote_post)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, scrollable = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = ElementTheme.typography.fontHeadingSmMedium, color = ElementTheme.colors.textPrimary)
            if (mode is ComposeMode.NewPost) {
                RoomPicker(
                    rooms = postable,
                    selected = target.value,
                    ownProfileId = ownProfileId,
                    onSelect = { target.value = it },
                )
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = when (mode) {
                    is ComposeMode.Reply -> stringResource(R.string.social_reply_placeholder)
                    is ComposeMode.Quote -> stringResource(R.string.social_quote_placeholder)
                    is ComposeMode.NewPost -> stringResource(R.string.social_post_placeholder)
                },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
            when (mode) {
                is ComposeMode.Reply -> QuotedPreview(mode.parent, deps)
                is ComposeMode.Quote -> QuotedPreview(mode.original, deps)
                is ComposeMode.NewPost -> Unit
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = stringResource(R.string.social_cancel), onClick = onDismiss)
                val canSend = !sending && text.isNotBlank() && (mode !is ComposeMode.NewPost || target.value != null)
                Button(
                    text = stringResource(if (mode is ComposeMode.Reply) R.string.social_action_reply else R.string.social_post),
                    enabled = canSend,
                    showProgress = sending,
                    size = ButtonSize.Medium,
                    onClick = {
                        sending = true
                        scope.launch {
                            val body = text.trim()
                            val result = when (mode) {
                                is ComposeMode.NewPost -> deps.actions.post(target.value!!, body)
                                is ComposeMode.Reply -> deps.actions.reply(mode.parent, body)
                                is ComposeMode.Quote -> deps.actions.quotePost(mode.original, body)
                            }
                            sending = false
                            result.fold(
                                onSuccess = { sheetState.hide(scope) { onDismiss() } },
                                onFailure = { onError(it.message ?: it.toString()) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QuotedPreview(post: FeedPost, deps: SocialDeps) {
    Column(Modifier.padding(start = 8.dp)) {
        Text(nameOf(post), style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textSecondary)
        PostBody(post.post.relation?.content?.takeIf { post.post.isPlainRepost } ?: post.post.content, deps, maxLines = 4)
    }
}

@Composable
private fun RoomPicker(rooms: List<SocialRoom>, selected: RoomId?, ownProfileId: RoomId?, onSelect: (RoomId) -> Unit) {
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
