/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.api

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData

/** What the home screen needs to show the Apps button and pinned apps. Provided through [LocalHavenAppsBar]. */
data class HavenAppsBarState(
    /** Every launchable app: built-in apps, then widget apps (like Haven Desktop's picker). */
    val allApps: List<HavenAppItem>,
    val pinnedApps: List<HavenAppItem>,
    val onOpenApp: (HavenAppItem) -> Unit,
    val onSetPinned: (HavenAppItem, Boolean) -> Unit,
    val onRemoveWidgetApp: (HavenAppItem.Widget) -> Unit,
    val onGoToRoom: (String) -> Unit,
)

/** How a bar lays out its items, so app buttons match the buttons next to them. */
enum class HavenBarStyle {
    /** Element's floating toolbar (Chats / Spaces): 56dp-wide icon buttons with a 24dp icon. */
    ElementToolbar,

    /** SchildiChat's compact space bar: 32dp icons, 8dp vertical and 16dp horizontal padding. */
    SpaceBarCompact,

    /** SchildiChat's space bar with labels: Material tabs (24dp icon above a label). */
    SpaceBarTab,
}

/** Null when apps are turned off (Settings → General → Apps) or unavailable (e.g. previews). */
val LocalHavenAppsBar = staticCompositionLocalOf<HavenAppsBarState?> { null }

/** The Apps button: opens a picker with every app. Long-press on an app offers to pin it. */
@Composable
fun HavenAppsButton(
    state: HavenAppsBarState,
    style: HavenBarStyle,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    HavenBarButton(
        style = style,
        label = stringResource(R.string.haven_apps_button),
        onClick = { showPicker = true },
        onLongClick = null,
        modifier = modifier,
    ) { size ->
        // Same multi-colour grid as Haven Desktop's Apps button. The grid has a margin built in (like a toolbar
        // glyph), so next to edge-to-edge space avatars it's drawn larger to look the same size.
        val drawnSize = if (style == HavenBarStyle.ElementToolbar) size else size * 1.2f
        Image(
            painter = painterResource(R.drawable.haven_apps_grid),
            contentDescription = stringResource(R.string.haven_apps_button),
            modifier = Modifier
                .size(size)
                .wrapContentSize(unbounded = true)
                .size(drawnSize),
        )
    }
    if (showPicker) {
        HavenAppsPicker(
            state = state,
            onDismiss = { showPicker = false },
        )
    }
}

/** A pinned app's button. Long-press shows the app menu (unpin, and for widgets: go to room, remove). */
@Composable
fun HavenPinnedAppButton(
    app: HavenAppItem,
    state: HavenAppsBarState,
    style: HavenBarStyle,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier) {
        HavenBarButton(
            style = style,
            label = havenAppName(app),
            onClick = { state.onOpenApp(app) },
            onLongClick = { showMenu = true },
        ) { size ->
            HavenAppIcon(app, size, shape = if (style == HavenBarStyle.SpaceBarCompact) RoundedCornerShape(8.dp) else null)
        }
        HavenAppMenu(app = app, state = state, expanded = showMenu, onDismiss = { showMenu = false })
    }
}

@Composable
fun havenAppName(app: HavenAppItem): String = when (app) {
    is HavenAppItem.Builtin -> stringResource(app.app.nameRes)
    is HavenAppItem.Widget -> app.name
}

