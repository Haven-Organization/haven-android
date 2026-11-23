/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import android.os.Parcelable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.replace
import io.element.android.appnav.di.RoomGraphFactory
import io.element.android.features.social.impl.ui.SocialSpinner
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.di.DependencyInjectionGraphOwner
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize

/**
 * Gives its child a room's scope, like Element X's room screen does ([RoomGraphFactory]), so room-scoped stock screens
 * (e.g. the message composer, see HavenPostComposerNode) can be used from Social, for any room the user picks.
 */
class SocialRoomScopeNode(
    buildContext: BuildContext,
    private val roomId: RoomId,
    private val loadRoom: suspend (RoomId) -> JoinedRoom?,
    private val roomGraphFactory: RoomGraphFactory,
    private val onUnavailable: () -> Unit,
    private val child: (parent: Node, buildContext: BuildContext) -> Node,
) : BaseFlowNode<SocialRoomScopeNode.Target>(
    backstack = BackStack(initialElement = Target.Loading, savedStateMap = buildContext.savedStateMap),
    buildContext = buildContext,
    plugins = emptyList(),
), DependencyInjectionGraphOwner {
    sealed interface Target : Parcelable {
        @Parcelize
        data object Loading : Target

        @Parcelize
        data object Content : Target
    }

    private var roomGraph: Any? = null

    // Nothing to offer until the room is loaded (lookups then go on to the session's graph).
    override val graph: Any get() = roomGraph ?: Unit

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(onCreate = {
            lifecycleScope.launch {
                val room = loadRoom(roomId)
                if (room == null) {
                    onUnavailable()
                } else {
                    roomGraph = roomGraphFactory.create(room)
                    backstack.replace(Target.Content)
                }
            }
        })
    }

    override fun resolve(navTarget: Target, buildContext: BuildContext): Node = when (navTarget) {
        Target.Loading -> node(buildContext) { modifier ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SocialSpinner() }
        }
        Target.Content -> child(this, buildContext)
    }

    @Composable
    override fun View(modifier: Modifier) {
        BackstackView(modifier)
    }
}
