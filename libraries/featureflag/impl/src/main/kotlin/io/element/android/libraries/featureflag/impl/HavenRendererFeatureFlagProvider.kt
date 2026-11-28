/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.featureflag.impl

import chat.schildi.lib.preferences.ScPreferencesStore
import chat.schildi.lib.preferences.ScPrefs
import dev.zacsweers.metro.Inject
import io.element.android.libraries.featureflag.api.Feature
import io.element.android.libraries.featureflag.api.FeatureFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Haven: Element's "New timeline event renderer" flag follows Developer options' "Message renderer" setting (on when it's
 * Element's Compose renderer), so one setting picks between the three renderers.
 */
@Inject
class HavenRendererFeatureFlagProvider(
    private val scPreferencesStore: ScPreferencesStore,
) : FeatureFlagProvider {
    override val priority = HIGH_PRIORITY

    override fun isFeatureEnabledFlow(feature: Feature): Flow<Boolean> =
        scPreferencesStore.settingFlow(ScPrefs.HAVEN_MESSAGE_RENDERER)
            .map { it == ScPrefs.MessageRenderer.ELEMENT }
            .distinctUntilChanged()

    override fun hasFeature(feature: Feature): Boolean = feature.key == FeatureFlags.NewTimelineEventRenderer.key
}
