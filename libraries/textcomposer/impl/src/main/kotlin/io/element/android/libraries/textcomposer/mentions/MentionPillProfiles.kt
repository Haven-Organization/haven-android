/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.textcomposer.mentions

import android.content.Context
import android.graphics.Bitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrixmedia.api.ImageLoaderHolder
import io.element.android.libraries.matrixmedia.api.InitialsAvatarBitmapGenerator
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** The avatar a user pill draws: the user's picture, or else their initial. */
data class PillAvatarKey(
    val userId: UserId,
    val url: String?,
    /** The name the initial comes from, when there's no [url]. */
    val name: String?,
    val isDark: Boolean,
)

/**
 * Haven: names and avatars for user pills, like Haven Desktop's pills.
 *
 * A user the room has no name or avatar for (someone who isn't a member, e.g. a bridged account mentioned in a bridge's
 * notifications room, or a member without a profile of their own) gets their global profile instead of a bare user id.
 */
interface MentionPillProfiles {
    /** The global profiles looked up so far. */
    val profiles: StateFlow<Map<UserId, MatrixUser>>

    /** The pill avatars loaded so far. */
    val avatars: StateFlow<Map<PillAvatarKey, Bitmap>>

    /**
     * The global profile of [userId] when the room's [members] have no name or avatar for them; looked up on first use,
     * so null until it has come in. Null as well while the members haven't loaded yet (they may well have the user).
     */
    fun fallbackProfile(userId: UserId, members: Map<UserId, RoomMember>): MatrixUser?

    /** The bitmap for [key], loaded on first use, so null until it has. */
    fun avatar(key: PillAvatarKey): Bitmap?
}

/** No lookups: pills keep what the room has, without avatars. */
object NoMentionPillProfiles : MentionPillProfiles {
    override val profiles: StateFlow<Map<UserId, MatrixUser>> = MutableStateFlow(emptyMap())
    override val avatars: StateFlow<Map<PillAvatarKey, Bitmap>> = MutableStateFlow(emptyMap())
    override fun fallbackProfile(userId: UserId, members: Map<UserId, RoomMember>): MatrixUser? = null
    override fun avatar(key: PillAvatarKey): Bitmap? = null
}

@ContributesBinding(SessionScope::class)
@SingleIn(SessionScope::class)
class DefaultMentionPillProfiles(
    @ApplicationContext private val context: Context,
    private val matrixClient: MatrixClient,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
    private val imageLoaderHolder: ImageLoaderHolder,
    private val initialsAvatarBitmapGenerator: InitialsAvatarBitmapGenerator,
) : MentionPillProfiles {
    private val _profiles = MutableStateFlow<Map<UserId, MatrixUser>>(emptyMap())
    override val profiles: StateFlow<Map<UserId, MatrixUser>> = _profiles.asStateFlow()

    private val _avatars = MutableStateFlow<Map<PillAvatarKey, Bitmap>>(emptyMap())
    override val avatars: StateFlow<Map<PillAvatarKey, Bitmap>> = _avatars.asStateFlow()

    private val requestedProfiles = ConcurrentHashMap.newKeySet<UserId>()
    private val requestedAvatars = ConcurrentHashMap.newKeySet<PillAvatarKey>()

    // A message can mention many people: don't flood the server
    private val lookups = Semaphore(MAX_LOOKUPS)

    override fun fallbackProfile(userId: UserId, members: Map<UserId, RoomMember>): MatrixUser? {
        val member = members[userId]
        if (member != null && (member.displayName != null || member.avatarUrl != null)) return null
        _profiles.value[userId]?.let { return it }
        if (members.isEmpty()) return null
        if (requestedProfiles.add(userId)) {
            sessionScope.launch {
                lookups.withPermit { matrixClient.getProfile(userId) }
                    .onSuccess { profile -> _profiles.update { it + (userId to profile) } }
                    .onFailure { Timber.w(it, "Couldn't look up the profile of %s for a pill", userId) }
            }
        }
        return null
    }

    override fun avatar(key: PillAvatarKey): Bitmap? {
        _avatars.value[key]?.let { return it }
        if (requestedAvatars.add(key)) {
            sessionScope.launch {
                val bitmap = key.url?.let { url -> lookups.withPermit { loadPicture(url) } } ?: initials(key)
                if (bitmap != null) _avatars.update { it + (key to bitmap) }
            }
        }
        return null
    }

    private suspend fun loadPicture(url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(MediaRequestData(MediaSource(url), MediaRequestData.Kind.Thumbnail(AVATAR_SIZE_PX.toLong())))
            .size(AVATAR_SIZE_PX)
            // Drawn through a shader, which a hardware bitmap can't be on a software canvas
            .allowHardware(false)
            .build()
        imageLoaderHolder.get(matrixClient).execute(request).image?.toBitmap()
    }.onFailure { Timber.w(it, "Couldn't load a pill avatar") }.getOrNull()

    private fun initials(key: PillAvatarKey): Bitmap? = initialsAvatarBitmapGenerator.generateBitmap(
        size = AVATAR_SIZE_PX,
        avatarData = AvatarData(id = key.userId.value, name = key.name, url = null, size = AvatarSize.TimelineSender),
        useDarkTheme = key.isDark,
    )

    private companion object {
        const val MAX_LOOKUPS = 4
        const val AVATAR_SIZE_PX = 96
    }
}
