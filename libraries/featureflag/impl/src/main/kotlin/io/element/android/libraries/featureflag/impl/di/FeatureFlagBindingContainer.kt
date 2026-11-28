/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.featureflag.impl.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.ElementsIntoSet
import dev.zacsweers.metro.Provides
import io.element.android.libraries.featureflag.impl.FeatureFlagProvider
import io.element.android.libraries.featureflag.impl.PreferencesFeatureFlagProvider

@BindingContainer
@ContributesTo(AppScope::class)
object FeatureFlagBindingContainer {
    @JvmStatic
    @Provides
    @ElementsIntoSet
    fun providesFeatureFlagProvider(
        mutableFeatureFlagProvider: PreferencesFeatureFlagProvider,
        havenRendererFeatureFlagProvider: io.element.android.libraries.featureflag.impl.HavenRendererFeatureFlagProvider,
    ): Set<FeatureFlagProvider> {
        return buildSet {
            add(mutableFeatureFlagProvider)
            // Haven: the renderer flag follows the "Message renderer" setting
            add(havenRendererFeatureFlagProvider)
        }
    }
}
