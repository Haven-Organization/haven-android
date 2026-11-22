/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.widgets.impl

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.features.widgets.api.WidgetsEntryPoint
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.matrix.api.core.RoomId

@ContributesBinding(AppScope::class)
class DefaultWidgetsEntryPoint : WidgetsEntryPoint {
    override fun createExtensionsNode(parentNode: Node, buildContext: BuildContext, roomId: RoomId, callback: WidgetsEntryPoint.Callback): Node {
        return parentNode.createNode<ExtensionsNode>(buildContext, listOf(ExtensionsNode.Inputs(roomId), callback))
    }

    override fun createWidgetNode(
        parentNode: Node,
        buildContext: BuildContext,
        roomId: RoomId,
        widgetId: String,
        callback: WidgetsEntryPoint.Callback,
    ): Node {
        return parentNode.createNode<WidgetNode>(buildContext, listOf(WidgetNode.Inputs(roomId, widgetId), callback))
    }
}
