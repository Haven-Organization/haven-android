/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.msc.PostMedia
import io.element.android.libraries.mediaviewer.api.MediaInfo
import java.util.Locale

/** What Element X's media viewer shows about a post's image or video. */
fun PostMedia.toMediaInfo(post: FeedPost): MediaInfo {
    val name = filename?.takeIf { it.isNotBlank() } ?: defaultName()
    val caption = post.post.content.body.takeIf { it.isNotBlank() && it != filename }
    return MediaInfo(
        filename = name,
        caption = caption,
        htmlCaption = null,
        mimeType = mimetype ?: defaultMimeType(),
        fileSize = size,
        formattedFileSize = size?.let(::formatSize).orEmpty(),
        fileExtension = name.substringAfterLast('.', "").lowercase(Locale.ROOT),
        senderId = post.sender,
        senderName = post.senderName,
        senderAvatar = post.senderAvatarUrl,
        dateSent = formatRelativeTime(post.timestamp),
        dateSentFull = formatFullTime(post.timestamp),
        waveform = null,
        duration = durationMs?.let(::formatDuration),
    )
}

private fun PostMedia.defaultName() = when (kind) {
    PostMedia.Kind.Video -> "video.mp4"
    PostMedia.Kind.Audio -> "audio"
    else -> "image.jpg"
}

private fun PostMedia.defaultMimeType() = when (kind) {
    PostMedia.Kind.Video -> "video/mp4"
    PostMedia.Kind.Audio -> "audio/ogg"
    PostMedia.Kind.File -> "application/octet-stream"
    else -> "image/jpeg"
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
