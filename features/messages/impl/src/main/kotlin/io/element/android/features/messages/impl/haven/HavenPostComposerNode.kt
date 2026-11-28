/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.messages.impl.haven

import io.element.android.libraries.emoji.api.imagepacks.CustomEmojiHtml
import io.element.android.libraries.emoji.api.imagepacks.ImagePackService
import io.element.android.libraries.matrix.api.HavenMarkdown
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import chat.schildi.lib.preferences.value
import chat.schildi.lib.preferences.ScPrefs
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.element.android.features.messages.impl.messagecomposer.rememberEmojiPickerKeyboardToggle
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.ui.strings.CommonStrings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.stringResource
import io.element.android.features.messages.impl.R
import android.net.Uri
import android.os.Parcelable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.push
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.location.api.ShareLocationEntryPoint
import io.element.android.features.messages.impl.MessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewNode
import io.element.android.features.messages.impl.messagecomposer.AttachmentsBottomSheet
import io.element.android.features.messages.impl.messagecomposer.ComposerEmojiPicker
import io.element.android.features.messages.impl.messagecomposer.ComposerEmojiPickerState
import io.element.android.features.messages.impl.messagecomposer.MessageComposerEvent
import io.element.android.features.messages.impl.messagecomposer.MessageComposerPresenter
import io.element.android.features.messages.impl.messagecomposer.MessageComposerView
import io.element.android.features.messages.impl.messagecomposer.rememberComposerEmojiPickerHeightPx
import io.element.android.features.messages.impl.messagecomposer.suggestions.SuggestionsPickerView
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.timeline.di.LiveTimeline
import io.element.android.features.messages.impl.voicemessages.composer.DefaultVoiceMessageComposerPresenter
import io.element.android.features.messages.impl.voicemessages.composer.VoiceMessagePermissionRationaleDialog
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.poll.api.create.CreatePollEntryPoint
import io.element.android.features.poll.api.create.CreatePollMode
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.meta.BuildMeta
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.theme.components.Button
import io.element.android.libraries.designsystem.theme.components.ButtonSize
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.timeline.MatrixTimelineItem
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.ui.messages.reply.map
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import chat.schildi.lib.preferences.ScPreferencesStore
import io.element.android.libraries.emoji.api.picker.EmojiPickerRenderer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize

/**
 * Haven: Social's post composer, for one room: Element X's own message composer (its presenter and view, so posts get
 * everything messages get: markdown / rich text, @mentions, emoji, slash commands, attachments with their preview,
 * voice messages, location and polls), on a full screen with a tall text box, the post being replied to or quoted
 * above / below it ([Callback.Header] / [Callback.Footer], drawn by Social).
 *
 * Created inside a room's scope (Social opens it in a room graph, like a room screen). Replies are sent in the post's
 * thread, as replies to it (MSC4501: `m.thread` to the root, `m.in_reply_to` the post). Quote posts need MSC4501's
 * `relates_to` on the event, which the stock sending can't add, so their text and attachment go to [Callback.sendQuote].
 * Chat drafts of the room are left alone.
 */
