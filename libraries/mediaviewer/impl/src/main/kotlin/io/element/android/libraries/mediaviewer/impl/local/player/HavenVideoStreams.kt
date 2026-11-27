/*
 * Copyright 2026 Haven contributors.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.local.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.HavenRawCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Haven: videos the media viewer plays straight from the homeserver instead of downloading them whole first.
 *
 * The player asks for the parts it needs with HTTP range requests, so playback starts after the first few hundred KB and
 * seeking jumps to the right place without downloading what comes before it. The access token for each stream stays
 * here in memory, out of the URL (which would end up in server logs) and out of the parcelable LocalMedia.
 *
 * What the player downloads is kept in a disk cache, so going back over a part already played doesn't download it again,
 * and sharing or saving a video that has been played through doesn't either. That matters most on servers without range
 * requests, where the player has to read an MP4 with its index at the end all the way through before it can start, then
 * go back to the start.
 */
@OptIn(UnstableApi::class)
internal object HavenVideoStreams {
    private const val CACHE_DIR = "haven_video_streams"
    private const val CACHE_MAX_BYTES = 512L * 1024 * 1024
    private const val SHARED_DIR = "haven_video_streams_files"

    private class Stream(val accessToken: String, val onFailure: () -> Unit)

    private val streams = ConcurrentHashMap<String, Stream>()

    // Its own client, without the app's logging interceptor, so the access token can't end up in logs.
    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

    // Only one SimpleCache may use a directory in a process
    @Volatile
    private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: SimpleCache(
                File(context.applicationContext.cacheDir, CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
    }

    /**
     * Where the video player reads http(s) media from: the disk cache, then the server with the access token added to
     * requests for streams. OkHttp drops the token if the server redirects to another host.
     */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(httpClient)) { dataSpec ->
            val headers = authorizationHeaders(dataSpec.uri)
            if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers)
        }
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Copies the stream at [uri] out of the disk cache to a file named [filename], if all of it has been downloaded.
     * The file is in a folder of its own, for [deleteCopy] to remove.
     */
    suspend fun copyFromCache(context: Context, uri: Uri, filename: String): File? = withContext(Dispatchers.IO) {
        runCatchingExceptions {
            val cache = cache(context)
            val key = uri.toString()
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (length <= 0 || !cache.isCached(key, 0, length)) return@runCatchingExceptions null
            val folder = File(File(context.applicationContext.cacheDir, SHARED_DIR), key.hashCode().toUInt().toString(16))
            folder.mkdirs()
            val file = File(folder, filename.substringAfterLast('/').ifBlank { "video" })
            // No upstream: reading anything that isn't cached fails instead of going to the network
            val source = CacheDataSource(cache, null)
            try {
                source.open(DataSpec(uri))
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        output.write(buffer, 0, read)
                    }
                }
            } finally {
                source.close()
            }
            file
        }.onFailure {
            Timber.w(it, "Couldn't copy a streamed video out of the cache")
        }.getOrNull()
    }

    fun deleteCopy(file: File) {
        file.parentFile?.deleteRecursively()
    }

    /**
     * The authenticated media URL for an unencrypted mxc:// [url], or null when it can't be streamed.
     */
    fun urlFor(credentials: HavenRawCredentials, url: String): Uri? {
        if (!url.startsWith("mxc://")) return null
        val (serverName, mediaId) = url.removePrefix("mxc://").split("/", limit = 2).takeIf { it.size == 2 } ?: return null
        if (serverName.isEmpty() || mediaId.isEmpty()) return null
        return Uri.parse(credentials.homeserverUrl.trimEnd('/')).buildUpon()
            .appendEncodedPath("_matrix/client/v1/media/download")
            .appendPath(serverName)
            .appendPath(mediaId)
            .build()
    }

    fun register(uri: Uri, accessToken: String, onFailure: () -> Unit) {
        streams[uri.toString()] = Stream(accessToken, onFailure)
    }

    fun unregister(uri: Uri) {
        streams.remove(uri.toString())
    }

    fun isStream(uri: Uri?): Boolean = uri != null && streams.containsKey(uri.toString())

    fun authorizationHeaders(uri: Uri): Map<String, String> {
        val stream = streams[uri.toString()] ?: return emptyMap()
        return mapOf("Authorization" to "Bearer ${stream.accessToken}")
    }

    /**
     * The server couldn't stream [uri] (for instance it doesn't have the authenticated media endpoint): download it whole instead.
     */
    fun onPlaybackFailed(uri: Uri) {
        streams.remove(uri.toString())?.onFailure?.invoke()
    }
}
