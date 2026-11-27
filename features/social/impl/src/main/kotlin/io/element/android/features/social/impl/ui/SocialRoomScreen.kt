/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.SocialNavigator
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.data.OwnProfileState
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.FloatingActionButton
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.OutlinedButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import io.element.android.libraries.matrix.api.room.join.JoinRule
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import kotlinx.coroutines.launch

/** A profile or group page opened from the feed, search, or a link. */
@Composable
fun SocialRoomScreen(
    deps: SocialDeps,
    navigator: SocialNavigator,
    roomId: RoomId,
    modifier: Modifier = Modifier,
    via: List<String> = emptyList(),
) {
    val interactions = rememberPostInteractionState()
    val callbacks = rememberPostCallbacks(deps, navigator, interactions)
    val kind by produceState<SocialRoomKind?>(null, roomId) { value = deps.repository.roomKind(roomId) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                titleStr = stringResource(if (kind == SocialRoomKind.Group) R.string.social_group else R.string.social_profile),
                navigationIcon = { BackButton(onClick = navigator::back) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            SocialRoomContent(deps = deps, navigator = navigator, roomId = roomId, via = via, callbacks = callbacks, interactions = interactions)
        }
    }
    PostInteractionOverlays(deps, interactions)
}

/** Header (banner, avatar, name, bio, follow/members/settings) and posts of a profile or group room. */
@Composable
fun SocialRoomContent(
    deps: SocialDeps,
    navigator: SocialNavigator,
    roomId: RoomId,
    via: List<String>,
    callbacks: PostCallbacks,
    interactions: PostInteractionState? = null,
) {
    val scope = rememberCoroutineScope()
    val roomInfo by produceState<io.element.android.libraries.matrix.api.room.RoomInfo?>(null, roomId) {
        deps.matrixClient.getRoomInfoFlow(roomId).collect { value = it.orElse(null) }
    }
    val membership = roomInfo?.currentUserMembership
    val isJoined = membership == CurrentUserMembership.JOINED
    val preview by produceState<io.element.android.libraries.matrix.api.room.preview.RoomPreviewInfo?>(null, roomId, isJoined) {
        if (!isJoined) {
            deps.matrixClient.getRoomPreview(roomId.toRoomIdOrAlias(), via).onSuccess { room ->
                value = room.previewInfo
                runCatching { room.close() }
            }
        }
    }
    val kind by produceState<SocialRoomKind?>(null, roomId, isJoined) { value = deps.repository.roomKind(roomId) }
    val owner by produceState<String?>(null, roomId, isJoined) { value = deps.repository.profileOwner(roomId) }
    val banner by produceState<String?>(null, roomId, isJoined) { value = deps.repository.roomBanner(roomId) }
    val ownProfile by deps.repository.ownProfile.collectAsState()
    val isOwnProfile = (ownProfile as? OwnProfileState.Linked)?.link?.roomId == roomId
    val isProfile = kind == SocialRoomKind.Profile || (kind == null && owner != null && preview == null)
    // Peeked pages (not following, world-readable room), like Haven Desktop: read-only until followed.
    val peekedPosts = remember(roomId) { androidx.compose.runtime.mutableStateListOf<FeedPost>() }
    var peekToken by remember(roomId) { mutableStateOf<String?>(null) }
    var peekExhausted by remember(roomId) { mutableStateOf(false) }
    val canPeek = !isJoined && preview?.isHistoryWorldReadable == true
    val joinedPosts by produceState(emptyList<FeedPost>(), roomId, isJoined) {
        if (isJoined) {
            // Like Haven Desktop's room page: top-level posts only.
            deps.timelineStore.posts(roomId).collect { list -> value = list.filter { !it.isThreadReply } }
        }
    }
    val posts = if (isJoined) joinedPosts else peekedPosts
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val name = roomInfo?.name ?: preview?.name ?: roomId.value
    val avatarUrl = roomInfo?.avatarUrl ?: preview?.avatarUrl
    val topic = roomInfo?.topic ?: preview?.topic
    val joinRule = roomInfo?.joinRule ?: preview?.joinRule
    val memberCount = roomInfo?.joinedMembersCount ?: preview?.numberOfJoinedMembers ?: 0L
    // MSC4501 "Following": a profile's own owner isn't counted as following themself.
    val followerCount = if (isProfile && isJoined) (memberCount - 1).coerceAtLeast(0) else memberCount
    val canPost = isJoined && (isOwnProfile || kind == SocialRoomKind.Group)

    fun membershipAction(action: suspend () -> Result<*>) {
        busy = true
        scope.launch {
            action().onFailure { error = it.message }
            deps.repository.refreshSocialRooms()
            busy = false
        }
    }

    val listState = rememberLazyListState()
    if (isJoined) PaginateOnScroll(listState, key = roomId) { deps.timelineStore.paginate(roomId) }
    if (canPeek) {
        PaginateOnScroll(listState, key = roomId to "peek") {
            if (peekExhausted) return@PaginateOnScroll false
            val page = deps.peek.roomPage(roomId, peekToken).getOrNull()
            if (page == null) {
                peekExhausted = true
                return@PaginateOnScroll false
            }
            peekedPosts.addAll(page.posts.filter { new -> peekedPosts.none { it.key == new.key } })
            peekToken = page.nextToken
            if (page.nextToken == null) peekExhausted = true
            page.nextToken != null
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column {
                    // Banner (MSC4221)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(ElementTheme.colors.bgSubtleSecondary),
                    ) {
                        banner?.let { url ->
                            AsyncImage(
                                model = MediaRequestData(MediaSource(url), MediaRequestData.Kind.Content),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(Modifier.offset(y = (-36).dp)) {
                            Avatar(
                                avatarData = AvatarData(roomId.value, name, avatarUrl, AvatarSize.RoomDetailsHeader),
                                avatarType = if (isProfile) AvatarType.User else AvatarType.Room(),
                                forcedAvatarSize = 80.dp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (isJoined) {
                            IconButton(onClick = { navigator.openRoomDetails(roomId) }) {
                                Icon(CompoundIcons.Settings(), contentDescription = stringResource(R.string.social_settings))
                            }
                        }
                        if (!isOwnProfile) {
                            MembershipButton(
                                membership = membership ?: preview?.membership,
                                joinRule = joinRule,
                                isProfile = isProfile,
                                busy = busy,
                                onJoin = { membershipAction { deps.matrixClient.joinRoomByIdOrAlias(roomId.toRoomIdOrAlias(), via) } },
                                // The stock knock screen (follow wording): it waits for the invite and accepts it automatically.
                                onKnock = { navigator.openFollow(roomId, null, via) },
                                onLeave = {
                                    membershipAction {
                                        deps.timelineStore.forget(roomId)
                                        deps.matrixClient.getRoom(roomId)?.use { it.leave() } ?: Result.success(Unit)
                                    }
                                },
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = 16.dp).offset(y = (-24).dp)) {
                        Text(name, style = ElementTheme.typography.fontHeadingMdBold, color = ElementTheme.colors.textPrimary)
                        val identifier = if (isProfile) owner else roomInfo?.canonicalAlias?.value ?: preview?.canonicalAlias?.value
                        identifier?.let {
                            Text(it, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
                        }
                        topic?.takeIf { it.isNotBlank() }?.let {
                            SocialTopicText(topic = it, deps = deps)
                        }
                        Text(
                            text = if (isProfile) {
                                pluralStringResource(R.plurals.social_followers, followerCount.toInt(), followerCount.toInt())
                            } else {
                                pluralStringResource(R.plurals.social_members, followerCount.toInt(), followerCount.toInt())
                            },
                            style = ElementTheme.typography.fontBodyMdMedium,
                            color = ElementTheme.colors.textActionAccent,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable(enabled = isJoined) { navigator.openRoomMembers(roomId) },
                        )
                        error?.let {
                            Text(it, color = ElementTheme.colors.textCriticalPrimary, style = ElementTheme.typography.fontBodySmRegular)
                        }
                    }
                    HorizontalDivider()
                }
            }
            if (!isJoined && !canPeek) {
                item(key = "not-joined") {
                    EmptyStateInline(
                        stringResource(
                            when {
                                joinRule == JoinRule.Invite -> R.string.social_private_room
                                else -> R.string.social_follow_to_see
                            }
                        )
                    )
                }
            } else if (posts.isEmpty()) {
                item(key = "empty") { EmptyStateInline(stringResource(R.string.social_no_posts)) }
            }
            items(posts, key = { it.key }) { post ->
                PostTile(post = post, deps = deps, callbacks = callbacks)
            }
        }
        if (canPost && interactions != null) {
            FloatingActionButton(
                onClick = { navigator.openComposer(ComposeMode.NewPost(roomId)) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(CompoundIcons.Compose(), contentDescription = stringResource(R.string.social_post))
            }
        }
    }
}

@Composable
private fun MembershipButton(
    membership: CurrentUserMembership?,
    joinRule: JoinRule?,
    isProfile: Boolean,
    busy: Boolean,
    onJoin: () -> Unit,
    onKnock: () -> Unit,
    onLeave: () -> Unit,
) {
    val follow = if (isProfile) R.string.social_follow else R.string.social_join
    when {
        membership == CurrentUserMembership.JOINED -> OutlinedButton(
            text = stringResource(if (isProfile) R.string.social_unfollow else R.string.social_leave),
            onClick = onLeave,
            showProgress = busy,
            size = ButtonSize.Medium,
        )
        membership == CurrentUserMembership.INVITED -> Button(
            text = stringResource(R.string.social_accept_invite),
            onClick = onJoin,
            showProgress = busy,
            size = ButtonSize.Medium,
        )
        membership == CurrentUserMembership.KNOCKED -> OutlinedButton(
            text = stringResource(R.string.social_request_sent),
            onClick = {},
            enabled = false,
            size = ButtonSize.Medium,
        )
        joinRule == JoinRule.Knock || joinRule is JoinRule.KnockRestricted -> Button(
            text = stringResource(if (isProfile) R.string.social_request_follow else R.string.social_request_join),
            onClick = onKnock,
            showProgress = busy,
            size = ButtonSize.Medium,
        )
        joinRule == JoinRule.Invite -> OutlinedButton(
            text = stringResource(R.string.social_private),
            onClick = {},
            enabled = false,
            size = ButtonSize.Medium,
        )
        else -> Button(text = stringResource(follow), onClick = onJoin, showProgress = busy, size = ButtonSize.Medium)
    }
}

@Composable
fun SocialRoomRow(
    roomId: String,
    name: String?,
    avatarUrl: String?,
    topic: String?,
    memberCount: Long,
    isProfile: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = AvatarData(roomId, name, avatarUrl, AvatarSize.RoomListItem),
            avatarType = if (isProfile) AvatarType.User else AvatarType.Room(),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name ?: roomId,
                style = ElementTheme.typography.fontBodyLgMedium,
                color = ElementTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            topic?.takeIf { it.isNotBlank() }?.let {
                Text(topicPlainText(it), style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = pluralStringResource(
                    if (isProfile) R.plurals.social_followers else R.plurals.social_members,
                    memberCount.toInt(),
                    memberCount.toInt(),
                ),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
fun EmptyStateInline(text: String) {
    Text(
        text = text,
        style = ElementTheme.typography.fontBodyLgRegular,
        color = ElementTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
