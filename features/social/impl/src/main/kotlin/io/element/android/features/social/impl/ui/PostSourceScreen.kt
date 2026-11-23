/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import io.element.android.features.messages.impl.timeline.debug.EventDebugInfoView
import io.element.android.features.social.impl.SocialDeps
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId

/**
 * A post's event source, like "View source" on a message in a room (the same stock screen): the event's JSON, its
 * latest edit's, and how Social read it as an MSC4501 post.
 */
@Composable
fun PostSourceScreen(
    deps: SocialDeps,
    roomId: RoomId,
    eventId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val post = rememberComposerTarget(deps, roomId, eventId)
    // The raw event, if the post doesn't carry it: from the server (as stored there, i.e. still encrypted in E2EE rooms).
    val fetchedJson by produceState<String?>(null, post) {
        if (post != null && post.originalJson == null) {
            value = deps.httpApi.getRoomEvent(roomId.value, eventId).getOrNull()?.toString(2)
        }
    }
    val clipboard = LocalClipboardManager.current
    EventDebugInfoView(
        eventId = runCatching { EventId(eventId) }.getOrNull(),
        model = post?.post?.toString().orEmpty(),
        originalJson = (post?.originalJson ?: fetchedJson)?.let(::prettyJson),
        latestEditedJson = post?.latestEditJson?.let(::prettyJson),
        onBackClick = onBack,
        onCopyText = { text -> clipboard.setText(AnnotatedString(text)) },
        modifier = modifier,
    )
}

private fun prettyJson(json: String): String =
    runCatching { org.json.JSONObject(json).toString(2) }.getOrDefault(json)
