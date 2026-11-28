/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrix.impl.room

import io.element.android.libraries.core.coroutine.parallelMap
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.ForwardEventException
import io.element.android.libraries.matrix.impl.roomlist.roomOrNull
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.contentWithoutRelationFromMessage
import kotlin.time.Duration.Companion.milliseconds

/**
 * Helper to forward event contents from a room to a set of other rooms.
 * @param roomListService the [RoomListService] to fetch room instances to forward the event to
 */
class RoomContentForwarder(
    private val roomListService: RoomListService,
) {
    /**
     * Forwards the events with the given [eventIds] from the [fromTimeline] to the given [toRoomIds].
     * The events are sent to each room in the order they are provided in [eventIds], it is up to the caller to sort them.
     * @param fromTimeline the room to forward the events from
     * @param eventIds the ids of the events to forward, in the order they must be sent
     * @param toRoomIds the ids of the rooms to forward the events to
     * @param timeoutMs the maximum time in milliseconds to wait for an event to be sent to a room
     */
    suspend fun forward(
        fromTimeline: Timeline,
        eventIds: List<EventId>,
        toRoomIds: List<RoomId>,
        timeoutMs: Long = 5000L
    ) {
        val contents = eventIds.map { eventId ->
            val timelineEvent = fromTimeline.getEventTimelineItemByEventId(eventId.value)
            val message = ((timelineEvent.content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)
            if (message != null) {
                Forwarded.Message(contentWithoutRelationFromMessage(message.content))
            } else {
                // Haven: an MSC4501 post with its own event type, which the SDK has no message for, goes as the message it
                // is (MSC4501 posts have m.room.message's content)
                socialPostAsMessage(timelineEvent.lazyProvider.debugInfo().originalJson)?.let(Forwarded::Raw)
                    ?: throw ForwardEventException(toRoomIds)
            }
        }
        val targetRooms = toRoomIds.toSet().mapNotNull { roomId -> roomListService.roomOrNull(roomId) }
        val failedForwardingTo = mutableSetOf<RoomId>()
        targetRooms.parallelMap { room ->
            room.use { targetRoom ->
                runCatchingExceptions {
                    targetRoom.timeline().use { timeline ->
                        // Send the contents sequentially, so that they are received in the same order
                        contents.forEach { content ->
                            withTimeout(timeoutMs.milliseconds) {
                                when (content) {
                                    is Forwarded.Message -> timeline.send(content.content)
                                    is Forwarded.Raw -> targetRoom.sendRaw(MESSAGE_EVENT_TYPE, content.json)
                                }
                            }
                        }
                    }
                }
            }.onFailure {
                failedForwardingTo.add(RoomId(room.id()))
            }
        }

        if (failedForwardingTo.isNotEmpty()) {
            throw ForwardEventException(failedForwardingTo.toList())
        }
    }
}

/** Haven: what's forwarded: a message as the SDK has it, or the JSON content of an `m.room.message` to send as it is. */
private sealed interface Forwarded {
    class Message(val content: RoomMessageEventContentWithoutRelation) : Forwarded
    class Raw(val json: String) : Forwarded
}

private const val MESSAGE_EVENT_TYPE = "m.room.message"

private val SOCIAL_POST_TYPES = setOf("org.matrix.msc4501.social.post", "org.matrix.msc3639.social.post", "org.matrix.msc3639.social.comment")

/**
 * Haven: the content of the MSC4501 post [eventJson] as a new `m.room.message`: without its relations (what it replies
 * to, reposts or quotes), as a forwarded message has none; null when it isn't such a post.
 */
private fun socialPostAsMessage(eventJson: String?): String? {
    val event = eventJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (event.optString("type") !in SOCIAL_POST_TYPES) return null
    val content = event.optJSONObject("content") ?: return null
    content.remove("m.relates_to")
    content.remove("m.new_content")
    content.remove("org.matrix.msc4501.social.relates_to")
    // The post's own text, when its body is a fallback for clients without MSC4501
    if (content.has("org.matrix.msc4501.social.body")) {
        content.put("body", content.optString("org.matrix.msc4501.social.body"))
        content.remove("org.matrix.msc4501.social.body")
        val formatted = content.optString("org.matrix.msc4501.social.formatted_body").takeIf { content.has("org.matrix.msc4501.social.formatted_body") }
        content.remove("org.matrix.msc4501.social.formatted_body")
        if (formatted != null) content.put("format", "org.matrix.custom.html").put("formatted_body", formatted) else {
            content.remove("format")
            content.remove("formatted_body")
        }
    }
    if (!content.has("msgtype")) content.put("msgtype", "m.text")
    return content.toString()
}
