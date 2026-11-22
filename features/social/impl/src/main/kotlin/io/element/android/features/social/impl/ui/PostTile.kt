/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.msc.PostContent
import io.element.android.features.social.impl.msc.PostMedia
import io.element.android.features.social.impl.msc.PostRelation
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.blurhash.BlurHashAsyncImage
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.theme.components.DropdownMenu
import io.element.android.libraries.designsystem.theme.components.DropdownMenuItem
import io.element.android.libraries.designsystem.theme.components.HorizontalDivider
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.htmlrenderer.api.ui.HtmlMessageContent
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrixmedia.api.MediaRequestData

/** What a post tile can do. Callbacks get the tile's own post. */
class PostCallbacks(
    val onOpenPost: (FeedPost) -> Unit,
    val onOpenUser: (UserId) -> Unit,
    val onOpenReference: (PostRelation) -> Unit,
    val onReply: (FeedPost) -> Unit,
    val onRepost: (FeedPost) -> Unit,
    val onQuote: (FeedPost) -> Unit,
    val onLike: (FeedPost) -> Unit,
    val onReact: (FeedPost) -> Unit,
    val onToggleReaction: (FeedPost, String) -> Unit,
    val onDelete: (FeedPost) -> Unit,
    val onViewInRoom: (FeedPost) -> Unit,
    val onOpenMedia: (FeedPost, PostMedia) -> Unit = { _, _ -> },
    /** Open a post by id in a room (e.g. the post a reply replies to). */
    val onOpenEvent: (io.element.android.libraries.matrix.api.core.RoomId, String) -> Unit = { _, _ -> },
)

