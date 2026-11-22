/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.data.CreateSocialRoomParams
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.features.social.impl.data.SocialRoomVisibility
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.RadioButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextField
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.launch

/** Create a group, or the user's profile, with Haven Desktop's fields and defaults. */
@Composable
fun CreateSocialRoomScreen(
    deps: SocialDeps,
    kind: SocialRoomKind,
    onBack: () -> Unit,
    onCreated: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isProfile = kind == SocialRoomKind.Profile
    val me by deps.matrixClient.userProfile.collectAsState()
    var name by remember { mutableStateOf("") }
    LaunchedEffect(me) {
        if (isProfile && name.isEmpty()) name = me.displayName ?: me.userId.value
    }
    var topic by remember { mutableStateOf("") }
    // Haven Desktop defaults: Ask to join, published to the directory, only trusted users post, unencrypted.
    var visibility by remember { mutableStateOf(SocialRoomVisibility.AskToJoin) }
    var publish by remember { mutableStateOf(true) }
    var allowAnyoneToPost by remember { mutableStateOf(false) }
    var encrypted by remember { mutableStateOf(false) }
    var crossPostReplies by remember { mutableStateOf(deps.actions.crossPostReplies) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(if (isProfile) R.string.social_create_profile_title else R.string.social_create_group_title),
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.social_field_name),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = topic,
                onValueChange = { topic = it },
                label = stringResource(if (isProfile) R.string.social_field_bio else R.string.social_field_topic),
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(if (isProfile) R.string.social_profile_visibility else R.string.social_group_visibility),
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textPrimary,
            )
            VisibilityOption(
                text = stringResource(if (isProfile) R.string.social_visibility_private_profile else R.string.social_visibility_private_group),
                selected = visibility == SocialRoomVisibility.Private,
            ) { visibility = SocialRoomVisibility.Private }
            VisibilityOption(
                text = stringResource(if (isProfile) R.string.social_visibility_ask_follow else R.string.social_visibility_ask_join),
                selected = visibility == SocialRoomVisibility.AskToJoin,
            ) { visibility = SocialRoomVisibility.AskToJoin }
            VisibilityOption(
                text = stringResource(if (isProfile) R.string.social_visibility_public_profile else R.string.social_visibility_public_group),
                selected = visibility == SocialRoomVisibility.Public,
            ) { visibility = SocialRoomVisibility.Public }
            if (visibility == SocialRoomVisibility.AskToJoin) {
                SwitchRow(stringResource(R.string.social_publish_directory), null, publish) { publish = it }
            }
            SwitchRow(
                stringResource(R.string.social_allow_anyone_post),
                stringResource(R.string.social_allow_anyone_post_help),
                allowAnyoneToPost,
            ) { allowAnyoneToPost = it }
            if (visibility != SocialRoomVisibility.Public) {
                SwitchRow(stringResource(R.string.social_encrypted), null, encrypted) { encrypted = it }
            }
            if (isProfile) {
                SwitchRow(
                    stringResource(R.string.social_cross_post_replies),
                    stringResource(R.string.social_cross_post_replies_help),
                    crossPostReplies,
                ) { crossPostReplies = it }
            }
            Button(
                text = stringResource(if (isProfile) R.string.social_create_profile else R.string.social_create_group),
                enabled = name.isNotBlank() && !creating,
                showProgress = creating,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    creating = true
                    deps.actions.crossPostReplies = crossPostReplies
                    scope.launch {
                        deps.repository.createSocialRoom(
                            CreateSocialRoomParams(
                                kind = kind,
                                name = name.trim(),
                                topic = topic.trim(),
                                visibility = visibility,
                                publishToDirectory = publish,
                                allowAnyoneToPost = allowAnyoneToPost,
                                encrypted = encrypted,
                            )
                        ).fold(
                            onSuccess = onCreated,
                            onFailure = { error = it.message ?: it.toString() },
                        )
                        creating = false
                    }
                },
            )
        }
    }
    error?.let { ErrorDialog(content = it, onSubmit = { error = null }) }
}

@Composable
private fun VisibilityOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text, style = ElementTheme.typography.fontBodyLgRegular, color = ElementTheme.colors.textPrimary)
    }
}

@Composable
private fun SwitchRow(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = ElementTheme.typography.fontBodyLgRegular, color = ElementTheme.colors.textPrimary)
            help?.let { Text(it, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
