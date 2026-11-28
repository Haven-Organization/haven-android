/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.poll.api.pollcontent.PollAnswerItem
import io.element.android.features.poll.api.pollcontent.PollContentState
import io.element.android.features.social.impl.msc.PollStart
import io.element.android.features.social.impl.msc.SocialPollParser
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.poll.isDisclosed
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * The live state of polls shown as posts, per (room, poll start event), for Element X's poll card.
 *
 * Built from raw data only, because posts come from several sources (live timelines, feed sync, peeks, the cache): the
 * question and answers from the post's content ([io.element.android.features.social.impl.msc.PostContent.poll]), the
 * votes and the end from the server's `m.reference` relations of the poll start event. Encrypted responses can't be read
 * this way and aren't counted.
 *
 * Votes and ends are sent through the room's live SDK timeline (they don't need the poll to be loaded in it); since the
 * SDK queues them, the state shows them at once ("pending") until the server's relations include them.
 */
@SingleIn(SessionScope::class)
@Inject
class SocialPolls(
    private val matrixClient: MatrixClient,
    private val httpApi: SocialHttpApi,
    private val timelineStore: SocialTimelineStore,
    @SessionCoroutineScope private val sessionScope: CoroutineScope,
) {
    private data class Key(val roomId: RoomId, val eventId: EventId)

    /** A poll response, in server order ([order]) for responses with the same timestamp. */
    private class Response(val sender: String, val timestamp: Long, val order: Int, val selections: List<String>)

    private class End(val sender: String, val timestamp: Long)

    private class Relations(val responses: List<Response>, val ends: List<End>)

    private class Entry(
        val eventId: EventId,
        @Volatile var start: PollStart,
        @Volatile var pollSender: String,
    ) {
        val state = MutableStateFlow<PollContentState?>(null)
        @Volatile var relations: Relations? = null

        /** The user's vote, sent but not yet in [relations]. */
        @Volatile var pendingVote: List<String>? = null

        /** The poll's end, sent but not yet in [relations]. */
        @Volatile var pendingEnd = false
        @Volatile var loaded = false
        var refreshJob: Job? = null
    }

    private val entries = ConcurrentHashMap<Key, Entry>()

    /** The state of [post]'s poll, loading its votes on first use; null when [post] isn't a poll (or has no event id yet). */
    fun state(post: FeedPost): StateFlow<PollContentState?>? {
        val start = post.post.content.poll ?: return null
        val eventId = post.eventId ?: return null
        return state(post.roomId, eventId, post.sender.value, start)
    }

    /**
     * The state of the poll [eventId] in [roomId] (e.g. one embedded in a repost), sent by [pollSender], with [start] its
     * question and answers. Loads its votes on first use.
     */
    fun state(roomId: RoomId, eventId: EventId, pollSender: String, start: PollStart): StateFlow<PollContentState?> {
        val key = Key(roomId, eventId)
        val entry = entries.getOrPut(key) { Entry(eventId, start, pollSender) }
        if (entry.start != start || entry.pollSender != pollSender) {
            // An edited poll (or a first look at it from another copy): the latest content wins.
            entry.start = start
            entry.pollSender = pollSender
            recompute(entry)
        }
        if (entry.state.value == null) recompute(entry)
        if (!entry.loaded) {
            entry.loaded = true
            launchRefresh(key, entry, followUps = emptyList())
        }
        return entry.state.asStateFlow()
    }

    /** Reloads the votes and end of a poll being shown (e.g. after it was ended elsewhere), now and again shortly after. */
    fun refresh(roomId: RoomId, eventId: EventId) {
        val key = Key(roomId, eventId)
        val entry = entries[key] ?: return
        launchRefresh(key, entry, followUps = REFRESH_FOLLOW_UP_DELAYS_MS)
    }

    /**
     * Keeps the poll's state up to date while it's shown, by reloading it when its room gets new events. Returns (or
     * never returns, while the caller is active) without doing anything for rooms the user isn't in.
     */
    suspend fun watch(roomId: RoomId, eventId: EventId) {
        val key = Key(roomId, eventId)
        val room = timelineStore.joinedRoom(roomId) ?: return
        room.syncUpdateFlow.drop(1).collectLatest {
            // collectLatest + delay: one reload per burst of events.
            delay(WATCH_DEBOUNCE_MS)
            entries[key]?.let { fetch(key, it) }
        }
    }

    /** Votes for [answerIds] in [post]'s poll (an empty list removes the vote). */
    suspend fun vote(post: FeedPost, answerIds: List<String>): Result<Unit> {
        val eventId = post.eventId ?: return Result.failure(IllegalStateException("Poll not sent yet"))
        val key = Key(post.roomId, eventId)
        // On the session scope, so leaving the screen doesn't cancel the vote halfway.
        return sessionScope.async {
            val room = timelineStore.joinedRoom(post.roomId)
                ?: return@async Result.failure(IllegalStateException("Not a member of ${post.roomId}"))
            val entry = entries[key]
            val previous = entry?.pendingVote
            entry?.let {
                it.pendingVote = answerIds
                recompute(it)
            }
            room.liveTimeline.sendPollResponse(pollStartId = eventId, answers = answerIds)
                .onSuccess { entry?.let { launchRefresh(key, it, followUps = FOLLOW_UP_DELAYS_MS, clearPendingAfter = true) } }
                .onFailure { error ->
                    Timber.w(error, "Couldn't vote in poll $eventId in ${post.roomId}")
                    entry?.let {
                        it.pendingVote = previous
                        recompute(it)
                    }
                }
        }.await()
    }

    /** Ends [post]'s poll (only its sender can). */
    suspend fun end(post: FeedPost): Result<Unit> {
        val eventId = post.eventId ?: return Result.failure(IllegalStateException("Poll not sent yet"))
        val key = Key(post.roomId, eventId)
        return sessionScope.async {
            val room = timelineStore.joinedRoom(post.roomId)
                ?: return@async Result.failure(IllegalStateException("Not a member of ${post.roomId}"))
            val entry = entries[key]
            entry?.let {
                it.pendingEnd = true
                recompute(it)
            }
            // The same fallback text as Element X's DefaultEndPollAction.
            room.liveTimeline.endPoll(pollStartId = eventId, text = "The poll with event id: $eventId has ended.")
                .onSuccess { entry?.let { launchRefresh(key, it, followUps = FOLLOW_UP_DELAYS_MS, clearPendingAfter = true) } }
                .onFailure { error ->
                    Timber.w(error, "Couldn't end poll $eventId in ${post.roomId}")
                    entry?.let {
                        it.pendingEnd = false
                        recompute(it)
                    }
                }
        }.await()
    }

    /**
     * Fetches the poll's relations now, then after each of [followUps] (ms from the previous fetch), stopping early once
     * nothing is pending. With [clearPendingAfter], whatever is still pending after the last fetch is dropped, so the
     * server's state shows.
     */
    private fun launchRefresh(key: Key, entry: Entry, followUps: List<Long>, clearPendingAfter: Boolean = false) {
        synchronized(entry) {
            entry.refreshJob?.cancel()
            // Replacing a refresh that was waiting for something pending: keep waiting for it, then drop it.
            val hasPending = entry.pendingVote != null || entry.pendingEnd
            val clearPending = clearPendingAfter || hasPending
            val delays = if (hasPending && followUps.isEmpty()) FOLLOW_UP_DELAYS_MS else followUps
            entry.refreshJob = sessionScope.launch {
                fetch(key, entry)
                for (delayMs in delays) {
                    if (clearPending && entry.pendingVote == null && !entry.pendingEnd) break
                    delay(delayMs)
                    fetch(key, entry)
                }
                if (clearPending && (entry.pendingVote != null || entry.pendingEnd)) {
                    entry.pendingVote = null
                    entry.pendingEnd = false
                    recompute(entry)
                }
            }
        }
    }

    private suspend fun fetch(key: Key, entry: Entry) {
        val relations = loadRelations(key).getOrElse { error ->
            Timber.w(error, "Couldn't load the votes of poll ${key.eventId} in ${key.roomId}")
            return
        }
        entry.relations = relations
        // What's pending is dropped once the server has it.
        val me = matrixClient.sessionId.value
        entry.pendingVote?.let { pending ->
            val mine = latestResponses(entry.start, entry.pollSender, relations)[me]
            if (mine != null && mine.toSet() == validSelections(entry.start, pending).toSet()) entry.pendingVote = null
        }
        if (entry.pendingEnd && endTimestamp(entry.pollSender, relations) != null) entry.pendingEnd = false
        recompute(entry)
    }

    private suspend fun loadRelations(key: Key): Result<Relations> = runCatching {
        val responses = mutableListOf<Response>()
        val ends = mutableListOf<End>()
        var from: String? = null
        var page = 0
        do {
            val json = httpApi.relations(key.roomId.value, key.eventId.value, REL_TYPE_REFERENCE, from).getOrThrow()
            val chunk = json.optJSONArray("chunk")
            for (i in 0 until (chunk?.length() ?: 0)) {
                val event = chunk!!.optJSONObject(i) ?: continue
                val type = event.optString("type")
                val sender = event.optString("sender").takeIf { it.isNotEmpty() } ?: continue
                val timestamp = event.optLong("origin_server_ts")
                val content = event.optJSONObject("content") ?: JSONObject()
                when {
                    SocialPollParser.isResponseEventType(type) -> {
                        val selections = SocialPollParser.parseResponseSelections(content) ?: continue
                        responses += Response(sender, timestamp, responses.size, selections)
                    }
                    SocialPollParser.isEndEventType(type) -> ends += End(sender, timestamp)
                }
            }
            from = json.optString("next_batch").takeIf { it.isNotEmpty() }
            page++
        } while (from != null && page < MAX_PAGES)
        Relations(responses, ends)
    }

    /** When the poll ended: its sender's first end event (an end by anyone else doesn't count, like Element). */
    private fun endTimestamp(pollSender: String, relations: Relations): Long? =
        relations.ends.filter { it.sender == pollSender }.minOfOrNull { it.timestamp }

    /** Each voter's valid selections: their latest response sent before the end. */
    private fun latestResponses(start: PollStart, pollSender: String, relations: Relations): Map<String, List<String>> {
        val endTimestamp = endTimestamp(pollSender, relations)
        return relations.responses
            .filter { endTimestamp == null || it.timestamp <= endTimestamp }
            .groupBy { it.sender }
            .mapValues { (_, responses) ->
                val latest = responses.maxWith(compareBy<Response> { it.timestamp }.thenBy { it.order })
                validSelections(start, latest.selections)
            }
    }

    /** Known answers only, once each, at most max_selections of them. */
    private fun validSelections(start: PollStart, selections: List<String>): List<String> {
        val answerIds = start.answers.mapTo(HashSet()) { it.id }
        return selections.filter { it in answerIds }.distinct().take(start.maxSelections)
    }

    /** Mirrors Element X's DefaultPollContentStateFactory, from the raw votes. */
    private fun recompute(entry: Entry) {
        val start = entry.start
        val me = matrixClient.sessionId.value
        val relations = entry.relations
        val endedOnServer = relations?.let { endTimestamp(entry.pollSender, it) } != null
        val votesByUser = relations?.let { latestResponses(start, entry.pollSender, it) }.orEmpty().toMutableMap()
        val pendingVote = entry.pendingVote
        if (pendingVote != null && !endedOnServer) votesByUser[me] = validSelections(start, pendingVote)
        val votes: Map<String, List<String>> = start.answers.associate { answer ->
            answer.id to votesByUser.filterValues { answer.id in it }.keys.toList()
        }

        val totalVoteCount = votes.values.sumOf { it.size }
        val myVotes = votesByUser[me].orEmpty().toSet()
        val isPollEnded = endedOnServer || entry.pendingEnd
        val winnerIds = if (!isPollEnded) {
            emptyList()
        } else {
            start.answers
                .map { answer -> answer.id }
                .groupBy { answerId -> votes[answerId]?.size ?: 0 }
                .maxByOrNull { (count, _) -> count }
                ?.takeIf { (count, _) -> count > 0 }
                ?.value
                .orEmpty()
        }
        val isSelectionLimitReached = myVotes.size >= start.maxSelections
        val answerItems = start.answers.map { answer ->
            val answerVoteCount = votes[answer.id]?.size ?: 0
            val isSelected = answer.id in myVotes
            PollAnswerItem(
                answer = answer,
                isSelected = isSelected,
                isEnabled = !isPollEnded && (!isSelectionLimitReached || isSelected),
                isWinner = answer.id in winnerIds,
                showVotes = start.kind.isDisclosed || isPollEnded,
                votesCount = answerVoteCount,
                percentage = if (totalVoteCount > 0) answerVoteCount.toFloat() / totalVoteCount.toFloat() else 0f,
            )
        }
        entry.state.value = PollContentState(
            eventId = entry.eventId,
            question = start.question,
            answerItems = answerItems.toImmutableList(),
            pollKind = start.kind,
            maxSelections = start.maxSelections.toULong(),
            // Editing polls isn't supported from Social.
            isPollEditable = false,
            isPollEnded = isPollEnded,
            isMine = entry.pollSender == me,
        )
    }

    private companion object {
        const val REL_TYPE_REFERENCE = "m.reference"

        /** 100 relations per page: up to 2000 responses. */
        const val MAX_PAGES = 20
        const val WATCH_DEBOUNCE_MS = 1_000L
        /** After a vote or end: until the server has it (the SDK sends them from its queue). */
        val FOLLOW_UP_DELAYS_MS = listOf(1_500L, 3_000L, 6_000L)

        /** After [refresh]: the change it's for may not have reached the server yet. */
        val REFRESH_FOLLOW_UP_DELAYS_MS = listOf(2_000L, 5_000L)
    }
}
