/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.tasks

import android.content.Context
import android.content.Intent
import coil3.SingletonImageLoader
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.invite.api.SeenInvitesStore
import io.element.android.features.preferences.impl.DefaultCacheService
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.push.api.PushService
import io.element.android.services.appnavstate.api.ActiveRoomsHolder
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

interface ClearCacheUseCase {
    suspend operator fun invoke()
}

@ContributesBinding(SessionScope::class)
class DefaultClearCacheUseCase(
    @ApplicationContext private val context: Context,
    private val matrixClient: MatrixClient,
    private val coroutineDispatchers: CoroutineDispatchers,
    private val defaultCacheService: DefaultCacheService,
    private val okHttpClient: () -> OkHttpClient,
    private val pushService: PushService,
    private val seenInvitesStore: SeenInvitesStore,
    private val activeRoomsHolder: ActiveRoomsHolder,
) : ClearCacheUseCase {
    override suspend fun invoke() = withContext(coroutineDispatchers.io) {
        // Active rooms should be disposed of before clearing the cache
        activeRoomsHolder.clear(matrixClient.sessionId)
        // Clear Matrix cache
        matrixClient.clearCache()
        // Clear Coil cache
        SingletonImageLoader.get(context).let {
            it.diskCache?.clear()
            it.memoryCache?.clear()
        }
        // Clear OkHttp cache
        okHttpClient().cache?.delete()
        // Clear app cache
        context.cacheDir?.listFiles {
            // But keep the logs
            it.name != "logs"
        }?.onEach {
            it.deleteRecursively()
        }
        // Clear some settings
        seenInvitesStore.clear()
        // Ensure any error will be displayed again
        pushService.setIgnoreRegistrationError(matrixClient.sessionId, false)
        pushService.resetBatteryOptimizationState()
        // Haven: restart the whole app process rather than only the session inside it. After the in-process restart
        // the home room list could stay on its loading placeholders indefinitely, even though the first sync had
        // finished, until the app was closed and opened again (seen 2026-11-26, ~330 rooms).
        if (restartProcess()) return@withContext
        // Ensure the app is restarted
        defaultCacheService.onClearedCache(matrixClient.sessionId)
    }

    /** Haven: relaunches the app in a new task and ends this process. False if there's nothing to launch. */
    private fun restartProcess(): Boolean {
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return false
        context.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
        return true
    }
}
