/*
 * Copyright 2026 Haven contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.api.roomlist

import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Haven: waits until the room list has loaded (straight away from the local cache, or after the first sync on a fresh
 * one), so background work that hits the server doesn't compete with that first sync. Gives up waiting after [timeout].
 */
suspend fun MatrixClient.awaitRoomListLoaded(timeout: Duration = 2.minutes) {
    withTimeoutOrNull(timeout) {
        roomListService.allRooms.loadingState.first { it is RoomList.LoadingState.Loaded }
    }
}