@Composable
fun HavenAppIcon(app: HavenAppItem, size: Dp, modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape? = null) {
    @Suppress("NAME_SHADOWING")
    val shape = shape ?: RoundedCornerShape(if (size > 32.dp) 12.dp else 6.dp)
    when (app) {
        is HavenAppItem.Builtin -> Image(
            painter = painterResource(app.app.iconRes),
            contentDescription = stringResource(app.app.nameRes),
            modifier = modifier.size(size).clip(shape),
        )
        is HavenAppItem.Widget -> if (app.avatarUrl != null) {
            // The room name's initial underneath: shown while the avatar loads, or if it can't be loaded, instead of
            // an invisible (but still full-width) button.
            Box(
                modifier = modifier
                    .size(size)
                    .clip(shape)
                    .background(ElementTheme.colors.bgSubtlePrimary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.name.trim().firstOrNull()?.uppercase() ?: "?",
                    style = if (size > 28.dp) ElementTheme.typography.fontBodyLgMedium else ElementTheme.typography.fontBodySmMedium,
                    color = ElementTheme.colors.textSecondary,
                )
                AsyncImage(
                    model = MediaRequestData(MediaSource(app.avatarUrl), MediaRequestData.Kind.Thumbnail(64L, 64L)),
                    contentDescription = app.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Icon(
                imageVector = CompoundIcons.Extensions(),
                contentDescription = app.name,
                tint = ElementTheme.colors.iconSecondary,
                modifier = modifier.size(size),
            )
        }
    }
}

@Composable
private fun HavenAppMenu(
    app: HavenAppItem,
    state: HavenAppsBarState,
    expanded: Boolean,
    onDismiss: () -> Unit,
) {
    val isPinned = state.pinnedApps.any { it.id == app.id }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(if (isPinned) R.string.haven_apps_unpin else R.string.haven_apps_pin)) },
            leadingIcon = { Icon(CompoundIcons.Pin(), contentDescription = null) },
            onClick = {
                onDismiss()
                state.onSetPinned(app, !isPinned)
            },
        )
        if (app is HavenAppItem.Widget) {
            DropdownMenuItem(
                text = { Text(goToRoomLabel(app.roomName)) },
                leadingIcon = { Icon(CompoundIcons.ArrowRight(), contentDescription = null) },
                onClick = {
                    onDismiss()
                    state.onGoToRoom(app.roomId)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.haven_apps_remove), color = ElementTheme.colors.textCriticalPrimary) },
                leadingIcon = { Icon(CompoundIcons.Delete(), contentDescription = null, tint = ElementTheme.colors.iconCriticalPrimary) },
                onClick = {
                    onDismiss()
                    state.onRemoveWidgetApp(app)
                },
            )
        }
    }
}

/** "Go to <room>", or "Go to room" when the name is long (like Haven Desktop). */
@Composable
private fun goToRoomLabel(roomName: String?): String =
    if (roomName.isNullOrBlank() || roomName.codePointCount(0, roomName.length) > 32) {
        stringResource(R.string.haven_apps_go_to_room)
    } else {
        stringResource(R.string.haven_apps_go_to_named_room, roomName)
    }

/** One bar item, laid out like the native items of [style]'s bar (but with long-press support). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HavenBarButton(
    style: HavenBarStyle,
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: @Composable (Dp) -> Unit,
) {
    when (style) {
        // Matches HorizontalFloatingToolbarItem: an IconButton (40dp tall) at least 56dp wide, 24dp icon.
        HavenBarStyle.ElementToolbar -> Box(
            modifier = modifier
                .widthIn(min = 56.dp)
                .height(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            icon(24.dp)
        }
        // Matches SchildiChat's compact space tab: 8dp/16dp padding around a 32dp icon.
        HavenBarStyle.SpaceBarCompact -> Box(
            modifier = modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(vertical = 8.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon(32.dp)
        }
        // Matches a Material tab with icon and text (72dp tall, 16dp horizontal padding).
        HavenBarStyle.SpaceBarTab -> Column(
            modifier = modifier
                .height(72.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon(24.dp)
            Text(
                text = label,
                style = ElementTheme.typography.fontBodySmMedium,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HavenAppsPicker(
    state: HavenAppsBarState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, scrollable = false) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.haven_apps_picker_title),
                style = ElementTheme.typography.fontHeadingSmMedium,
                color = ElementTheme.colors.textPrimary,
            )
            FlowRow(
                modifier = Modifier.padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.allApps.forEach { app ->
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(80.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        onDismiss()
                                        state.onOpenApp(app)
                                    },
                                    onLongClick = { showMenu = true },
                                )
                                .padding(8.dp),
                        ) {
                            HavenAppIcon(app, 56.dp)
                            Text(
                                text = havenAppName(app),
                                style = ElementTheme.typography.fontBodySmMedium,
                                color = ElementTheme.colors.textPrimary,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        HavenAppMenu(app = app, state = state, expanded = showMenu, onDismiss = { showMenu = false })
                    }
                }
            }
        }
    }
}
