/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import io.element.android.features.social.api.HavenApp
import io.element.android.features.social.api.HavenAppsService
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.social.api.SocialEntryPoint
import io.element.android.features.social.api.SocialProfileLinks
import io.element.android.features.social.impl.data.SocialRepository
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.UserId

@ContributesBinding(SessionScope::class)
class DefaultSocialProfileLinks(
    private val repository: SocialRepository,
    private val appsService: HavenAppsService,
) : SocialProfileLinks {
    // None while Social is turned off (itself, or all apps): no Profile button then, like Haven Desktop
    override suspend fun profileOf(userId: UserId): SocialEntryPoint.InitialTarget? =
        if (!appsService.appPreferences.value.isShown(HavenApp.Social.id)) null else repository.getProfileRoomLink(userId.value).getOrNull()?.let { link ->
            SocialEntryPoint.InitialTarget(roomId = link.roomId, eventId = null, via = link.via)
        }
}
