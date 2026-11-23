/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.layout.Arrangement
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
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
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

@Composable
private fun UserFallback(user: MatrixUser, text: String, onOpenUser: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Avatar(
            avatarData = AvatarData(user.userId.value, user.displayName, user.avatarUrl, AvatarSize.RoomDetailsHeader),
            avatarType = AvatarType.User,
        )
        EmptyStateInline(text)
        OutlinedButton(text = stringResource(R.string.social_view_user), onClick = onOpenUser)
    }
}
