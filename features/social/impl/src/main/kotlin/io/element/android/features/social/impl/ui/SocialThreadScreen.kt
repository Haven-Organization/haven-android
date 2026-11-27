/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
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
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.timeline.Timeline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
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
    // Opened from a feed or list: that post is already known, so it shows at once while its thread loads.
    val known = remember(roomId, eventId) { deps.timelineStore.openedPost(roomId, eventId) }
    val initial = if (known != null) ThreadState.Loaded(listOf(known)) else ThreadState.Loading
    val state by produceState(initial, roomId, eventId) {
        val room = deps.timelineStore.joinedRoom(roomId) ?: run {
            // Not following this room: peek, like Haven Desktop (works for world-readable profiles and groups).
            val focusedPost = deps.peek.post(roomId, eventId).getOrNull()
            val peeked = focusedPost?.let { deps.peek.thread(roomId, it.post.threadRootId ?: eventId).getOrNull() }
            value = if (peeked != null) ThreadState.Loaded(peeked) else ThreadState.NotJoined
            // Then live once the room is joined (a follow, or just joined and not ready yet), so new replies show.
            awaitJoinedRoom(deps, roomId)
        }
        // Find the thread root: the opened post may itself be a reply. Known already when opened from a list.
        val knownRootId = known?.let { it.post.threadRootId ?: eventId }
        val focused = if (knownRootId != null) null else room.createTimeline(CreateTimelineParams.Focused(EventId(eventId))).getOrNull()
        val focusedPosts = focused?.let { timeline ->
            openedTimelines += timeline
            withTimeoutOrNull(15.seconds) {
                deps.timelineStore.postsOf(roomId, timeline).first { list -> list.any { it.eventId?.value == eventId } }
            }
        }.orEmpty()
        val rootId = focusedPosts.firstOrNull { it.eventId?.value == eventId }?.post?.threadRootId ?: knownRootId ?: eventId
        // Posts shown even if the thread's timeline doesn't have them (yet): the one opened from a list, and the thread
        // root, which a threaded timeline doesn't always include (it may only hold the replies). The thread's own copy
        // replaces either once it's there.
        val extra = MutableStateFlow(listOfNotNull(known) + focusedPosts.filter { it.eventId?.value == eventId || it.eventId?.value == rootId })
        launch {
            if (extra.value.any { it.eventId?.value == rootId }) return@launch
            val root = deps.timelineStore.feedPost(roomId, rootId)
                ?: deps.peek.post(roomId, rootId).getOrNull()?.copy(peeked = false)
                ?: return@launch
            extra.update { current -> if (current.any { it.eventId == root.eventId }) current else current + root }
        }
        val thread = room.createTimeline(CreateTimelineParams.Threaded(ThreadId(rootId))).getOrNull()
        if (thread == null) {
            extra.collect { posts -> value = ThreadState.Loaded(posts.distinctBy { it.listKey }.sortedBy { it.timestamp }) }
            return@produceState
        }
        openedTimelines += thread
        // Show the thread as it loads, rather than after the first page.
        launch {
            thread.paginate(Timeline.PaginationDirection.BACKWARDS)
            // Nothing at all after loading: the post isn't available.
            if (value == ThreadState.Loading) value = ThreadState.Loaded(emptyList())
        }
        combine(deps.timelineStore.postsOf(roomId, thread), extra) { list, extras ->
            list + extras.filter { e -> list.none { it.eventId == e.eventId } }.distinctBy { it.listKey }
        }.collect { posts ->
            if (posts.isEmpty()) return@collect
            // Oldest first, like a conversation.
            value = ThreadState.Loaded(posts.sortedBy { it.timestamp })
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
                ThreadState.Loading -> SocialSpinner(Modifier.align(Alignment.Center))
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
                        items(s.posts, key = { it.listKey }) { post ->
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

/**
 * Waits until the user is in the room and the SDK has it ready (just after a join it can take a moment to appear in the
 * room list).
 */
private suspend fun awaitJoinedRoom(deps: SocialDeps, roomId: RoomId): JoinedRoom {
    deps.matrixClient.getRoomInfoFlow(roomId).first { it.orElse(null)?.currentUserMembership == CurrentUserMembership.JOINED }
    while (true) {
        deps.timelineStore.joinedRoom(roomId)?.let { return it }
        delay(500.milliseconds)
    }
}
