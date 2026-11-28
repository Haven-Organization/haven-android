/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.social.api.SocialEntryPoint
import io.element.android.features.social.api.SocialProfileLinks
import io.element.android.features.social.impl.data.SocialRepository
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.UserId

@ContributesBinding(SessionScope::class)
class DefaultSocialProfileLinks(
    private val repository: SocialRepository,
) : SocialProfileLinks {
    override suspend fun profileOf(userId: UserId): SocialEntryPoint.InitialTarget? =
        repository.getProfileRoomLink(userId.value).getOrNull()?.let { link ->
            SocialEntryPoint.InitialTarget(roomId = link.roomId, eventId = null, via = link.via)
        }
}
