/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.msc

/**
 * MSC4501 (Social Media on Matrix) identifiers.
 *
 * MSC4501 is not merged yet, so everything this client writes uses the unstable `org.matrix.msc4501.social.*` names
 * from the MSC's "Unstable prefix" section, the same as Haven Desktop. Older names (MSC3639, MSC4501's predecessor, and
 * MSC4501's pre-2026-08 flat profile link) are only ever read, never written.
 */
object Msc4501 {
    const val ROOM_TYPE_PROFILE = "org.matrix.msc4501.social.profile"
    const val ROOM_TYPE_GROUP = "org.matrix.msc4501.social.group"
    const val EVENT_POST = "org.matrix.msc4501.social.post"
    const val RELATES_TO = "org.matrix.msc4501.social.relates_to"
    const val REL_TYPE_REPOST = "org.matrix.msc4501.social.repost"
    const val REL_TYPE_REPLY = "org.matrix.msc4501.social.reply"
    const val PROFILE_ROOM = "org.matrix.msc4501.social.profile_room"
    const val PROFILE_USER_ID = "org.matrix.msc4501.social.profile_user_id"
    const val BODY = "org.matrix.msc4501.social.body"
    const val FORMATTED_BODY = "org.matrix.msc4501.social.formatted_body"

    /** Older Haven flag (before the two fields above): the stock body's first line is a header to leave out. */
    const val REMOVE_HEADER = "software.haven.remove_header"

    /** Read-only: the flat room id string MSC4501 used before the `profile_room` block. */
    const val PROFILE_ROOM_ID_LEGACY = "org.matrix.msc4501.social.profile_room_id"

    // Read-only MSC3639 compatibility.
    private const val MSC3639_ROOM_TYPE_PROFILE = "org.matrix.msc3639.social.profile"
    private const val MSC3639_ROOM_TYPE_GROUP = "org.matrix.msc3639.social.group"
    private const val MSC3639_EVENT_POST = "org.matrix.msc3639.social.post"
    private const val MSC3639_EVENT_COMMENT = "org.matrix.msc3639.social.comment"

    /** MSC4221 room banner, unstable name (same as Haven Desktop). */
    const val ROOM_BANNER = "page.codeberg.everypizza.room.banner"

    /** MSC4501 "Liking": an m.reaction with this key is a like. */
    const val LIKE_KEY = "👍"

    /** MSC4501 "Repost counts": an m.reaction with this key annotates a reposted post. */
    const val REPOST_KEY = "🔁"

    fun isProfileRoomType(type: String?) = type == ROOM_TYPE_PROFILE || type == MSC3639_ROOM_TYPE_PROFILE
    fun isGroupRoomType(type: String?) = type == ROOM_TYPE_GROUP || type == MSC3639_ROOM_TYPE_GROUP
    fun isSocialRoomType(type: String?) = isProfileRoomType(type) || isGroupRoomType(type)

    fun isSocialPostEventType(type: String?) = type == EVENT_POST || type == MSC3639_EVENT_POST || type == MSC3639_EVENT_COMMENT

    /** Event types shown as posts (MSC4501 "Handling m.room.message in social rooms", phase 1), as on Haven Desktop. */
    fun isFeedEventType(type: String?) = isSocialPostEventType(type) ||
        type == "m.room.message" || type == "m.sticker" || type == "m.poll.start" || type == "org.matrix.msc3381.poll.start"
}

/** Haven's own (non-MSC) account data, shared with Haven Desktop. */
object HavenAccountData {
    const val PINNED_APPS = "software.haven.pinned_apps"
    const val FEED_FILTER = "software.haven.social.feed_filter"
    const val WIDGET_APPS = "software.haven.widget_apps"

    /** Settings > Apps: `{"disabled": bool, "hidden": [app ids]}`. */
    const val APP_PREFERENCES = "software.haven.app_preferences"
}