@Composable
fun PostTile(
    post: FeedPost,
    deps: SocialDeps,
    callbacks: PostCallbacks,
    modifier: Modifier = Modifier,
    roomName: String? = null,
    focused: Boolean = false,
    /** Display name of the post this one replies to (in-room thread reply), when known. */
    repliedToName: String? = null,
) {
    val social = post.post
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !focused && post.eventId != null) { callbacks.onOpenPost(post) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (roomName != null) {
            Text(
                text = roomName,
                style = ElementTheme.typography.fontBodyXsMedium,
                color = ElementTheme.colors.textSecondary,
                modifier = Modifier.padding(start = 52.dp, bottom = 4.dp),
            )
        }
        // Relation header line (MSC4501: "Alice reposted Bob's post" / "Alice replied to Bob's post")
        val relation = social.relation
        when {
            social.isPlainRepost && relation != null -> RelationLine("🔁", stringResource(R.string.social_reposted, nameOf(post), relation.authorName())) {
                callbacks.onOpenReference(relation)
            }
            social.isCrossPostedReply && relation != null -> RelationLine("↩", stringResource(R.string.social_replied_to, relation.authorName())) {
                callbacks.onOpenReference(relation)
            }
            social.inReplyToId != null && !focused -> RelationLine(
                "↩",
                repliedToName?.let { stringResource(R.string.social_replied_to, it) } ?: stringResource(R.string.social_reply),
            ) {
                callbacks.onOpenEvent(post.roomId, social.inReplyToId)
            }
        }
        Row {
            Box(Modifier.clickable { callbacks.onOpenUser(post.sender) }) {
                Avatar(
                    avatarData = AvatarData(post.sender.value, post.senderName, post.senderAvatarUrl, AvatarSize.RoomListItem),
                    avatarType = AvatarType.User,
                    forcedAvatarSize = 40.dp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                PostHeader(post, focused, callbacks)
                if (social.isPlainRepost && relation != null) {
                    // A plain repost shows only the original post.
                    EmbeddedPost(relation, deps, callbacks)
                } else {
                    PostBody(social.content, deps, onOpenMedia = { media -> callbacks.onOpenMedia(post, media) })
                    if (relation != null) EmbeddedPost(relation, deps, callbacks)
                }
                if (post.reactions.isNotEmpty()) ReactionsRow(post, callbacks)
                ActionBar(post, callbacks)
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun PostHeader(post: FeedPost, focused: Boolean, callbacks: PostCallbacks) {
    var showMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        Column(
            Modifier
                .weight(1f)
                .clickable { callbacks.onOpenUser(post.sender) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = nameOf(post),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = ElementTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = " · " + if (focused) formatFullTime(post.timestamp) else formatRelativeTime(post.timestamp),
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                    maxLines = 1,
                )
                if (post.post.isEdited) {
                    Text(
                        text = " · " + stringResource(R.string.social_edited),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            }
            Text(
                text = post.sender.value,
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                Icon(CompoundIcons.OverflowHorizontal(), contentDescription = stringResource(R.string.social_more), modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.social_view_in_room)) },
                    onClick = {
                        showMenu = false
                        callbacks.onViewInRoom(post)
                    },
                )
                if (post.isOwn) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.social_delete_post), color = ElementTheme.colors.textCriticalPrimary) },
                        onClick = {
                            showMenu = false
                            callbacks.onDelete(post)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RelationLine(emoji: String, text: String, onClick: (() -> Unit)?) {
    Text(
        text = "$emoji $text",
        style = ElementTheme.typography.fontBodySmMedium,
        color = ElementTheme.colors.textActionAccent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(start = 52.dp, bottom = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** The text and media of a post (or of an embedded post). */
@Composable
fun PostBody(content: PostContent, deps: SocialDeps, maxLines: Int = Int.MAX_VALUE, onOpenMedia: ((PostMedia) -> Unit)? = null) {
    val media = content.media
    val showText = content.body.isNotBlank() && (media == null || content.body != media.filename)
    if (showText) {
        val html = content.formattedBody
        val documentNode = remember(html) {
            html?.let {
                runCatching {
                    FormattedBody(format = MessageFormat.HTML, body = it).toHtmlDocument(deps.permalinkParser)?.let { doc ->
                        deps.htmlMessageParser.parse(doc)
                    }
                }.getOrNull()
            }
        }
        val uriHandler = LocalUriHandler.current
        if (documentNode != null && maxLines == Int.MAX_VALUE) {
            HtmlMessageContent(
                node = documentNode,
                currentUserId = deps.matrixClient.sessionId,
                onLinkClick = { url, _ -> runCatching { uriHandler.openUri(url) } },
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = content.body,
                style = ElementTheme.typography.fontBodyLgRegular,
                color = ElementTheme.colors.textPrimary,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (media != null) PostMediaView(media, onOpenMedia)
}

/** Images and videos like Element X's timeline: blurhash placeholder, aspect ratio, video thumbnail with play button. */
@Composable
private fun PostMediaView(media: PostMedia, onOpen: ((PostMedia) -> Unit)?) {
    when (media.kind) {
        PostMedia.Kind.Image, PostMedia.Kind.Sticker, PostMedia.Kind.Video -> {
            val isVideo = media.kind == PostMedia.Kind.Video
            val ratio = if (media.width != null && media.height != null) media.width.toFloat() / media.height else null
            // Videos show their thumbnail (poster frame); images load the image itself.
            val source = if (isVideo) {
                (media.thumbnailUrl ?: media.thumbnailFileJson?.let { json ->
                    runCatching { org.json.JSONObject(json).optString("url") }.getOrNull()
                })?.let { MediaSource(it, media.thumbnailFileJson) }
            } else {
                media.url?.let { MediaSource(it, media.encryptedFileJson) }
            }
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(if (media.kind == PostMedia.Kind.Sticker) 0.4f else 1f)
                    .then(if (ratio != null) Modifier.aspectRatio(ratio.coerceIn(0.5f, 3f)) else Modifier.heightIn(min = 180.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(ElementTheme.colors.bgSubtleSecondary)
                    .then(if (onOpen != null) Modifier.clickable { onOpen(media) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                BlurHashAsyncImage(
                    model = source?.let {
                        MediaRequestData(
                            it,
                            if (isVideo) MediaRequestData.Kind.Thumbnail(800L, 800L) else MediaRequestData.Kind.Content,
                        )
                    },
                    blurHash = media.blurhash,
                    contentScale = ContentScale.Crop,
                    contentDescription = media.filename,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isVideo) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(CompoundIcons.PlaySolid(), contentDescription = null, tint = Color.White)
                    }
                    media.durationMs?.let { duration ->
                        Text(
                            text = formatDuration(duration),
                            style = ElementTheme.typography.fontBodySmMedium,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
        PostMedia.Kind.Audio, PostMedia.Kind.File -> {
            Text(
                text = "📎 " + (media.filename ?: ""),
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textPrimary,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .then(if (onOpen != null) Modifier.clickable { onOpen(media) } else Modifier),
            )
        }
    }
}

/** The quoted / reposted / replied-to post embedded in a post (MSC4501 `relates_to.content`). */
@Composable
private fun EmbeddedPost(relation: PostRelation, deps: SocialDeps, callbacks: PostCallbacks) {
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, ElementTheme.colors.borderInteractiveSecondary), RoundedCornerShape(12.dp))
            .clickable { callbacks.onOpenReference(relation) }
            .padding(12.dp),
    ) {
        Text(
            text = relation.authorName(),
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val content = relation.content
        if (content != null) {
            PostBody(content, deps, maxLines = 12)
        } else {
            Text(
                text = stringResource(R.string.social_view_original),
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textActionAccent,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReactionsRow(post: FeedPost, callbacks: PostCallbacks) {
    FlowRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        post.reactions.forEach { reaction ->
            Text(
                text = "${reaction.key} ${reaction.count}",
                style = ElementTheme.typography.fontBodySmMedium,
                color = if (reaction.byMe) ElementTheme.colors.textActionAccent else ElementTheme.colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        BorderStroke(1.dp, if (reaction.byMe) ElementTheme.colors.borderAccentSubtle else ElementTheme.colors.borderInteractiveSecondary),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { callbacks.onToggleReaction(post, reaction.key) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionBar(post: FeedPost, callbacks: PostCallbacks) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(CompoundIcons.Reply(), stringResource(R.string.social_action_reply), post.replyCount, false) { callbacks.onReply(post) }
        ActionButton(Icons.Outlined.Repeat, stringResource(R.string.social_action_repost), post.repostCount, post.repostedByMe) { callbacks.onRepost(post) }
        ActionButton(Icons.Outlined.FormatQuote, stringResource(R.string.social_action_quote), 0, false) { callbacks.onQuote(post) }
        ActionButton(
            if (post.likedByMe) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            stringResource(R.string.social_action_like),
            post.likeCount,
            post.likedByMe,
        ) { callbacks.onLike(post) }
        // No hover on mobile, so reacting gets its own button next to Like.
        ActionButton(Icons.Outlined.AddReaction, stringResource(R.string.social_action_react), 0, false) { callbacks.onReact(post) }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    val color = if (active) ElementTheme.colors.iconAccentPrimary else ElementTheme.colors.iconSecondary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(20.dp))
        if (count > 0) {
            Text(
                text = count.toString(),
                style = ElementTheme.typography.fontBodySmRegular,
                color = color,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

fun nameOf(post: FeedPost) = post.senderName?.takeIf { it.isNotBlank() } ?: post.sender.value

/** MSC4501: fall back to the bare sender ID when no display name snapshot was sent. */
fun PostRelation.authorName() = displayname?.takeIf { it.isNotBlank() } ?: sender
