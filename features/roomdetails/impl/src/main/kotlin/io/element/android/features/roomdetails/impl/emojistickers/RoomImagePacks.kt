/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.roomdetails.impl.emojistickers

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import io.element.android.libraries.emoji.api.imagepacks.ImageUsage
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.BaseRoom
import io.element.android.libraries.matrix.api.room.havenStateEventsRaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/*
 * Haven: a room's MSC2545 image packs (custom emoji and stickers) for its "Emoji & Stickers" settings, like Haven
 * Desktop's EmojiStickersRoomSettingsTab and PackEditor (ImagePacks.ts).
 *
 * Packs are read from both `m.room.image_pack` and the older `im.ponies.room_emotes` (the stable one wins for the same
 * state key) and always saved as `m.room.image_pack`. State can't be removed, so deleting a pack saves it empty.
 */

internal const val STABLE_PACK = "m.room.image_pack"
private const val UNSTABLE_PACK = "im.ponies.room_emotes"

internal data class EditableImage(
    /** The key the image was saved under, null for one added since. */
    val key: String?,
    val shortcode: String,
    val url: String,
    val body: String,
    val usages: Set<ImageUsage>,
    /** The image's `info`, as JSON. */
    val infoJson: String?,
    /** The image's saved JSON, so fields this screen doesn't edit are kept. */
    val rawJson: String?,
)

internal data class EditablePack(
    val stateKey: String,
    val displayName: String,
    /** The pack's own avatar, null when it has none. */
    val avatarUrl: String?,
    val usages: Set<ImageUsage>,
    val images: List<EditableImage>,
    /** The pack's saved `pack` object, so fields this screen doesn't edit (e.g. attribution) are kept. */
    val metaJson: String?,
) {
    /** What to show as its icon: its avatar, else its first image. */
    val iconUrl: String? get() = avatarUrl ?: images.firstOrNull()?.url
}

