/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import android.content.Context
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * The newest feed posts, saved on disk so the Feed shows them as soon as it opens, before the rooms' timelines have
 * loaded. Each room's cached posts are replaced by its live ones as soon as that room loads (see [SocialTimelineStore.feed]).
 */
@SingleIn(SessionScope::class)
@Inject
class FeedCache(
    @ApplicationContext context: Context,
    private val matrixClient: MatrixClient,
) {
    private val file = File(
        context.cacheDir,
        "haven_social_feed_" + matrixClient.sessionId.value.hashCode().toUInt().toString(16) + ".json",
    )

    @Volatile private var loaded: List<FeedPost>? = null
    @Volatile private var lastSaved: List<String> = emptyList()

    /** The cached posts, newest first (read from disk once per session). */
    suspend fun posts(): List<FeedPost> = loaded ?: withContext(Dispatchers.IO) {
        loaded ?: read().also { posts ->
            loaded = posts
            lastSaved = posts.map { it.listKey }
        }
    }

    suspend fun save(posts: List<FeedPost>) = withContext(Dispatchers.IO) {
        val toSave = posts.asSequence().filter { !it.fromCache && it.eventId != null && it.originalJson != null }.take(MAX_POSTS).toList()
        if (toSave.isEmpty()) return@withContext
        val keys = toSave.map { it.listKey }
        loaded = toSave
        // Counts and reactions change too, but the cache only needs to be close: it's replaced by live data within seconds.
        if (keys == lastSaved) return@withContext
        lastSaved = keys
        runCatching {
            val array = JSONArray()
            toSave.forEach { array.put(it.toJson()) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("version", VERSION).put("posts", array).toString())
            tmp.renameTo(file)
        }.onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "Couldn't save the feed cache") }
    }

    private fun read(): List<FeedPost> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        if (root.optInt("version") != VERSION) return emptyList()
        val array = root.optJSONArray("posts") ?: return emptyList()
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::fromJson) }
    }.onFailure { Timber.tag(SocialRepository.LOG_TAG).w(it, "Couldn't read the feed cache") }.getOrDefault(emptyList())

    private fun FeedPost.toJson() = JSONObject()
        .put("room_id", roomId.value)
        .put("event_id", eventId?.value)
        .put("sender", sender.value)
        .put("sender_name", senderName)
        .put("sender_avatar", senderAvatarUrl)
        .put("ts", timestamp)
        .put("own", isOwn)
        .put("original", originalJson)
        .put("edit", latestEditJson)
        .put("likes", likeCount)
        .put("liked", likedByMe)
        .put("reposts", repostCount)
        .put("reposted", repostedByMe)
        .put("replies", replyCount)
        .put(
            "reactions",
            JSONArray().also { a ->
                reactions.forEach { r ->
                    val senders = JSONArray().also { sa -> r.senders.forEach { sa.put(JSONObject().put("id", it.userId.value).put("ts", it.timestamp)) } }
                    a.put(JSONObject().put("key", r.key).put("count", r.count).put("me", r.byMe).put("senders", senders))
                }
            },
        )

    private fun fromJson(json: JSONObject): FeedPost? {
        val roomId = json.optString("room_id").takeIf { it.isNotEmpty() } ?: return null
        val eventId = json.optString("event_id").takeIf { it.isNotEmpty() } ?: return null
        val original = json.optString("original").takeIf { it.isNotEmpty() } ?: return null
        val edit = json.optString("edit").takeIf { it.isNotEmpty() && json.has("edit") && !json.isNull("edit") }
        val parsed = ParsedPost.of(original, edit)
        val post = parsed.post ?: return null
        val reactions = json.optJSONArray("reactions")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                a.optJSONObject(i)?.let { r ->
                    val senders = r.optJSONArray("senders")?.let { sa ->
                        (0 until sa.length()).mapNotNull { j ->
                            sa.optJSONObject(j)?.let { runCatching { ReactionSender(UserId(it.optString("id")), it.optLong("ts")) }.getOrNull() }
                        }
                    }.orEmpty()
                    ReactionSummary(r.optString("key"), r.optInt("count"), r.optBoolean("me"), senders)
                }
            }
        }.orEmpty()
        return runCatching {
            FeedPost(
                key = "$roomId|cache|$eventId",
                roomId = RoomId(roomId),
                eventId = EventId(eventId),
                transactionId = null,
                sender = UserId(json.optString("sender")),
                senderName = json.optString("sender_name").takeIf { !json.isNull("sender_name") && it.isNotEmpty() },
                senderAvatarUrl = json.optString("sender_avatar").takeIf { !json.isNull("sender_avatar") && it.isNotEmpty() },
                timestamp = json.optLong("ts"),
                isOwn = json.optBoolean("own"),
                post = post,
                contentJson = parsed.contentJson,
                likeCount = json.optInt("likes"),
                likedByMe = json.optBoolean("liked"),
                repostCount = json.optInt("reposts"),
                repostedByMe = json.optBoolean("reposted"),
                reactions = reactions,
                replyCount = json.optInt("replies"),
                originalJson = original,
                latestEditJson = edit,
                fromCache = true,
            )
        }.getOrNull()
    }

    private companion object {
        // 2: reactions carry their senders (for the reactions shelf).
        const val VERSION = 2
        const val MAX_POSTS = 150
    }
}
