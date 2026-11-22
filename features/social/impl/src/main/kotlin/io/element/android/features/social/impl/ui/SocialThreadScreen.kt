/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private sealed interface ThreadState {
    data object Loading : ThreadState
    data object NotJoined : ThreadState
    data class Loaded(val posts: List<FeedPost>) : ThreadState
}

/** A post and its replies (`m.thread`), with the opened post highlighted. */
@Composable
fun SocialThreadScreen(
    deps: SocialDeps,
    navigator: SocialNavigator,
    roomId: RoomId,
    eventId: String,
    modifier: Modifier = Modifier,
    via: List<String> = emptyList(),
) {
    val interactions = rememberPostInteractionState()
    val callbacks = rememberPostCallbacks(deps, navigator, interactions)
    val openedTimelines = remember { mutableListOf<Timeline>() }
    DisposableEffect(Unit) {
        onDispose { openedTimelines.forEach { runCatching { it.close() } } }
    }
    val state by produceState<ThreadState>(ThreadState.Loading, roomId, eventId) {
        val room = deps.timelineStore.joinedRoom(roomId)
        if (room == null) {
            // Not following this room: peek, like Haven Desktop (works for world-readable profiles and groups).
            val focusedPost = deps.peek.post(roomId, eventId).getOrNull()
            val peeked = focusedPost?.let { deps.peek.thread(roomId, it.post.threadRootId ?: eventId).getOrNull() }
            value = if (peeked != null) ThreadState.Loaded(peeked) else ThreadState.NotJoined
            return@produceState
        }
        // Find the thread root: the opened post may itself be a reply.
        val focused = room.createTimeline(CreateTimelineParams.Focused(EventId(eventId))).getOrNull()
        val rootId = focused?.let { timeline ->
            openedTimelines += timeline
            withTimeoutOrNull(15.seconds) {
                deps.timelineStore.postsOf(roomId, timeline).first { list -> list.any { it.eventId?.value == eventId } }
            }?.first { it.eventId?.value == eventId }?.post?.threadRootId
        } ?: eventId
        val thread = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(rootId))).getOrNull()
        if (thread == null) {
            value = ThreadState.Loaded(emptyList())
            return@produceState
        }
        openedTimelines += thread
        thread.paginate(Timeline.PaginationDirection.BACKWARDS)
        deps.timelineStore.postsOf(roomId, thread).collect { list ->
            // Oldest first, like a conversation.
            value = ThreadState.Loaded(list.sortedBy { it.timestamp })
        }
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(R.string.social_post_title),
                navigationIcon = { BackButton(onClick = navigator::back) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ThreadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ThreadState.NotJoined -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EmptyStateInline(stringResource(R.string.social_follow_to_see_post))
                        Button(text = stringResource(R.string.social_follow), onClick = { navigator.openFollow(roomId, eventId, via) })
                    }
                }
                is ThreadState.Loaded -> if (s.posts.isEmpty()) {
                    EmptyStateInline(stringResource(R.string.social_post_unavailable))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.posts, key = { it.key }) { post ->
                            val isFocused = post.eventId?.value == eventId
                            PostTile(
                                post = post,
                                deps = deps,
                                callbacks = callbacks,
                                focused = isFocused,
                                modifier = if (isFocused) Modifier.background(ElementTheme.colors.bgSubtleSecondary) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
    PostInteractionOverlays(deps, interactions)
}