@ContributesNode(RoomScope::class)
@AssistedInject
class HavenPostComposerNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val room: JoinedRoom,
    @LiveTimeline private val liveTimeline: Timeline,
    private val messageComposerPresenterFactory: MessageComposerPresenter.Factory,
    private val voiceMessageComposerPresenterFactory: DefaultVoiceMessageComposerPresenter.Factory,
    private val shareLocationEntryPoint: ShareLocationEntryPoint,
    private val createPollEntryPoint: CreatePollEntryPoint,
    private val emojiPickerRenderer: EmojiPickerRenderer,
    private val permalinkParser: PermalinkParser,
    private val permalinkBuilder: PermalinkBuilder,
    private val scPreferencesStore: ScPreferencesStore,
    private val dispatchers: CoroutineDispatchers,
    private val buildMeta: BuildMeta,
    private val imagePackService: ImagePackService,
    private val havenMarkdown: HavenMarkdown,
) : BaseFlowNode<HavenPostComposerNode.NavTarget>(
    backstack = BackStack(initialElement = NavTarget.Composer, savedStateMap = buildContext.savedStateMap),
    buildContext = buildContext,
    plugins = plugins,
), MessagesNavigator {
    data class Inputs(
        val title: String,
        val sendLabel: String,
        val placeholder: String,
        /** Replies: the thread to send in (the post's thread root). */
        val threadRootId: ThreadId?,
        /** Replies: the post replied to. */
        val replyToEventId: EventId?,
        /** Quote posts: sent by [Callback.sendQuote] instead. */
        val isQuote: Boolean,
        /** Text to start with, e.g. when switching the room a new post goes to. */
        val initialText: String?,
        /** Haven: editing a post instead of sending a new one. */
        val edit: EditPost? = null,
    ) : NodeInputs

    /**
     * A post to edit, with its current [text]. A message is edited like one (the composer's edit mode); a post with its
     * own event type ([raw], which the SDK can't edit) by [Callback.sendEdit].
     */
    data class EditPost(val eventId: EventId, val text: String, val raw: Boolean)

    interface Callback : Plugin {
        fun onClose()

        /** A text post or reply was sent, with this text (Social cross-posts replies to the user's profile). */
        fun onTextSent(markdown: String, html: String?)

        /**
         * Sends a quote post (with MSC4501's `relates_to`), with the attachment picked for it, if any. [html]: the text's
         * HTML (its markdown formatting and custom emoji), when it has any.
         */
        suspend fun sendQuote(markdown: String, html: String?, attachment: Pair<Uri, String>?): Result<Unit>

        /** Sends the edit of a post [Inputs.edit] says is [EditPost.raw] ([html] as for [sendQuote]). */
        suspend fun sendEdit(markdown: String, html: String?): Result<Unit> = Result.failure(UnsupportedOperationException("Can't edit this post"))

        /** Above the text box: the post being replied to, or the room picker of a new post ([currentText] to keep). */
        @Composable
        fun Header(currentText: () -> String)

        /** Below the text box: the quoted post. */
        @Composable
        fun Footer()
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Composer : NavTarget

        @Parcelize
        data class AttachmentPreview(val attachments: ImmutableList<Attachment>, val timelineMode: Timeline.Mode, val inReplyToEventId: EventId?) : NavTarget

        @Parcelize
        data class SendLocation(val timelineMode: Timeline.Mode) : NavTarget

        @Parcelize
        data class CreatePoll(val timelineMode: Timeline.Mode) : NavTarget
    }

    private val inputs = inputs<Inputs>()
    private val callback = callback<Callback>()

    /** Sent by the [Callback] rather than the room: quote posts, and edits of posts the SDK can't edit. */
    private val sendsItself get() = inputs.isQuote || inputs.edit?.raw == true

    /** Only text: no attachments, voice messages, polls or locations (quote posts take one attachment, see below). */
    private val textOnly get() = inputs.isQuote || inputs.edit != null

    private var timeline: Timeline? by mutableStateOf(null)
    private var timelineController: TimelineController? by mutableStateOf(null)
    private var composerPresenter: MessageComposerPresenter? by mutableStateOf(null)
    private var voicePresenter: DefaultVoiceMessageComposerPresenter? by mutableStateOf(null)
    private var threadTimeline: Timeline? = null

    // Own messages in the timeline before an attachment preview / location / poll screen opened: if a new one shows up
    // once it's closed, the post was sent from there.
    private var ownItemsBeforeChild: Set<String>? = null

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(
            onCreate = {
                lifecycleScope.launch {
                    val timeline = inputs.threadRootId?.let { root ->
                        room.createTimeline(CreateTimelineParams.Threaded(threadRootEventId = root)).getOrNull()?.also { threadTimeline = it }
                    } ?: liveTimeline
                    val controller = TimelineController(
                        room,
                        liveTimeline = timeline,
                        roomCoroutineScope = room.roomCoroutineScope,
                        scPreferencesStore = scPreferencesStore,
                        dispatchers = dispatchers,
                    )
                    this@HavenPostComposerNode.timeline = timeline
                    timelineController = controller
                    voicePresenter = voiceMessageComposerPresenterFactory.create(timelineMode = controller.mainTimelineMode())
                    composerPresenter = messageComposerPresenterFactory.create(
                        timelineController = controller,
                        navigator = this@HavenPostComposerNode,
                        threadRoot = inputs.threadRootId.takeIf { threadTimeline != null },
                    ).apply { havenDraftsEnabled = false }
                }
            },
            onDestroy = {
                timelineController?.close()
                threadTimeline?.close()
            },
        )
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node = when (navTarget) {
        NavTarget.Composer -> node(buildContext) { modifier -> ComposerScreen(modifier) }
        is NavTarget.AttachmentPreview -> createNode<AttachmentsPreviewNode>(
            buildContext,
            listOf(AttachmentsPreviewNode.Inputs(navTarget.attachments, navTarget.timelineMode, navTarget.inReplyToEventId)),
        )
        is NavTarget.SendLocation -> shareLocationEntryPoint.createNode(this, buildContext, navTarget.timelineMode)
        is NavTarget.CreatePoll -> createPollEntryPoint.createNode(
            parentNode = this,
            buildContext = buildContext,
            params = CreatePollEntryPoint.Params(timelineMode = navTarget.timelineMode, mode = CreatePollMode.NewPoll),
        )
    }

    @Composable
    override fun View(modifier: Modifier) {
        BackstackView(modifier)
    }

    /**
     * The HTML of text the [Callback] sends ([sendsItself]), like a message's: its custom emoji `:shortcode:`s as their
     * images, and its markdown formatting; null when it has neither.
     */
    private suspend fun htmlOf(markdown: String): String? {
        val shortcodes = runCatching { imagePackService.emoticonShortcodes(room.roomId) }.getOrDefault(emptyMap())
        return CustomEmojiHtml.htmlFromMarkdown(markdown, shortcodes, havenMarkdown)
            ?: runCatching { havenMarkdown.toHtml(markdown) }.getOrNull()
    }

    private fun currentMode(): Timeline.Mode = timelineController?.mainTimelineMode() ?: Timeline.Mode.Live

    private suspend fun ownItemIds(): Set<String> =
        timeline?.timelineItems?.first()?.ownItemIds().orEmpty()

    private fun List<MatrixTimelineItem>.ownItemIds(): Set<String> =
        filterIsInstance<MatrixTimelineItem.Event>().filter { it.event.isOwn }.mapTo(HashSet()) { it.uniqueId.value }

    private fun pushChild(target: NavTarget) {
        lifecycleScope.launch {
            ownItemsBeforeChild = ownItemIds()
            backstack.push(target)
        }
    }

    // The composer's one-time setup (reply mode, starting text, focus) is done: not again when coming back from an
    // attachment preview, location or poll screen.
    private var composerSetUp = false

    // Replies: the post's details for the composer's reply mode, loaded once.
    private var replyDetails: io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails? = null

    // Quote posts: the attachment picked for it (sent along by Social).
    private var quoteAttachment: Pair<Uri, String>? by mutableStateOf(null)

    // MessagesNavigator: the composer's own navigation.
    override fun navigateToPreviewAttachments(attachments: ImmutableList<Attachment>, inReplyToEventId: EventId?) {
        if (inputs.edit != null) return
        if (inputs.isQuote) {
            // One attachment, sent by Social with the quote post.
            val media = attachments.firstNotNullOfOrNull { it as? Attachment.Media } ?: return
            quoteAttachment = media.localMedia.uri to media.localMedia.info.mimeType
            return
        }
        pushChild(NavTarget.AttachmentPreview(attachments, currentMode(), inReplyToEventId))
    }

    override fun navigateToEventDebugInfo(eventId: EventId?, debugInfo: io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo) = Unit
    override fun forwardEvents(eventIds: List<EventId>) = Unit
    override fun navigateToReportMessage(eventId: EventId, senderId: io.element.android.libraries.matrix.api.core.UserId) = Unit
    override fun navigateToEditPoll(eventId: EventId) = Unit
    override fun navigateToRoom(roomId: io.element.android.libraries.matrix.api.core.RoomId, eventId: EventId?, serverNames: List<String>) = Unit
    override fun navigateToMember(userId: io.element.android.libraries.matrix.api.core.UserId) = Unit
    override fun navigateToThread(threadRootId: ThreadId, focusedEventId: EventId?) = Unit
    override fun navigateToDeveloperSettings() = Unit
    override fun navigateToCurrentLiveLocation() = Unit
    override fun close() = callback.onClose()

    @Composable
    private fun ComposerScreen(modifier: Modifier) {
        val presenter = composerPresenter
        val voice = voicePresenter
        if (presenter == null || voice == null) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        val baseState = presenter.present()
        val voiceState = voice.present()
        val scope = rememberCoroutineScope()
        val latestState by rememberUpdatedState(baseState)

        // Back from an attachment preview, location or poll screen: close if it sent something.
        LaunchedEffect(Unit) {
            val before = ownItemsBeforeChild ?: return@LaunchedEffect
            ownItemsBeforeChild = null
            val sent = withTimeoutOrNull(2_000) { timeline?.timelineItems?.first { items -> (items.ownItemIds() - before).isNotEmpty() } }
            if (sent != null) callback.onClose()
        }

        LaunchedEffect(Unit) {
            if (composerSetUp) return@LaunchedEffect
            composerSetUp = true
            val edit = inputs.edit
            when {
                // The composer's edit mode puts the text in
                edit != null && !edit.raw -> baseState.eventSink(
                    MessageComposerEvent.SetMode(MessageComposerMode.Edit(edit.eventId.toEventOrTransactionId(), edit.text))
                )
                edit != null -> baseState.textEditorState.setMarkdown(edit.text)
                else -> inputs.initialText?.takeIf { it.isNotEmpty() }?.let { baseState.textEditorState.setMarkdown(it) }
            }
            baseState.textEditorState.requestFocus()
        }
        // Replies: always answering the post (the composer's reply mode, as for a message in the thread; its "replying
        // to" chip isn't shown, the post is shown above already). The stock composer leaves reply mode once something is
        // attached (a message on its own in chat), so it's put back whenever it's gone.
        LaunchedEffect(baseState.mode) {
            val replyTo = inputs.replyToEventId ?: return@LaunchedEffect
            if (baseState.mode !is MessageComposerMode.Normal) return@LaunchedEffect
            timelineController?.invokeOnCurrentTimeline {
                val details = replyDetails ?: loadReplyDetails(replyTo).map(permalinkParser).also { replyDetails = it }
                latestState.eventSink(MessageComposerEvent.SetMode(MessageComposerMode.Reply(replyToDetails = details, hideImage = false)))
            }
        }

        fun markdown(state: TextEditorState) = state.messageMarkdown(permalinkBuilder)
        var sendingText by remember { mutableStateOf<String?>(null) }
        // Edits: leaving the edit mode (its close button) is leaving the edit
        var wasEditing by remember { mutableStateOf(false) }
        LaunchedEffect(baseState.mode) {
            if (inputs.edit == null || inputs.edit?.raw == true) return@LaunchedEffect
            if (baseState.mode is MessageComposerMode.Edit) wasEditing = true else if (wasEditing && sendingText == null) callback.onClose()
        }
        var quoteSending by remember { mutableStateOf(false) }
        var quoteError by remember { mutableStateOf<String?>(null) }

        // A text post / reply was handed to the room's send queue once the composer has been cleared.
        LaunchedEffect(sendingText) {
            val text = sendingText ?: return@LaunchedEffect
            snapshotFlow { markdown(latestState.textEditorState).isBlank() }.first { it }
            callback.onTextSent(text, htmlOf(text))
            callback.onClose()
        }
        // A voice message: sent once its preview is gone.
        var voiceSent by remember { mutableStateOf(false) }
        LaunchedEffect(voiceState.voiceMessageState) {
            val voiceMessage = voiceState.voiceMessageState
            if (voiceMessage is VoiceMessageState.Preview && voiceMessage.isSending) voiceSent = true
            if (voiceMessage is VoiceMessageState.Idle && voiceSent) callback.onClose()
        }

        fun sendQuote() {
            if (quoteSending) return
            quoteSending = true
            scope.launch {
                val text = markdown(latestState.textEditorState).trim()
                val html = htmlOf(text)
                val result = if (inputs.edit != null) callback.sendEdit(text, html) else callback.sendQuote(text, html, quoteAttachment)
                quoteSending = false
                result.fold(onSuccess = { callback.onClose() }, onFailure = { quoteError = it.message ?: it.toString() })
            }
        }
        // Quote posts: sending goes to Social (with MSC4501's relates_to) instead of the room; so do edits of posts the
        // SDK can't edit.
        val state = if (sendsItself) {
            baseState.copy(eventSink = { event -> if (event is MessageComposerEvent.SendMessage) sendQuote() else baseState.eventSink(event) })
        } else {
            baseState
        }
        val hasText = markdown(state.textEditorState).isNotBlank()
        val canSend = !quoteSending && sendingText == null && (hasText || (inputs.isQuote && quoteAttachment != null))
        // Reads everything from state when tapped, never from values captured in a composition: the click handler can be
        // a copy from an earlier frame (e.g. the first one, with the text box still empty), which would then do nothing.
        fun send() {
            val current = latestState
            val text = markdown(current.textEditorState).trim()
            if (quoteSending || sendingText != null) return
            if (sendsItself) {
                if (text.isNotEmpty() || quoteAttachment != null) sendQuote()
            } else if (text.isNotEmpty()) {
                sendingText = text
                current.eventSink(MessageComposerEvent.SendMessage)
            }
        }

        val density = LocalDensity.current
        val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
        val emojiPickerHeightPx = rememberComposerEmojiPickerHeightPx(
            isShown = state.emojiPickerState != ComposerEmojiPickerState.Hidden,
            availableHeightPx = screenHeightPx,
        )

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    titleStr = inputs.title,
                    navigationIcon = { BackButton(onClick = callback::onClose, imageVector = CompoundIcons.Close()) },
                    actions = {
                        Button(
                            text = inputs.sendLabel,
                            enabled = canSend,
                            showProgress = quoteSending || sendingText != null,
                            size = ButtonSize.Small,
                            onClick = ::send,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding()
                    .fillMaxSize(),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    callback.Header { markdown(latestState.textEditorState) }
                    MessageComposerView(
                        state = state,
                        voiceMessageState = voiceState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        scBeforeSend = { if (!sendsItself) sendingText = markdown(state.textEditorState).trim() },
                        minInputHeight = 180.dp,
                        // Sending is the top bar's button; voice messages and emoji are on the shelf below.
                        showEndButton = false,
                        showEmojiButton = false,
                        placeholderOverride = inputs.placeholder,
                        // The "+" is on the shelf below instead, so the text box takes the whole width.
                        showAttachmentButton = false,
                        // The replied-to post is shown above.
                        showReplyPreview = false,
                    )
                    AttachmentShelf(
                        onToggleEmoji = rememberEmojiPickerKeyboardToggle(state),
                        isEmojiPickerShown = state.emojiPickerState != ComposerEmojiPickerState.Hidden && !state.isStickerPickerShown,
                        // Haven: stickers from image packs, left of the emoji button
                        onToggleSticker = rememberEmojiPickerKeyboardToggle(state, stickers = true)
                            .takeIf { !ScPrefs.HAVEN_DISABLE_CUSTOM_EMOJI_PICKER.value() },
                        isStickerPickerShown = state.isStickerPickerShown,
                        // A quote post can't be a voice message (it couldn't carry the quote).
                        voiceMessageState = voiceState.voiceMessageState.takeIf { !textOnly },
                        onVoiceRecorderEvent = { voiceState.eventSink(VoiceMessageComposerEvent.RecorderEvent(it)) },
                        // Not leaving reply mode afterwards (unlike the stock button): this composer only answers its post.
                        onSendVoiceMessage = { voiceState.eventSink(VoiceMessageComposerEvent.SendVoiceMessage) },
                        onAddAttachment = { state.eventSink(MessageComposerEvent.AddAttachment) },
                        onPickFromGallery = { state.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery) },
                        // A quote post can't be a poll (it couldn't carry the quote).
                        onCreatePoll = if (textOnly) {
                            null
                        } else {
                            {
                                state.eventSink(MessageComposerEvent.PickAttachmentSource.Poll)
                                pushChild(NavTarget.CreatePoll(currentMode()))
                            }
                        },
                    )
                    quoteAttachment?.let { (uri, mimeType) ->
                        QuoteAttachmentPreview(uri, mimeType, onRemove = { quoteAttachment = null })
                    }
                    callback.Footer()
                    quoteError?.let { message ->
                        Text(
                            text = message,
                            color = ElementTheme.colors.textCriticalPrimary,
                            style = ElementTheme.typography.fontBodySmRegular,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (state.suggestions.isNotEmpty()) {
                    SuggestionsPickerView(
                        modifier = Modifier
                            .background(ElementTheme.colors.bgCanvasDefault)
                            .heightIn(max = 230.dp),
                        roomId = room.roomId,
                        roomName = room.info().name,
                        roomAvatarData = AvatarData(room.roomId.value, room.info().name, room.info().avatarUrl, AvatarSize.TimelineRoom),
                        suggestions = state.suggestions,
                        onSelectSuggestion = { state.eventSink(MessageComposerEvent.InsertSuggestion(it)) },
                    )
                }
                ComposerEmojiPicker(
                    state = state,
                    emojiPickerRenderer = emojiPickerRenderer,
                    height = with(density) { emojiPickerHeightPx.toDp() },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }

        AttachmentsBottomSheet(
            state = state,
            onSendLocationClick = { pushChild(NavTarget.SendLocation(currentMode())) },
            onCreatePollClick = { pushChild(NavTarget.CreatePoll(currentMode())) },
            enableTextFormatting = true,
            // On the shelf instead.
            showGallery = false,
            showPoll = false,
            showLocation = !textOnly,
        )
        if (voiceState.showPermissionRationaleDialog) {
            VoiceMessagePermissionRationaleDialog(
                onContinue = { voiceState.eventSink(VoiceMessageComposerEvent.AcceptPermissionRationale) },
                onDismiss = { voiceState.eventSink(VoiceMessageComposerEvent.DismissPermissionsRationale) },
                appName = buildMeta.applicationName,
            )
        }
    }
}

@Composable
private fun QuoteAttachmentPreview(uri: Uri, mimeType: String, onRemove: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .padding(16.dp),
            ) {
                Icon(CompoundIcons.Attachment(), contentDescription = null, tint = ElementTheme.colors.iconSecondary)
                Text(uri.lastPathSegment.orEmpty(), modifier = Modifier.padding(start = 12.dp, end = 40.dp))
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(36.dp),
        ) {
            Icon(CompoundIcons.Close(), contentDescription = null)
        }
    }
}

/**
 * Below the text box: the "+" (the message composer's attachment menu), the photo / video library and poll (taken out
 * of that menu to be one tap away), and the composer's emoji and voice message buttons, moved here so the text box
 * takes the whole width. The voice button records, stops, then sends, like the composer's own.
 */
@Composable
private fun AttachmentShelf(
    onToggleEmoji: () -> Unit,
    isEmojiPickerShown: Boolean,
    onToggleSticker: (() -> Unit)?,
    isStickerPickerShown: Boolean,
    voiceMessageState: VoiceMessageState?,
    onVoiceRecorderEvent: (VoiceMessageRecorderEvent) -> Unit,
    onSendVoiceMessage: () -> Unit,
    onAddAttachment: () -> Unit,
    onPickFromGallery: () -> Unit,
    onCreatePoll: (() -> Unit)?,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        IconButton(onClick = onAddAttachment, modifier = Modifier.size(48.dp)) {
            Icon(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(30.dp)
                    .background(ElementTheme.colors.iconPrimary)
                    .padding(3.dp),
                imageVector = CompoundIcons.Plus(),
                contentDescription = stringResource(io.element.android.libraries.textcomposer.R.string.rich_text_editor_a11y_add_attachment),
                tint = ElementTheme.colors.iconOnSolidPrimary,
            )
        }
        IconButton(onClick = onPickFromGallery, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = CompoundIcons.Image(),
                contentDescription = stringResource(R.string.screen_room_attachment_source_gallery),
                tint = ElementTheme.colors.iconSecondary,
            )
        }
        if (onCreatePoll != null) {
            IconButton(onClick = onCreatePoll, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = CompoundIcons.Polls(),
                    contentDescription = stringResource(R.string.screen_room_attachment_source_poll),
                    tint = ElementTheme.colors.iconSecondary,
                )
            }
        }
        if (onToggleSticker != null) {
            IconButton(onClick = onToggleSticker, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = CompoundIcons.Sticker(),
                    contentDescription = stringResource(chat.schildi.lib.R.string.haven_send_sticker),
                    tint = if (isStickerPickerShown) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
                    modifier = if (isStickerPickerShown) {
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ElementTheme.colors.bgAccentSelected)
                            .padding(8.dp)
                    } else {
                        Modifier
                    },
                )
            }
        }
        // Highlighted while the emoji panel replaces the keyboard: tapping it again brings the keyboard back.
        IconButton(onClick = onToggleEmoji, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = CompoundIcons.ReactionAdd(),
                contentDescription = stringResource(CommonStrings.a11y_react_with_other_emojis),
                tint = if (isEmojiPickerShown) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary,
                modifier = if (isEmojiPickerShown) {
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ElementTheme.colors.bgAccentSelected)
                        .padding(8.dp)
                } else {
                    Modifier
                },
            )
        }
        Spacer(Modifier.weight(1f))
        when (voiceMessageState) {
            null -> Unit
            VoiceMessageState.Idle -> IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onVoiceRecorderEvent(VoiceMessageRecorderEvent.Start)
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(CompoundIcons.MicOnSolid(), contentDescription = stringResource(CommonStrings.a11y_voice_message_record), tint = ElementTheme.colors.iconSecondary)
            }
            is VoiceMessageState.Recording -> IconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onVoiceRecorderEvent(VoiceMessageRecorderEvent.Stop)
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = CompoundIcons.StopSolid(),
                    contentDescription = stringResource(CommonStrings.a11y_voice_message_stop_recording),
                    tint = ElementTheme.colors.iconCriticalPrimary,
                )
            }
            is VoiceMessageState.Preview -> if (voiceMessageState.isSending) {
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
            } else {
                IconButton(onClick = onSendVoiceMessage, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = CompoundIcons.SendSolid(),
                        contentDescription = stringResource(CommonStrings.action_send_voice_message),
                        tint = ElementTheme.colors.iconAccentPrimary,
                    )
                }
            }
        }
    }
}
