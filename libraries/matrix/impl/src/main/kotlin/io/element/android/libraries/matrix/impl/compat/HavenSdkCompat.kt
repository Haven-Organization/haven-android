/*
 * Copyright (c) 2026 Haven contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.compat

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.RoomInterface
import org.matrix.rustcomponents.sdk.RoomMembersIterator
import kotlin.time.Duration

/*
 * Haven: SDK compat.
 *
 * Element X v26.11.0 targets org.matrix.rustcomponents 26.11.05, but Haven uses the SchildiChat
 * rustcomponents fork (chat.schildi.rustcomponents 0.4.56, based on 26.10.x) because most SchildiChat
 * features depend on it. These shims stand in for the newer upstream APIs the fork does not have yet.
 * Delete each one once the fork provides the real API (the member function will then take precedence
 * and the compiler will flag the extension as shadowed).
 */

/**
 * Upstream: `Room.membersFetchingGlobalProfiles()`. The fork has no global-profile fetching,
 * so fall back to the plain member list.
 */
internal suspend fun RoomInterface.membersFetchingGlobalProfiles(): RoomMembersIterator = members()

/**
 * Upstream: `Client.observeTotalUnreadNotifications(listener, minInterval)`. The fork only exposes
 * the synchronous `totalUnreadNotifications()`, so poll it while the flow is collected.
 */
internal fun Client.totalUnreadNotificationsFlow(pollInterval: Duration): Flow<Long> = flow {
    while (true) {
        emit(totalUnreadNotifications().toLong())
        delay(pollInterval)
    }
}.distinctUntilChanged()
