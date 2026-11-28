/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.hide
import io.element.android.libraries.ui.strings.CommonStrings

/**
 * The actions of a post that can't have Element X's message action sheet (one in a room that isn't followed, so there's
 * no room to load it in), in that sheet's look: the post at the top, then the actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PostActionsSheet(
    post: FeedPost,
    showViewSource: Boolean,
    onDismiss: () -> Unit,
    onViewInRoom: () -> Unit,
    onViewSource: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val scope = rememberCoroutineScope()
    fun pick(action: () -> Unit) {
        sheetState.hide(scope) {
            onDismiss()
            action()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, scrollable = false) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Like the stock sheet's message summary
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Avatar(
                    avatarData = AvatarData(post.sender.value, post.senderName, post.senderAvatarUrl, AvatarSize.MessageActionSender),
                    avatarType = AvatarType.User,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(
                            text = post.senderName ?: post.sender.value,
                            style = ElementTheme.typography.fontBodyMdMedium,
                            color = ElementTheme.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = formatFullTime(post.timestamp),
                            style = ElementTheme.typography.fontBodyXsRegular,
                            color = ElementTheme.colors.textSecondary,
                            textAlign = TextAlign.End,
                        )
                    }
                    Text(
                        text = post.post.content.body,
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { pick(onViewInRoom) },
                content = { Text(stringResource(R.string.social_view_in_room)) },
                leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.VisibilityOn())),
            )
            // Like messages in rooms: with Settings' "View source" (developer mode) on
            if (showViewSource && post.eventId != null) {
                ListItem(
                    modifier = Modifier.clickable { pick(onViewSource) },
                    content = { Text(stringResource(CommonStrings.action_view_source)) },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Code())),
                )
            }
            if (post.isOwn) {
                ListItem(
                    modifier = Modifier.clickable { pick(onDelete) },
                    content = { Text(stringResource(R.string.social_delete_post)) },
                    leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Delete())),
                    style = ListItemStyle.Destructive,
                )
            }
        }
    }
}
