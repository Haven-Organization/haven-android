/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.messages.impl.haven

import android.content.Context
import android.os.Parcelable
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.bumble.appyx.core.lifecycle.subscribe
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.node
import com.bumble.appyx.core.plugin.Plugin
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.pop
import com.bumble.appyx.navmodel.backstack.operation.push
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.features.forward.api.ForwardEntryPoint
import io.element.android.features.messages.api.timeline.HtmlConverterProvider
import io.element.android.features.poll.api.actions.EndPollAction
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.UserEventPermissions
import io.element.android.features.messages.impl.actionlist.ActionListEvent
import io.element.android.features.messages.impl.actionlist.ActionListPresenter
import io.element.android.features.messages.impl.actionlist.ActionListView
import io.element.android.features.messages.impl.actionlist.model.TimelineItemAction
import io.element.android.features.messages.impl.actionlist.model.TimelineItemActionPostProcessor
import io.element.android.features.messages.impl.report.ReportMessageNode
import io.element.android.features.messages.impl.timeline.debug.EventDebugInfoNode
import io.element.android.features.messages.impl.timeline.factories.TimelineItemsFactory
import io.element.android.features.messages.impl.timeline.factories.TimelineItemsFactoryConfig
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemStateContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.features.messages.impl.timeline.model.event.captionOrNull
import io.element.android.features.messages.impl.userEventPermissions
import io.element.android.libraries.androidutils.clipboard.ClipboardHelper
import io.element.android.libraries.androidutils.toast.ToastHelper
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.architecture.createNode
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.powerlevels.permissionsAsState
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.item.TimelineItemDebugInfo
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

/**
 * Haven: Element X's message action sheet (the one a long press on a message opens) for any one event of the room, from
 * outside the room's screen: Social's posts. The stock actions are handled here (forward, report, view source, copy,
 * pin), the ones that belong to where the event is shown (replying, removing, reacting, viewing it in the room) by the
 * [Callback].
 *
 * It's meant to be shown over the screen it was opened from: the sheet has nothing behind it. It finishes ([Callback.onDone])
 * when the sheet is dismissed, or after its action, including any screen that action opened.
 */
