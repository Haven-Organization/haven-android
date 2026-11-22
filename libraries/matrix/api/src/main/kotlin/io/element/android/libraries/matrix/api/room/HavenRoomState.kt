/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.api.room

import org.json.JSONObject

/*
 * Haven: sliding sync only syncs the state events Element X asks for (its `required_state`), so the local store has no
 * widgets (`im.vector.modular.widgets`) or other custom state. These read the local state first, then fall back to
 * fetching the room's full state from the server.
 */

/** A state event's raw JSON (the whole event), or null if the room doesn't have it. */
suspend fun BaseRoom.havenStateEventRaw(eventType: String, stateKey: String): String? {
    getRawState(eventType, stateKey).getOrNull()?.let { return it }
    return fetchFullRoomState().getOrNull()?.firstOrNull { json ->
        val event = runCatching { JSONObject(json) }.getOrNull() ?: return@firstOrNull false
        event.optString("type") == eventType && event.optString("state_key") == stateKey
    }
}

/** All state events of [eventType] (raw JSON of whole events). */
suspend fun BaseRoom.havenStateEventsRaw(eventType: String): List<String> {
    getRawState(eventType).getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    return fetchFullRoomState().getOrNull().orEmpty().filter { json ->
        runCatching { JSONObject(json).optString("type") }.getOrNull() == eventType
    }
}
