/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.api

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import android.os.Parcelable
import com.bumble.appyx.core.plugin.Plugin
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.parcelize.Parcelize

/**
 * Haven's Social app (MSC4501 profiles, groups and posts).
 */
interface SocialEntryPoint : FeatureEntryPoint {
    fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        callback: Callback,
        initialTarget: InitialTarget? = null,
    ): Node

    /**
     * Where Social opens, instead of its home: a profile or group room's page, or a post in it when [eventId] is set
     * (a matrix.to link to it, like Haven Desktop). [via]: the link's servers, for a room this server doesn't know yet.
     */
    @Parcelize
    data class InitialTarget(
        val roomId: RoomId,
        val eventId: String?,
        val via: List<String>,
    ) : NodeInputs, Parcelable

    interface Callback : Plugin {
        /** Open the stock room settings for a profile or group room. */
        fun navigateToRoomDetails(roomId: RoomId)

        /** Open the stock member list for a profile or group room (followers / members). */
        fun navigateToRoomMembers(roomId: RoomId)

        /** Open the stock room timeline. */
        fun navigateToRoom(roomId: RoomId)

        /** Open the stock user profile screen. */
        fun navigateToUser(userId: UserId)
    }
}

/**
 * Haven: tells whether a room link points into Social, so matrix.to links to profile and group rooms (and posts in them)
 * open in the Social app rather than as a chat room, like Haven Desktop.
 */
interface SocialPermalinkResolver {
    /** The room's ID when it's a Social profile or group room, else null (not one, or it couldn't be looked up). */
    suspend fun socialRoomId(roomIdOrAlias: RoomIdOrAlias, via: List<String>): RoomId?
}
