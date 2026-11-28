/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.api.SocialSettings
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import timber.log.Timber

/**
 * Social's settings, where Haven Desktop keeps them: its account-level settings (Element Web's `im.vector.web.settings`
 * account data), so they're the same on every device.
 */
@SingleIn(SessionScope::class)
@ContributesBinding(SessionScope::class)
class DefaultSocialSettings(
    private val matrixClient: MatrixClient,
    private val httpApi: SocialHttpApi,
    @SessionCoroutineScope sessionScope: CoroutineScope,
) : SocialSettings {
    private val writeMutex = Mutex()
    private val _crossPostReplies = MutableStateFlow(true)
    override val crossPostReplies: StateFlow<Boolean> = _crossPostReplies.asStateFlow()

    init {
        sessionScope.launch {
            read().onSuccess { settings -> _crossPostReplies.value = settings?.crossPostReplies() ?: true }
        }
    }

    override suspend fun setCrossPostReplies(enabled: Boolean): Result<Unit> = writeMutex.withLock {
        // The latest settings, keeping everything else in them (Haven Desktop's other settings)
        val settings = read().getOrElse { return@withLock Result.failure(it) } ?: JSONObject()
        settings.put(CROSS_POST_REPLIES, enabled)
        matrixClient.setAccountData(WEB_SETTINGS, settings.toString())
            .onSuccess { _crossPostReplies.value = enabled }
            .onFailure { Timber.w(it, "Couldn't save Social's settings") }
    }

    private fun JSONObject.crossPostReplies(): Boolean = if (has(CROSS_POST_REPLIES)) optBoolean(CROSS_POST_REPLIES, true) else true

    /** The settings: from the session's store, or else from the server; null when there are none yet. */
    private suspend fun read(): Result<JSONObject?> {
        matrixClient.getAccountData(WEB_SETTINGS).getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.let { return Result.success(unwrap(it)) }
        return httpApi.getAccountData(WEB_SETTINGS).map { it?.let(::unwrap) }
    }

    // The content, whether given as is or as a whole event
    private fun unwrap(json: JSONObject): JSONObject =
        if (json.optJSONObject("content") != null && !json.has(CROSS_POST_REPLIES) && json.has("type")) json.getJSONObject("content") else json

    private companion object {
        const val WEB_SETTINGS = "im.vector.web.settings"
        const val CROSS_POST_REPLIES = "Social.crossPostReplies"
    }
}
