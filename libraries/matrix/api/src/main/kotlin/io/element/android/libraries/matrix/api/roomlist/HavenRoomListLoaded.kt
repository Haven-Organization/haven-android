/*
 * Copyright 2026 Haven contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.roomlist

import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Haven: waits until the whole room list has synced (straight away from the local cache, or once every room has come
 * in on a fresh one) and the sync indicator is gone, so background work that hits the server doesn't compete with the
 * first sync. Gives up waiting after [timeout].
 *
 * The room list counts as "loaded" as soon as its first batch of rooms is in, while the rest are still being fetched in
 * further batches (seen 2026-11-26: on ~330 rooms, "loaded" came after the first ~1 MB response, with several MB still
 * to come), so that alone is too early.
 */
suspend fun MatrixClient.awaitRoomListLoaded(timeout: Duration = 5.minutes) {
    withTimeoutOrNull(timeout) {
        val allRooms = roomListService.allRooms
        combine(allRooms.loadingState, allRooms.summaries) { state, summaries ->
            state is RoomList.LoadingState.Loaded && summaries.size >= state.numberOfRooms
        }.first { it }
        roomListService.syncIndicator.first { it == RoomListService.SyncIndicator.Hide }
    }
}
