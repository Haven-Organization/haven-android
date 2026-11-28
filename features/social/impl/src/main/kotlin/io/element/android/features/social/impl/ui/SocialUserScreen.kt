/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.data.ProfileRoomLink
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.ui.formattedname.DisplayNameText
import io.element.android.libraries.matrix.ui.formattedname.rememberProfileFormattedName
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.user.MatrixUser

private sealed interface UserProfileState {
    data object Loading : UserProfileState
    data class Linked(val link: ProfileRoomLink) : UserProfileState
    data class NoProfile(val user: MatrixUser) : UserProfileState
    data class Error(val user: MatrixUser) : UserProfileState
}

/** A user's social profile, found through their `org.matrix.msc4501.social.profile_room` profile field. */
@Composable
fun SocialUserScreen(
    deps: SocialDeps,
    navigator: SocialNavigator,
    userId: UserId,
    modifier: Modifier = Modifier,
) {
    val interactions = rememberPostInteractionState()
    val callbacks = rememberPostCallbacks(deps, navigator, interactions)
    val state by produceState<UserProfileState>(UserProfileState.Loading, userId) {
        val user = deps.matrixClient.getProfile(userId).getOrNull() ?: MatrixUser(userId)
        value = deps.repository.getProfileRoomLink(userId.value).fold(
            onSuccess = { link -> link?.let { UserProfileState.Linked(it) } ?: UserProfileState.NoProfile(user) },
            onFailure = { UserProfileState.Error(user) },
        )
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.social_profile),
                navigationIcon = { BackButton(onClick = navigator::back) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UserProfileState.Loading -> SocialSpinner(Modifier.align(Alignment.Center))
                is UserProfileState.Linked -> SocialRoomContent(
                    deps = deps,
                    navigator = navigator,
                    roomId = s.link.roomId,
                    via = s.link.via,
                    callbacks = callbacks,
                    interactions = interactions,
                )
                is UserProfileState.NoProfile -> UserFallback(
                    user = s.user,
                    text = stringResource(R.string.social_user_no_profile, s.user.displayName ?: s.user.userId.value),
                    onOpenUser = { navigator.openUserDetails(s.user.userId) },
                )
                is UserProfileState.Error -> UserFallback(
                    user = s.user,
                    text = stringResource(R.string.social_user_profile_error, s.user.displayName ?: s.user.userId.value),
                    onOpenUser = { navigator.openUserDetails(s.user.userId) },
                )
            }
        }
    }
    PostInteractionOverlays(deps, interactions)
}

/**
 * In place of a profile room the user doesn't have (or that couldn't be loaded), like Haven Desktop's
 * SocialUserProfileView: a profile page's header (banner, avatar, name, user id) from their Matrix profile, with no
 * posts, and [text] saying why.
 */
@Composable
private fun UserFallback(user: MatrixUser, text: String, onOpenUser: () -> Unit) {
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.userId.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // An empty banner, like a profile room without one
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(ElementTheme.colors.bgSubtleSecondary),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.offset(y = (-36).dp)) {
                Avatar(
                    avatarData = AvatarData(user.userId.value, name, user.avatarUrl, AvatarSize.RoomDetailsHeader),
                    avatarType = AvatarType.User,
                    forcedAvatarSize = 80.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                text = stringResource(R.string.social_view_user),
                onClick = onOpenUser,
                size = ButtonSize.Medium,
            )
        }
        Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
            DisplayNameText(
                name = name,
                formatted = rememberProfileFormattedName(user.userId, user.displayName),
                style = ElementTheme.typography.fontHeadingMdBold,
                color = ElementTheme.colors.textPrimary,
            )
            if (name != user.userId.value) {
                Text(user.userId.value, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
            }
        }
        HorizontalDivider()
        EmptyStateInline(text)
    }
}
