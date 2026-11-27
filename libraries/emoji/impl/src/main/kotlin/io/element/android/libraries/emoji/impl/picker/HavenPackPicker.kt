/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.emoji.impl.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import chat.schildi.lib.R
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.emojibasebindings.Emoji
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.emoji.api.imagepacks.ImagePack
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.emoji.api.imagepacks.ImageUsage
import io.element.android.libraries.emoji.api.imagepacks.PackImage
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch

/*
 * Haven: custom emoji and stickers from image packs, in the emoji picker and the sticker picker.
 *
 * Memory, like Haven Desktop's fixes for the picker running out of memory with many packs: the pack icons on the rail
 * are always small server thumbnails (even of animated images), grid cells load small thumbnails of still images, and
 * only animated images (which a thumbnail would freeze) load the original, decoded at the cell's size. The grids are lazy,
 * so only the cells on screen load anything.
 */

/** The pack service, for the grid cells (to check whether images without the animated flag are animated). */
internal val LocalImagePackService = androidx.compose.runtime.staticCompositionLocalOf<ImagePackService?> { null }

/** One page of the emoji picker: a standard emoji category, or a pack of custom emoji. */
internal sealed interface PickerPage {
    data class Unicode(val category: EmojiCategory) : PickerPage
    data class Pack(val pack: ImagePack) : PickerPage
}

/** Packs go right after the recent emoji, before the standard categories, like Haven Desktop. */
internal fun buildPickerPages(categories: List<EmojiCategory>, packs: List<ImagePack>): ImmutableList<PickerPage> {
    val unicode = categories.map { PickerPage.Unicode(it) }
    val packPages = packs.map { PickerPage.Pack(it) }
    val recentCount = if (categories.firstOrNull()?.titleId == io.element.android.libraries.emoji.impl.R.string.emoji_picker_category_recent) 1 else 0
    return (unicode.take(recentCount) + packPages + unicode.drop(recentCount)).toImmutableList()
}

/**
 * The thumbnail size to ask the server for, in px. Only a few fixed sizes, so the same thumbnail is cached once and reused
 * by the rail, the pickers and the emoji in messages (which also ask for 96px), and is quick to load the next time.
 */
internal fun thumbnailPx(sizePx: Int): Long = if (sizePx <= 96) 96L else 320L

/** A still thumbnail of [image], for showing it [size] big. */
@Composable
internal fun packThumbnailModel(url: String, size: Dp): MediaRequestData {
    val px = with(LocalDensity.current) { size.roundToPx() }
    return MediaRequestData(MediaSource(url), MediaRequestData.Kind.Thumbnail(thumbnailPx(px)))
}

