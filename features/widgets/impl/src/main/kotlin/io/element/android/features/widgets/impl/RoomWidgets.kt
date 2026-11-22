/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.widgets.impl

import dev.zacsweers.metro.Inject
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.havenStateEventRaw
import io.element.android.libraries.matrix.api.room.havenStateEventsRaw
import org.json.JSONObject

/** A room widget from `im.vector.modular.widgets` state (what Element and Haven Desktop read). */
data class RoomWidget(
    val roomId: RoomId,
    val widgetId: String,
    val eventId: String?,
    val sender: String,
    val name: String,
    val type: String,
    val url: String,
    val avatarUrl: String?,
    val data: JSONObject,
) {
    /** matrix-widget-api: wait for the iframe load (rather than `content_loaded`) unless `waitForIframeLoad` is false. */
    val waitForIframeLoad: Boolean get() = data.optBoolean("waitForIframeLoad", true)
}

@Inject
class RoomWidgets(
    private val matrixClient: MatrixClient,
) {
    /** Widgets with both a `type` and a `url`, like Element's WidgetStore. */
    suspend fun list(roomId: RoomId): List<RoomWidget> {
        val room = matrixClient.getRoom(roomId) ?: return emptyList()
        val events = room.use { it.havenStateEventsRaw(WIDGET_EVENT_TYPE) }
        return events.mapNotNull { json -> parse(roomId, json) }.sortedBy { it.name.lowercase() }
    }

    suspend fun get(roomId: RoomId, widgetId: String): RoomWidget? {
        val room = matrixClient.getRoom(roomId) ?: return null
        val json = room.use { it.havenStateEventRaw(WIDGET_EVENT_TYPE, widgetId) } ?: return null
        return parse(roomId, json)
    }

    private fun parse(roomId: RoomId, json: String): RoomWidget? {
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val content = event.optJSONObject("content") ?: return null
        val url = content.optString("url").takeIf { it.isNotEmpty() } ?: return null
        val type = content.optString("type").takeIf { it.isNotEmpty() } ?: return null
        val widgetId = event.optString("state_key").ifEmpty { content.optString("id") }.takeIf { it.isNotEmpty() } ?: return null
        val data = content.optJSONObject("data") ?: JSONObject()
        val name = content.optString("name").trim().ifEmpty { data.optString("title").trim() }.ifEmpty { "Widget" }
        return RoomWidget(
            roomId = roomId,
            widgetId = widgetId,
            eventId = event.optString("event_id").takeIf { it.isNotEmpty() },
            sender = event.optString("sender"),
            name = name,
            type = type,
            url = url,
            avatarUrl = content.optString("avatar_url").takeIf { it.startsWith("mxc://") },
            data = data,
        )
    }

    /**
     * Element's load consent: widgets you added yourself load without asking; others need a yes, remembered in the
     * `im.vector.setting.allowed_widgets` room account data (`{"<state event id>": true}`), shared with Element/Haven Desktop.
     */
    suspend fun isLoadAllowed(widget: RoomWidget): Boolean {
        if (widget.sender == matrixClient.sessionId.value) return true
        val eventId = widget.eventId ?: return false
        val json = matrixClient.getRoomAccountData(widget.roomId, ALLOWED_WIDGETS) ?: return false
        val content = runCatching { JSONObject(json) }.getOrNull() ?: return false
        return (content.optJSONObject("content") ?: content).optBoolean(eventId, false)
    }

    suspend fun allowLoad(widget: RoomWidget): Result<Unit> {
        val eventId = widget.eventId ?: return Result.success(Unit)
        val existing = matrixClient.getRoomAccountData(widget.roomId, ALLOWED_WIDGETS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { it.optJSONObject("content") ?: it }
            ?: JSONObject()
        existing.put(eventId, true)
        return matrixClient.setRoomAccountData(widget.roomId, ALLOWED_WIDGETS, existing.toString())
    }

    companion object {
        const val WIDGET_EVENT_TYPE = "im.vector.modular.widgets"
        private const val ALLOWED_WIDGETS = "im.vector.setting.allowed_widgets"
    }
}
