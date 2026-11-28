/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.preferences.impl.developer.appsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import chat.schildi.lib.R as ScR
import chat.schildi.lib.preferences.LocalScPreferencesStore
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import io.element.android.libraries.designsystem.components.preferences.DropdownOption
import io.element.android.libraries.designsystem.components.preferences.PreferenceDropdown
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/** Haven: the renderers "Message renderer" picks between ([ScPrefs.HAVEN_MESSAGE_RENDERER]). */
enum class HavenMessageRendererOption(val key: String) : DropdownOption {
    ELEMENT(ScPrefs.MessageRenderer.ELEMENT) {
        @Composable
        override fun getText(): String = stringResource(ScR.string.haven_message_renderer_element)
    },
    SCHILDI(ScPrefs.MessageRenderer.SCHILDI) {
        @Composable
        override fun getText(): String = stringResource(ScR.string.haven_message_renderer_schildi)
    },
    LEGACY(ScPrefs.MessageRenderer.LEGACY) {
        @Composable
        override fun getText(): String = stringResource(ScR.string.haven_message_renderer_legacy)
    },
}

/**
 * Haven: picks how formatted messages are drawn: Element's Compose renderer, SchildiChat's, or Element's legacy one. One
 * setting for what were SchildiChat's "Legacy message renderer" and Element's "New timeline event renderer" flag.
 */
@Composable
internal fun HavenMessageRendererPreference() {
    val store = LocalScPreferencesStore.current
    val scope = rememberCoroutineScope()
    val current = ScPrefs.HAVEN_MESSAGE_RENDERER.value()
    PreferenceDropdown(
        title = stringResource(ScR.string.haven_message_renderer_title),
        selectedOption = HavenMessageRendererOption.entries.firstOrNull { it.key == current } ?: HavenMessageRendererOption.ELEMENT,
        options = HavenMessageRendererOption.entries.toImmutableList(),
        onSelectOption = { option -> scope.launch { store.setSetting(ScPrefs.HAVEN_MESSAGE_RENDERER, option.key) } },
    )
}