@ContributesNode(RoomScope::class)
@AssistedInject
class HavenEventActionsNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    private val room: JoinedRoom,
    timelineItemsFactoryCreator: TimelineItemsFactory.Creator,
    actionListPresenterFactory: ActionListPresenter.Factory,
    private val forwardEntryPoint: ForwardEntryPoint,
    private val endPollAction: EndPollAction,
    private val htmlConverterProvider: HtmlConverterProvider,
    private val clipboardHelper: ClipboardHelper,
    private val toastHelper: ToastHelper,
    @ApplicationContext private val context: Context,
) : BaseFlowNode<HavenEventActionsNode.NavTarget>(
    backstack = BackStack(initialElement = NavTarget.Sheet, savedStateMap = buildContext.savedStateMap),
    buildContext = buildContext,
    plugins = plugins,
) {
    /** [canEdit]: whether "Edit" is offered (editing is done where the event is shown, see [Callback.onEdit]). */
    data class Inputs(val eventId: EventId, val canEdit: Boolean = false) : NodeInputs

    interface Callback : Plugin {
        /** Finished: the sheet was dismissed, or its action is done. */
        fun onDone()
        fun onReply()
        fun onEdit()
        fun onRemove()

        /** A poll was ended here. */
        fun onPollEnded()
        fun onToggleReaction(emoji: String)
        fun onCustomReaction()
        fun onViewInTimeline()
    }

    sealed interface NavTarget : Parcelable {
        @Parcelize
        data object Sheet : NavTarget

        @Parcelize
        data class EventDebugInfo(val eventId: EventId?, val debugInfo: TimelineItemDebugInfo) : NavTarget

        @Parcelize
        data class Forward(val eventId: EventId) : NavTarget

        @Parcelize
        data class Report(val eventId: EventId, val senderId: UserId) : NavTarget
    }

    private val inputs = inputs<Inputs>()
    private val callback: Callback = callback()

    private val timelineItemsFactory = timelineItemsFactoryCreator.create(
        TimelineItemsFactoryConfig(computeReadReceipts = false, computeReactions = true)
    )
    private val actionListPresenter = actionListPresenterFactory.create(
        postProcessor = PostActions(canEdit = inputs.canEdit),
        timelineMode = Timeline.Mode.FocusedOnEvent(inputs.eventId),
    )

    private var timeline: Timeline? = null
    private val event = MutableStateFlow<TimelineItem.Event?>(null)
    private var closed = false

    override fun onBuilt() {
        super.onBuilt()
        lifecycle.subscribe(onDestroy = { timeline?.close() })
        // Not there after a while (e.g. the server can't find it): nothing to show
        lifecycleScope.launch {
            delay(LOAD_TIMEOUT)
            if (event.value == null) {
                toastHelper.show(CommonStrings.common_error)
                close()
            }
        }
        // Back from a screen an action opened (forwarding, reporting, the source): done
        lifecycleScope.launch {
            var wentAway = false
            backstack.elements
                .map { elements -> elements.any { it.targetState == BackStack.State.ACTIVE && it.key.navTarget != NavTarget.Sheet } }
                .distinctUntilChanged()
                .collect { away ->
                    if (away) wentAway = true else if (wentAway) close()
                }
        }
    }

    private var loadStarted = false

    /**
     * Loads the event, as the room's timeline has it. Only once the HTML converter is set up ([View]): the timeline items
     * factory needs it for formatted messages.
     */
    private fun load() {
        if (loadStarted) return
        loadStarted = true
        lifecycleScope.launch {
            val focused = room.createTimeline(CreateTimelineParams.Focused(inputs.eventId)).getOrElse {
                Timber.w(it, "Couldn't load %s for its actions", inputs.eventId)
                toastHelper.show(CommonStrings.common_error)
                close()
                return@launch
            }
            timeline = focused
            launch { focused.timelineItems.collect { timelineItemsFactory.replaceWith(it) } }
            timelineItemsFactory.timelineItems.collect { items ->
                items.filterIsInstance<TimelineItem.Event>().firstOrNull { it.eventId == inputs.eventId }?.let { event.value = it }
            }
        }
    }

    private companion object {
        val LOAD_TIMEOUT = 15.seconds
    }

    private fun close() {
        if (closed) return
        closed = true
        callback.onDone()
    }

    override fun resolve(navTarget: NavTarget, buildContext: BuildContext): Node = when (navTarget) {
        NavTarget.Sheet -> node(buildContext) { modifier -> Sheet(modifier) }
        is NavTarget.EventDebugInfo -> createNode<EventDebugInfoNode>(buildContext, listOf(EventDebugInfoNode.Inputs(navTarget.eventId, navTarget.debugInfo)))
        is NavTarget.Report -> createNode<ReportMessageNode>(buildContext, listOf(ReportMessageNode.Inputs(navTarget.eventId, navTarget.senderId)))
        is NavTarget.Forward -> forwardEntryPoint.createNode(
            parentNode = this,
            buildContext = buildContext,
            params = ForwardEntryPoint.Params(eventIds = listOf(navTarget.eventId), fromRoomId = room.roomId),
            callback = object : ForwardEntryPoint.Callback {
                override fun onDone(roomIds: List<RoomId>) {
                    if (roomIds.isNotEmpty()) {
                        val text = context.resources.getQuantityString(R.plurals.screen_room_messages_forwarded, 1, 1)
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                    }
                    backstack.pop()
                }
            },
        )
    }

    @Composable
    private fun Sheet(modifier: Modifier) {
        val event by event.collectAsState()
        val permissions by room.permissionsAsState(UserEventPermissions.DEFAULT) { perms -> perms.userEventPermissions() }
        val state = actionListPresenter.present()
        var shown by remember { mutableStateOf(false) }
        var actionTaken by remember { mutableStateOf(false) }
        LaunchedEffect(event, permissions) {
            event?.let { state.eventSink(ActionListEvent.ComputeForMessage(it, permissions)) }
        }
        LaunchedEffect(state.target) {
            when (state.target) {
                is io.element.android.features.messages.impl.actionlist.ActionListState.Target.Success -> shown = true
                // Dismissed without an action (an action is set before the sheet is cleared)
                io.element.android.features.messages.impl.actionlist.ActionListState.Target.None -> if (shown && !actionTaken) close()
                else -> Unit
            }
        }
        ActionListView(
            state = state,
            onSelectAction = { action, target ->
                actionTaken = true
                handleAction(action, target)
            },
            onEmojiReactionClick = { emoji, _ ->
                actionTaken = true
                callback.onToggleReaction(emoji)
                close()
            },
            onCustomReactionClick = {
                actionTaken = true
                callback.onCustomReaction()
                close()
            },
            onVerifiedUserSendFailureClick = { actionTaken = true; close() },
            modifier = modifier,
        )
    }

    private fun handleAction(action: TimelineItemAction, target: TimelineItem.Event) {
        val eventId = target.eventId
        when (action) {
            TimelineItemAction.ViewSource -> return backstack.push(NavTarget.EventDebugInfo(eventId, target.debugInfo))
            TimelineItemAction.Forward -> if (eventId != null) return backstack.push(NavTarget.Forward(eventId))
            TimelineItemAction.ReportContent -> if (eventId != null) return backstack.push(NavTarget.Report(eventId, target.senderId))
            TimelineItemAction.Reply -> callback.onReply()
            TimelineItemAction.Edit -> callback.onEdit()
            TimelineItemAction.EndPoll -> return endPoll(eventId)
            TimelineItemAction.Redact -> callback.onRemove()
            TimelineItemAction.ViewInTimeline -> callback.onViewInTimeline()
            TimelineItemAction.CopyText -> copyText(target)
            TimelineItemAction.CopyCaption -> target.content.captionOrNull()?.let { copy(it, CommonStrings.common_copied_to_clipboard) }
            TimelineItemAction.CopyLink -> return copyLink(eventId)
            TimelineItemAction.Pin, TimelineItemAction.Unpin -> return pin(eventId, action == TimelineItemAction.Pin)
            else -> Unit
        }
        close()
    }

    private fun copyText(target: TimelineItem.Event) {
        val text = when (val content = target.content) {
            is TimelineItemTextBasedContent -> content.body
            is TimelineItemStateContent -> content.body
            else -> return
        }
        copy(text, CommonStrings.common_copied_to_clipboard)
    }

    private fun copy(text: String, confirmation: Int) {
        clipboardHelper.copyPlainText(text) { toastHelper.show(confirmation) }
    }

    private fun copyLink(eventId: EventId?) {
        if (eventId == null) return close()
        lifecycleScope.launch {
            room.getPermalinkFor(eventId)
                .onSuccess { copy(it, CommonStrings.common_link_copied_to_clipboard) }
                .onFailure {
                    Timber.w(it, "Couldn't get the link to %s", eventId)
                    toastHelper.show(CommonStrings.common_error)
                }
            close()
        }
    }

    private fun pin(eventId: EventId?, pin: Boolean) {
        val focused = timeline
        if (eventId == null || focused == null) return close()
        lifecycleScope.launch {
            (if (pin) focused.pinEvent(eventId) else focused.unpinEvent(eventId))
                .onFailure {
                    Timber.w(it, "Couldn't (un)pin %s", eventId)
                    toastHelper.show(CommonStrings.common_error)
                }
            close()
        }
    }

    private fun endPoll(eventId: EventId?) {
        val focused = timeline
        if (eventId == null || focused == null) return close()
        lifecycleScope.launch {
            endPollAction.execute(focused, eventId)
                .onSuccess { callback.onPollEnded() }
                .onFailure {
                    Timber.w(it, "Couldn't end the poll %s", eventId)
                    toastHelper.show(CommonStrings.common_error)
                }
            close()
        }
    }

    @Composable
    override fun View(modifier: Modifier) {
        // Like the room's screen: the HTML converter, for the event's formatted text, then the event
        htmlConverterProvider.Update()
        LaunchedEffect(Unit) { load() }
        BackstackView(modifier)
    }

    /**
     * The stock actions, without those that need the room's own screen (its composer: captions, replying in a thread,
     * editing polls) or don't apply (retrying or cancelling sending), with "View in timeline" after "Reply", and "Edit"
     * when the caller can edit the event ([canEdit]), whatever the SDK thinks.
     */
    private class PostActions(private val canEdit: Boolean) : TimelineItemActionPostProcessor {
        override fun process(actions: List<TimelineItemAction>): List<TimelineItemAction> {
            val kept = actions.filter { it !in HIDDEN }.toMutableList()
            val replyIndex = kept.indexOf(TimelineItemAction.Reply)
            kept.add(if (replyIndex >= 0) replyIndex + 1 else 0, TimelineItemAction.ViewInTimeline)
            if (canEdit) {
                // Where the stock list puts it: after forwarding, else after viewing it
                val after = kept.indexOf(TimelineItemAction.Forward).takeIf { it >= 0 } ?: kept.indexOf(TimelineItemAction.ViewInTimeline)
                kept.add(after + 1, TimelineItemAction.Edit)
            }
            return kept
        }

        private companion object {
            val HIDDEN = setOf(
                TimelineItemAction.ViewInTimeline,
                TimelineItemAction.Edit,
                TimelineItemAction.EditPoll,
                TimelineItemAction.EditCaption,
                TimelineItemAction.AddCaption,
                TimelineItemAction.RemoveCaption,
                TimelineItemAction.ReplyInThread,
                TimelineItemAction.RetrySending,
                TimelineItemAction.AbortSending,
            )
        }
    }
}
