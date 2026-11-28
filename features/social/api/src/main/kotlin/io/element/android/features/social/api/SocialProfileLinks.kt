/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.api

import androidx.compose.runtime.staticCompositionLocalOf
import io.element.android.libraries.matrix.api.core.UserId

/**
 * Haven: the Social profile (MSC4501 profile room) a user's Matrix profile links to, for Haven Desktop's "View Profile"
 * button on user and member pages.
 */
interface SocialProfileLinks {
    /** Where Social shows [userId]'s profile, when their profile links a profile room; null when it doesn't (or can't be read). */
    suspend fun profileOf(userId: UserId): SocialEntryPoint.InitialTarget?
}

/** [SocialProfileLinks], and how to open Social at a profile from anywhere in the app. */
class SocialProfileOpener(
    val links: SocialProfileLinks,
    val open: (SocialEntryPoint.InitialTarget) -> Unit,
)

/** The session's [SocialProfileOpener]; null outside a session (and in previews), where there's no View Profile button. */
val LocalSocialProfileOpener = staticCompositionLocalOf<SocialProfileOpener?> { null }
