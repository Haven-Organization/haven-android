/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import io.element.android.features.social.api.HavenAppPreferences
import java.util.concurrent.atomic.AtomicBoolean
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

    private val _appPreferences = MutableStateFlow(HavenAppPreferences())
    override val appPreferences: StateFlow<HavenAppPreferences> = _appPreferences.asStateFlow()

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
            refreshFromRooms()
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

    private val refreshing = AtomicBoolean(false)
    @Volatile private var lastRefresh = 0L

    override fun refresh() {
        val now = System.currentTimeMillis()
        if (now - lastRefresh < REFRESH_INTERVAL_MS || !refreshing.compareAndSet(false, true)) return
        lastRefresh = now
        sessionScope.launch {
            try {
                if (readAccountData()) save()
                refreshFromRooms()
                cacheIcons()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /**
     * Like Haven Desktop's refreshWidgetAppEntry: brings each widget app's entry in step with the widget as its room has
     * it now (a new icon, name or URL), so the bar shows its current look and "Add it back" restores its latest version.
     *
     * A rewrite by another account only carries over when it's the same account that set the widget before, on the same
     * site (Desktop's widgetAppConsentCarriesOver); otherwise the entry keeps its last version. The room name is left as
     * it is: Desktop computes it differently, and the two would keep overwriting each other's.
     */
    private suspend fun refreshFromRooms() {
        val me = matrixClient.sessionId.value
        val updates = mutableMapOf<Pair<String, String>, JSONObject>()
        widgetEntries.groupBy { it.getString("room_id") }.forEach { (roomId, entries) ->
            val room = runCatching { RoomId(roomId) }.getOrNull()?.let { matrixClient.getRoom(it) } ?: return@forEach
            // Widgets aren't in what sliding sync stores for a room, so its state comes from the server
            val state = room.use { it.fetchFullRoomState().getOrNull() } ?: return@forEach
            val events = state.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            entries.forEach { entry ->
                val current = currentEntry(events, entry) ?: return@forEach
                if (jsonEquals(entry, current)) return@forEach
                val sender = current.optString("sender")
                val origin = originOf(entry.optJSONObject("content")?.optStringOrNull("url"))
                val carriesOver = origin != null && sender == entry.optString("sender") &&
                    originOf(current.optJSONObject("content")?.optStringOrNull("url")) == origin
                if (sender != me && !carriesOver) return@forEach
                updates[roomId to entry.getString("widget_id")] = current
            }
        }
        if (updates.isEmpty()) return
        writeMutex.withLock {
            // Re-read, so changes made on another device meanwhile aren't lost
            val latest = runCatching { readWidgetEntries() }.getOrElse { return@withLock }
            val updated = latest.map { e -> updates[e.getString("room_id") to e.getString("widget_id")] ?: e }
            if (updated.zip(latest).all { (a, b) -> a === b }) return@withLock
            matrixClient.setAccountData(HavenAccountData.WIDGET_APPS, JSONObject().put("widgets", JSONArray(updated)).toString())
                .onSuccess {
                    setWidgetEntries(updated)
                    save()
                }
                .onFailure { Timber.w(it, "Couldn't update the widget apps") }
        }
    }

    /** [entry] as its room's widget is now ([events], the room's state), or null when the room no longer has it. */
    private fun currentEntry(events: List<JSONObject>, entry: JSONObject): JSONObject? {
        val widgetId = entry.getString("widget_id")
        fun widget(type: String) = events.firstOrNull { it.optString("type") == type && it.optString("state_key") == widgetId }
        val legacy = widget(WIDGET_LEGACY) ?: return null
        val content = legacy.optJSONObject("content")?.takeIf { it.optStringOrNull("url") != null } ?: return null
        val stateTypes = JSONArray().put(WIDGET_LEGACY)
        if (widget(WIDGET_STABLE)?.optJSONObject("content")?.optStringOrNull("url") != null) stateTypes.put(WIDGET_STABLE)
        return JSONObject()
            .put("room_id", entry.getString("room_id"))
            .put("widget_id", widgetId)
            .apply { entry.optStringOrNull("room_name")?.let { put("room_name", it) } }
            .put("state_types", stateTypes)
            .put("content", content)
            .put("sender", legacy.optString("sender"))
    }

    private fun originOf(url: String?): String? = url?.let { runCatching { java.net.URI(it) }.getOrNull() }
        ?.takeIf { it.scheme != null && it.host != null }
        ?.let { "${it.scheme}://${it.host}${if (it.port != -1) ":${it.port}" else ""}" }

    /** Whether two JSON values are the same, whatever order their keys are in. */
    private fun jsonEquals(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && jsonEquals(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { jsonEquals(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    /** Returns whether both lists could be read (from the session's store, or else from the server). */
    private suspend fun readAccountData(): Boolean {
        val pinned = readContentOrError(HavenAccountData.PINNED_APPS)
            .onSuccess { content -> content?.let { _pinnedAppIds.value = it.optJSONArray("pinned").toStringList() } }
        val widgets = readContentOrError(HavenAccountData.WIDGET_APPS)
            .onSuccess { content -> content?.let { setWidgetEntries(validEntries(it)) } }
        readContentOrError(HavenAccountData.APP_PREFERENCES)
            .onSuccess { content -> _appPreferences.value = preferencesFrom(content) }
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
            _appPreferences.value = preferencesFrom(saved.optJSONObject("preferences"))
        }.onFailure { Timber.w(it, "Couldn't read the saved apps") }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject()
                .put("pinned", JSONArray(_pinnedAppIds.value))
                .put("widgets", JSONArray(widgetEntries))
                .put(
                    "preferences",
                    JSONObject().put("disabled", _appPreferences.value.disabled).put("hidden", JSONArray(_appPreferences.value.hidden)),
                )
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

    override suspend fun setAppsDisabled(disabled: Boolean): Result<Unit> = writePreferences { it.put("disabled", disabled) }

    override suspend fun setAppHidden(appId: String, hidden: Boolean): Result<Unit> = writePreferences { content ->
        val others = content.optJSONArray("hidden").toStringList().filter { it != appId }
        content.put("hidden", JSONArray(if (hidden) others + appId else others))
    }

    /**
     * Like Haven Desktop: changes the latest preferences (re-read, so changes made on another device aren't lost), keeping
     * any fields a newer version added. When they can't be read, nothing is saved rather than saving over them.
     */
    private suspend fun writePreferences(change: (JSONObject) -> Unit): Result<Unit> = writeMutex.withLock {
        val content = readContentOrError(HavenAccountData.APP_PREFERENCES).getOrElse { return@withLock Result.failure(it) } ?: JSONObject()
        change(content)
        matrixClient.setAccountData(HavenAccountData.APP_PREFERENCES, content.toString())
            .onSuccess {
                _appPreferences.value = preferencesFrom(content)
                save()
            }
    }

    private fun preferencesFrom(content: JSONObject?) = HavenAppPreferences(
        disabled = content?.optBoolean("disabled") == true,
        hidden = content?.optJSONArray("hidden").toStringList(),
    )

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
        /** The least time between two [refresh]es. */
        const val REFRESH_INTERVAL_MS = 60_000L

        /** Px; the same size the icons were already asked for, at most 64dp-ish on the bars. */
        const val ICON_SIZE = 128L

        fun sha(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)

        const val WIDGET_LEGACY = "im.vector.modular.widgets"
        const val WIDGET_STABLE = "m.widget"
    }
}
