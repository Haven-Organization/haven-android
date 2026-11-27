/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.social.api.SocialEntryPoint
import io.element.android.libraries.architecture.createNode

@ContributesBinding(AppScope::class)
class DefaultSocialEntryPoint : SocialEntryPoint {
    override fun createNode(
        parentNode: Node,
        buildContext: BuildContext,
        callback: SocialEntryPoint.Callback,
        initialTarget: SocialEntryPoint.InitialTarget?,
    ): Node {
        return parentNode.createNode<SocialFlowNode>(buildContext, listOfNotNull(callback, initialTarget))
    }
}
