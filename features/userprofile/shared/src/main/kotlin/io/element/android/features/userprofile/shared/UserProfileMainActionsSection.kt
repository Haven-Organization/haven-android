/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.userprofile.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.social.api.HavenApp
import io.element.android.libraries.designsystem.theme.components.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.button.MainActionButton
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.notification.CallIntent
import io.element.android.libraries.ui.strings.CommonStrings

@Composable
fun UserProfileMainActionsSection(
    isCurrentUser: Boolean,
    canCall: Boolean,
    onShareUser: () -> Unit,
    onStartDM: () -> Unit,
    onCall: (CallIntent) -> Unit,
    modifier: Modifier = Modifier,
    // Haven: their Social profile, when their profile links one (Haven Desktop's View Profile)
    onViewSocialProfile: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (!isCurrentUser) {
            MainActionButton(
                title = stringResource(CommonStrings.action_message),
                imageVector = CompoundIcons.Chat(),
                onClick = onStartDM,
            )
        }
        if (onViewSocialProfile != null) {
            SocialProfileActionButton(onClick = onViewSocialProfile)
        }
        if (canCall) {
            MainActionButton(
                title = stringResource(CommonStrings.action_call),
                imageVector = CompoundIcons.VoiceCall(),
                onClick = { onCall(CallIntent.AUDIO) },
            )

            MainActionButton(
                title = stringResource(CommonStrings.common_video),
                imageVector = CompoundIcons.VideoCall(),
                onClick = { onCall(CallIntent.VIDEO) },
            )
        }
        MainActionButton(
            title = stringResource(CommonStrings.action_share),
            imageVector = CompoundIcons.ShareAndroid(),
            onClick = onShareUser
        )
    }
}

/**
 * Haven: Haven Desktop's View Profile button, opening the user's Social profile: shaped like the actions next to it, but
 * in the Social app's look (its icon, an accent outline and text on a neutral fill), so it's clear it goes to Social.
 */
@Composable
private fun SocialProfileActionButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            // Its outline's padding above, so its icon and title line up with the other actions'
            .offset(y = -OUTLINE_PADDING)
            .clip(shape)
            .border(1.dp, ElementTheme.colors.borderAccentPrimary, shape)
            .background(ElementTheme.colors.bgSubtleSecondary)
            .clickable(onClick = onClick)
            .widthIn(min = 76.dp, max = 96.dp)
            .padding(OUTLINE_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(HavenApp.Social.iconRes),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.haven_action_view_social_profile),
            style = ElementTheme.typography.fontBodyMdMedium.copy(hyphens = Hyphens.Auto),
            color = ElementTheme.colors.textActionAccent,
            textAlign = TextAlign.Center,
        )
    }
}

private val OUTLINE_PADDING = 8.dp

@PreviewsDayNight()
@Composable
internal fun UserProfileMainActionsSectionPreview() = ElementPreview {
    UserProfileMainActionsSection(
        isCurrentUser = false,
        canCall = true,
        onShareUser = { },
        onStartDM = { },
        onCall = { }
    )
}
