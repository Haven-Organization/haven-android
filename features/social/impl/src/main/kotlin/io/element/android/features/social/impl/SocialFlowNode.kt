/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl

import android.os.Parcelable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.lifecycle.subscribe
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
import io.element.android.features.social.impl.ui.toGalleryInfo
import io.element.android.features.social.impl.ui.toGalleryItem
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.mediaviewer.api.GalleryInfo
import io.element.android.libraries.mediaviewer.api.GalleryItemData
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import androidx.lifecycle.lifecycleScope
import im.vector.app.features.analytics.plan.JoinedRoom
import io.element.android.features.joinroom.api.JoinRoomEntryPoint
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.room.CurrentUserMembership
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Optional

/** How many of the user's rooms [SocialFlowNode] tries when it needs any joined room. */
private const val MAX_ROOMS_TRIED = 5

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
    val dateFormatter: io.element.android.libraries.dateformatter.api.DateFormatter,
    val appPreferencesStore: io.element.android.libraries.preferences.api.store.AppPreferencesStore,
)

/** Navigation between Social screens, and out to stock Element X screens. */
interface SocialNavigator {
    fun back()
    fun openRoomPage(roomId: RoomId)
    fun openThread(roomId: RoomId, eventId: String, via: List<String> = emptyList())

    /** A post's event source (Settings' "View source" / developer mode). */
    fun openSource(roomId: RoomId, eventId: String)

    /** The post composer screen, for a new post, a reply or a quote post. */
    fun openComposer(mode: io.element.android.features.social.impl.ui.ComposeMode)

    /**
     * The stock join / knock screen, with "follow" wording for profiles and groups. Once joined (including after a
     * follow request is accepted, which the screen auto-accepts), continues to [eventId]'s post, or the room's page.
     */
    fun openFollow(roomId: RoomId, eventId: String?, via: List<String>)

