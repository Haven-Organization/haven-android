/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.data.FeedFilter
import io.element.android.features.social.impl.data.FeedFilterValidation
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.hide
import kotlinx.coroutines.launch

/**
 * Haven Desktop's "Feed filters" dialog, saved to the same `software.haven.social.feed_filter` account data:
 * extra room types, rooms to include, rooms to filter out, users to filter out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedFilterSheet(deps: SocialDeps, onDismiss: () -> Unit) {
    val current by deps.repository.feedFilter.collectAsState()
    val roomTypes = remember { mutableStateListOf(*current.extraRoomTypes.toTypedArray()) }
    val included = remember { mutableStateListOf(*current.includedRoomIds.toTypedArray()) }
    val excluded = remember { mutableStateListOf(*current.excludedRoomIds.toTypedArray()) }
    val users = remember { mutableStateListOf(*current.excludedUserIds.toTypedArray()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val bothMessage = stringResource(R.string.social_filter_room_both)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, scrollable = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                // No verticalScroll here: the sheet itself scrolls (scrollable = true).
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.social_feed_filters), style = ElementTheme.typography.fontHeadingSmMedium, color = ElementTheme.colors.textPrimary)
            PillSection(
                title = stringResource(R.string.social_filter_room_types),
                values = roomTypes,
                lockedPill = stringResource(R.string.social_filter_social_rooms),
                placeholder = "org.example.room_type",
                isValid = FeedFilterValidation::isRoomType,
                invalidMessage = stringResource(R.string.social_filter_invalid_room_type),
                onError = { error = it },
            )
            PillSection(
                title = stringResource(R.string.social_filter_include_rooms),
                values = included,
                placeholder = "#room:server or !id:server",
                isValid = FeedFilterValidation::isRoom,
                invalidMessage = stringResource(R.string.social_filter_invalid_room),
                onError = { error = it },
            )
            PillSection(
                title = stringResource(R.string.social_filter_exclude_rooms),
                values = excluded,
                placeholder = "#room:server or !id:server",
                isValid = FeedFilterValidation::isRoom,
                invalidMessage = stringResource(R.string.social_filter_invalid_room),
                onError = { error = it },
            )
            PillSection(
                title = stringResource(R.string.social_filter_exclude_users),
                values = users,
                placeholder = "@user:server",
                isValid = FeedFilterValidation::isUser,
                invalidMessage = stringResource(R.string.social_filter_invalid_user),
                onError = { error = it },
            )
            error?.let { Text(it, color = ElementTheme.colors.textCriticalPrimary, style = ElementTheme.typography.fontBodySmRegular) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(text = stringResource(R.string.social_cancel), onClick = onDismiss)
                Button(
                    text = stringResource(R.string.social_save),
                    size = ButtonSize.Medium,
                    showProgress = saving,
                    enabled = !saving,
                    onClick = {
                        // Like Haven Desktop: a room can't be both included and filtered out.
                        val both = included.filter { it in excluded }
                        if (both.isNotEmpty()) {
                            error = bothMessage + " " + both.joinToString()
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            deps.repository.saveFeedFilter(
                                FeedFilter(
                                    extraRoomTypes = roomTypes.toList(),
                                    includedRoomIds = included.toList(),
                                    excludedRoomIds = excluded.toList(),
                                    excludedUserIds = users.toList(),
                                )
                            ).fold(
                                onSuccess = {
                                    deps.repository.refreshSocialRooms()
                                    sheetState.hide(scope) { onDismiss() }
                                },
                                onFailure = { error = it.message ?: it.toString() },
                            )
                            saving = false
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PillSection(
    title: String,
    values: SnapshotStateList<String>,
    placeholder: String,
    isValid: (String) -> Boolean,
    invalidMessage: String,
    onError: (String?) -> Unit,
    lockedPill: String? = null,
) {
    var input by remember { mutableStateOf("") }
    fun add() {
        val value = input.trim()
        if (value.isEmpty()) return
        if (!isValid(value)) {
            onError("$invalidMessage $value")
            return
        }
        if (value !in values) values.add(value)
        input = ""
        onError(null)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = ElementTheme.typography.fontBodyMdMedium, color = ElementTheme.colors.textPrimary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lockedPill?.let { Pill(text = it, onRemove = null) }
            values.forEach { value -> Pill(text = value, onRemove = { values.remove(value) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = placeholder,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(text = stringResource(R.string.social_add), onClick = ::add)
        }
    }
}

@Composable
private fun Pill(text: String, onRemove: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, ElementTheme.colors.borderInteractiveSecondary), RoundedCornerShape(16.dp))
            .then(if (onRemove != null) Modifier.clickable(onClick = onRemove) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = ElementTheme.typography.fontBodySmMedium, color = ElementTheme.colors.textPrimary)
        if (onRemove != null) {
            Icon(CompoundIcons.Close(), contentDescription = null, modifier = Modifier.padding(start = 4.dp), tint = ElementTheme.colors.iconSecondary)
        }
    }
}
