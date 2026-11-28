/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.ui.formattedname

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import chat.schildi.lib.preferences.ScPreferencesStore
import chat.schildi.lib.preferences.ScPrefs
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * A name in one place (a room's member event, a user's global profile, or a room's m.room.name): the plain name the
 * formatted one is checked against (MSC4589, MSC4591).
 */
data class NameSource(
    val displayName: String?,
    /** The raw formatted name object, if any. */
    val formatted: JSONObject?,
    /** The membership, for a member event; null otherwise. */
    val membership: String? = null,
)

/**
 * Haven: where formatted names come from, like Haven Desktop:
 * - display names (MSC4589): a room member's current member event (only while they're joined), or a user's global
 *   profile;
 * - room names (MSC4591): the room's m.room.name, in rooms the user is in (not invites).
 *
 * Looked up on first use and kept for the session; [updates] changes whenever something new has come in. Nothing is
 * looked up while formatted names are turned off.
 */
@SingleIn(SessionScope::class)
@Inject
class FormattedDisplayNames(
    private val matrixClient: MatrixClient,
    private val scPreferencesStore: ScPreferencesStore,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
    okHttpClient: OkHttpClient,
) {
    // Its own client without the app's logging interceptor, so the access token can't end up in logs
    private val httpClient = okHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val members = ConcurrentHashMap<Pair<RoomId, UserId>, NameSource>()
    private val profiles = ConcurrentHashMap<UserId, NameSource>()

    /** Each room's m.room.name, as a [NameSource]. */
    private val roomNames = ConcurrentHashMap<RoomId, NameSource>()
    private val requested = ConcurrentHashMap.newKeySet<Any>()
    private val lookups = Semaphore(MAX_LOOKUPS)

    private val _updates = MutableStateFlow(0)

    /** Changes whenever a member event or profile has come in. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    private val enabled get() = scPreferencesStore.getCachedOrDefaultValue(ScPrefs.HAVEN_FORMATTED_DISPLAY_NAMES)

    /**
     * The formatted name of [userId] in [roomId], when it's shown as [displayName]: from their current member event,
     * only while they're joined and that event's display name is [displayName].
     */
    fun member(roomId: RoomId, userId: UserId, displayName: String?): FormattedName? {
        if (!enabled || displayName.isNullOrEmpty() || displayName == userId.value) return null
        val key = roomId to userId
        val source = members[key]
        // Looked up again when the name differs: it may have changed since
        if (source == null || source.displayName != displayName) lookUpMember(roomId, userId, displayName)
        source ?: return null
        if (source.membership != "join" || source.displayName != displayName) return null
        return parseFormattedDisplayName(source.formatted, displayName)
    }

    /** The formatted name in [userId]'s global profile, when it's shown as [displayName] and that's their profile's. */
    fun profile(userId: UserId, displayName: String?): FormattedName? {
        if (!enabled || displayName.isNullOrEmpty() || displayName == userId.value) return null
        val source = profiles[userId]
        if (source == null || source.displayName != displayName) lookUpProfile(userId, displayName)
        source ?: return null
        if (source.displayName != displayName) return null
        return parseFormattedDisplayName(source.formatted, displayName)
    }

    /**
     * [member] when the room has a member event for [userId] (joined or not), else [profile]: someone who isn't in the
     * room, e.g. a user mentioned there or opened from elsewhere. Outside a room ([roomId] null), [profile].
     */
    fun memberElseProfile(roomId: RoomId?, userId: UserId, displayName: String?): FormattedName? {
        if (roomId == null) return profile(userId, displayName)
        val formatted = member(roomId, userId, displayName)
        val source = members[roomId to userId]
        return if (formatted == null && source != null && source.membership == NOT_IN_ROOM) profile(userId, displayName) else formatted
    }

    /**
     * [member], else [profile] when that has the same plain name (like Haven Desktop's Social posts): for an author whose
     * member event has no formatted name, or who isn't in the room at all. Not for anyone in the room who isn't
     * currently joined (invited, left, banned).
     */
    fun memberThenProfile(roomId: RoomId, userId: UserId, displayName: String?): FormattedName? {
        member(roomId, userId, displayName)?.let { return it }
        // Not looked up yet: wait for it, rather than showing the profile's and then maybe nothing
        val source = members[roomId to userId] ?: return null
        if (source.membership != NOT_IN_ROOM && source.membership != "join") return null
        return profile(userId, displayName)
    }

    /**
     * The formatted room name (MSC4591) of [roomId] when it's shown as [roomName]: from its m.room.name, when that's the
     * name shown (not one worked out from the members) and the user is in the room (an invite shows the plain name).
     */
    fun roomName(roomId: RoomId, roomName: String?): FormattedName? {
        if (!enabled || roomName.isNullOrEmpty()) return null
        val source = roomNames[roomId]
        // Looked up again when the name differs: it may have changed since
        if (source == null || source.displayName != roomName) lookUpRoomName(roomId, roomName)
        source ?: return null
        if (source.displayName != roomName) return null
        return parseFormattedDisplayName(source.formatted, roomName)
    }

    private fun lookUpMember(roomId: RoomId, userId: UserId, displayName: String) {
        if (!requested.add(Triple(roomId, userId, displayName))) return
        sessionScope.launch {
            // Only the local store: what the room already knows about the member
            val json = matrixClient.getRoom(roomId)?.use { room -> room.getRawState(MEMBER_EVENT, userId.value).getOrNull() }
            val content = json?.let { runCatching { JSONObject(it) }.getOrNull() }?.optJSONObject("content")
            members[roomId to userId] = if (content == null) NameSource(null, null, NOT_IN_ROOM) else NameSource(
                displayName = content.optString("displayname").takeIf { content.has("displayname") },
                formatted = rawFormattedDisplayName(content),
                membership = content.optString("membership"),
            )
            _updates.update { it + 1 }
        }
    }

    private fun lookUpRoomName(roomId: RoomId, roomName: String) {
        if (!requested.add(Triple(ROOM_NAME_EVENT, roomId, roomName))) return
        sessionScope.launch {
            // Only the local store, and only rooms the user is in
            val content = matrixClient.getRoom(roomId)?.use { room ->
                if (room.info().currentUserMembership != CurrentUserMembership.JOINED) return@use null
                room.getRawState(ROOM_NAME_EVENT, "").getOrNull()
            }?.let { json -> runCatching { JSONObject(json) }.getOrNull() }?.optJSONObject("content")
            roomNames[roomId] = NameSource(
                displayName = content?.optString("name")?.takeIf { content.has("name") },
                formatted = rawFormattedRoomName(content),
            )
            _updates.update { it + 1 }
        }
    }

    private fun lookUpProfile(userId: UserId, displayName: String?) {
        if (!requested.add(userId to displayName)) return
        sessionScope.launch {
            val profile = lookups.withPermit { fetchProfile(userId) }
                .onFailure { Timber.w(it, "Couldn't look up the profile of %s for its formatted display name", userId) }
                .getOrNull() ?: return@launch
            profiles[userId] = NameSource(
                displayName = profile.optString("displayname").takeIf { profile.has("displayname") },
                formatted = rawFormattedDisplayName(profile),
            )
            _updates.update { it + 1 }
        }
    }

    private suspend fun fetchProfile(userId: UserId): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val credentials = matrixClient.havenRawCredentials().getOrThrow()
            val request = Request.Builder()
                .url(credentials.homeserverUrl.trimEnd('/') + "/_matrix/client/v3/profile/" + Uri.encode(userId.value))
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                JSONObject(response.body.string())
            }
        }
    }

    private companion object {
        const val MEMBER_EVENT = "m.room.member"
        const val ROOM_NAME_EVENT = "m.room.name"

        /** The [NameSource.membership] of someone the room has no member event for. */
        const val NOT_IN_ROOM = "haven.none"
        const val MAX_LOOKUPS = 4
    }
}

