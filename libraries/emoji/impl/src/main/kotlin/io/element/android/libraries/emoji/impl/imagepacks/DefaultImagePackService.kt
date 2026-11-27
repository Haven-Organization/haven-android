/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.emoji.impl.imagepacks

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.emoji.api.imagepacks.ImagePack
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.emoji.api.imagepacks.ImageUsage
import io.element.android.libraries.emoji.api.imagepacks.PackImage
import io.element.android.libraries.emoji.api.imagepacks.PackRef
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.roomlist.awaitRoomListLoaded
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.havenStateEventsRaw
import io.element.android.libraries.matrix.api.room.join.JoinRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Reads image packs like Haven Desktop's ImagePacks.ts.
 *
 * Element X's sync doesn't include pack state, so the packs of every joined room are fetched with one request of their
 * own (simplified sliding sync, on a separate connection, asking only for pack state and each room's name and avatar),
 * then kept for a few minutes.
 *
 * The rooms' pack state is also saved to the cache directory, so after a restart the packs are there straight away (from
 * the saved copy) while the fresh copy loads in the background; [updates] then tells the pickers to show it.
 */
@SingleIn(SessionScope::class)
@Inject
@ContributesBinding(SessionScope::class)
class DefaultImagePackService(
    private val matrixClient: MatrixClient,
    okHttpClient: OkHttpClient,
    @CacheDirectory cacheDirectory: File,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
) : ImagePackService {
    private val httpClient = okHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private class RoomPacks(val packs: List<ImagePack>)

    /** A room's pack state as fetched, which is what's saved to disk (parsed again when read back). */
    private class RawRoom(val name: String?, val avatar: String?, val events: List<JSONObject>)

    private val cacheFile = File(
        File(cacheDirectory, "haven_image_packs"),
        MessageDigest.getInstance("SHA-256").digest(matrixClient.sessionId.value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32) + ".json",
    )

    // Haven Desktop's PackImageAnimationCache: whether images without the is_animated flag really are animated, by mxc
    // URL (an mxc URL's content never changes), checked once from the image's own bytes and kept on the device.
    private val animatedCacheFile = File(File(cacheDirectory, "haven_image_packs"), "animated.json")
    private val animatedCache: MutableMap<String, Boolean> = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        runCatching {
            if (animatedCacheFile.exists()) {
                val json = JSONObject(animatedCacheFile.readText())
                json.keys().forEach { put(it, json.getBoolean(it)) }
            }
        }
    }
    private val animatedChecks = kotlinx.coroutines.sync.Semaphore(3)
    private val animatedInFlight = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Boolean>>()

    override fun knownAnimated(image: PackImage): Boolean? = image.animatedFlag ?: animatedCache[image.url]

    override suspend fun isAnimated(image: PackImage): Boolean {
        knownAnimated(image)?.let { return it }
        val check = animatedInFlight.getOrPut(image.url) {
            sessionCoroutineScope.async {
                animatedChecks.withPermit {
                    // To a file rather than memory (some stickers are tens of MB), reading only its start: the second
                    // frame of a GIF, an APNG's acTL or a WebP's header are all near the beginning.
                    val bytes = matrixClient.matrixMediaLoader
                        .downloadMediaFile(MediaSource(image.url), image.mimetype, image.shortcode)
                        .mapCatching { mediaFile ->
                            mediaFile.use { readStart(it.toFile(), SNIFF_BYTES) }
                        }
                        .getOrNull()
                        ?: return@withPermit false
                    val animated = AnimatedImageSniffer.isAnimated(bytes)
                    animatedCache[image.url] = animated
                    saveAnimatedCache()
                    animated
                }
            }
        }
        return try {
            check.await()
        } finally {
            if (check.isCompleted) animatedInFlight.remove(image.url)
        }
    }

    private suspend fun saveAnimatedCache() = withContext(Dispatchers.IO) {
        runCatching {
            animatedCacheFile.parentFile?.mkdirs()
            val tmp = File(animatedCacheFile.path + ".tmp")
            tmp.writeText(JSONObject(animatedCache.toMap()).toString())
            tmp.renameTo(animatedCacheFile)
        }
    }

    private val mutex = Mutex()
    private var cache: Map<String, RoomPacks>? = null
    private var cachedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var refreshJob: Job? = null
    private val updatesFlow = MutableStateFlow(0L)
    override val updates: StateFlow<Long> = updatesFlow.asStateFlow()

    init {
        // Read the saved copy (and start refreshing it) as soon as the session has this service, before any picker opens
        // (the server fetch waits for the room list's first sync, so it doesn't slow that down on a fresh cache).
        sessionCoroutineScope.launch {
            mutex.withLock { if (cache == null) cache = readDiskCache() }
            matrixClient.awaitRoomListLoaded()
            allRoomPacks()
        }
    }

    private suspend fun allRoomPacks(forceRefresh: Boolean = false): Map<String, RoomPacks> {
        val current = mutex.withLock {
            if (cache == null) cache = readDiskCache()
            cache
        }
        if (current != null && !forceRefresh) {
            // Saved or in-memory packs show at once; a stale copy is refreshed in the background
            if (cachedAt?.elapsedNow()?.let { it < CACHE_TIME } != true) refreshInBackground()
            return current
        }
        return refresh() ?: current.orEmpty()
    }

    private fun refreshInBackground() = synchronized(this) {
        if (refreshJob?.isActive == true) return@synchronized
        refreshJob = sessionCoroutineScope.launch { refresh() }
    }

    /** Fetches the packs, keeps and saves them, and signals [updates]. Null if they couldn't be fetched. */
    private suspend fun refresh(): Map<String, RoomPacks>? {
        val raw = fetchAllRoomsRaw() ?: return null
        val parsed = raw.mapValues { (roomId, room) -> parseRoomPacks(roomId, room.events, room.name, room.avatar) }
        mutex.withLock {
            cache = parsed
            cachedAt = TimeSource.Monotonic.markNow()
        }
        updatesFlow.update { it + 1 }
        writeDiskCache(raw)
        return parsed
    }

    private suspend fun readDiskCache(): Map<String, RoomPacks>? = withContext(Dispatchers.IO) {
        runCatching {
            if (!cacheFile.exists()) return@runCatching null
            val saved = JSONObject(cacheFile.readText())
            val rooms = saved.optJSONObject("rooms") ?: return@runCatching null
            val order = saved.optJSONArray("order").strings()
            (order + rooms.keys().asSequence()).distinct().mapNotNull { roomId ->
                val room = rooms.optJSONObject(roomId) ?: return@mapNotNull null
                roomId to parseRoomPacks(
                    roomId,
                    room.optJSONArray("events").objects(),
                    room.optString("name").takeIf { it.isNotEmpty() },
                    room.optString("avatar").takeIf { it.isNotEmpty() },
                )
            }.toMap()
        }.onFailure { Timber.w(it, "Couldn't read the saved image packs") }.getOrNull()
    }

    private suspend fun writeDiskCache(raw: Map<String, RawRoom>) = withContext(Dispatchers.IO) {
        runCatching {
            val rooms = JSONObject()
            raw.forEach { (roomId, room) ->
                // Rooms without packs are kept too (as empty), so they aren't looked up one by one
                val packEvents = room.events.filter { it.optString("type") == STABLE_PACK || it.optString("type") == UNSTABLE_PACK }
                rooms.put(
                    roomId,
                    JSONObject()
                        .put("name", room.name ?: "")
                        .put("avatar", room.avatar ?: "")
                        .put("events", JSONArray().apply { packEvents.forEach { put(it) } }),
                )
            }
            val json = JSONObject().put("version", 1).put("order", JSONArray(raw.keys.toList())).put("rooms", rooms)
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        }.onFailure { Timber.w(it, "Couldn't save the image packs") }
    }

    override suspend fun packsFor(roomId: RoomId?, usage: ImageUsage): List<ImagePack> {
        val all = allRoomPacks()
        val roomPacks = roomId?.let { id -> all[id.value]?.packs ?: fetchRoomPacks(id) }.orEmpty()
        val favorites = favoriteRefs().mapNotNull { ref -> all[ref.roomId]?.packs?.firstOrNull { it.ref == ref } }
        val seen = HashSet<PackRef>()
        return (roomPacks + favorites)
            .filter { seen.add(it.ref) }
            .mapNotNull { pack -> pack.copy(images = pack.imagesFor(usage)).takeIf { it.images.isNotEmpty() } }
    }

    override suspend fun allJoinedRoomPacks(forceRefresh: Boolean): List<ImagePack> =
        allRoomPacks(forceRefresh).values.flatMap { it.packs }

    override suspend fun favoriteRefs(): Set<PackRef> {
        val refs = LinkedHashSet<PackRef>()
        for (type in listOf(IMAGE_PACK_ROOMS, EMOTE_ROOMS)) {
            val content = matrixClient.getAccountData(type).getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
            val rooms = content.optJSONObject("rooms") ?: continue
            rooms.keys().forEach { roomId ->
                rooms.optJSONObject(roomId)?.keys()?.forEach { stateKey -> refs += PackRef(roomId, stateKey) }
            }
        }
        return refs
    }

    override suspend fun setFavoriteRefs(refs: Set<PackRef>): Result<Unit> = runCatching {
        val rooms = JSONObject()
        refs.groupBy { it.roomId }.forEach { (roomId, packs) ->
            rooms.put(roomId, JSONObject().apply { packs.forEach { put(it.stateKey, JSONObject()) } })
        }
        val content = JSONObject().put("rooms", rooms).toString()
        matrixClient.setAccountData(IMAGE_PACK_ROOMS, content).getOrThrow()
        matrixClient.setAccountData(EMOTE_ROOMS, content).getOrThrow()
    }

    override suspend fun imageSourcePacksJson(images: Collection<PackImage>): String? {
        val via = matrixClient.sessionId.value.substringAfter(':', "").takeIf { it.isNotEmpty() }
        val result = JSONObject()
        val shareable = HashMap<String, Boolean>()
        images.distinctBy { it.url }.forEach { image ->
            val roomId = image.pack.roomId
            val isShareable = shareable.getOrPut(roomId) {
                val joinRule = runCatching { RoomId(roomId) }.getOrNull()?.let { id -> matrixClient.getRoom(id)?.use { it.info().joinRule } }
                joinRule == JoinRule.Public || joinRule == JoinRule.Knock || joinRule is JoinRule.KnockRestricted
            }
            if (!isShareable) return@forEach
            result.put(
                image.url,
                JSONObject()
                    .put("room_id", roomId)
                    .put("via", JSONArray().apply { via?.let { put(it) } })
                    .put("state_key", image.pack.stateKey)
                    .put("shortcode", image.shortcode),
            )
        }
        return result.takeIf { it.length() > 0 }?.toString()
    }

    /** One room's packs from its state, for a room the bulk request didn't cover (e.g. it failed). */
    private suspend fun fetchRoomPacks(roomId: RoomId): List<ImagePack> {
        val room = matrixClient.getRoom(roomId) ?: return emptyList()
        return room.use {
            val info = it.info()
            val events = (it.havenStateEventsRaw(UNSTABLE_PACK) + it.havenStateEventsRaw(STABLE_PACK))
                .mapNotNull { json -> runCatching { JSONObject(json) }.getOrNull() }
            parseRoomPacks(roomId.value, events, info.name, info.avatarUrl).packs
        }
    }

    private suspend fun fetchAllRoomsRaw(): Map<String, RawRoom>? = withContext(Dispatchers.IO) {
        runCatching {
            val credentials = matrixClient.havenRawCredentials().getOrThrow()
            val requiredState = JSONArray()
                .put(JSONArray().put(UNSTABLE_PACK).put("*"))
                .put(JSONArray().put(STABLE_PACK).put("*"))
                .put(JSONArray().put("m.room.name").put(""))
                .put(JSONArray().put("m.room.avatar").put(""))
            val body = JSONObject()
                .put("conn_id", CONN_ID)
                .put(
                    "lists",
                    JSONObject().put(
                        "packs",
                        JSONObject()
                            .put("ranges", JSONArray().put(JSONArray().put(0).put(MAX_ROOMS)))
                            .put("timeline_limit", 0)
                            .put("required_state", requiredState),
                    ),
                )
            val request = Request.Builder()
                .url(credentials.homeserverUrl.trimEnd('/') + "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync?timeout=0")
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val json = httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                JSONObject(response.body.string())
            }
            // In the list's order (most recent activity first), like the room list
            val order = json.optJSONObject("lists")?.optJSONObject("packs")?.optJSONArray("ops")
                ?.let { ops -> (0 until ops.length()).flatMap { i -> ops.optJSONObject(i)?.optJSONArray("room_ids").strings() } }
                .orEmpty()
            val rooms = json.optJSONObject("rooms") ?: JSONObject()
            val ids = (order + rooms.keys().asSequence()).distinct()
            ids.mapNotNull { roomId ->
                val room = rooms.optJSONObject(roomId) ?: return@mapNotNull null
                val state = room.optJSONArray("required_state").objects()
                val name = room.optString("name").takeIf { it.isNotEmpty() }
                    ?: state.firstOrNull { it.optString("type") == "m.room.name" }?.optJSONObject("content")?.optString("name")?.takeIf { it.isNotEmpty() }
                val avatar = room.optString("avatar").takeIf { it.startsWith("mxc://") }
                    ?: state.firstOrNull { it.optString("type") == "m.room.avatar" }?.optJSONObject("content")?.optString("url")?.takeIf { it.startsWith("mxc://") }
                roomId to RawRoom(name, avatar, state)
            }.toMap()
        }.onFailure { Timber.w(it, "Couldn't load image packs") }.getOrNull()
    }

    private fun parseRoomPacks(roomId: String, stateEvents: List<JSONObject>, roomName: String?, roomAvatar: String?): RoomPacks {
        // The stable event wins over the unstable one with the same state key
        val byStateKey = LinkedHashMap<String, JSONObject>()
        stateEvents.filter { it.optString("type") == UNSTABLE_PACK }.forEach { byStateKey[it.optString("state_key")] = it }
        stateEvents.filter { it.optString("type") == STABLE_PACK }.forEach { byStateKey[it.optString("state_key")] = it }
        val packs = byStateKey.mapNotNull { (stateKey, event) ->
            parsePack(PackRef(roomId, stateKey), event.optJSONObject("content") ?: return@mapNotNull null, roomName, roomAvatar)
        }
        return RoomPacks(packs)
    }

    private fun parsePack(ref: PackRef, content: JSONObject, roomName: String?, roomAvatar: String?): ImagePack? {
        val meta = content.optJSONObject("pack")
        val packUsages = meta?.optJSONArray("usage").usages()
        val imagesJson = content.optJSONObject("images") ?: return null
        val images = imagesJson.keys().asSequence().mapNotNull { shortcode ->
            val image = imagesJson.optJSONObject(shortcode) ?: return@mapNotNull null
            val url = image.optString("url").takeIf { it.startsWith("mxc://") } ?: return@mapNotNull null
            if (shortcode.isBlank() || shortcode.any { it.isWhitespace() || it == ':' }) return@mapNotNull null
            val info = image.optJSONObject("info")
            val mimetype = info?.optString("mimetype")?.takeIf { it.isNotEmpty() }
            PackImage(
                pack = ref,
                shortcode = shortcode,
                url = url,
                body = image.optString("body").takeIf { it.isNotBlank() },
                // An image's usage wins over the pack's; with neither, it's both
                usages = image.optJSONArray("usage").usages() ?: packUsages ?: ImageUsage.entries.toSet(),
                infoJson = info?.toString(),
                mimetype = mimetype,
                // Without the flag the mimetype isn't reliable (plenty of animated stickers are labelled PNG, and plenty of
                // still ones GIF), so the image itself is checked once (see isAnimated); until then, a still thumbnail.
                isAnimated = if (info?.has(IS_ANIMATED) == true) info.optBoolean(IS_ANIMATED) else animatedCache[url] ?: false,
                animatedFlag = if (info?.has(IS_ANIMATED) == true) info.optBoolean(IS_ANIMATED) else null,
            )
        }.toList()
        if (images.isEmpty()) return null
        return ImagePack(
            ref = ref,
            displayName = meta?.optString("display_name")?.trim()?.takeIf { it.isNotEmpty() }
                ?: ref.stateKey.takeIf { it.isNotBlank() }
                ?: roomName
                ?: ref.roomId,
            iconUrl = meta?.optString("avatar_url")?.takeIf { it.startsWith("mxc://") } ?: images.first().url,
            roomName = roomName,
            images = images,
        )
    }

    private fun JSONArray?.usages(): Set<ImageUsage>? {
        val values = strings().mapNotNull {
            when (it) {
                "emoticon" -> ImageUsage.Emoticon
                "sticker" -> ImageUsage.Sticker
                else -> null
            }
        }
        return values.toSet().takeIf { it.isNotEmpty() }
    }

    private fun JSONArray?.strings(): List<String> = this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()

    private fun JSONArray?.objects(): List<JSONObject> = this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()

    private companion object {
        /** How much of an image is read to tell whether it's animated. */
        const val SNIFF_BYTES = 4 * 1024 * 1024

        fun readStart(file: File, max: Int): ByteArray = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(max.toLong(), file.length()).toInt())
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }

        const val STABLE_PACK = "m.room.image_pack"
        const val UNSTABLE_PACK = "im.ponies.room_emotes"
        const val IMAGE_PACK_ROOMS = "m.image_pack.rooms"
        const val EMOTE_ROOMS = "im.ponies.emote_rooms"
        const val IS_ANIMATED = "org.matrix.msc4230.is_animated"
        const val CONN_ID = "haven-image-packs"

        const val MAX_ROOMS = 4999
        val CACHE_TIME = 5.minutes
    }
}
