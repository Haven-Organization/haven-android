/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProduceStateScope
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import io.element.android.features.contentscanner.api.ContentScannerService
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.extensions.mapCatchingExceptions
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.matrix.api.HavenRawCredentials
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaFile
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.ui.media.contentvalidation.ContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.DefaultContentValidationState
import io.element.android.libraries.matrix.ui.media.contentvalidation.EventContentValidationCache
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint.MediaViewerMode
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaFactory
import io.element.android.libraries.mediaviewer.impl.datasource.MediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.gallery.MediaGalleryMode
import io.element.android.libraries.mediaviewer.impl.local.player.HavenVideoStreams
import io.element.android.libraries.mediaviewer.impl.model.MediaItem
import io.element.android.libraries.mediaviewer.impl.model.eventId
import io.element.android.libraries.mediaviewer.impl.model.mediaInfo
import io.element.android.libraries.mediaviewer.impl.model.mediaSource
import io.element.android.libraries.mediaviewer.impl.model.thumbnailSource
import io.element.android.services.toolbox.api.systemclock.SystemClock
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaViewerDataSource(
    mode: MediaViewerMode,
    coroutineScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val galleryDataSource: MediaGalleryDataSource,
    private val mediaLoader: MatrixMediaLoader,
    private val localMediaFactory: LocalMediaFactory,
    private val systemClock: SystemClock,
    private val pagerKeysHandler: PagerKeysHandler,
    private val contentScannerService: ContentScannerService,
    private val contentValidationCache: EventContentValidationCache,
    // Haven: lets unencrypted videos be streamed from the homeserver, see HavenVideoStreams
    private val streamCredentials: suspend () -> HavenRawCredentials? = { null },
    // Haven: a streamed video copied out of the player's disk cache, if all of it has been downloaded
    private val cachedStreamFile: suspend (uri: Uri, filename: String) -> File? = { _, _ -> null },
    private val deleteCachedStreamFile: (File) -> Unit = {},
) {
    private val scope = CoroutineScope(coroutineScope.coroutineContext + dispatcher)

    // Haven: the URLs of the videos being streamed, by source URL
    private val streamUris: ConcurrentHashMap<String, Uri> = ConcurrentHashMap()

    // Haven: the whole files of streamed videos, downloaded when they're shared or saved
    private val streamedFiles: ConcurrentHashMap<String, Deferred<Result<LocalMedia>>> = ConcurrentHashMap()

    // Haven: the streamed videos copied out of the player's cache, to delete when the viewer closes
    private val cacheCopies: MutableSet<File> = ConcurrentHashMap.newKeySet()

    // List of media files that are currently being loaded
    private val mediaFiles: ConcurrentHashMap<MediaSource, MediaFile> = ConcurrentHashMap()

    private val galleryMode = when (mode) {
        is MediaViewerMode.TimelineImagesAndVideos -> MediaGalleryMode.Images
        is MediaViewerMode.TimelineFilesAndAudios -> MediaGalleryMode.Files
        MediaViewerMode.EventGallery -> MediaGalleryMode.Images
    }

    // Map of sourceUrl to local media state
    private val localMediaStates: MutableMap<String, MutableState<AsyncData<LocalMedia>>> =
        mutableMapOf()

    private val mediaValidationState: MutableMap<String, ContentValidationState> =
        mutableMapOf()

    fun setup(coroutineScope: CoroutineScope) {
        galleryDataSource.start(coroutineScope)
    }

    fun dispose() {
        Timber.d("Disposing MediaViewerDataSource, closing ${mediaFiles.size} media files")
        mediaFiles.values.forEach { it.close() }
        mediaFiles.clear()
        localMediaStates.clear()
        streamUris.values.forEach { HavenVideoStreams.unregister(it) }
        streamUris.clear()
        streamedFiles.values.forEach { it.cancel() }
        streamedFiles.clear()
        cacheCopies.forEach(deleteCachedStreamFile)
        cacheCopies.clear()
    }

    /**
     * Helper function to translate the [dataFlow] result to a Compose [State] that can be observed in the UI.
     */
    @Composable
    fun produceState(
        producer: suspend ProduceStateScope<ImmutableList<MediaViewerPageData>>.(StateFlow<ImmutableList<MediaViewerPageData>>) -> Unit
    ): State<ImmutableList<MediaViewerPageData>> {
        val latestProducer by rememberUpdatedState(producer)
        return produceState(initialValue = initialData()) {
            latestProducer(dataFlow)
        }
    }

    /**
     * Find the index of the page corresponding to the given eventId, or null if not found.
     */
    fun findEventIndex(eventId: EventId?, mediaSource: MediaSource? = null): Int? {
        if (eventId == null) return null
        return dataFlow.value.indexOfFirst {
            val pageData = it as? MediaViewerPageData.MediaViewerData
            pageData?.eventId == eventId && (mediaSource == null || pageData.mediaSource == mediaSource)
        }.takeIf { it >= 0 }
    }

    @VisibleForTesting
    internal val dataFlow: StateFlow<ImmutableList<MediaViewerPageData>> = galleryDataSource.groupedMediaItemsFlow()
        .map { groupedItems ->
            when (groupedItems) {
                AsyncData.Uninitialized,
                is AsyncData.Loading -> {
                    persistentListOf(
                        MediaViewerPageData.Loading(
                            direction = Timeline.PaginationDirection.BACKWARDS,
                            timestamp = systemClock.epochMillis(),
                            pagerKey = Long.MIN_VALUE,
                        )
                    )
                }
                is AsyncData.Failure -> {
                    persistentListOf(
                        MediaViewerPageData.Failure(groupedItems.error),
                    )
                }
                is AsyncData.Success -> {
                    withContext(dispatcher) {
                        val mediaItems = groupedItems.data.getItems(galleryMode)
                        buildMediaViewerPageList(mediaItems)
                    }
                }
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Lazily,
            initialValue = initialData(),
        )

    private fun initialData(): ImmutableList<MediaViewerPageData> {
        val initialMediaItems =
            galleryDataSource.getLastData().dataOrNull()?.getItems(galleryMode).orEmpty()
        return buildMediaViewerPageList(initialMediaItems)
    }

    /**
     * Build a list of [MediaViewerPageData] from a list of [MediaItem].
     * In particular, create a mutable state of AsyncData<LocalMedia> for each media item, which
     * will be used to render the downloaded media (see [loadMedia] which will update this value).
     */
    private fun buildMediaViewerPageList(groupedItems: List<MediaItem>) = buildList {
        // Filter out DateSeparator items, we do not need them for the media viewer
        val groupedItemsNoDateSeparator = groupedItems.filterNot { it is MediaItem.DateSeparator }
        pagerKeysHandler.accept(groupedItemsNoDateSeparator)
        groupedItemsNoDateSeparator.forEach { mediaItem ->
            when (mediaItem) {
                is MediaItem.DateSeparator -> Unit
                is MediaItem.Event -> {
                    val sourceUrl = mediaItem.mediaSource().safeUrl
                    val localMedia = localMediaStates.getOrPut(sourceUrl) {
                        mutableStateOf(AsyncData.Uninitialized)
                    }
                    val validationState = mediaValidationState.getOrPut(sourceUrl) {
                        mediaItem.eventId()?.let { contentValidationCache[it] } ?: DefaultContentValidationState()
                    }
                    add(
                        MediaViewerPageData.MediaViewerData(
                            eventId = mediaItem.eventId(),
                            mediaInfo = mediaItem.mediaInfo(),
                            mediaSource = mediaItem.mediaSource(),
                            thumbnailSource = mediaItem.thumbnailSource(),
                            downloadedMedia = localMedia,
                            pagerKey = pagerKeysHandler.getKey(mediaItem),
                            validationState = validationState,
                        )
                    )
                }
                is MediaItem.LoadingIndicator -> add(
                    MediaViewerPageData.Loading(
                        direction = mediaItem.direction,
                        timestamp = mediaItem.timestamp,
                        pagerKey = pagerKeysHandler.getKey(mediaItem),
                    )
                )
            }
        }
    }.toImmutableList()

    fun clearLoadingError(data: MediaViewerPageData.MediaViewerData) {
        localMediaStates[data.mediaSource.safeUrl]?.value = AsyncData.Uninitialized
    }

    suspend fun loadMore(direction: Timeline.PaginationDirection) {
        if (galleryDataSource.isReady) {
            galleryDataSource.loadMore(direction)
        }
    }

    suspend fun loadMedia(data: MediaViewerPageData.MediaViewerData, allowStreaming: Boolean = true) {
        val currentState = localMediaStates[data.mediaSource.safeUrl]?.value
        // If the media is already loading or has been loaded successfully, do nothing
        if (currentState?.isLoading() == true || currentState?.isSuccess() == true) {
            return
        }

        Timber.d("loadMedia for ${data.eventId}")
        val localMediaState = localMediaStates.getOrPut(data.mediaSource.safeUrl) {
            mutableStateOf(AsyncData.Uninitialized)
        }
        localMediaState.value = AsyncData.Loading()
        val stream = if (allowStreaming) streamFor(data) else null
        if (stream != null) {
            localMediaState.value = AsyncData.Success(stream)
            return
        }
        downloadFile(data)
            .onSuccess {
                localMediaState.value = AsyncData.Success(it)
            }
            .onFailure {
                localMediaState.value = AsyncData.Failure(it)
            }
    }

    private suspend fun downloadFile(data: MediaViewerPageData.MediaViewerData): Result<LocalMedia> {
        return mediaLoader
            .downloadMediaFile(
                source = data.mediaSource,
                mimeType = data.mediaInfo.mimeType,
                filename = data.mediaInfo.filename
            )
            .onSuccess { mediaFile ->
                mediaFiles[data.mediaSource] = mediaFile
            }
            .mapCatchingExceptions { mediaFile ->
                localMediaFactory.createFromMediaFile(
                    mediaFile = mediaFile,
                    mediaInfo = data.mediaInfo
                )
            }
    }

    /**
     * Haven: an unencrypted video is played straight from the homeserver, see [HavenVideoStreams]. If the server can't
     * stream it, the player falls back to downloading it whole.
     */
    private suspend fun streamFor(data: MediaViewerPageData.MediaViewerData): LocalMedia? {
        if (!data.mediaInfo.mimeType.isMimeTypeVideo() || data.mediaSource.isEncrypted()) return null
        val sourceUrl = data.mediaSource.safeUrl
        val credentials = streamCredentials() ?: return null
        val uri = HavenVideoStreams.urlFor(credentials, sourceUrl) ?: return null
        streamUris[sourceUrl] = uri
        HavenVideoStreams.register(uri, credentials.accessToken) {
            Timber.d("Streaming failed for ${data.eventId}, downloading it instead")
            streamUris.remove(sourceUrl)
            scope.launch {
                localMediaStates[sourceUrl]?.value = AsyncData.Uninitialized
                loadMedia(data, allowStreaming = false)
            }
        }
        return LocalMedia(uri = uri, info = data.mediaInfo)
    }

    /**
     * The media as a local file, to share or save it. Waits for it to finish downloading if needed, and for a streamed
     * video, downloads the whole file.
     */
    suspend fun localFile(data: MediaViewerPageData.MediaViewerData): Result<LocalMedia> {
        val sourceUrl = data.mediaSource.safeUrl
        val state = localMediaStates.getOrPut(sourceUrl) { mutableStateOf(AsyncData.Uninitialized) }
        if (state.value.let { it is AsyncData.Uninitialized || it is AsyncData.Failure }) {
            loadMedia(data)
        }
        val loaded = snapshotFlow { state.value }.first { !it.isLoading() && it !is AsyncData.Uninitialized }
        return when (loaded) {
            is AsyncData.Success -> if (HavenVideoStreams.isStream(loaded.data.uri)) {
                val download = streamedFiles.computeIfAbsent(sourceUrl) {
                    scope.async { copyFromCache(data, loaded.data.uri) ?: downloadFile(data) }
                }
                download.await().onFailure { streamedFiles.remove(sourceUrl, download) }
            } else {
                Result.success(loaded.data)
            }
            is AsyncData.Failure -> Result.failure(loaded.error)
            else -> Result.failure(IllegalStateException("Media not loaded"))
        }
    }

    private suspend fun copyFromCache(data: MediaViewerPageData.MediaViewerData, uri: Uri): Result<LocalMedia>? {
        val file = cachedStreamFile(uri, data.mediaInfo.filename) ?: return null
        cacheCopies.add(file)
        Timber.d("Using the cached stream for ${data.eventId}")
        return runCatchingExceptions {
            localMediaFactory.createFromUri(
                uri = Uri.fromFile(file),
                mimeType = data.mediaInfo.mimeType,
                name = data.mediaInfo.filename,
                formattedFileSize = data.mediaInfo.formattedFileSize,
            )
        }
    }

    /**
     * Whether [localFile] will have to wait for the media to download: it's still loading, or it's a streamed video.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun needsDownload(data: MediaViewerPageData.MediaViewerData): Boolean {
        val state = localMediaStates[data.mediaSource.safeUrl]?.value ?: return false
        if (state.isLoading()) return true
        val loaded = state.dataOrNull() ?: return false
        if (!HavenVideoStreams.isStream(loaded.uri)) return false
        return streamedFiles[data.mediaSource.safeUrl]?.let { it.isCompleted && it.getCompleted().isSuccess } != true
    }

    fun validateMedia(mediaSource: MediaSource, thumbnailMediaSource: MediaSource?) {
        val validationState = mediaValidationState[mediaSource.safeUrl] ?: return
        val currentState = validationState.getCurrentOverallState()

        if (currentState.isLoading() || currentState.isValid()) {
            return
        }

        contentScannerService.scan(listOfNotNull(mediaSource, thumbnailMediaSource), validationState)
    }

    fun cancelLoadingMedia(data: MediaViewerPageData.MediaViewerData) {
        if (localMediaStates[data.mediaSource.safeUrl]?.value?.isLoading() == true) {
            Timber.d("cancelLoadingMedia for ${data.eventId}")
            mediaFiles.remove(data.mediaSource)?.close()
            localMediaStates[data.mediaSource.safeUrl]?.value = AsyncData.Uninitialized
        }
    }
}

/**
 * Haven: the SDK sets json for every source, `{"url": "mxc://…"}` for a plain file and `{"file": {…}}` with the
 * decryption key for an encrypted one. Counts anything it can't read as encrypted, so it's never streamed.
 */
internal fun MediaSource.isEncrypted(): Boolean {
    val json = json ?: return false
    return runCatchingExceptions { JSONObject(json).let { it.has("file") || it.has("key") || !it.has("url") } }.getOrDefault(true)
}
