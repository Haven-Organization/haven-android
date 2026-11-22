/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.data.OwnProfileState
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.NavigationBar
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SocialSection { Feed, Groups, Profile, Search }

@Composable
fun SocialHomeScreen(
    deps: SocialDeps,
    navigator: SocialNavigator,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Haven Desktop starts on the Feed.
    var section by rememberSaveable { mutableStateOf(SocialSection.Feed) }
    var showFeedFilter by remember { mutableStateOf(false) }
    val interactions = rememberPostInteractionState()
    val callbacks = rememberPostCallbacks(deps, navigator, interactions)
    val ownProfile by deps.repository.ownProfile.collectAsState()
    LaunchedEffect(Unit) { deps.repository.refreshSocialRooms() }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(
                    when (section) {
                        SocialSection.Feed -> R.string.social_feed
                        SocialSection.Groups -> R.string.social_groups
                        SocialSection.Profile -> R.string.social_profile
                        SocialSection.Search -> R.string.social_search
                    }
                ),
                navigationIcon = { BackButton(onClick = onBack) },
                actions = {
                    if (section == SocialSection.Feed) {
                        io.element.android.libraries.designsystem.theme.components.IconButton(onClick = { showFeedFilter = true }) {
                            Icon(CompoundIcons.Filter(), contentDescription = stringResource(R.string.social_feed_filters))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (section != SocialSection.Search) {
                FloatingActionButton(onClick = {
                    interactions.compose = ComposeMode.NewPost((ownProfile as? OwnProfileState.Linked)?.link?.roomId)
                }) {
                    Icon(CompoundIcons.Compose(), contentDescription = stringResource(R.string.social_post))
                }
            }
        },
        bottomBar = {
            NavigationBar {
                SocialSection.entries.forEach { item ->
                    val (icon, label) = when (item) {
                        SocialSection.Feed -> CompoundIcons.Home() to R.string.social_feed
                        SocialSection.Groups -> CompoundIcons.Group() to R.string.social_groups
                        SocialSection.Profile -> CompoundIcons.UserProfile() to R.string.social_profile
                        SocialSection.Search -> CompoundIcons.Search() to R.string.social_search
                    }
                    NavigationBarItem(
                        selected = section == item,
                        onClick = { section = item },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (section) {
                SocialSection.Feed -> FeedPane(deps, callbacks)
                SocialSection.Groups -> GroupsPane(deps, navigator)
                SocialSection.Profile -> ProfilePane(deps, navigator, callbacks)
                SocialSection.Search -> SocialSearchPane(deps, navigator)
            }
        }
    }
    PostInteractionOverlays(deps, interactions)
    if (showFeedFilter) FeedFilterSheet(deps = deps, onDismiss = { showFeedFilter = false })
}

@Composable
private fun FeedPane(deps: SocialDeps, callbacks: PostCallbacks) {
    val feedRooms by deps.repository.feedRooms.collectAsState()
    val filter by deps.repository.feedFilter.collectAsState()
    val roomIds = remember(feedRooms) { feedRooms.map { it.roomId } }
    // Like Haven Desktop (hideRoomName = hasProfileOwner(room)): no room name on posts in profile rooms (every profile
    // room has an owner); only group and other feed rooms show where the post was made.
    val roomNames = remember(feedRooms) {
        feedRooms
            .filter { !io.element.android.features.social.impl.msc.Msc4501.isProfileRoomType(it.type) }
            .associate { it.roomId to it.info.name }
    }
    val hasScanned by deps.repository.hasScannedRooms.collectAsState()
    val snapshot by produceState<io.element.android.features.social.impl.data.FeedSnapshot?>(null, roomIds) {
        deps.timelineStore.feed(roomIds).collect { value = it }
    }
    val posts = remember(snapshot, filter) {
        snapshot?.posts.orEmpty().filter { !it.isThreadReply && it.sender.value !in filter.excludedUserIds }
    }
    val listState = rememberLazyListState()
    // Extend the feed by loading more from the rooms holding it back (the ones loaded the least far back).
    val latestSnapshot by rememberUpdatedState(snapshot)
    PaginateOnScroll(listState, key = roomIds) {
        val limiting = latestSnapshot?.limitingRooms.orEmpty().take(8)
        if (limiting.isEmpty()) {
            // Nothing holding the feed back (yet): only keep going while rooms are still loading.
            latestSnapshot?.isLoading != false
        } else {
            kotlinx.coroutines.coroutineScope {
                limiting.map { roomId -> async { deps.timelineStore.paginate(roomId) } }.awaitAll()
            }
            true
        }
    }
    if (posts.isEmpty()) {
        val current = snapshot
        when {
            // Still finding the profile/group rooms (first launch checks each room's type once).
            !hasScanned -> FeedLoading(stringResource(R.string.social_feed_finding_rooms))
            current == null || current.isLoading || current.hasMore -> FeedLoading(
                stringResource(R.string.social_feed_loading_posts, current?.loadedRooms ?: 0, current?.totalRooms ?: roomIds.size)
            )
            else -> EmptyState(stringResource(R.string.social_feed_empty))
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(posts, key = { it.key }) { post ->
            PostTile(post = post, deps = deps, callbacks = callbacks, roomName = roomNames[post.roomId])
        }
        if (snapshot?.hasMore == true || snapshot?.isLoading == true) {
            item(key = "loading-more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/**
 * Loads older posts while the list is near its end (including when it's too short to scroll). [loadMore] returns
 * whether there may be more; loading stops when it says there isn't, or after a few loads that add nothing.
 */
@Composable
fun PaginateOnScroll(listState: LazyListState, key: Any?, loadMore: suspend () -> Boolean) {
    LaunchedEffect(key) {
        var fruitless = 0
        while (isActive && fruitless < 5) {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            val nearEnd = info.totalItemsCount == 0 || last >= info.totalItemsCount - 5
            if (nearEnd) {
                val before = info.totalItemsCount
                val mayHaveMore = loadMore()
                if (!mayHaveMore) break
                delay(500)
                if (listState.layoutInfo.totalItemsCount <= before) fruitless++ else fruitless = 0
            } else {
                fruitless = 0
                delay(500)
            }
        }
    }
}

@Composable
private fun GroupsPane(deps: SocialDeps, navigator: SocialNavigator) {
    val rooms by deps.repository.socialRooms.collectAsState()
    val groups = remember(rooms) { rooms.filter { it.kind == SocialRoomKind.Group }.sortedBy { it.info.name.orEmpty().lowercase() } }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                text = stringResource(R.string.social_new_group),
                onClick = { navigator.createRoom(SocialRoomKind.Group) },
                leadingIcon = io.element.android.libraries.designsystem.theme.components.IconSource.Vector(CompoundIcons.Plus()),
            )
        }
        if (groups.isEmpty()) {
            EmptyState(stringResource(R.string.social_groups_empty))
        } else {
            LazyColumn {
                items(groups, key = { it.roomId.value }) { group ->
                    SocialRoomRow(
                        roomId = group.roomId.value,
                        name = group.info.name,
                        avatarUrl = group.info.avatarUrl,
                        topic = group.info.topic,
                        memberCount = group.info.joinedMembersCount,
                        isProfile = false,
                        onClick = { navigator.openRoomPage(group.roomId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilePane(deps: SocialDeps, navigator: SocialNavigator, callbacks: PostCallbacks) {
    val ownProfile by deps.repository.ownProfile.collectAsState()
    when (val state = ownProfile) {
        OwnProfileState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        OwnProfileState.None -> CreateProfilePrompt { navigator.createRoom(SocialRoomKind.Profile) }
        is OwnProfileState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.social_profile_load_error), textAlign = TextAlign.Center, color = ElementTheme.colors.textSecondary)
            val scope = rememberCoroutineScope()
            Button(
                text = stringResource(R.string.social_retry),
                onClick = { scope.launch { deps.repository.refreshOwnProfile() } },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        is OwnProfileState.Linked -> SocialRoomContent(
            deps = deps,
            navigator = navigator,
            roomId = state.link.roomId,
            via = state.link.via,
            callbacks = callbacks,
        )
    }
}

@Composable
private fun CreateProfilePrompt(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.social_no_profile),
            style = ElementTheme.typography.fontBodyLgRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Button(text = stringResource(R.string.social_create_profile), onClick = onCreate, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun FeedLoading(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            text = text,
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = ElementTheme.typography.fontBodyLgRegular,
            color = ElementTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
