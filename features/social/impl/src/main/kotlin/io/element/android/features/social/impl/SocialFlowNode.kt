/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import com.bumble.appyx.navmodel.backstack.operation.replace
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.Inject
import io.element.android.annotations.ContributesNode
import io.element.android.features.social.api.SocialEntryPoint
import io.element.android.features.social.impl.data.SocialActions
import io.element.android.features.social.impl.data.SocialHttpApi
import io.element.android.features.social.impl.data.SocialRepository
import io.element.android.features.social.impl.data.SocialRoomKind
import io.element.android.features.social.impl.data.SocialTimelineStore
import io.element.android.features.social.impl.ui.CreateSocialRoomScreen
import io.element.android.features.social.impl.ui.SocialHomeScreen
import io.element.android.features.social.impl.ui.SocialRoomScreen
import io.element.android.features.social.impl.ui.SocialThreadScreen
import io.element.android.features.social.impl.ui.SocialUserScreen
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.emoji.api.picker.EmojiPickerPresenter
import io.element.android.libraries.emoji.api.picker.EmojiPickerRenderer
import io.element.android.libraries.emoji.api.recentemojis.GetRecentEmojis
import io.element.android.libraries.htmlrenderer.api.HtmlMessageParser
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import kotlinx.parcelize.Parcelize
import androidx.compose.runtime.LaunchedEffect
import io.element.android.features.social.impl.ui.toMediaInfo
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaviewer.api.MediaInfo
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import androidx.lifecycle.lifecycleScope
import im.vector.app.features.analytics.plan.JoinedRoom
import io.element.android.features.joinroom.api.JoinRoomEntryPoint
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Optional

/** Everything the Social screens use, injected once into [SocialFlowNode]. */
@Inject
class SocialDeps(
    val matrixClient: MatrixClient,
    val repository: SocialRepository,
    val timelineStore: SocialTimelineStore,
    val actions: SocialActions,
    val httpApi: SocialHttpApi,
    val peek: io.element.android.features.social.impl.data.SocialPeek,
    val emojiPickerPresenterFactory: EmojiPickerPresenter.Factory,
    val getRecentEmojis: GetRecentEmojis,
    val emojiPickerRenderer: EmojiPickerRenderer,
    val htmlMessageParser: HtmlMessageParser,
    val permalinkParser: PermalinkParser,
)

/** Navigation between Social screens, and out to stock Element X screens. */
interface SocialNavigator {
    fun back()
    fun openRoomPage(roomId: RoomId)
    fun openThread(roomId: RoomId, eventId: String, via: List<String> = emptyList())

    /**
     * The stock join / knock screen, with "follow" wording for profiles and groups. Once joined (including after a
     * follow request is accepted, which the screen auto-accepts), continues to [eventId]'s post, or the room's page.
     */
    fun openFollow(roomId: RoomId, eventId: String?, via: List<String>)

    /** Element X's full-screen media viewer for a post's image or video (posts in followed rooms). */
    fun openMedia(post: io.element.android.features.social.impl.data.FeedPost, media: io.element.android.features.social.impl.msc.PostMedia): Boolean
    fun openUser(userId: UserId)
    fun createRoom(kind: SocialRoomKind)
    fun openRoomDetails(roomId: RoomId)
    fun openRoomMembers(roomId: RoomId)
    fun openRoomTimeline(roomId: RoomId)

    /** The stock user profile screen. */
    fun openUserDetails(userId: UserId)
}

