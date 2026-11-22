/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.api

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId

/**
 * Haven's Social app (MSC4501 profiles, groups and posts).
 */
interface SocialEntryPoint : FeatureEntryPoint {
    fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        callback: Callback,
    ): Node

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
