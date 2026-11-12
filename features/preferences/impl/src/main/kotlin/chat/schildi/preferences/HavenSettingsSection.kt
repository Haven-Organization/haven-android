/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.preferences

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import chat.schildi.lib.R
import chat.schildi.lib.preferences.ScPrefScreen
import chat.schildi.lib.preferences.ScPrefs
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.Text

/**
 * Haven: the Turtle tweaks sub-screens, listed directly in the main settings instead of behind a "Turtle tweaks" entry.
 */
@Composable
fun HavenSettingsSection(
    navigateToScTweaks: (ScPrefScreen) -> Unit,
) {
    ScPrefs.havenSettingsScreens.forEach { screen ->
        HavenSettingsItem(screen, navigateToScTweaks)
    }
    HavenSettingsItem(ScPrefs.havenAdvancedScreen, navigateToScTweaks)
}

@Composable
private fun HavenSettingsItem(
    screen: ScPrefScreen,
    navigateToScTweaks: (ScPrefScreen) -> Unit,
) {
    val (titleRes, icon) = when (screen.titleRes) {
        R.string.sc_pref_category_general -> screen.titleRes to CompoundIcons.Theme()
        R.string.sc_pref_category_chat_overview -> screen.titleRes to CompoundIcons.ListView()
        R.string.sc_pref_category_spaces -> screen.titleRes to CompoundIcons.Space()
        R.string.sc_pref_category_timeline -> screen.titleRes to CompoundIcons.Chat()
        // Upstream already has a "Notifications" entry, so use a distinct title here
        R.string.sc_pref_category_notifications -> R.string.haven_pref_notification_alerts_title to CompoundIcons.Notifications()
        R.string.sc_pref_screen_experimental_title -> screen.titleRes to CompoundIcons.Labs()
        R.string.haven_pref_advanced_title -> screen.titleRes to CompoundIcons.AdvancedSettings()
        else -> screen.titleRes to CompoundIcons.Settings()
    }
    ListItem(
        content = { Text(stringResource(id = titleRes)) },
        supportingContent = screen.summaryRes?.let { { Text(stringResource(id = it)) } },
        leadingContent = ListItemContent.Icon(IconSource.Vector(icon)),
        onClick = { navigateToScTweaks(screen) },
    )
}