@ContributesNode(SessionScope::class)
@AssistedInject
class SocialFlowNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val deps: SocialDeps,
    private val joinRoomEntryPoint: JoinRoomEntryPoint,
) : BaseFlowNode<SocialFlowNode.NavTarget>(
    backstack = BackStack(
        initialElement = NavTarget.Home,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins,
) {
    private val callback: SocialEntryPoint.Callback = callback()

    private val navigator = object : SocialNavigator {
        override fun back() {
            if (backstack.elements.value.size > 1) backstack.pop() else navigateUp()
        }

        override fun openRoomPage(roomId: RoomId) = backstack.push(NavTarget.RoomPage(roomId))
        override fun openThread(roomId: RoomId, eventId: String, via: List<String>) = backstack.push(NavTarget.Thread(roomId, eventId, via))

        override fun openFollow(roomId: RoomId, eventId: String?, via: List<String>) {
            val target = NavTarget.Follow(roomId, eventId, via)
            backstack.push(target)
            lifecycleScope.launch {
                deps.matrixClient.getRoomInfoFlow(roomId)
                    .first { it.orElse(null)?.currentUserMembership == CurrentUserMembership.JOINED }
                // Straight to the post (only if the follow screen is still the one showing); the room list catches up after.
                if (backstack.elements.value.lastOrNull()?.key?.navTarget == target) {
                    backstack.replace(if (eventId != null) NavTarget.Thread(roomId, eventId, via) else NavTarget.RoomPage(roomId))
                }
                deps.repository.refreshSocialRooms()
            }
        }
        override fun openUser(userId: UserId) = backstack.push(NavTarget.User(userId))
        override fun createRoom(kind: SocialRoomKind) = backstack.push(NavTarget.CreateRoom(kind))
        override fun openRoomDetails(roomId: RoomId) = callback.navigateToRoomDetails(roomId)
        override fun openRoomMembers(roomId: RoomId) = callback.navigateToRoomMembers(roomId)
        override fun openRoomTimeline(roomId: RoomId) = callback.navigateToRoom(roomId)
        override fun openUserDetails(userId: UserId) = callback.navigateToUser(userId)

        override fun openMedia(
            post: io.element.android.features.social.impl.data.FeedPost,
            media: io.element.android.features.social.impl.msc.PostMedia,
        ): Boolean {
            val url = media.url ?: return false
            if (deps.timelineStore.cachedJoinedRoom(post.roomId) == null) return false
            backstack.push(
                NavTarget.Media(
                    roomId = post.roomId,
                    eventId = post.eventId,
                    mediaInfo = media.toMediaInfo(post),
                    mediaSource = MediaSource(url, media.encryptedFileJson),
                    thumbnailSource = (media.thumbnailUrl ?: media.thumbnailFileJson?.let { json ->
                        runCatching { org.json.JSONObject(json).optString("url") }.getOrNull()
                    })?.let { MediaSource(it, media.thumbnailFileJson) },
                    blurHash = media.blurhash,
                )
            )
            return true
        }
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node = when (navTarget) {
        NavTarget.Home -> node(buildContext) { modifier ->
            SocialHomeScreen(deps = deps, navigator = navigator, onBack = ::navigateUp, modifier = modifier)
        }
        is NavTarget.RoomPage -> node(buildContext) { modifier ->
            SocialRoomScreen(deps = deps, navigator = navigator, roomId = navTarget.roomId, modifier = modifier)
        }
        is NavTarget.Thread -> node(buildContext) { modifier ->
            SocialThreadScreen(deps = deps, navigator = navigator, roomId = navTarget.roomId, eventId = navTarget.eventId, via = navTarget.via, modifier = modifier)
        }
        is NavTarget.Media -> {
            val room = deps.timelineStore.cachedJoinedRoom(navTarget.roomId)
            if (room == null) {
                node(buildContext) { LaunchedEffect(Unit) { backstack.pop() } }
            } else {
                createNode<SocialMediaViewerNode>(
                    buildContext = buildContext,
                    plugins = listOf(
                        SocialMediaViewerNode.Inputs(
                            joinedRoom = room,
                            params = MediaViewerEntryPoint.Params.RoomMedia(
                                mode = MediaViewerEntryPoint.MediaViewerMode.TimelineImagesAndVideos(Timeline.Mode.Live),
                                eventId = navTarget.eventId,
                                mediaInfo = navTarget.mediaInfo,
                                mediaSource = navTarget.mediaSource,
                                thumbnailSource = navTarget.thumbnailSource,
                                blurHash = navTarget.blurHash,
                            ),
                            onDone = { backstack.pop() },
                        )
                    ),
                )
            }
        }
        is NavTarget.Follow -> joinRoomEntryPoint.createNode(
            parentNode = this,
            buildContext = buildContext,
            inputs = JoinRoomEntryPoint.Inputs(
                roomId = navTarget.roomId,
                roomIdOrAlias = navTarget.roomId.toRoomIdOrAlias(),
                roomDescription = Optional.empty(),
                serverNames = navTarget.via,
                trigger = JoinedRoom.Trigger.MobilePermalink,
            ),
        )
        is NavTarget.User -> node(buildContext) { modifier ->
            SocialUserScreen(deps = deps, navigator = navigator, userId = navTarget.userId, modifier = modifier)
        }
        is NavTarget.CreateRoom -> node(buildContext) { modifier ->
            CreateSocialRoomScreen(
                deps = deps,
                kind = navTarget.kind,
                onBack = navigator::back,
                onCreated = { roomId -> backstack.replace(NavTarget.RoomPage(roomId)) },
                modifier = modifier,
            )
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        BackstackView()
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Home : NavTarget

        @Parcelize
        data class RoomPage(val roomId: RoomId) : NavTarget

        @Parcelize
        data class Thread(val roomId: RoomId, val eventId: String, val via: List<String> = emptyList()) : NavTarget

        @Parcelize
        data class Media(
            val roomId: RoomId,
            val eventId: EventId?,
            val mediaInfo: MediaInfo,
            val mediaSource: MediaSource,
            val thumbnailSource: MediaSource?,
            val blurHash: String?,
        ) : NavTarget

        @Parcelize
        data class Follow(val roomId: RoomId, val eventId: String?, val via: List<String>) : NavTarget

        @Parcelize
        data class User(val userId: UserId) : NavTarget

        @Parcelize
        data class CreateRoom(val kind: SocialRoomKind) : NavTarget
    }
}
