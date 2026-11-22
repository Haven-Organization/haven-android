/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.widgets.api

import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import io.element.android.libraries.architecture.FeatureEntryPoint
import io.element.android.libraries.matrix.api.core.RoomId

/** Haven: room widgets (`im.vector.modular.widgets`), like Haven Desktop's Extensions and widget apps. */
interface WidgetsEntryPoint : FeatureEntryPoint {
    /** The room's Extensions list: open a widget, or add it to Apps. */
    fun createExtensionsNode(parentNode: Node, buildContext: BuildContext, roomId: RoomId, callback: Callback): Node

    /** A single widget, full screen. */
    fun createWidgetNode(parentNode: Node, buildContext: BuildContext, roomId: RoomId, widgetId: String, callback: Callback): Node

    interface Callback : Plugin {
        fun onDone()
        fun navigateToRoom(roomId: RoomId)

        /** From the Extensions list: open a widget (the host pushes [createWidgetNode]). */
        fun openWidget(roomId: RoomId, widgetId: String)
    }
}