internal class RoomImagePacks(
    private val room: BaseRoom,
    private val matrixClient: MatrixClient,
    private val context: Context,
) {
    /** The room's packs, including ones with no images yet (just created), but not deleted (empty) ones. */
    suspend fun load(): List<EditablePack> = withContext(Dispatchers.IO) {
        val byStateKey = LinkedHashMap<String, JSONObject>()
        for (type in listOf(UNSTABLE_PACK, STABLE_PACK)) {
            room.havenStateEventsRaw(type)
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .filter { it.optString("type") == type }
                .forEach { event -> event.optJSONObject("content")?.let { byStateKey[event.optString("state_key")] = it } }
        }
        byStateKey.mapNotNull { (stateKey, content) -> parse(stateKey, content) }
    }

    private fun parse(stateKey: String, content: JSONObject): EditablePack? {
        val meta = content.optJSONObject("pack")
        val imagesJson = content.optJSONObject("images") ?: JSONObject()
        if (meta == null && imagesJson.length() == 0) return null
        val packUsages = meta?.optJSONArray("usage").usages() ?: ImageUsage.entries.toSet()
        val images = imagesJson.keys().asSequence().mapNotNull { key ->
            val image = imagesJson.optJSONObject(key) ?: return@mapNotNull null
            val url = image.optString("url").takeIf { it.startsWith("mxc://") } ?: return@mapNotNull null
            EditableImage(
                key = key,
                shortcode = key,
                url = url,
                body = image.optString("body"),
                usages = image.optJSONArray("usage").usages() ?: packUsages,
                infoJson = image.optJSONObject("info")?.toString(),
                rawJson = image.toString(),
            )
        }.toList()
        return EditablePack(
            stateKey = stateKey,
            displayName = meta?.optString("display_name")?.trim()?.takeIf { it.isNotEmpty() } ?: stateKey,
            avatarUrl = meta?.optString("avatar_url")?.takeIf { it.startsWith("mxc://") },
            usages = packUsages,
            images = images,
            metaJson = meta?.toString(),
        )
    }

    /** Saves [pack]; returns it as saved (shortcodes cleaned up and made unique). */
    suspend fun save(pack: EditablePack): Result<EditablePack> = runCatching {
        val meta = (pack.metaJson?.let(::JSONObject) ?: JSONObject())
            .put("display_name", pack.displayName.trim().ifEmpty { pack.stateKey })
            .put("usage", pack.usages.toJson())
        if (pack.avatarUrl != null) meta.put("avatar_url", pack.avatarUrl) else meta.remove("avatar_url")
        val images = JSONObject()
        val usedKeys = HashSet<String>()
        val savedImages = pack.images.map { image ->
            val key = uniqueShortcode(sanitizeShortcode(image.shortcode.trim().ifEmpty { image.key ?: "image" }), usedKeys)
            usedKeys += key
            val json = (image.rawJson?.let(::JSONObject) ?: JSONObject())
                .put("url", image.url)
                .put("usage", image.usages.toJson())
            if (image.body.isNotBlank()) json.put("body", image.body.trim()) else json.remove("body")
            image.infoJson?.let { json.put("info", JSONObject(it)) }
            images.put(key, json)
            image.copy(key = key, shortcode = key, rawJson = json.toString())
        }
        val content = JSONObject().put("pack", meta).put("images", images)
        room.sendRawState(STABLE_PACK, pack.stateKey, content.toString()).getOrThrow()
        pack.copy(displayName = meta.getString("display_name"), images = savedImages, metaJson = meta.toString())
    }

    suspend fun create(name: String, existing: List<EditablePack>): Result<EditablePack> = runCatching {
        val stateKey = newStateKey(name, existing.map { it.stateKey }.toSet())
        val pack = EditablePack(
            stateKey = stateKey,
            displayName = name.trim(),
            avatarUrl = null,
            usages = ImageUsage.entries.toSet(),
            images = emptyList(),
            metaJson = null,
        )
        save(pack).getOrThrow()
    }

    suspend fun delete(pack: EditablePack): Result<Unit> = runCatching {
        room.sendRawState(STABLE_PACK, pack.stateKey, JSONObject().put("images", JSONObject()).toString()).getOrThrow()
    }

    /** Uploads the image at [uri]: its mxc:// URL, `info` and a shortcode from its file name. */
    suspend fun upload(uri: Uri): Result<UploadedImage> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the image")
            val mimeType = resolver.getType(uri) ?: "image/png"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val mxcUrl = matrixClient.uploadMedia(mimeType, bytes).getOrThrow()
            val info = JSONObject().put("mimetype", mimeType).put("size", bytes.size)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) info.put("w", bounds.outWidth).put("h", bounds.outHeight)
            UploadedImage(mxcUrl, info.toString(), sanitizeShortcode(name ?: mxcUrl.substringAfterLast('/')))
        }
    }

    private fun JSONArray?.usages(): Set<ImageUsage>? {
        this ?: return null
        val values = (0 until length()).mapNotNull {
            when (optString(it)) {
                "emoticon" -> ImageUsage.Emoticon
                "sticker" -> ImageUsage.Sticker
                else -> null
            }
        }.toSet()
        return values.takeIf { it.isNotEmpty() }
    }

    private fun Set<ImageUsage>.toJson() = JSONArray().apply {
        if (ImageUsage.Emoticon in this@toJson) put("emoticon")
        if (ImageUsage.Sticker in this@toJson) put("sticker")
    }
}

internal data class UploadedImage(val url: String, val infoJson: String, val shortcode: String)

/** Drops a file extension and replaces anything a shortcode can't have, like Haven Desktop. */
internal fun sanitizeShortcode(text: String): String =
    text.replace(Regex("\\.[^./]+$"), "").replace(Regex("[^a-zA-Z0-9_-]+"), "_").ifEmpty { "image" }

internal fun uniqueShortcode(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var i = 2
    while ("${base}_$i" in taken) i++
    return "${base}_$i"
}

private fun newStateKey(name: String, taken: Set<String>): String {
    val base = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "pack" }
    if (base !in taken) return base
    var i = 2
    while ("$base-$i" in taken) i++
    return "$base-$i"
}
