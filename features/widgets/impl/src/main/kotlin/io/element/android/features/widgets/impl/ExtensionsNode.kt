/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.widgets.impl

import android.os.Parcelable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.api.HavenAppIcon
import io.element.android.features.social.api.HavenAppItem
import io.element.android.features.social.api.HavenAppsService
import io.element.android.features.widgets.api.WidgetsEntryPoint
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ErrorDialog
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

/** A room's Extensions: its widgets, each openable and addable to Apps (like Haven Desktop's room info). */
@ContributesNode(SessionScope::class)
@AssistedInject
class ExtensionsNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val roomWidgets: RoomWidgets,
    private val appsService: HavenAppsService,
) : Node(buildContext, plugins = plugins) {
    @Parcelize
    data class Inputs(val roomId: RoomId) : NodeInputs, Parcelable

    private val inputs = inputs<Inputs>()
    private val callback: WidgetsEntryPoint.Callback = callback()

    @Composable
    override fun View(modifier: Modifier) {
        val widgets by produceState<List<RoomWidget>?>(null) { value = roomWidgets.list(inputs.roomId) }
        val widgetApps by appsService.widgetApps.collectAsState()
        val scope = rememberCoroutineScope()
        var error by remember { mutableStateOf<String?>(null) }
        val appsEnabled = !appsService.appPreferences.collectAsState().value.disabled
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    titleStr = stringResource(R.string.widgets_extensions),
                    navigationIcon = { BackButton(onClick = callback::onDone) },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                val list = widgets
                when {
                    list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    list.isEmpty() -> Text(
                        text = stringResource(R.string.widgets_extensions_empty),
                        textAlign = TextAlign.Center,
                        color = ElementTheme.colors.textSecondary,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )
                    else -> LazyColumn {
                        items(list, key = { it.widgetId }) { widget ->
                            val appId = HavenAppItem.widgetAppId(widget.roomId.value, widget.widgetId)
                            val isApp = widgetApps.any { it.id == appId }
                            ExtensionRow(
                                widget = widget,
                                isApp = isApp,
                                showAppToggle = appsEnabled,
                                onOpen = { callback.openWidget(widget.roomId, widget.widgetId) },
                                onToggleApp = {
                                    scope.launch {
                                        val result = if (isApp) appsService.removeWidgetApp(appId) else appsService.addWidgetApp(widget.roomId.value, widget.widgetId)
                                        result.onSuccess {
                                            // Adding to Apps also counts as consent to load it, like Haven Desktop.
                                            if (!isApp) roomWidgets.allowLoad(widget)
                                        }.onFailure { e ->
                                            error = e.message ?: e.toString()
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
        error?.let { ErrorDialog(content = it, onSubmit = { error = null }) }
    }
}

@Composable
private fun ExtensionRow(
    widget: RoomWidget,
    isApp: Boolean,
    showAppToggle: Boolean,
    onOpen: () -> Unit,
    onToggleApp: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HavenAppIcon(
            app = HavenAppItem.Widget(widget.roomId.value, widget.widgetId, widget.name, widget.avatarUrl, null),
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(widget.name, style = ElementTheme.typography.fontBodyLgMedium, color = ElementTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(widget.url, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showAppToggle) {
            IconButton(onClick = onToggleApp) {
                Icon(
                    imageVector = CompoundIcons.Grid(),
                    contentDescription = stringResource(if (isApp) R.string.widgets_remove_from_apps else R.string.widgets_add_to_apps),
                    tint = if (isApp) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
                )
            }
        }
    }
}

