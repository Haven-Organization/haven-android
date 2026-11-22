/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.social.impl.msc.HavenAccountData
import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.features.social.impl.msc.optStringOrNull
import io.element.android.features.social.impl.msc.toStringList
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.RoomInfo
import io.element.android.libraries.matrix.api.room.havenStateEventRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

enum class SocialRoomKind { Profile, Group }

/** A joined room with its `m.room.create` type (null for an ordinary room). */
data class JoinedRoomEntry(
    val roomId: RoomId,
    val type: String?,
    val info: RoomInfo,
)

data class SocialRoom(
    val roomId: RoomId,
    val kind: SocialRoomKind,
    val info: RoomInfo,
)

/** Where a user's MSC4501 profile room is, from their `org.matrix.msc4501.social.profile_room` profile field. */
data class ProfileRoomLink(val roomId: RoomId, val via: List<String>)

sealed interface OwnProfileState {
    data object Loading : OwnProfileState
    data object None : OwnProfileState
    data class Linked(val link: ProfileRoomLink) : OwnProfileState
    data class Error(val error: Throwable) : OwnProfileState
}

/** Haven Desktop's feed filter, `software.haven.social.feed_filter` account data. */
data class FeedFilter(
    val extraRoomTypes: List<String> = emptyList(),
    val includedRoomIds: List<String> = emptyList(),
    val excludedRoomIds: List<String> = emptyList(),
    val excludedUserIds: List<String> = emptyList(),
)

enum class SocialRoomVisibility { Private, AskToJoin, Public }

data class CreateSocialRoomParams(
    val kind: SocialRoomKind,
    val name: String,
    val topic: String,
    val visibility: SocialRoomVisibility,
    /** Ask to join only: publish to the room directory. */
    val publishToDirectory: Boolean,
    /** When false, only power level 10+ users can post. */
    val allowAnyoneToPost: Boolean,
    /** Not available for public rooms. */
    val encrypted: Boolean,
)

/** Haven Desktop's feed filter validation. */
object FeedFilterValidation {
    private val roomType = Regex("^\\S+$")
    private val room = Regex("^[!#][^\\s:]+:\\S+$")
    private val user = Regex("^@[^\\s:]+:\\S+$")
    fun isRoomType(value: String) = roomType.matches(value)
    fun isRoom(value: String) = room.matches(value)
    fun isUser(value: String) = user.matches(value)
}

