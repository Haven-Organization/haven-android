/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.composable.Children
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.model.permanent.PermanentNavModel
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.ParentNode
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.appnav.di.RoomGraphFactory
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import kotlinx.parcelize.Parcelize

/**
 * Haven: Element X's own full-screen media viewer (images, and videos with the stock player) for a post's media.
 * The viewer lives in the room scope, so this node provides that scope for the post's room, the same way
 * Element X's room screen does.
 */
@ContributesNode(SessionScope::class)
@AssistedInject
class SocialMediaViewerNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    roomGraphFactory: RoomGraphFactory,
    private val mediaViewerEntryPoint: MediaViewerEntryPoint,
) : ParentNode<SocialMediaViewerNode.NavTarget>(
    navModel = PermanentNavModel(
        navTargets = setOf(NavTarget),
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins,
), DependencyInjectionGraphOwner {
    @Parcelize object NavTarget : Parcelable

    data class Inputs(
        val joinedRoom: JoinedRoom,
        val params: MediaViewerEntryPoint.Params,
        val onDone: () -> Unit,
    ) : NodeInputs

    private val inputs = inputs<Inputs>()

    override val graph = roomGraphFactory.create(inputs.joinedRoom)

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node {
        return mediaViewerEntryPoint.createNode(
            parentNode = this,
            buildContext = buildContext,
            params = inputs.params,
            callback = object : MediaViewerEntryPoint.Callback {
                override fun onDone() = inputs.onDone()
                override fun viewInTimeline(eventId: EventId) = inputs.onDone()
                override fun forwardEvent(eventId: EventId) = Unit
            },
        )
    }

    @Composable
    override fun View(modifier: Modifier) {
        Children(modifier = modifier, navModel = navModel)
    }
}