    /**
     * Element X's full-screen media viewer for a post's image or video, or for [media] of the post [post] reposts, quotes
     * or replies to ([embeddedIn]); [onFailed] when it can't be opened.
     */
    fun openMedia(
        post: io.element.android.features.social.impl.data.FeedPost,
        media: io.element.android.features.social.impl.msc.PostMedia,
        embeddedIn: io.element.android.features.social.impl.msc.PostRelation? = null,
        onFailed: () -> Unit,
    )
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
    private val roomGraphFactory: io.element.android.appnav.di.RoomGraphFactory,
    @io.element.android.libraries.di.annotations.ApplicationContext private val context: android.content.Context,
    @io.element.android.libraries.di.annotations.SessionCoroutineScope private val sessionScope: kotlinx.coroutines.CoroutineScope,
    private val socialPermalinkResolver: io.element.android.features.social.api.SocialPermalinkResolver,
) : BaseFlowNode<SocialFlowNode.NavTarget>(
    backstack = BackStack(
        // Haven: a matrix.to link opens Social straight at that page or post (Back then returns to where the link was)
        initialElement = plugins.filterIsInstance<SocialEntryPoint.InitialTarget>().firstOrNull()?.let { target ->
            val eventId = target.eventId
            if (eventId != null) {
                NavTarget.Thread(target.roomId, eventId, target.via)
            } else {
                NavTarget.RoomPage(target.roomId, target.via)
            }
        } ?: NavTarget.Home,
        savedStateMap = buildContext.savedStateMap,
    ),
    buildContext = buildContext,
    plugins = plugins,
) {
    private val callback: SocialEntryPoint.Callback = callback()

    override fun onBuilt() {
        super.onBuilt()
        // The rooms' timelines stay open while Social is, and are closed once it's left (see SocialTimelineStore.release).
        lifecycle.subscribe(
            onCreate = { deps.timelineStore.acquire() },
            onDestroy = { deps.timelineStore.release() },
        )
    }

    private val navigator = object : SocialNavigator {
        override fun back() {
            if (backstack.elements.value.size > 1) backstack.pop() else navigateUp()
        }

        override fun openRoomPage(roomId: RoomId) = backstack.push(NavTarget.RoomPage(roomId))
        override fun openThread(roomId: RoomId, eventId: String, via: List<String>) = backstack.push(NavTarget.Thread(roomId, eventId, via))

        override fun openSource(roomId: RoomId, eventId: String) = backstack.push(NavTarget.Source(roomId, eventId))

        override fun openComposer(mode: io.element.android.features.social.impl.ui.ComposeMode) {
            val target = when (mode) {
                is io.element.android.features.social.impl.ui.ComposeMode.NewPost -> {
                    // The room to post to: the one given, else the user's profile, else their first group.
                    val ownProfile = deps.repository.ownProfile.value
                    val roomId = mode.defaultRoomId
                        ?: (ownProfile as? io.element.android.features.social.impl.data.OwnProfileState.Linked)?.link?.roomId
                        ?: io.element.android.features.social.impl.ui.postableRooms(deps.repository.socialRooms.value, ownProfile).firstOrNull()?.roomId
                        ?: return
                    NavTarget.Compose(ComposeKind.Post, roomId, null, null)
                }
                is io.element.android.features.social.impl.ui.ComposeMode.Reply -> {
                    val eventId = mode.parent.eventId ?: return
                    // The composer shows the post at once (see SocialTimelineStore.openedPost).
                    deps.timelineStore.rememberOpenedPost(mode.parent)
                    NavTarget.Compose(ComposeKind.Reply, mode.parent.roomId, mode.parent.roomId, eventId.value)
                }
                is io.element.android.features.social.impl.ui.ComposeMode.Quote -> {
                    val eventId = mode.original.eventId ?: return
                    // Quote posts go to the user's profile.
                    val profileRoomId = (deps.repository.ownProfile.value as? io.element.android.features.social.impl.data.OwnProfileState.Linked)
                        ?.link?.roomId ?: return
                    deps.timelineStore.rememberOpenedPost(mode.original)
                    NavTarget.Compose(ComposeKind.Quote, profileRoomId, mode.original.roomId, eventId.value)
                }
            }
            backstack.push(target)
        }

        override fun openFollow(roomId: RoomId, eventId: String?, via: List<String>) {
            val target = NavTarget.Follow(roomId, eventId, via)
            backstack.push(target)
            lifecycleScope.launch {
                deps.matrixClient.getRoomInfoFlow(roomId)
                    .first { it.orElse(null)?.currentUserMembership == CurrentUserMembership.JOINED }
                // Straight to the post (only if the follow screen is still the one showing); the room list catches up after.
                val elements = backstack.elements.value
                if (elements.lastOrNull()?.key?.navTarget == target) {
                    // Followed from that post or page itself: back to it (it goes live once joined) rather than a second copy,
                    // which Back would then return to.
                    val below = elements.getOrNull(elements.size - 2)?.key?.navTarget
                    val fromSameScreen = when (below) {
                        is NavTarget.Thread -> eventId != null && below.roomId == roomId && below.eventId == eventId
                        is NavTarget.RoomPage -> eventId == null && below.roomId == roomId
                        else -> false
                    }
                    if (fromSameScreen) {
                        backstack.pop()
                    } else {
                        backstack.replace(if (eventId != null) NavTarget.Thread(roomId, eventId, via) else NavTarget.RoomPage(roomId, via))
                    }
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
            embeddedIn: io.element.android.features.social.impl.msc.PostRelation?,
            onFailed: () -> Unit,
        ) {
            val item = media.toGalleryItem() ?: return onFailed()
            lifecycleScope.launch {
                // The viewer needs a room to run in. It shows just this media, with nothing that acts on the room, so for a
                // post in a room that isn't followed (a peeked post, or a repost's original) any joined room will do.
                val ownRoom = deps.timelineStore.joinedRoom(post.roomId)
                val room = ownRoom ?: deps.timelineStore.anyCachedJoinedRoom() ?: anyJoinedRoom()
                if (room == null) {
                    onFailed()
                    return@launch
                }
                backstack.push(
                    NavTarget.Media(
                        roomId = room.roomId,
                        // Only the post's own media in its own room: anything else isn't an event of the room the viewer is
                        // in, so the viewer's event actions mustn't act on it there
                        eventId = if (embeddedIn == null && ownRoom != null) post.eventId else null,
                        galleryInfo = if (embeddedIn == null) media.toGalleryInfo(post) else media.toGalleryInfo(embeddedIn),
                        item = item,
                    )
                )
            }
        }
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node = when (navTarget) {
        NavTarget.Home -> node(buildContext) { modifier ->
            SocialHomeScreen(deps = deps, navigator = navigator, onBack = ::navigateUp, modifier = modifier)
        }
        is NavTarget.RoomPage -> node(buildContext) { modifier ->
            SocialRoomScreen(deps = deps, navigator = navigator, roomId = navTarget.roomId, via = navTarget.via, modifier = modifier)
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
                            // Just this post's media: the room's media timeline doesn't have Social posts' media (or, for
                            // reposts, the original's), so the viewer would lose it
                            params = MediaViewerEntryPoint.Params.EventGallery(
                                eventId = navTarget.eventId,
                                galleryInfo = navTarget.galleryInfo,
                                galleryItems = listOf(navTarget.item),
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
        is NavTarget.Compose -> composerNode(navTarget, buildContext)
        is NavTarget.Source -> node(buildContext) { modifier ->
            io.element.android.features.social.impl.ui.PostSourceScreen(
                deps = deps,
                roomId = navTarget.roomId,
                eventId = navTarget.eventId,
                onBack = navigator::back,
                modifier = modifier,
            )
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
        // Haven: links tapped in Social (post bodies) that point to a profile or group room, or a post in one, open here,
        // like Haven Desktop; any other link opens as before.
        val platformUriHandler = LocalUriHandler.current
        val uriHandler = remember(platformUriHandler) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    if (!openSocialLink(uri, platformUriHandler)) platformUriHandler.openUri(uri)
                }
            }
        }
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            BackstackView()
        }
    }

    /** One of the user's joined rooms (opened in [SocialTimelineStore]), for a screen that needs some room to run in. */
    private suspend fun anyJoinedRoom(): io.element.android.libraries.matrix.api.room.JoinedRoom? {
        for (id in deps.httpApi.joinedRooms().getOrNull().orEmpty().take(MAX_ROOMS_TRIED)) {
            val roomId = runCatching { RoomId(id) }.getOrNull() ?: continue
            deps.timelineStore.joinedRoom(roomId)?.let { return it }
        }
        return null
    }

    /** Takes a link to a room (true), opening it in Social when it's a profile or group room, else as before. */
    private fun openSocialLink(uri: String, fallback: UriHandler): Boolean {
        val link = deps.permalinkParser.parse(uri) as? PermalinkData.RoomLink ?: return false
        lifecycleScope.launch {
            val roomId = socialPermalinkResolver.socialRoomId(link.roomIdOrAlias, link.viaParameters)
            when {
                roomId == null -> runCatching { fallback.openUri(uri) }
                link.eventId != null -> backstack.push(NavTarget.Thread(roomId, link.eventId!!.value, link.viaParameters))
                else -> backstack.push(NavTarget.RoomPage(roomId, link.viaParameters))
            }
        }
        return true
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Home : NavTarget

        @Parcelize
        data class RoomPage(val roomId: RoomId, val via: List<String> = emptyList()) : NavTarget

        @Parcelize
        data class Thread(val roomId: RoomId, val eventId: String, val via: List<String> = emptyList()) : NavTarget

        @Parcelize
        data class Media(
            val roomId: RoomId,
            val eventId: EventId?,
            val galleryInfo: GalleryInfo,
            val item: GalleryItemData,
        ) : NavTarget

        @Parcelize
        data class Follow(val roomId: RoomId, val eventId: String?, val via: List<String>) : NavTarget

        @Parcelize
        data class User(val userId: UserId) : NavTarget

        @Parcelize
        data class CreateRoom(val kind: SocialRoomKind) : NavTarget

        /** The post composer: [roomId] is the default room for a new post, else the replied-to / quoted post's room. */
        @Parcelize
        data class Source(val roomId: RoomId, val eventId: String) : NavTarget

        /**
         * The post composer, sending to [roomId]; [targetRoomId] / [targetEventId]: the post replied to or quoted.
         * [initialText]: kept when a new post's room is changed.
         */
        @Parcelize
        data class Compose(
            val kind: ComposeKind,
            val roomId: RoomId,
            val targetRoomId: RoomId?,
            val targetEventId: String?,
            val initialText: String? = null,
        ) : NavTarget
    }

    enum class ComposeKind { Post, Reply, Quote }

    /**
     * The post composer: Element X's message composer for [Compose.roomId] (HavenPostComposerNode, in that room's scope,
     * so it has everything a message has), with the replied-to / quoted post around it.
     */
    private fun composerNode(target: NavTarget.Compose, buildContext: BuildContext): Node {
        val targetRoomId = target.targetRoomId
        val targetEventId = target.targetEventId
        val known = if (targetRoomId != null && targetEventId != null) deps.timelineStore.openedPost(targetRoomId, targetEventId) else null
        val inputs = io.element.android.features.messages.impl.haven.HavenPostComposerNode.Inputs(
            title = context.getString(
                when (target.kind) {
                    ComposeKind.Post -> R.string.social_new_post
                    ComposeKind.Reply -> R.string.social_action_reply
                    ComposeKind.Quote -> R.string.social_quote_post
                }
            ),
            sendLabel = context.getString(if (target.kind == ComposeKind.Reply) R.string.social_action_reply else R.string.social_post),
            placeholder = context.getString(
                when (target.kind) {
                    ComposeKind.Post -> R.string.social_post_placeholder
                    ComposeKind.Reply -> R.string.social_reply_placeholder
                    ComposeKind.Quote -> R.string.social_quote_placeholder
                }
            ),
            // A reply goes in its post's thread (the thread's root, as the post may itself be a reply), answering the post.
            threadRootId = if (target.kind == ComposeKind.Reply && targetEventId != null) {
                io.element.android.libraries.matrix.api.core.ThreadId(known?.post?.threadRootId ?: targetEventId)
            } else {
                null
            },
            replyToEventId = if (target.kind == ComposeKind.Reply) targetEventId?.let(::EventId) else null,
            isQuote = target.kind == ComposeKind.Quote,
            initialText = target.initialText,
        )
        val callback = object : io.element.android.features.messages.impl.haven.HavenPostComposerNode.Callback {
            override fun onClose() {
                navigator.back()
            }

            override fun onTextSent(markdown: String) {
                // MSC4501 "Cross-posted reply": a copy of the reply on the user's profile, for their followers.
                if (target.kind != ComposeKind.Reply || targetRoomId == null || targetEventId == null) return
                sessionScope.launch {
                    val parent = known ?: deps.timelineStore.feedPost(targetRoomId, targetEventId)
                        ?: deps.peek.post(targetRoomId, targetEventId).getOrNull()
                        ?: return@launch
                    deps.actions.crossPostReply(parent, markdown)
                }
            }

            override suspend fun sendQuote(markdown: String, attachment: Pair<android.net.Uri, String>?): Result<Unit> {
                if (targetRoomId == null || targetEventId == null) return Result.failure(IllegalStateException("No post to quote"))
                val original = known ?: deps.timelineStore.feedPost(targetRoomId, targetEventId)
                    ?: deps.peek.post(targetRoomId, targetEventId).getOrNull()
                    ?: return Result.failure(IllegalStateException("Couldn't load the quoted post"))
                return deps.actions.publishQuote(
                    original,
                    markdown,
                    attachment?.let { (uri, mimeType) -> io.element.android.features.social.impl.data.PostAttachment(uri, mimeType) },
                )
            }

            @Composable
            override fun Header(currentText: () -> String) {
                when (target.kind) {
                    ComposeKind.Post -> io.element.android.features.social.impl.ui.NewPostComposerHeader(deps, target.roomId) { roomId ->
                        // Another room: the composer is the room's own, so it opens again there, with the text so far.
                        backstack.replace(target.copy(roomId = roomId, initialText = currentText()))
                    }
                    ComposeKind.Reply -> if (targetRoomId != null && targetEventId != null) {
                        io.element.android.features.social.impl.ui.ReplyComposerHeader(deps, targetRoomId, targetEventId)
                    }
                    ComposeKind.Quote -> Unit
                }
            }

            @Composable
            override fun Footer() {
                if (target.kind == ComposeKind.Quote && targetRoomId != null && targetEventId != null) {
                    io.element.android.features.social.impl.ui.QuoteComposerFooter(deps, targetRoomId, targetEventId)
                }
            }
        }
        return SocialRoomScopeNode(
            buildContext = buildContext,
            roomId = target.roomId,
            loadRoom = { deps.timelineStore.joinedRoom(it) },
            roomGraphFactory = roomGraphFactory,
            onUnavailable = { navigator.back() },
            child = { parent, childBuildContext ->
                parent.createNode<io.element.android.features.messages.impl.haven.HavenPostComposerNode>(childBuildContext, listOf(inputs, callback))
            },
        )
    }
}
