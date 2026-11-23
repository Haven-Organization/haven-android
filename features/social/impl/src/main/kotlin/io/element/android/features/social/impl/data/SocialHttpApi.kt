/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.data

import android.net.Uri
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.MatrixClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SocialHttpException(val statusCode: Int, val errcode: String?, message: String) : IOException(message)

/**
 * Client-Server API calls the Rust SDK doesn't wrap, needed for MSC4501:
 * - `/createRoom` with a room `type` (`creation_content`) and `initial_state`
 * - per-key profile fields (Matrix 1.16, MSC4133) for `org.matrix.msc4501.social.profile_room`
 * - `/publicRooms` filtered by `room_types` (MSC3827)
 * - `/search`
 *
 * Uses its own OkHttp client without the app's logging interceptor, so the access token can't end up in logs.
 */
@SingleIn(SessionScope::class)
@Inject
class SocialHttpApi(
    private val matrixClient: MatrixClient,
    okHttpClient: OkHttpClient,
) {
    private val httpClient = okHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun createRoom(request: JSONObject): Result<String> = call("POST", "/_matrix/client/v3/createRoom", request)
        .map { it.getString("room_id") }

    /** Every room the user is joined to (`GET /joined_rooms`), including ones sliding sync hasn't loaded yet. */
    suspend fun joinedRooms(): Result<List<String>> = call("GET", "/_matrix/client/v3/joined_rooms").map { json ->
        val array = json.optJSONArray("joined_rooms")
        (0 until (array?.length() ?: 0)).mapNotNull { array!!.optString(it).takeIf { id -> id.startsWith("!") } }
    }

    /** A single event (`GET /rooms/{roomId}/event/{eventId}`); works without joining for world-readable rooms. */
    suspend fun getRoomEvent(roomId: String, eventId: String): Result<JSONObject> =
        call("GET", "/_matrix/client/v3/rooms/${enc(roomId)}/event/${enc(eventId)}")

    /** Events relating to [eventId] with [relType] (e.g. `m.thread` replies), oldest first via `dir=f`. */
    suspend fun relations(roomId: String, eventId: String, relType: String, from: String? = null): Result<JSONObject> =
        call(
            "GET",
            "/_matrix/client/v1/rooms/${enc(roomId)}/relations/${enc(eventId)}/${enc(relType)}?dir=f&limit=100" +
                (from?.let { "&from=${enc(it)}" } ?: ""),
        )

    /** A page of a room's events, newest first (`dir=b`); works without joining for world-readable rooms. */
    suspend fun roomMessages(roomId: String, from: String?, limit: Int = 50, lazyLoadMembers: Boolean = false): Result<JSONObject> =
        call(
            "GET",
            "/_matrix/client/v3/rooms/${enc(roomId)}/messages?dir=b&limit=$limit" + (from?.let { "&from=${enc(it)}" } ?: "") +
                // The senders' member events come along in `state`, for their names and avatars.
                (if (lazyLoadMembers) "&filter=" + enc("""{"lazy_load_members":true}""") else ""),
        )

    /**
     * Simplified sliding sync (MSC4186), on a connection of its own ([body]'s `conn_id`), so the SDK's sync isn't
     * affected. Waits up to [timeoutMs] for news when [pos] is given.
     */
    suspend fun slidingSync(body: JSONObject, pos: String?, timeoutMs: Long): Result<JSONObject> =
        call(
            "POST",
            "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync?timeout=$timeoutMs" + (pos?.let { "&pos=${enc(it)}" } ?: ""),
            body,
        )

    /** Redacts (deletes) an event, e.g. a post or the user's own reaction. Needs no SDK timeline holding the event. */
    suspend fun redact(roomId: String, eventId: String): Result<Unit> =
        call(
            "PUT",
            "/_matrix/client/v3/rooms/${enc(roomId)}/redact/${enc(eventId)}/${enc("haven-" + java.util.UUID.randomUUID())}",
            JSONObject(),
        ).map { }

    /** One state event's content, from the server. */
    suspend fun getStateEventContent(roomId: String, eventType: String, stateKey: String): Result<JSONObject> =
        call("GET", "/_matrix/client/v3/rooms/${enc(roomId)}/state/${enc(eventType)}/${enc(stateKey)}")

    /** The whole profile of [userId], including custom fields. */
    suspend fun getProfile(userId: String): Result<JSONObject> = call("GET", "/_matrix/client/v3/profile/${enc(userId)}")

    suspend fun setProfileField(userId: String, key: String, value: Any): Result<Unit> =
        call("PUT", "/_matrix/client/v3/profile/${enc(userId)}/${enc(key)}", JSONObject().put(key, value)).map { }

    suspend fun deleteProfileField(userId: String, key: String): Result<Unit> =
        call("DELETE", "/_matrix/client/v3/profile/${enc(userId)}/${enc(key)}").map { }

    /** `POST /publicRooms` with a `room_types` filter (MSC3827). */
    suspend fun publicRooms(server: String?, query: String, roomTypes: List<String>, limit: Int, since: String?): Result<JSONObject> {
        val filter = JSONObject().put("room_types", org.json.JSONArray(roomTypes))
        if (query.isNotBlank()) filter.put("generic_search_term", query)
        val body = JSONObject().put("limit", limit).put("filter", filter)
        since?.let { body.put("since", it) }
        val path = "/_matrix/client/v3/publicRooms" + (server?.let { "?server=${enc(it)}" } ?: "")
        return call("POST", path, body)
    }

    /** Server-side search of room events in [roomIds] (same request as Haven Desktop's post search). */
    suspend fun searchRoomEvents(term: String, roomIds: List<String>, nextBatch: String?): Result<JSONObject> {
        val roomEvents = JSONObject()
            .put("search_term", term)
            .put("order_by", "rank")
            .put("filter", JSONObject().put("rooms", org.json.JSONArray(roomIds)))
            .put("event_context", JSONObject().put("before_limit", 0).put("after_limit", 0).put("include_profile", true))
        val body = JSONObject().put("search_categories", JSONObject().put("room_events", roomEvents))
        val path = "/_matrix/client/v3/search" + (nextBatch?.let { "?next_batch=${enc(it)}" } ?: "")
        return call("POST", path, body)
    }

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val credentials = matrixClient.havenRawCredentials().getOrThrow()
            val url = credentials.homeserverUrl.trimEnd('/') + path
            val requestBody = body?.toString()?.toRequestBody(JSON)
                ?: if (method == "GET" || method == "DELETE") null else "{}".toRequestBody(JSON)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${credentials.accessToken}")
                .method(method, requestBody)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                val json = text.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
                if (!response.isSuccessful) {
                    throw SocialHttpException(
                        statusCode = response.code,
                        errcode = json.optString("errcode").takeIf { it.isNotEmpty() },
                        message = json.optString("error").ifEmpty { "HTTP ${response.code}" },
                    )
                }
                json
            }
        }
    }

    private fun enc(value: String) = Uri.encode(value)

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
