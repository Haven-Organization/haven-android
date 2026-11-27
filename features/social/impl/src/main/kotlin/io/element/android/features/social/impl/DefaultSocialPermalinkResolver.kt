/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.social.api.SocialPermalinkResolver
import io.element.android.features.social.impl.data.SocialRepository
import io.element.android.features.social.impl.msc.Msc4501
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.room.RoomType
import timber.log.Timber

@ContributesBinding(SessionScope::class)
class DefaultSocialPermalinkResolver(
    private val matrixClient: MatrixClient,
    private val repository: SocialRepository,
) : SocialPermalinkResolver {
    override suspend fun socialRoomId(roomIdOrAlias: RoomIdOrAlias, via: List<String>): RoomId? {
        if (roomIdOrAlias is RoomIdOrAlias.Id) {
            val roomId = roomIdOrAlias.roomId
            // A joined room: its type is known (cached after the first time), no need for a preview
            if (repository.roomKind(roomId) != null) return roomId
            if (matrixClient.getRoomInfo(roomId).getOrNull() != null) return null
        }
        // Not joined (or an alias): the room's summary has its type, like Haven Desktop's getRoomSummary
        return matrixClient.getRoomPreview(roomIdOrAlias, via)
            .onFailure { Timber.w(it, "Couldn't preview a linked room") }
            .getOrNull()
            ?.use { room ->
                val info = room.previewInfo
                val type = (info.roomType as? RoomType.Other)?.type
                info.roomId.takeIf { Msc4501.isProfileRoomType(type) || Msc4501.isGroupRoomType(type) }
            }
    }
}