@Composable
internal fun PackTabIcon(pack: ImagePack) {
    val iconUrl = pack.iconUrl
    if (iconUrl == null) {
        Text(pack.displayName.take(1), style = ElementTheme.typography.fontBodyLgMedium)
        return
    }
    AsyncImage(
        // Always a (still) thumbnail: every rail icon loads as soon as the picker opens
        model = packThumbnailModel(iconUrl, RailIconSize),
        contentDescription = pack.displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(RailIconSize)
            .clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
internal fun PackImageCell(
    image: PackImage,
    imageSize: Dp,
    onClick: (PackImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick(image) }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Whether it's animated: the pack's flag, or else (older packs) a one-time check of the image itself, kept on the
        // device, like Haven Desktop. Until known, a still thumbnail (most images are still).
        val service = LocalImagePackService.current
        var animated by remember(image.url) { mutableStateOf(service?.knownAnimated(image) ?: image.isAnimated) }
        if (service != null && service.knownAnimated(image) == null) {
            LaunchedEffect(image.url) { animated = service.isAnimated(image) }
        }
        // The still thumbnail shows quickly; an animated image then loads its original over it (decoded at the cell's
        // size), so the grid isn't left empty while the GIFs download. As a file, like the timeline's stickers: some
        // are tens of MB, too big to hold in memory (they stayed frozen), and the file stays cached on the device.
        AsyncImage(
            model = packThumbnailModel(image.url, imageSize),
            contentDescription = image.label,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(imageSize),
        )
        if (animated) {
            AsyncImage(
                model = MediaRequestData(
                    source = MediaSource(image.url),
                    kind = MediaRequestData.Kind.File(
                        fileName = image.shortcode,
                        mimeType = image.mimetype ?: "application/octet-stream",
                    ),
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(imageSize),
            )
        }
    }
}

@Composable
internal fun PackImageGrid(
    images: List<PackImage>,
    onSelect: (PackImage) -> Unit,
    modifier: Modifier = Modifier,
    cellSize: Dp = 48.dp,
    imageSize: Dp = 32.dp,
) {
    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        columns = GridCells.Adaptive(minSize = cellSize),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(images, key = { "${it.pack.roomId}/${it.pack.stateKey}/${it.shortcode}" }) { image ->
            PackImageCell(image = image, imageSize = imageSize, onClick = onSelect, modifier = Modifier.aspectRatio(1f))
        }
    }
}

/** Custom emoji matching a search, like Haven Desktop: on the name, description or shortcode. */
internal fun List<ImagePack>.searchImages(query: String): List<PackImage> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return flatMap { it.images }
        .filter { it.shortcode.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true) }
        .sortedWith(
            compareByDescending<PackImage> { it.shortcode.contains(q, ignoreCase = true) }
                .thenByDescending { it.shortcode.startsWith(q, ignoreCase = true) }
                .thenBy { it.shortcode.length }
        )
        .take(60)
}

/** [emoji] in the chosen skin tone (by its Fitzpatrick modifier), when it has that variant. */
internal fun Emoji.withSkinTone(modifierHex: String?): Emoji {
    if (modifierHex == null) return this
    val skin = skins?.firstOrNull { skin ->
        val tones = skin.hexcode.split('-').filter { it in SKIN_TONE_MODIFIERS }
        tones.isNotEmpty() && tones.all { it == modifierHex }
    } ?: return this
    return Emoji(skin.hexcode, label, tags, shortcodes, skin.unicode, skins)
}

private val SKIN_TONE_MODIFIERS = setOf("1F3FB", "1F3FC", "1F3FD", "1F3FE", "1F3FF")

/** The sticker picker: Haven Desktop's sticker tab, with the rail showing only the packs. */
@Composable
internal fun StickerPickerView(
    roomId: RoomId?,
    imagePackService: ImagePackService,
    onSelectSticker: (PackImage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val packsUpdate by imagePackService.updates.collectAsState()
    val packs by produceState<ImmutableList<ImagePack>?>(null, roomId, packsUpdate) {
        value = imagePackService.packsFor(roomId, ImageUsage.Sticker).toImmutableList()
    }
    val current = packs
    Column(modifier) {
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            current.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.haven_no_sticker_packs),
                    style = ElementTheme.typography.fontBodyMdRegular,
                    color = ElementTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            else -> {
                val pagerState = rememberPagerState { current.size }
                val scope = rememberCoroutineScope()
                SecondaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage.coerceAtMost(current.lastIndex),
                    edgePadding = 0.dp,
                    minTabWidth = PackRailTabMinWidth,
                ) {
                    current.forEachIndexed { index, pack ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            // Straight to the page: animating through the packs in between would start loading all of theirs too
                            onClick = { scope.launch { pagerState.scrollToPage(index) } },
                            icon = { PackTabIcon(pack) },
                        )
                    }
                }
                HorizontalPager(state = pagerState, beyondViewportPageCount = 1, modifier = Modifier.fillMaxWidth()) { page ->
                    // Bigger cells for stickers, 4 or so per row like Haven Desktop
                    PackImageGrid(
                        images = current[page].images,
                        onSelect = onSelectSticker,
                        cellSize = 76.dp,
                        imageSize = 68.dp,
                    )
                }
            }
        }
    }
}

private val RailIconSize = 28.dp

/** About the width of the emoji picker's stock category tabs, rather than Material's 90dp scrollable-tab minimum. */
private val PackRailTabMinWidth = 40.dp
