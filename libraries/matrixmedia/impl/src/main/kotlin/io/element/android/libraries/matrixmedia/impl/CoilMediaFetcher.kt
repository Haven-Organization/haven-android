/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.matrixmedia.impl

import android.media.MediaMetadataRetriever
import android.os.Build
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import io.element.android.libraries.matrix.api.exception.isNetworkError
import io.element.android.libraries.matrix.api.media.MatrixMediaLoader
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.media.toFile
import io.element.android.libraries.matrixmedia.api.MAX_THUMBNAIL_HEIGHT
import io.element.android.libraries.matrixmedia.api.MAX_THUMBNAIL_WIDTH
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import timber.log.Timber
import java.nio.ByteBuffer

internal class CoilMediaFetcher(
    private val mediaLoader: MatrixMediaLoader,
    private val mediaData: MediaRequestData,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val mediaSource = mediaData.source
        if (mediaSource == null) {
            Timber.e("MediaData source is null")
            return null
        }
        return when (val kind = mediaData.kind) {
            is MediaRequestData.Kind.Content -> fetchContent(mediaSource)
            is MediaRequestData.Kind.Thumbnail -> {
                fetchThumbnail(mediaSource, kind).fold(
                    onSuccess = { it },
                    onFailure = { error ->
                        if (error.isNetworkError()) {
                            throw error
                        } else {
                            fetchContent(mediaSource)
                        }
                    }
                )
            }
            is MediaRequestData.Kind.File -> fetchFile(mediaSource, kind)
            is MediaRequestData.Kind.VideoFrame -> fetchVideoFrame(mediaSource, kind)
        }
    }

    /** Haven: downloads the video and decodes a frame near its start, scaled down to thumbnail size. */
    private suspend fun fetchVideoFrame(mediaSource: MediaSource, kind: MediaRequestData.Kind.VideoFrame): FetchResult? {
        val mediaFile = mediaLoader.downloadMediaFile(mediaSource, kind.mimeType ?: "video/mp4", "video")
            .onFailure { Timber.e(it) }
            .getOrThrow()
        val bitmap = mediaFile.use { file ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.toFile().path)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val scale = if (width > 0 && height > 0) {
                    minOf(1f, MAX_THUMBNAIL_WIDTH.toFloat() / width, MAX_THUMBNAIL_HEIGHT.toFloat() / height)
                } else {
                    1f
                }
                // A little way in rather than the very first frame, which is often black (a fade-in).
                val timeUs = 500_000L
                if (scale < 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        (width * scale).toInt().coerceAtLeast(1),
                        (height * scale).toInt().coerceAtLeast(1),
                    )
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } ?: retriever.frameAtTime
            } catch (e: Exception) {
                Timber.w(e, "Couldn't grab a frame from a video")
                null
            } finally {
                runCatching { retriever.release() }
            }
        } ?: return null
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    /**
     * This method is here to avoid using [MatrixMediaLoader.loadMediaContent] as too many ByteArray allocations will flood the memory and cause lots of GC.
     * The MediaFile will be closed (and so destroyed from disk) when the image source is closed.
     *
     */
    private suspend fun fetchFile(mediaSource: MediaSource, kind: MediaRequestData.Kind.File): FetchResult? {
        return mediaLoader.downloadMediaFile(mediaSource, kind.mimeType, kind.fileName)
            .map { mediaFile ->
                val file = mediaFile.toFile()
                SourceFetchResult(
                    source = ImageSource(
                        file = file.toOkioPath(),
                        fileSystem = FileSystem.SYSTEM,
                        closeable = mediaFile,
                    ),
                    mimeType = null,
                    dataSource = DataSource.DISK
                )
            }
            .onFailure {
                Timber.e(it)
            }
            .getOrThrow()
    }

    private suspend fun fetchContent(mediaSource: MediaSource): FetchResult? {
        return mediaLoader.loadMediaContent(
            source = mediaSource,
        ).map { byteArray ->
            byteArray.asSourceResult()
        }.onFailure {
            Timber.e(it)
        }.getOrThrow()
    }

    private suspend fun fetchThumbnail(mediaSource: MediaSource, kind: MediaRequestData.Kind.Thumbnail): Result<FetchResult> {
        return mediaLoader.loadMediaThumbnail(
            source = mediaSource,
            width = kind.width,
            height = kind.height,
        ).map { byteArray ->
            byteArray.asSourceResult()
        }.onFailure {
            Timber.e(it)
        }
    }

    private fun ByteArray.asSourceResult(): SourceFetchResult {
        val byteBuffer = ByteBuffer.wrap(this)
        val bufferedSource = try {
            Buffer().apply { write(byteBuffer) }
        } finally {
            byteBuffer.position(0)
        }
        return SourceFetchResult(
            source = ImageSource(
                source = bufferedSource,
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = null,
            dataSource = DataSource.MEMORY
        )
    }
}