@SingleIn(SessionScope::class)
@Inject
class SocialRepository(
    private val matrixClient: MatrixClient,
    private val httpApi: SocialHttpApi,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
    @ApplicationContext context: Context,
) {
    // A room's type never changes, so it's cached on disk ("" = not a typed room) to avoid re-checking every launch.
    private val typePrefs = context.getSharedPreferences(
        "haven_social_room_types_" + matrixClient.sessionId.value.hashCode().toUInt().toString(16),
        Context.MODE_PRIVATE,
    )
    private val refreshMutex = Mutex()
    private val roomTypeCache = mutableMapOf<RoomId, String?>()
    private val roomTypeMutex = Mutex()

    private val _ownProfile = MutableStateFlow<OwnProfileState>(OwnProfileState.Loading)
    val ownProfile: StateFlow<OwnProfileState> = _ownProfile.asStateFlow()


    private val _feedFilter = MutableStateFlow(FeedFilter())
    val feedFilter: StateFlow<FeedFilter> = _feedFilter.asStateFlow()

    private val _hasScannedRooms = MutableStateFlow(false)

    /** False until the first scan of the joined rooms has finished. */
    val hasScannedRooms: StateFlow<Boolean> = _hasScannedRooms.asStateFlow()

    private val _socialRooms = MutableStateFlow<List<SocialRoom>>(emptyList())

    /** Joined MSC4501 profile and group rooms. Call [refreshSocialRooms] to update. */
    val socialRooms: StateFlow<List<SocialRoom>> = _socialRooms.asStateFlow()

    /** Every joined room with its create type, for the feed filter (which can add non-social rooms). */
    private val joinedRooms = MutableStateFlow<List<JoinedRoomEntry>>(emptyList())

    /** Rooms whose posts make up the Feed, after Haven Desktop's feed filter. */
    val feedRooms: StateFlow<List<JoinedRoomEntry>> = combine(joinedRooms, _feedFilter) { rooms, filter ->
        rooms.filter { countsForFeed(it, filter) }
    }.stateIn(sessionScope, SharingStarted.Eagerly, emptyList())

    init {
        sessionScope.launch { refreshSocialRooms() }
        sessionScope.launch { refreshOwnProfile() }
        sessionScope.launch { refreshFeedFilter() }
    }

    /**
     * Rebuilds [socialRooms] and [feedRooms] from every joined room on the server. Sliding sync only loads rooms
     * gradually (as the room list scrolls), so the social ones are subscribed to here so the SDK syncs them in the
     * background, ahead of the Social app being opened.
     */
    suspend fun refreshSocialRooms() = refreshMutex.withLock {
        val joined = httpApi.joinedRooms().getOrNull()?.map { RoomId(it) }
            ?: matrixClient.getJoinedRoomIds().getOrNull()?.toList()
            ?: return@withLock
        // Resolve unknown types a few rooms at a time (cached, so normally only new rooms hit the server).
        val semaphore = Semaphore(6)
        val types = coroutineScope {
            joined.map { roomId -> async { semaphore.withPermit { roomId to roomType(roomId) } } }.awaitAll()
        }
        val filter = _feedFilter.value
        val wanted = types.filter { (roomId, type) ->
            Msc4501.isSocialRoomType(type) || roomId.value in filter.includedRoomIds || (type != null && type in filter.extraRoomTypes)
        }.map { it.first }
        // Make sure the SDK syncs them, even if they haven't shown up in the room list yet.
        val unknown = wanted.filter { matrixClient.getRoomInfo(it).getOrNull() == null }
        if (unknown.isNotEmpty()) matrixClient.roomListService.subscribeToVisibleRooms(unknown)
        Timber.tag(LOG_TAG).d("%d joined rooms, %d social/feed rooms, %d not synced yet", joined.size, wanted.size, unknown.size)

        // Publish what's known now, then again as subscribed rooms arrive.
        var attempt = 0
        while (true) {
            val entries = types.mapNotNull { (roomId, type) ->
                if (roomId !in wanted) return@mapNotNull null
                val info = matrixClient.getRoomInfo(roomId).getOrNull() ?: return@mapNotNull null
                JoinedRoomEntry(roomId, type, info)
            }
            joinedRooms.value = entries
            _socialRooms.value = entries.mapNotNull { entry ->
                when {
                    Msc4501.isProfileRoomType(entry.type) -> SocialRoom(entry.roomId, SocialRoomKind.Profile, entry.info)
                    Msc4501.isGroupRoomType(entry.type) -> SocialRoom(entry.roomId, SocialRoomKind.Group, entry.info)
                    else -> null
                }
            }
            if (entries.isNotEmpty()) _hasScannedRooms.value = true
            if (entries.size >= wanted.size || attempt++ >= 30) break
            delay(1000)
        }
        _hasScannedRooms.value = true
    }

    /**
     * The `type` from the room's `m.room.create` event: from local state, else from the server.
     * Cached in memory and on disk: a room's type never changes.
     */
    suspend fun roomType(roomId: RoomId): String? {
        roomTypeMutex.withLock { if (roomId in roomTypeCache) return roomTypeCache[roomId] }
        typePrefs.getString(roomId.value, null)?.let { stored ->
            val type = stored.ifEmpty { null }
            roomTypeMutex.withLock { roomTypeCache[roomId] = type }
            return type
        }
        // Straight from the server (once per room, then cached): getRoom() can wait indefinitely for Element X's
        // all-rooms list, which SchildiChat doesn't drive.
        val content = httpApi.getStateEventContent(roomId.value, "m.room.create", "")
            .onFailure { Timber.tag(LOG_TAG).w(it, "Couldn't fetch m.room.create of %s", roomId) }
            .getOrNull()
            ?: return null // Unknown for now: try again next time, don't cache.
        val type = content.optStringOrNull("type")
        roomTypeMutex.withLock { roomTypeCache[roomId] = type }
        typePrefs.edit().putString(roomId.value, type.orEmpty()).apply()
        return type
    }

    suspend fun roomKind(roomId: RoomId): SocialRoomKind? {
        val type = roomType(roomId)
        return when {
            Msc4501.isProfileRoomType(type) -> SocialRoomKind.Profile
            Msc4501.isGroupRoomType(type) -> SocialRoomKind.Group
            else -> null
        }
    }

    /**
     * The user a profile room belongs to: its `org.matrix.msc4501.social.profile_user_id` state, falling back to the room
     * creator (MSC4501 "Profile rooms"; same as Haven Desktop).
     */
    suspend fun profileOwner(roomId: RoomId): String? {
        val room = matrixClient.getRoom(roomId) ?: return null
        return room.use {
            val claimed = it.havenStateEventRaw(Msc4501.PROFILE_USER_ID, "")
                ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
                ?.let { event -> (event.optJSONObject("content") ?: event).optStringOrNull("user_id") }
                ?.takeIf { id -> USER_ID_REGEX.matches(id) }
            claimed ?: it.info().creators.firstOrNull()?.value
        }
    }

    /** The banner (MSC4221) mxc URL of a room, if any. */
    suspend fun roomBanner(roomId: RoomId): String? {
        val room = matrixClient.getRoom(roomId) ?: return null
        return room.use {
            it.havenStateEventRaw(Msc4501.ROOM_BANNER, "")
                ?.let { json -> runCatching { JSONObject(json) }.getOrNull() }
                ?.let { event -> (event.optJSONObject("content") ?: event).optStringOrNull("url") }
                ?.takeIf { url -> url.startsWith("mxc://") }
        }
    }

    suspend fun refreshOwnProfile() {
        _ownProfile.value = getProfileRoomLink(matrixClient.sessionId.value).fold(
            onSuccess = { link -> link?.let { OwnProfileState.Linked(it) } ?: OwnProfileState.None },
            onFailure = { OwnProfileState.Error(it) },
        )
    }

    /** Reads [userId]'s profile room link. Falls back to MSC4501's older flat field only when the block is absent. */
    suspend fun getProfileRoomLink(userId: String): Result<ProfileRoomLink?> {
        return httpApi.getProfile(userId).map { profile ->
            val block = profile.optJSONObject(Msc4501.PROFILE_ROOM)
            when {
                block != null -> block.optStringOrNull("room_id")
                    ?.takeIf { it.startsWith("!") }
                    ?.let { ProfileRoomLink(RoomId(it), block.optJSONArray("via").toStringList()) }
                else -> profile.optStringOrNull(Msc4501.PROFILE_ROOM_ID_LEGACY)
                    ?.takeIf { it.startsWith("!") }
                    ?.let { ProfileRoomLink(RoomId(it), emptyList()) }
            }
        }.recoverCatching { error ->
            // No profile at all for this user is "no link", not an error.
            if (error is SocialHttpException && error.statusCode == 404) null else throw error
        }
    }

    /** Points the user's `org.matrix.msc4501.social.profile_room` profile field at [roomId]. */
    suspend fun setProfileRoomLink(roomId: RoomId, via: List<String>): Result<Unit> {
        val block = JSONObject().put("room_id", roomId.value)
        if (via.isNotEmpty()) block.put("via", JSONArray(via))
        return httpApi.setProfileField(matrixClient.sessionId.value, Msc4501.PROFILE_ROOM, block)
            .onSuccess { _ownProfile.value = OwnProfileState.Linked(ProfileRoomLink(roomId, via)) }
    }

    suspend fun refreshFeedFilter() {
        val json = matrixClient.getAccountData(HavenAccountData.FEED_FILTER).getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        val content = json.optJSONObject("content") ?: json
        _feedFilter.value = FeedFilter(
            extraRoomTypes = content.optJSONArray("extraRoomTypes").toStringList(),
            includedRoomIds = content.optJSONArray("includedRoomIds").toStringList(),
            excludedRoomIds = content.optJSONArray("excludedRoomIds").toStringList(),
            excludedUserIds = content.optJSONArray("excludedUserIds").toStringList(),
        )
    }

    /**
     * Haven Desktop's `roomCountsForFeed`: excluded (by id or any alias) → no, always wins; included → yes; a social
     * profile/group type → yes; one of the extra room types → yes.
     */
    fun countsForFeed(room: JoinedRoomEntry, filter: FeedFilter): Boolean {
        val ids = listOf(room.roomId.value) + room.info.aliases.map { it.value }
        if (ids.any { it in filter.excludedRoomIds }) return false
        if (ids.any { it in filter.includedRoomIds }) return true
        if (Msc4501.isSocialRoomType(room.type)) return true
        return room.type != null && room.type in filter.extraRoomTypes
    }

    /** Saves the feed filter to the same account data as Haven Desktop. */
    suspend fun saveFeedFilter(filter: FeedFilter): Result<Unit> {
        val content = JSONObject()
            .put("extraRoomTypes", JSONArray(filter.extraRoomTypes))
            .put("includedRoomIds", JSONArray(filter.includedRoomIds))
            .put("excludedRoomIds", JSONArray(filter.excludedRoomIds))
            .put("excludedUserIds", JSONArray(filter.excludedUserIds))
        return matrixClient.setAccountData(HavenAccountData.FEED_FILTER, content.toString())
            .onSuccess { _feedFilter.value = filter }
    }

    /**
     * Creates a profile or group room, with the same `/createRoom` request as Haven Desktop.
     * For a profile, also links it from the user's profile.
     */
    suspend fun createSocialRoom(params: CreateSocialRoomParams): Result<RoomId> {
        val me = matrixClient.sessionId.value
        val isProfile = params.kind == SocialRoomKind.Profile
        val roomType = if (isProfile) Msc4501.ROOM_TYPE_PROFILE else Msc4501.ROOM_TYPE_GROUP
        val isPublic = params.visibility == SocialRoomVisibility.Public
        val events = JSONObject()
            .put("m.room.avatar", 50)
            .put("m.room.canonical_alias", 50)
            .put("m.room.encryption", 100)
            .put("m.room.history_visibility", 100)
            .put("m.room.name", 50)
            .put("m.room.power_levels", 100)
            .put("m.room.server_acl", 100)
            .put("m.room.tombstone", 100)
        if (isProfile) {
            events.put("m.reaction", 0)
            // MSC4501: protect the owner claim with an explicit override, above state_default.
            events.put(Msc4501.PROFILE_USER_ID, 100)
        }
        val powerLevels = JSONObject().put("events", events)
        if (!params.allowAnyoneToPost) powerLevels.put("events_default", 10)

        val initialState = JSONArray()
        when (params.visibility) {
            SocialRoomVisibility.AskToJoin -> {
                initialState.put(stateEvent("m.room.guest_access", JSONObject().put("guest_access", "can_join")))
                initialState.put(stateEvent("m.room.join_rules", JSONObject().put("join_rule", "knock")))
                initialState.put(stateEvent("m.room.history_visibility", JSONObject().put("history_visibility", "invited")))
            }
            SocialRoomVisibility.Private -> {
                initialState.put(stateEvent("m.room.history_visibility", JSONObject().put("history_visibility", "invited")))
            }
            SocialRoomVisibility.Public -> Unit
        }
        if (params.encrypted && !isPublic) {
            initialState.put(stateEvent("m.room.encryption", JSONObject().put("algorithm", "m.megolm.v1.aes-sha2")))
        }
        if (isProfile) {
            initialState.put(stateEvent(Msc4501.PROFILE_USER_ID, JSONObject().put("user_id", me)))
        }

        val request = JSONObject()
            .put("name", params.name)
            .put("preset", if (isPublic) "public_chat" else "private_chat")
            .put(
                "visibility",
                if (isPublic || (params.visibility == SocialRoomVisibility.AskToJoin && params.publishToDirectory)) "public" else "private",
            )
            .put("creation_content", JSONObject().put("type", roomType))
            .put("power_level_content_override", powerLevels)
            .put("initial_state", initialState)
        if (params.topic.isNotBlank()) request.put("topic", params.topic)

        return httpApi.createRoom(request).mapCatching { id ->
            val roomId = RoomId(id)
            roomTypeMutex.withLock { roomTypeCache[roomId] = roomType }
            typePrefs.edit().putString(roomId.value, roomType).apply()
            sessionScope.launch { refreshSocialRooms() }
            if (isProfile) {
                setProfileRoomLink(roomId, listOf(me.substringAfter(':'))).onFailure {
                    Timber.w(it, "Created profile room but couldn't link it from the profile")
                }
            }
            roomId
        }
    }

    private fun stateEvent(type: String, content: JSONObject) = JSONObject()
        .put("type", type)
        .put("state_key", "")
        .put("content", content)

    companion object {
        const val LOG_TAG = "HavenSocial"
        private val USER_ID_REGEX = Regex("^@[^\\s:]+:\\S+$")
    }
}
