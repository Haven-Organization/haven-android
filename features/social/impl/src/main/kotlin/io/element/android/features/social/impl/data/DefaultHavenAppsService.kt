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
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.havenStateEventRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pinned apps and widget apps, in account data shared with Haven Desktop:
 * - `software.haven.pinned_apps`: `{"pinned": [...]}`
 * - `software.haven.widget_apps`: `{"widgets": [{room_id, widget_id, room_name, state_types, content, sender}]}`
 *
 * A widget app links to the room's live widget (`im.vector.modular.widgets` state), it isn't a copy.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultHavenAppsService(
    private val matrixClient: MatrixClient,
    @SessionCoroutineScope sessionScope: CoroutineScope,
    socialRepository: SocialRepository,
    socialTimelineStore: SocialTimelineStore,
) : HavenAppsService {
    private val writeMutex = Mutex()
    private val _pinnedAppIds = MutableStateFlow<List<String>>(emptyList())
    override val pinnedAppIds: StateFlow<List<String>> = _pinnedAppIds.asStateFlow()

    private val _widgetApps = MutableStateFlow<List<HavenAppItem.Widget>>(emptyList())
    override val widgetApps: StateFlow<List<HavenAppItem.Widget>> = _widgetApps.asStateFlow()

    init {
        sessionScope.launch {
            _pinnedAppIds.value = readPinned()
            _widgetApps.value = readWidgetEntries().map { it.toItem() }
        }
        // Created with the home screen: find and load the Social rooms ahead of time, so the Feed, Groups and Profile
        // are ready when the Social app is opened.
        sessionScope.launch {
            socialRepository.refreshSocialRooms()
            val semaphore = Semaphore(4)
            socialRepository.feedRooms.value.map { room ->
                async {
                    semaphore.withPermit {
                        if (socialTimelineStore.joinedRoom(room.roomId) != null) socialTimelineStore.paginate(room.roomId)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun readContent(type: String): JSONObject? {
        val json = matrixClient.getAccountData(type).getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        // Accept both the bare content and a whole event.
        return if (json.has("content") && json.optJSONObject("content") != null && !json.has("pinned") && !json.has("widgets")) {
            json.optJSONObject("content")
        } else {
            json
        }
    }

    private suspend fun readPinned(): List<String> = readContent(HavenAccountData.PINNED_APPS)?.optJSONArray("pinned").toStringList()

    /** Valid entries only, like Haven Desktop's `isEntry`. */
    private suspend fun readWidgetEntries(): List<JSONObject> {
        val array = readContent(HavenAccountData.WIDGET_APPS)?.optJSONArray("widgets") ?: return emptyList()
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

    private fun JSONObject.toItem(): HavenAppItem.Widget {
        val content = getJSONObject("content")
        val name = content.optStringOrNull("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: content.optJSONObject("data")?.optStringOrNull("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Widget"
        return HavenAppItem.Widget(
            roomId = getString("room_id"),
            widgetId = getString("widget_id"),
            name = name,
            avatarUrl = content.optStringOrNull("avatar_url")?.takeIf { it.startsWith("mxc://") },
            roomName = optStringOrNull("room_name"),
        )
    }

    override suspend fun setPinned(appId: String, pinned: Boolean): Result<Unit> = writeMutex.withLock {
        // Like Haven Desktop: re-read so pins made on another device aren't lost, then remove and (when pinning) append.
        val current = readPinned().filter { it != appId }
        val updated = if (pinned) current + appId else current
        matrixClient.setAccountData(HavenAccountData.PINNED_APPS, JSONObject().put("pinned", JSONArray(updated)).toString())
            .onSuccess { _pinnedAppIds.value = updated }
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
            _widgetApps.value = updated.map { it.toItem() }
        }
        setPinned(HavenAppItem.widgetAppId(roomId, widgetId), true).getOrThrow()
    }

    override suspend fun removeWidgetApp(appId: String): Result<Unit> = runCatching {
        val (roomId, widgetId) = HavenAppItem.parseWidgetAppId(appId) ?: error("Not a widget app")
        writeMutex.withLock {
            val updated = readWidgetEntries().filter { e -> !(e.getString("room_id") == roomId && e.getString("widget_id") == widgetId) }
            matrixClient.setAccountData(HavenAccountData.WIDGET_APPS, JSONObject().put("widgets", JSONArray(updated)).toString()).getOrThrow()
            _widgetApps.value = updated.map { it.toItem() }
        }
        setPinned(appId, false).getOrThrow()
    }

    private companion object {
        const val WIDGET_LEGACY = "im.vector.modular.widgets"
        const val WIDGET_STABLE = "m.widget"
    }
}
