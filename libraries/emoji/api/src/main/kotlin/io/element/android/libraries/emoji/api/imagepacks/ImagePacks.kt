/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.emoji.api.imagepacks

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.HavenMarkdown
import io.element.android.libraries.matrix.api.core.RoomId

/*
 * Haven: MSC2545 image packs (custom emoji and stickers), like Haven Desktop (ImagePacks.ts).
 *
 * Packs are `m.room.image_pack` / `im.ponies.room_emotes` room state. A room's own packs are offered in that room, and
 * "favorite" packs (the `m.image_pack.rooms` / `im.ponies.emote_rooms` account data) everywhere.
 */

enum class ImageUsage { Emoticon, Sticker }

/** Which pack: the room holding it and its state key. */
data class PackRef(val roomId: String, val stateKey: String)

@Immutable
data class PackImage(
    val pack: PackRef,
    val shortcode: String,
    /** mxc:// URL of the image itself. */
    val url: String,
    /** The image's description (`body`), shown as its name when set. */
    val body: String?,
    val usages: Set<ImageUsage>,
    /** The image's `info` object, as JSON (sent as is with a sticker). */
    val infoJson: String?,
    val mimetype: String?,
    /**
     * Animated (`org.matrix.msc4230.is_animated`), or a GIF when that isn't set. Animated images can't use the server's
     * thumbnails (they're a still frame), everything else does.
     */
    val isAnimated: Boolean,
    /** Haven: `org.matrix.msc4230.is_animated` when the pack says; null when it doesn't (then the image itself is checked). */
    val animatedFlag: Boolean? = null,
) {
    val label: String get() = body?.trim()?.takeIf { it.isNotEmpty() } ?: shortcode
}

@Immutable
data class ImagePack(
    val ref: PackRef,
    val displayName: String,
    /** The pack's icon: its avatar, else its first image, else its room's avatar. */
    val iconUrl: String?,
    val roomName: String?,
    val images: List<PackImage>,
) {
    fun imagesFor(usage: ImageUsage) = images.filter { usage in it.usages }
}

interface ImagePackService {
    /**
     * Haven: whether [image] is animated. When the pack doesn't say (older packs, from before the flag existed), the image
     * itself is checked once, like Haven Desktop's PackImageAnimationCache, and the answer kept on the device.
     */
    suspend fun isAnimated(image: PackImage): Boolean = image.animatedFlag ?: image.isAnimated

    /** The kept answer of [isAnimated] for [image], without checking it now; null when it hasn't been checked yet. */
    fun knownAnimated(image: PackImage): Boolean? = image.animatedFlag

    /** Changes whenever fresh packs have been loaded, so what's showing can be read again. */
    val updates: kotlinx.coroutines.flow.StateFlow<Long>
        get() = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /**
     * The packs to offer in [roomId] for [usage]: the room's own packs, then the favorite ones (each pack once), each
     * with only the images for [usage]. Packs with none are left out.
     */
    suspend fun packsFor(roomId: RoomId?, usage: ImageUsage): List<ImagePack>

    /** Every non-empty pack in the rooms the user is in (for choosing favorites), in room order. */
    suspend fun allJoinedRoomPacks(forceRefresh: Boolean = false): List<ImagePack>

    /** The favorite packs (both the stable and unstable account data). */
    suspend fun favoriteRefs(): Set<PackRef>

    /** Saves the favorite packs, to both account data events. */
    suspend fun setFavoriteRefs(refs: Set<PackRef>): Result<Unit>

    /**
     * MSC4459 pack provenance for [images] (`com.beeper.msc4459.image_source_packs`, as a JSON object keyed by mxc URL), so
     * recipients can find the packs; only for packs in public or knockable rooms. Null when there's nothing to send.
     */
    suspend fun imageSourcePacksJson(images: Collection<PackImage>): String? = null

    /** `shortcode` (without colons) to image, for the custom emoji usable in [roomId]; the first pack wins. */
    suspend fun emoticonShortcodes(roomId: RoomId?): Map<String, PackImage> {
        val map = LinkedHashMap<String, PackImage>()
        packsFor(roomId, ImageUsage.Emoticon).forEach { pack ->
            pack.images.forEach { image -> map.putIfAbsent(image.shortcode, image) }
        }
        return map
    }
}

const val IMAGE_SOURCE_PACKS_KEY = "com.beeper.msc4459.image_source_packs"

object CustomEmojiHtml {
    /** `:shortcode:` in text. Shortcodes have no spaces or colons. */
    val SHORTCODE_REGEX = Regex(":([^\\s:]+):")

    /** The `<img>` Haven Desktop and other clients send for a custom emoji. */
    fun imgTag(image: PackImage): String {
        val alt = escape(":${image.shortcode}:")
        return "<img data-mx-emoticon height=\"32\" src=\"${escape(image.url)}\" alt=\"$alt\" title=\"$alt\" />"
    }

    /**
     * The HTML for a markdown message whose `:shortcode:`s are custom emoji in [shortcodes], or null when it has none
     * (so the message is sent as usual). The markdown is converted as it would have been when sending, with each
     * custom emoji's `<img>` put in place of its shortcode (the plain body keeps the `:shortcode:`).
     */
    fun htmlFromMarkdown(markdown: String, shortcodes: Map<String, PackImage>, havenMarkdown: HavenMarkdown): String? {
        if (shortcodes.isEmpty()) return null
        val images = mutableListOf<PackImage>()
        val placeholderText = SHORTCODE_REGEX.replace(markdown) { match ->
            val image = shortcodes[match.groupValues[1]] ?: return@replace match.value
            images += image
            "$PLACEHOLDER${images.size - 1}$PLACEHOLDER_END"
        }
        if (images.isEmpty()) return null
        val html = runCatching { havenMarkdown.toHtml(placeholderText) }.getOrNull() ?: return null
        return PLACEHOLDER_REGEX.replace(html) { match ->
            images.getOrNull(match.groupValues[1].toInt())?.let(::imgTag) ?: match.value
        }
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // Letters and digits only, so markdown leaves them alone
    private const val PLACEHOLDER = "HAVENCUSTOMEMOJI"
    private const val PLACEHOLDER_END = "Z"
    private val PLACEHOLDER_REGEX = Regex("$PLACEHOLDER(\\d+)$PLACEHOLDER_END")
}
