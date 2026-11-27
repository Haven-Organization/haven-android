/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.api.HavenAppItem
import io.element.android.features.social.api.HavenAppsService
import io.element.android.features.social.impl.msc.HavenAccountData
import io.element.android.features.social.impl.msc.optStringOrNull
import io.element.android.features.social.impl.msc.toStringList
import io.element.android.libraries.di.CacheDirectory
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.roomlist.awaitRoomListLoaded
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.havenStateEventRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * Pinned apps and widget apps, in account data shared with Haven Desktop:
 * - `software.haven.pinned_apps`: `{"pinned": [...]}`
 * - `software.haven.widget_apps`: `{"widgets": [{room_id, widget_id, room_name, state_types, content, sender}]}`
 *
 * A widget app links to the room's live widget (`im.vector.modular.widgets` state), it isn't a copy.
 *
 * Both lists, and the widgets' icons, are also saved to the cache directory, so after a restart the pinned apps and their
 * icons show straight away (from the saved copy, without waiting for the session or the network) and are then brought
 * up to date from the account data.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultHavenAppsService(
    private val matrixClient: MatrixClient,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
    socialRepository: SocialRepository,
    socialTimelineStore: SocialTimelineStore,
    @CacheDirectory cacheDirectory: File,
    private val httpApi: SocialHttpApi,
) : HavenAppsService {
    private val writeMutex = Mutex()
    private val cacheDir = File(cacheDirectory, "haven_apps")
    private val iconDir = File(cacheDir, "icons")
    private val savedFile = File(cacheDir, sha(matrixClient.sessionId.value) + ".json")

    /** The valid widget entries, as last read or written (what the saved copy holds). */
    @Volatile private var widgetEntries: List<JSONObject> = emptyList()
    private val _pinnedAppIds = MutableStateFlow<List<String>>(emptyList())
    override val pinnedAppIds: StateFlow<List<String>> = _pinnedAppIds.asStateFlow()

    private val _widgetApps = MutableStateFlow<List<HavenAppItem.Widget>>(emptyList())
    override val widgetApps: StateFlow<List<HavenAppItem.Widget>> = _widgetApps.asStateFlow()

    init {
        // The saved copy first (a small file, read here so the very first frame already has the pinned apps)...
        readSaved()
        sessionScope.launch {
            // ...then the account data, when there is some (none yet means "not synced yet", so the saved copy stays)
            if (!readAccountData()) {
                // Couldn't reach the server either (offline): the first sync brings the account data
                matrixClient.awaitRoomListLoaded()
                readAccountData()
            }
            save()
            syncWidgetRoomsFirst()
            cacheIcons()
        }
        // Created with the home screen: find and load the Social rooms ahead of time, so the Feed, Groups and Profile
        // are ready when the Social app is opened.
        sessionScope.launch {
            // Not until the room list's first sync is in: on a fresh cache every Social room is still unknown, and
            // subscribing to them all (plus the feed's own sync) on top of that first sync could leave it stuck.
            matrixClient.awaitRoomListLoaded()
            socialRepository.refreshSocialRooms()
            // One sliding sync request for the feed rooms' latest posts (no room timelines are opened for this).
            socialTimelineStore.prefetchFeed(socialRepository.feedRooms.value.map { it.roomId })
        }
    }

    /**
     * After clearing the cache, the widget apps' rooms are synced ahead of the rest, so the apps open straight away (the
     * bar shows them already: everything it needs is in the account data).
     */
    private suspend fun syncWidgetRoomsFirst() {
        val unknown = widgetEntries.mapNotNull { it.optStringOrNull("room_id") }.distinct()
            .mapNotNull { runCatching { RoomId(it) }.getOrNull() }
            .filter { roomId -> matrixClient.getRoom(roomId)?.use { true } != true }
        if (unknown.isNotEmpty()) matrixClient.roomListService.subscribeToVisibleRooms(unknown)
    }

    /** Returns whether both lists could be read (from the session's store, or else from the server). */
    private suspend fun readAccountData(): Boolean {
        val pinned = readContentOrError(HavenAccountData.PINNED_APPS)
            .onSuccess { content -> content?.let { _pinnedAppIds.value = it.optJSONArray("pinned").toStringList() } }
        val widgets = readContentOrError(HavenAccountData.WIDGET_APPS)
            .onSuccess { content -> content?.let { setWidgetEntries(validEntries(it)) } }
        return pinned.isSuccess && widgets.isSuccess
    }

    /**
     * The account data of [type]: from the session's store, or else from the server. After clearing the cache the store
     * doesn't have it, and the lists mustn't look empty meanwhile (pinning or adding an app then would save over them).
     * Null when it has never been set; fails when it's not known whether there is any (offline with nothing stored).
     */
    private suspend fun readContentOrError(type: String): Result<JSONObject?> {
        matrixClient.getAccountData(type).getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { return Result.success(unwrap(it)) }
        return httpApi.getAccountData(type)
            .map { it?.let(::unwrap) }
            .onFailure { Timber.w(it, "Couldn't read the %s account data", type) }
    }

    // Accept both the bare content and a whole event.
    private fun unwrap(json: JSONObject): JSONObject =
        if (json.has("content") && json.optJSONObject("content") != null && !json.has("pinned") && !json.has("widgets")) {
            json.getJSONObject("content")
        } else {
            json
        }

    private suspend fun readPinned(): List<String> =
        readContentOrError(HavenAccountData.PINNED_APPS).getOrThrow()?.optJSONArray("pinned").toStringList()

    private suspend fun readWidgetEntries(): List<JSONObject> =
        readContentOrError(HavenAccountData.WIDGET_APPS).getOrThrow()?.let { validEntries(it) }.orEmpty()

    /** Valid entries only, like Haven Desktop's `isEntry`. */
    private fun validEntries(content: JSONObject): List<JSONObject> {
        val array = content.optJSONArray("widgets") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.takeIf { entry ->
                entry.optStringOrNull("room_id") != null &&
                    entry.optStringOrNull("widget_id") != null &&
                    entry.optStringOrNull("sender") != null &&
                    entry.optJSONArray("state_types") != null &&
                    entry.optJSONObject("content") != null
            }
        }
    }

    private fun setWidgetEntries(entries: List<JSONObject>) {
        widgetEntries = entries
        _widgetApps.value = entries.map { it.toItem() }
    }

    private fun readSaved() {
        runCatching {
            if (!savedFile.exists()) return
            val saved = JSONObject(savedFile.readText())
            _pinnedAppIds.value = saved.optJSONArray("pinned").toStringList()
            setWidgetEntries(validEntries(saved))
        }.onFailure { Timber.w(it, "Couldn't read the saved apps") }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject()
                .put("pinned", JSONArray(_pinnedAppIds.value))
                .put("widgets", JSONArray(widgetEntries))
            cacheDir.mkdirs()
            val tmp = File(savedFile.path + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(savedFile)) {
                savedFile.delete()
                tmp.renameTo(savedFile)
            }
        }.onFailure { Timber.w(it, "Couldn't save the apps") }
    }

    private fun iconFile(avatarUrl: String) = File(iconDir, sha(avatarUrl))

    /** Downloads the icons not saved yet (once each: an icon's mxc URL never changes what it points to). */
    private suspend fun cacheIcons() {
        val missing = _widgetApps.value.mapNotNull { it.avatarUrl }.distinct().filter { !iconFile(it).exists() }
        if (missing.isEmpty()) return
        var added = false
        missing.forEach { url ->
            matrixClient.matrixMediaLoader.loadMediaThumbnail(MediaSource(url), ICON_SIZE, ICON_SIZE)
                .onSuccess { bytes ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            iconDir.mkdirs()
                            val tmp = File(iconFile(url).path + ".tmp")
                            tmp.writeBytes(bytes)
                            tmp.renameTo(iconFile(url))
                            added = true
                        }
                    }
                }
                .onFailure { Timber.w(it, "Couldn't save an app icon") }
        }
        if (added) setWidgetEntries(widgetEntries)
    }

    private fun JSONObject.toItem(): HavenAppItem.Widget {
        val content = getJSONObject("content")
        val name = content.optStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: content.optJSONObject("data")?.optStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Widget"
        val avatarUrl = content.optStringOrNull("avatar_url")?.takeIf { it.startsWith("mxc://") }
        return HavenAppItem.Widget(
            roomId = getString("room_id"),
            widgetId = getString("widget_id"),
            name = name,
            avatarUrl = avatarUrl,
            roomName = optStringOrNull("room_name"),
            iconFile = avatarUrl?.let { iconFile(it) }?.takeIf { it.exists() }?.path,
        )
    }

    override suspend fun setPinned(appId: String, pinned: Boolean): Result<Unit> = writeMutex.withLock {
        // Like Haven Desktop: re-read so pins made on another device aren't lost, then remove and (when pinning) append.
        // When the current list can't be read, nothing is saved rather than saving over it.
        val current = runCatching { readPinned() }.getOrElse { return@withLock Result.failure(it) }.filter { it != appId }
        val updated = if (pinned) current + appId else current
        matrixClient.setAccountData(HavenAccountData.PINNED_APPS, JSONObject().put("pinned", JSONArray(updated)).toString())
            .onSuccess {
                _pinnedAppIds.value = updated
                save()
            }
    }

    override suspend fun addWidgetApp(roomId: String, widgetId: String): Result<Unit> = runCatching {
        val room = matrixClient.getRoom(RoomId(roomId)) ?: error("Room not found")
        val entry = room.use {
            val legacy = it.havenStateEventRaw(WIDGET_LEGACY, widgetId)?.let { json -> JSONObject(json) }
                ?: error("Widget not found")
            val content = (legacy.optJSONObject("content") ?: legacy).takeIf { c -> c.optStringOrNull("url") != null } ?: error("Widget was removed")
            val stateTypes = JSONArray().put(WIDGET_LEGACY)
            val stable = it.havenStateEventRaw(WIDGET_STABLE, widgetId)?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
            if ((stable?.optJSONObject("content") ?: stable)?.optStringOrNull("url") != null) stateTypes.put(WIDGET_STABLE)
            JSONObject()
                .put("room_id", roomId)
                .put("widget_id", widgetId)
                .apply { it.info().name?.let { name -> put("room_name", name) } }
                .put("state_types", stateTypes)
                .put("content", content)
                .put("sender", legacy.optString("sender"))
        }
        writeMutex.withLock {
            val others = readWidgetEntries().filter { e -> !(e.getString("room_id") == roomId && e.getString("widget_id") == widgetId) }
            val updated = others + entry
            matrixClient.setAccountData(HavenAccountData.WIDGET_APPS, JSONObject().put("widgets", JSONArray(updated)).toString()).getOrThrow()
            setWidgetEntries(updated)
            save()
        }
        sessionScope.launch { cacheIcons() }
        setPinned(HavenAppItem.widgetAppId(roomId, widgetId), true).getOrThrow()
    }

    override suspend fun removeWidgetApp(appId: String): Result<Unit> = runCatching {
        val (roomId, widgetId) = HavenAppItem.parseWidgetAppId(appId) ?: error("Not a widget app")
        writeMutex.withLock {
            val updated = readWidgetEntries().filter { e -> !(e.getString("room_id") == roomId && e.getString("widget_id") == widgetId) }
            matrixClient.setAccountData(HavenAccountData.WIDGET_APPS, JSONObject().put("widgets", JSONArray(updated)).toString()).getOrThrow()
            setWidgetEntries(updated)
            save()
        }
        setPinned(appId, false).getOrThrow()
    }

    private companion object {
        /** Px; the same size the icons were already asked for, at most 64dp-ish on the bars. */
        const val ICON_SIZE = 128L

        fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

        const val WIDGET_LEGACY = "im.vector.modular.widgets"
        const val WIDGET_STABLE = "m.widget"
    }
}