/** The session's [FormattedDisplayNames]; null outside a session (and in previews), where names stay plain. */
val LocalFormattedDisplayNames = staticCompositionLocalOf<FormattedDisplayNames?> { null }

/** The room whose member events names are looked up in (see [rememberMemberFormattedName]); null outside a room. */
val LocalFormattedNamesRoomId = staticCompositionLocalOf<RoomId?> { null }

/**
 * The formatted name of [userId] in the current room ([LocalFormattedNamesRoomId]), when they're shown as
 * [displayName]; null when there's none, it doesn't match, or formatted display names are turned off.
 */
@Composable
fun rememberMemberFormattedName(userId: UserId, displayName: String?): FormattedName? {
    val roomId = LocalFormattedNamesRoomId.current ?: return null
    val names = LocalFormattedDisplayNames.current ?: return null
    if (!formattedDisplayNamesEnabled()) return null
    val updates by names.updates.collectAsState()
    return remember(roomId, userId, displayName, updates) { names.member(roomId, userId, displayName) }
}

/**
 * The formatted name of [userId] shown as [displayName]: from the current room's member event when the room has one
 * (see [rememberMemberFormattedName]), else from their global profile, e.g. for a user mentioned in or opened from a room
 * they aren't in, or outside any room.
 */
@Composable
fun rememberUserFormattedName(userId: UserId, displayName: String?): FormattedName? {
    val names = LocalFormattedDisplayNames.current ?: return null
    if (!formattedDisplayNamesEnabled()) return null
    val roomId = LocalFormattedNamesRoomId.current
    val updates by names.updates.collectAsState()
    return remember(roomId, userId, displayName, updates) { names.memberElseProfile(roomId, userId, displayName) }
}

/** [FormattedDisplayNames.memberThenProfile] of [userId] in [roomId], shown as [displayName]. */
@Composable
fun rememberMemberThenProfileFormattedName(roomId: RoomId, userId: UserId, displayName: String?): FormattedName? {
    val names = LocalFormattedDisplayNames.current ?: return null
    if (!formattedDisplayNamesEnabled()) return null
    val updates by names.updates.collectAsState()
    return remember(roomId, userId, displayName, updates) { names.memberThenProfile(roomId, userId, displayName) }
}

/**
 * The formatted room name (MSC4591) to show in place of [roomName] for [roomId]; null when there's none, it doesn't
 * match, the room is an invite, or formatted names are turned off.
 */
@Composable
fun rememberRoomFormattedName(roomId: RoomId, roomName: String?): FormattedName? {
    val names = LocalFormattedDisplayNames.current ?: return null
    if (!formattedDisplayNamesEnabled()) return null
    val updates by names.updates.collectAsState()
    return remember(roomId, roomName, updates) { names.roomName(roomId, roomName) }
}

/** The formatted name in [userId]'s global profile, when they're shown as [displayName] (see [rememberMemberFormattedName]). */
@Composable
fun rememberProfileFormattedName(userId: UserId, displayName: String?): FormattedName? {
    val names = LocalFormattedDisplayNames.current ?: return null
    if (!formattedDisplayNamesEnabled()) return null
    val updates by names.updates.collectAsState()
    return remember(userId, displayName, updates) { names.profile(userId, displayName) }
}
