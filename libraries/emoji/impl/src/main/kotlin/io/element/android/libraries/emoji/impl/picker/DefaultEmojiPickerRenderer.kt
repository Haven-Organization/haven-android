/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.emoji.impl.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.emojibasebindings.Emoji
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.emoji.api.imagepacks.ImagePack
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.emoji.api.imagepacks.ImageUsage
import io.element.android.libraries.emoji.api.imagepacks.PackImage
import io.element.android.libraries.emoji.api.picker.EmojiPickerRenderer
import io.element.android.libraries.emoji.api.picker.EmojiPickerState
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Inject
@ContributesBinding(SessionScope::class)
class DefaultEmojiPickerRenderer(
    // Haven: custom emoji and stickers
    private val imagePackService: ImagePackService,
) : EmojiPickerRenderer {
    @Composable
    override fun Render(
        state: EmojiPickerState,
        onSelectEmoji: (Emoji) -> Unit,
        onSelectCustomEmoji: (String) -> Unit, // SC
        selectedEmojis: ImmutableSet<String>,
        modifier: Modifier,
        onBackspace: (() -> Unit)?,
        contentDescription: @Composable (Emoji, Boolean) -> String,
        roomId: RoomId?,
        onSelectPackImage: ((PackImage) -> Unit)?,
    ) {
        // Haven: the room's and the favorite packs, unless the custom emoji picker is turned off
        val packsEnabled = onSelectPackImage != null && !ScPrefs.HAVEN_DISABLE_CUSTOM_EMOJI_PICKER.value()
        val packsUpdate by imagePackService.updates.collectAsState()
        val packs by produceState<ImmutableList<ImagePack>>(persistentListOf(), roomId, packsEnabled, packsUpdate) {
            value = if (packsEnabled) imagePackService.packsFor(roomId, ImageUsage.Emoticon).toImmutableList() else persistentListOf()
        }
        if (state is DefaultEmojiPickerState) {
            androidx.compose.runtime.CompositionLocalProvider(LocalImagePackService provides imagePackService) {
            EmojiPickerView(
                state = state,
                onSelectEmoji = onSelectEmoji,
                onSelectCustomEmoji = onSelectCustomEmoji, // SC
                selectedEmojis = selectedEmojis,
                contentDescription = contentDescription,
                modifier = modifier,
                onBackspace = onBackspace,
                packs = if (packsEnabled) packs else persistentListOf(),
                onSelectPackImage = { onSelectPackImage?.invoke(it) },
            )
            }
        } else {
            error("Unsupported state type: ${state::class.java}")
        }
    }

    @Composable
    override fun RenderStickers(
        roomId: RoomId?,
        onSelectSticker: (PackImage) -> Unit,
        modifier: Modifier,
    ) {
        if (ScPrefs.HAVEN_DISABLE_CUSTOM_EMOJI_PICKER.value()) return
        androidx.compose.runtime.CompositionLocalProvider(LocalImagePackService provides imagePackService) {
            StickerPickerView(
                roomId = roomId,
                imagePackService = imagePackService,
                onSelectSticker = onSelectSticker,
                modifier = modifier,
            )
        }
    }
}
