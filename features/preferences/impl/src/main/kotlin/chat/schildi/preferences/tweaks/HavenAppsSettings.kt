/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.preferences.tweaks

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.preferences.impl.R
import io.element.android.features.social.api.HavenApp
import io.element.android.features.social.api.HavenAppIcon
import io.element.android.features.social.api.HavenAppItem
import io.element.android.features.social.api.HavenAppsService
import io.element.android.features.social.api.SocialSettings
import io.element.android.features.social.api.havenAppName
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.list.SwitchListItem
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.IconSource
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Switch
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Haven: Settings > Apps, like Haven Desktop's Apps tab: a switch for apps as a whole, then every app (built-in apps and
 * the widgets added from rooms), each with a switch to show or hide it. Social has a Settings button, which shows its
 * settings here instead of the list (Desktop's "Apps: Social" page); widget apps have Remove, after a confirmation.
 * Turning apps off greys out every app's controls. All of it is kept in account data, shared with Haven Desktop.
 */
@Composable
internal fun HavenAppsSettings(appsService: HavenAppsService, socialSettings: SocialSettings) {
    val prefs by appsService.appPreferences.collectAsState()
    val widgetApps by appsService.widgetApps.collectAsState()
    val scope = rememberCoroutineScope()
    var openSocialSettings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<HavenAppItem.Widget?>(null) }
    var removeFailed by remember { mutableStateOf(false) }

    if (openSocialSettings) {
        BackHandler { openSocialSettings = false }
        SocialAppSettings(socialSettings, onBack = { openSocialSettings = false })
        return
    }

    fun change(what: String, action: suspend () -> Result<Unit>) {
        busy = true
        scope.launch {
            action().onFailure { Timber.w(it, "Couldn't %s", what) }
            busy = false
        }
    }

    Column {
        SwitchListItem(
            headline = stringResource(R.string.haven_apps_enable_title),
            supportingText = stringResource(R.string.haven_apps_enable_summary),
            value = !prefs.disabled,
            enabled = !busy,
            onChange = { enabled -> change("turn apps on or off") { appsService.setAppsDisabled(!enabled) } },
        )
        val apps = HavenApp.entries.map { HavenAppItem.Builtin(it) } + widgetApps
        // Every app's row as tall as the tallest (e.g. one whose room name takes two lines), so they're evenly spaced
        val density = LocalDensity.current
        var rowHeight by remember { mutableIntStateOf(0) }
        apps.forEach { app ->
            AppRow(
                modifier = Modifier
                    .onSizeChanged { size -> if (size.height > rowHeight) rowHeight = size.height }
                    .heightIn(min = with(density) { rowHeight.toDp() }),
                app = app,
                shown = app.id !in prefs.hidden,
                // Apps turned off: every app's controls are greyed out
                enabled = !prefs.disabled,
                onShowChange = { show -> change("show or hide an app") { appsService.setAppHidden(app.id, !show) } },
                onOpenSettings = { openSocialSettings = true },
                onRemove = { toRemove = app as? HavenAppItem.Widget },
            )
        }
    }

    toRemove?.let { app ->
        val room = app.roomName ?: stringResource(R.string.haven_apps_its_room)
        ConfirmationDialog(
            title = stringResource(R.string.haven_apps_remove_title, app.name),
            content = stringResource(R.string.haven_apps_remove_widget_description, app.name, room),
            submitText = stringResource(R.string.haven_apps_remove),
            destructiveSubmit = true,
            onSubmitClick = {
                toRemove = null
                scope.launch {
                    appsService.removeWidgetApp(app.id).onFailure {
                        Timber.e(it, "Couldn't remove an app from Apps")
                        removeFailed = true
                    }
                }
            },
            onDismiss = { toRemove = null },
        )
    }
    if (removeFailed) {
        ErrorDialog(
            title = stringResource(R.string.haven_apps_remove_failed_title),
            content = stringResource(R.string.haven_apps_remove_failed_description),
            onSubmit = { removeFailed = false },
        )
    }
}

/** One app: its icon, name and where it's from, its Settings or Remove button, and whether it shows. */
@Composable
private fun AppRow(
    modifier: Modifier,
    app: HavenAppItem,
    shown: Boolean,
    enabled: Boolean,
    onShowChange: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        modifier = modifier,
        leadingContent = ListItemContent.Custom { HavenAppIcon(app, size = 40.dp) },
        // At most two lines each, cut off after that: every row is as tall as the tallest, so one very long name mustn't
        // make them all tall
        content = { Text(havenAppName(app), maxLines = MAX_LINES, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                maxLines = MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                text = when (app) {
                    is HavenAppItem.Builtin -> stringResource(R.string.haven_apps_origin_builtin)
                    is HavenAppItem.Widget -> app.roomName?.let { stringResource(R.string.haven_apps_origin_widget_from, it) }
                        ?: stringResource(R.string.haven_apps_origin_widget)
                }
            )
        },
        trailingContent = ListItemContent.Custom {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Built-in apps with settings of their own: Social
                if (app is HavenAppItem.Builtin && app.app == HavenApp.Social) {
                    OutlinedButton(
                        text = stringResource(CommonStrings.common_settings),
                        onClick = onOpenSettings,
                        enabled = enabled,
                        size = ButtonSize.Small,
                    )
                }
                // Apps added from rooms can be removed (they stay in their room)
                if (app is HavenAppItem.Widget) {
                    OutlinedButton(
                        text = stringResource(R.string.haven_apps_remove),
                        onClick = onRemove,
                        enabled = enabled,
                        size = ButtonSize.Small,
                        destructive = true,
                    )
                }
                Switch(checked = shown, onCheckedChange = onShowChange, enabled = enabled)
            }
        },
    )
}

private const val MAX_LINES = 2

/** Social's settings (Haven Desktop's "Apps: Social" page), in place of the apps list. */
@Composable
private fun SocialAppSettings(socialSettings: SocialSettings, onBack: () -> Unit) {
    val crossPostReplies by socialSettings.crossPostReplies.collectAsState()
    val scope = rememberCoroutineScope()
    Column {
        ListItem(
            leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.ArrowLeft())),
            content = { Text(stringResource(R.string.haven_apps_back_to_apps)) },
            onClick = onBack,
        )
        PreferenceCategory(title = stringResource(R.string.haven_apps_social_posting)) {
            SwitchListItem(
                headline = stringResource(R.string.haven_social_cross_post_replies),
                supportingText = stringResource(R.string.haven_social_cross_post_replies_description),
                value = crossPostReplies,
                onChange = { enabled -> scope.launch { socialSettings.setCrossPostReplies(enabled) } },
            )
        }
    }
}
