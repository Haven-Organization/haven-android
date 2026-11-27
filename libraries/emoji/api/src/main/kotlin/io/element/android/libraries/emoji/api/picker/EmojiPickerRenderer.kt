/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.emoji.api.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import io.element.android.emojibasebindings.Emoji
import io.element.android.libraries.emoji.api.imagepacks.PackImage
import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.collections.immutable.ImmutableSet

/**
 * Renders the emoji picker, so that callers can embed it without depending on its implementation.
 *
 * See [NoOpEmojiPickerRenderer] for a renderer that draws nothing, used where the picker is disabled.
 */
@Immutable
interface EmojiPickerRenderer {
    /**
     * Draws the picker.
     *
     * @param state opaque state produced by [EmojiPickerPresenter.present].
     * @param onSelectEmoji invoked when the user taps an emoji (including a skin-tone variant).
     * @param selectedEmojis emojis to visually mark as already selected.
     * @param modifier layout modifier for the picker container.
     * @param onBackspace when supplied, the picker shows a backspace key that invokes it, allowing a picker standing in for the keyboard to delete as well.
     * @param contentDescription accessibility label for each emoji cell.
     * @param roomId Haven: the room the picker is for, whose image packs (and the favorite ones) it offers.
     * @param onSelectPackImage Haven: invoked when a custom emoji is picked; without it, no custom emoji are offered.
     */
    @Composable
    fun Render(
        state: EmojiPickerState,
        onSelectEmoji: (Emoji) -> Unit,
        onSelectCustomEmoji: (String) -> Unit, // SC
        selectedEmojis: ImmutableSet<String>,
        modifier: Modifier = Modifier,
        onBackspace: (() -> Unit)? = null,
        contentDescription: @Composable (Emoji, Boolean) -> String = { emoji, _ -> emoji.unicode },
        roomId: RoomId? = null,
        onSelectPackImage: ((PackImage) -> Unit)? = null,
    )

    /**
     * Haven: the sticker picker, the stickers of [roomId]'s packs and the favorite packs, with a rail of the packs.
     * Draws nothing when the custom emoji & sticker picker is turned off.
     */
    @Composable
    fun RenderStickers(
        roomId: RoomId?,
        onSelectSticker: (PackImage) -> Unit,
        modifier: Modifier = Modifier,
    ) = Unit
}

object NoOpEmojiPickerRenderer : EmojiPickerRenderer {
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
    ) = Unit
}
