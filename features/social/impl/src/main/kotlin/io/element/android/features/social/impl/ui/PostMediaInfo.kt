/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.msc.PostMedia
import io.element.android.features.social.impl.msc.PostRelation
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.mediaviewer.api.GalleryInfo
import io.element.android.libraries.mediaviewer.api.GalleryItemData
import org.json.JSONObject

/** A post's image or video as the one item of Element X's media viewer; null when it has no URL to load it from. */
fun PostMedia.toGalleryItem(): GalleryItemData? {
    val url = url ?: return null
    val name = filename?.takeIf { it.isNotBlank() } ?: defaultName()
    return GalleryItemData(
        filename = name,
        mimeType = mimetype ?: defaultMimeType(),
        mediaSource = MediaSource(url, encryptedFileJson),
        thumbnailSource = (thumbnailUrl ?: thumbnailFileJson?.let { json -> runCatching { JSONObject(json).optString("url") }.getOrNull() })
            ?.takeIf { it.isNotEmpty() }
            ?.let { MediaSource(it, thumbnailFileJson) },
        type = when (kind) {
            PostMedia.Kind.Image, PostMedia.Kind.Sticker -> GalleryItemData.Type.Image
            PostMedia.Kind.Video -> GalleryItemData.Type.Video
            PostMedia.Kind.Audio -> GalleryItemData.Type.Audio
            PostMedia.Kind.File -> GalleryItemData.Type.File
        },
        blurHash = blurhash,
    )
}

/** What Element X's media viewer shows about [post]'s image or video: its author, when and its text. */
fun PostMedia.toGalleryInfo(post: FeedPost): GalleryInfo = GalleryInfo(
    caption = post.post.content.body.takeIf { it.isNotBlank() && it != filename },
    senderId = post.sender,
    senderName = post.senderName,
    senderAvatar = post.senderAvatarUrl,
    dateSent = formatRelativeTime(post.timestamp),
    dateSentFull = formatFullTime(post.timestamp),
    initialIndex = 0,
)

/** [toGalleryInfo] for the media of a reposted, quoted or replied-to post (its embedded copy, [relation]). */
fun PostMedia.toGalleryInfo(relation: PostRelation): GalleryInfo {
    val perMessage = relation.content?.perMessageProfile
    return GalleryInfo(
        caption = relation.content?.body?.takeIf { it.isNotBlank() && it != filename },
        senderId = runCatching { UserId(relation.sender) }.getOrNull(),
        senderName = perMessage?.displayname ?: relation.displayname,
        senderAvatar = perMessage?.avatarUrl?.takeUnless { perMessage.avatarRemoved },
        dateSent = null,
        dateSentFull = null,
        initialIndex = 0,
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
