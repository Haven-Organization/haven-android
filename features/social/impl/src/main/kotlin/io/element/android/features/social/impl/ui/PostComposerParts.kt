/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.social.impl.R
import io.element.android.features.social.impl.SocialDeps
import io.element.android.features.social.impl.data.FeedPost
import io.element.android.features.social.impl.data.OwnProfileState
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.RoomId

/*
 * Social's parts of the post composer (HavenPostComposerNode, the stock message composer on a full screen): the post
 * being replied to above the text box, the room a new post goes to, and the quoted post below the text box.
 */

/** The post replied to or quoted: usually known from the list it was opened from, else fetched. */
@Composable
fun rememberComposerTarget(deps: SocialDeps, roomId: RoomId, eventId: String): FeedPost? {
    val post by produceState(deps.timelineStore.openedPost(roomId, eventId), roomId, eventId) {
        if (value == null) {
            value = deps.timelineStore.feedPost(roomId, eventId) ?: deps.peek.post(roomId, eventId).getOrNull()?.copy(peeked = false)
        }
    }
    return post
}

/** Above the text box of a reply: the post it answers. */
@Composable
fun ReplyComposerHeader(deps: SocialDeps, roomId: RoomId, eventId: String) {
    val parent = rememberComposerTarget(deps, roomId, eventId) ?: return
    Column {
        PostTile(post = parent, deps = deps, callbacks = remember { readOnlyPostCallbacks() }, showActions = false, wide = true)
        Text(
            text = stringResource(R.string.social_replying_to, nameOf(parent)),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
    }
}

/** Above the text box of a new post: the room it goes to (own profile first, then joined groups). */
@Composable
fun NewPostComposerHeader(deps: SocialDeps, roomId: RoomId, onSelectRoom: (RoomId) -> Unit) {
    val rooms by deps.repository.socialRooms.collectAsState()
    val ownProfile by deps.repository.ownProfile.collectAsState()
    val ownProfileId = (ownProfile as? OwnProfileState.Linked)?.link?.roomId
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        RoomPicker(
            rooms = postableRooms(rooms, ownProfile),
            selected = roomId,
            ownProfileId = ownProfileId,
            onSelect = { if (it != roomId) onSelectRoom(it) },
        )
    }
}

/** Below the text box of a quote post: the quoted post. */
@Composable
fun QuoteComposerFooter(deps: SocialDeps, roomId: RoomId, eventId: String) {
    val original = rememberComposerTarget(deps, roomId, eventId) ?: return
    Box(
        Modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, ElementTheme.colors.borderInteractiveSecondary, RoundedCornerShape(12.dp)),
    ) {
        PostTile(post = original, deps = deps, callbacks = remember { readOnlyPostCallbacks() }, showActions = false, wide = true)
    }
}

/** For the previews of the replied-to / quoted post: nothing in them reacts to taps. */
private fun readOnlyPostCallbacks() = PostCallbacks(
    onOpenPost = {},
    onOpenUser = {},
    onOpenReference = {},
    onReply = {},
    onRepost = {},
    onQuote = {},
    onLike = {},
    onReact = {},
    onToggleReaction = { _, _ -> },
    onDelete = {},
    onViewInRoom = {},
)
