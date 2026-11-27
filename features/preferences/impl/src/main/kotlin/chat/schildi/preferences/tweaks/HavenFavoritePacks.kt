/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.preferences.tweaks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.schildi.lib.R
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.SimpleAlertDialogContent
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.Checkbox
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TextButton
import io.element.android.libraries.emoji.api.imagepacks.ImagePack
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.emoji.api.imagepacks.PackImage
import io.element.android.libraries.emoji.api.imagepacks.PackRef
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import kotlinx.coroutines.launch

/** Haven: draws the [chat.schildi.lib.preferences.ScCustomPref] entries, by key. */
val LocalScCustomPrefContent = staticCompositionLocalOf<@Composable (String) -> Unit> { {} }

/**
 * Haven Desktop's "Favorite Packs": every pack in the rooms the user is in, ticked when it's a favorite (offered in every
 * room, not just its own). Changes are kept until saved, to both the stable and unstable account data.
 */
@Composable
fun HavenFavoritePacks(imagePackService: ImagePackService) {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ImagePack>?>(null) }
    var saved by remember { mutableStateOf<Set<PackRef>>(emptySet()) }
    var draft by remember { mutableStateOf<Set<PackRef>>(emptySet()) }
    var isSaving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ImagePack?>(null) }
    LaunchedEffect(Unit) {
        val favorites = imagePackService.favoriteRefs()
        saved = favorites
        draft = favorites
        packs = imagePackService.allJoinedRoomPacks(forceRefresh = true)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.haven_favorite_packs_summary),
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
        )
        Spacer(Modifier.size(8.dp))
        val current = packs
        when {
            current == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.haven_favorite_packs_loading), color = ElementTheme.colors.textSecondary)
            }
            current.isEmpty() -> Text(
                text = stringResource(R.string.haven_favorite_packs_empty),
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textSecondary,
            )
            else -> current.forEach { pack ->
                PackRow(
                    pack = pack,
                    checked = pack.ref in draft,
                    onCheckedChange = { checked -> draft = if (checked) draft + pack.ref else draft - pack.ref },
                    onView = { viewing = pack },
                )
            }
        }
        if (draft != saved) {
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    text = stringResource(R.string.haven_favorite_packs_save),
                    showProgress = isSaving,
                    onClick = {
                        isSaving = true
                        scope.launch {
                            imagePackService.setFavoriteRefs(draft)
                                .onSuccess {
                                    saved = draft
                                    saveFailed = false
                                }
                                .onFailure { saveFailed = true }
                            isSaving = false
                        }
                    },
                )
                OutlinedButton(
                    text = stringResource(R.string.haven_favorite_packs_discard),
                    onClick = { draft = saved },
                )
            }
        }
        if (saveFailed) {
            Text(
                text = stringResource(R.string.haven_favorite_packs_save_failed),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textCriticalPrimary,
            )
        }
    }
    viewing?.let { pack -> PackDialog(pack, onDismiss = { viewing = null }) }
}

@Composable
private fun PackRow(pack: ImagePack, checked: Boolean, onCheckedChange: (Boolean) -> Unit, onView: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Thumbnail(pack.iconUrl, 40.dp, pack.displayName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pack.displayName, style = ElementTheme.typography.fontBodyLgMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(pack.roomName, stringResource(R.string.haven_pack_images_count, pack.images.size)).joinToString(" · "),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(text = stringResource(R.string.haven_pack_view), onClick = onView)
    }
}

/** The pack's images (still thumbnails, so a big pack can't use up the memory). */
@Composable
private fun PackDialog(pack: ImagePack, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        SimpleAlertDialogContent(
            title = pack.displayName,
            submitText = stringResource(io.element.android.libraries.ui.strings.CommonStrings.action_close),
            onSubmitClick = onDismiss,
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 72.dp),
                contentPadding = PaddingValues(4.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(pack.images, key = { it.shortcode }) { image -> PackImageTile(image) }
            }
        }
    }
}

@Composable
private fun PackImageTile(image: PackImage) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Thumbnail(image.url, 48.dp, image.label)
        Text(
            text = ":${image.shortcode}:",
            style = ElementTheme.typography.fontBodyXsRegular,
            color = ElementTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Thumbnail(url: String?, size: Dp, description: String) {
    if (url == null) {
        Box(Modifier.size(size))
        return
    }
    val px = with(LocalDensity.current) { size.roundToPx() }.toLong()
    AsyncImage(
        model = MediaRequestData(MediaSource(url), MediaRequestData.Kind.Thumbnail(px)),
        contentDescription = description,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)),
    )
}
