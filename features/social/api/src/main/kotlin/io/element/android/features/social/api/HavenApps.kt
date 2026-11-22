/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.api

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.StateFlow

/**
 * A built-in app launched from the Apps button, matching Haven Desktop's apps framework.
 *
 * @param id stable identifier, shared with Haven Desktop (stored in the pinned apps account data).
 */
enum class HavenApp(
    val id: String,
    @StringRes val nameRes: Int,
    @DrawableRes val iconRes: Int,
) {
    Social(
        id = "social",
        nameRes = R.string.haven_app_social_name,
        iconRes = R.drawable.haven_app_social,
    );

    companion object {
        fun fromId(id: String): HavenApp? = entries.firstOrNull { it.id == id }
    }
}

/** Anything shown in the Apps picker or pinned to a bar: a built-in app, or a room widget added to Apps. */
sealed interface HavenAppItem {
    /** Shared with Haven Desktop: "social", or "widget:<roomId>/<widgetId>". */
    val id: String

    data class Builtin(val app: HavenApp) : HavenAppItem {
        override val id = app.id
    }

    data class Widget(
        val roomId: String,
        val widgetId: String,
        val name: String,
        /** mxc:// avatar of the widget, if any. */
        val avatarUrl: String?,
        val roomName: String?,
    ) : HavenAppItem {
        override val id = widgetAppId(roomId, widgetId)
    }

    companion object {
        /** Haven Desktop's widget app id format. */
        fun widgetAppId(roomId: String, widgetId: String) = "widget:$roomId/$widgetId"

        /** Parses a widget app id into (roomId, widgetId). */
        fun parseWidgetAppId(id: String): Pair<String, String>? {
            if (!id.startsWith("widget:")) return null
            val rest = id.removePrefix("widget:")
            val slash = rest.indexOf('/')
            if (slash <= 0 || slash == rest.length - 1) return null
            return rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}

/**
 * Pinned apps and widget apps, stored in account data shared with Haven Desktop:
 * - `software.haven.pinned_apps`: `{"pinned": ["social", "widget:!room:hs/widgetId", ...]}`
 * - `software.haven.widget_apps`: `{"widgets": [{room_id, widget_id, room_name, state_types, content, sender}, ...]}`
 */
interface HavenAppsService {
    /** Pinned app ids, in pin order. Unknown ids (apps Android doesn't have) are kept. */
    val pinnedAppIds: StateFlow<List<String>>

    /** Room widgets the user added to Apps, in the order they were added. */
    val widgetApps: StateFlow<List<HavenAppItem.Widget>>

    suspend fun setPinned(appId: String, pinned: Boolean): Result<Unit>

    /** Adds a room widget to Apps (and pins it, like Haven Desktop). */
    suspend fun addWidgetApp(roomId: String, widgetId: String): Result<Unit>

    /** Removes a widget app and unpins it. */
    suspend fun removeWidgetApp(appId: String): Result<Unit>
}
