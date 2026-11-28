/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.mentions

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.zacsweers.metro.ContributesBinding
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.ui.formattedname.FormattedNameRun
import io.element.android.libraries.matrix.ui.formattedname.LocalFormattedNamesRoomId
import io.element.android.libraries.matrix.ui.formattedname.formattedDisplayNamesEnabled
import io.element.android.libraries.matrix.ui.messages.RoomMemberProfilesCache
import io.element.android.libraries.matrix.ui.messages.RoomNamesCache
import io.element.android.libraries.matrix.ui.model.toEmojiText

interface MentionSpanUpdater {
    fun updateMentionSpans(text: CharSequence): CharSequence

    @Composable
    fun rememberMentionSpans(text: CharSequence): CharSequence
}

@ContributesBinding(RoomScope::class)
class DefaultMentionSpanUpdater(
    private val formatter: MentionSpanFormatter,
    private val theme: MentionSpanTheme,
    private val roomMemberProfilesCache: RoomMemberProfilesCache,
    private val roomNamesCache: RoomNamesCache,
    private val pillProfiles: MentionPillProfiles,
) : MentionSpanUpdater {
    // Haven: the room the pills are in, for its members' formatted display names (set while composing)
    private var roomId: RoomId? = null

    @Composable
    override fun rememberMentionSpans(text: CharSequence): CharSequence {
        val isLightTheme = ElementTheme.isLightTheme
        val roomNames by roomNamesCache.displayNames.collectAsState()
        val roomMembers by roomMemberProfilesCache.membersFlow.collectAsState()
        // Haven: global profiles and avatars as they come in
        val profiles by pillProfiles.profiles.collectAsState()
        val avatars by pillProfiles.avatars.collectAsState()
        val emojis by pillProfiles.emojis.collectAsState()
        val formattedNamesEnabled = formattedDisplayNamesEnabled()
        val formattedNamesUpdates by pillProfiles.formattedNamesUpdates.collectAsState()
        roomId = LocalFormattedNamesRoomId.current
        return remember(text, roomNames, roomMembers, isLightTheme, profiles, avatars, emojis, formattedNamesEnabled, formattedNamesUpdates) {
            updateMentionSpans(text)
            text
        }
    }

    override fun updateMentionSpans(text: CharSequence): CharSequence {
        for (mentionSpan in text.getMentionSpans()) {
            mentionSpan.updateTheme(theme)
            mentionSpan.updateDisplayText(formatter)
            mentionSpan.updateTrailingText(
                if (mentionSpan.type is MentionType.User) {
                    roomMemberProfilesCache.getDisplayedStatus(mentionSpan.type.userId)?.toEmojiText()
                } else {
                    null
                }
            )
            mentionSpan.updateAvatar((mentionSpan.type as? MentionType.User)?.let { avatarFor(it.userId) })
            mentionSpan.updateFormattedName((mentionSpan.type as? MentionType.User)?.let { formattedNameFor(it.userId, mentionSpan.displayText) })
        }
        return text
    }

    /** Haven: the formatted display name (MSC4589) of the pill for [userId] showing [displayText], if it has one. */
    private fun formattedNameFor(userId: UserId, displayText: CharSequence): List<PillTextRun>? {
        val name = displayText.toString().takeIf { it.startsWith("@") && it != userId.value }?.removePrefix("@") ?: return null
        val formatted = pillProfiles.formattedName(roomId, userId, name) ?: return null
        return listOf(PillTextRun("@")) + formatted.runs.map { run ->
            when (run) {
                is FormattedNameRun.Emoji -> PillTextRun(run.text, isEmoji = true, emoji = pillProfiles.emoji(run.url))
                is FormattedNameRun.Text -> PillTextRun(run.text, run.bold, run.italic, run.underline, run.strikethrough)
            }
        }
    }

    /** Haven: the user's avatar from the room, or else from their global profile, like Haven Desktop's pills. */
    private fun avatarFor(userId: UserId): Bitmap? {
        val members = roomMemberProfilesCache.membersFlow.value
        val member = members[userId]
        val profile = pillProfiles.fallbackProfile(userId, members)
        val url = profile?.avatarUrl ?: member?.avatarUrl
        val name = profile?.displayName ?: member?.displayName
        return pillProfiles.avatar(PillAvatarKey(userId, url, name.takeIf { url == null }, theme.isDark.takeIf { url == null } ?: false))
    }
}

private object NoOpMentionSpanUpdater : MentionSpanUpdater {
    override fun updateMentionSpans(text: CharSequence): CharSequence {
        return text
    }

    @Composable
    override fun rememberMentionSpans(text: CharSequence): CharSequence {
        return text
    }
}

val LocalMentionSpanUpdater = staticCompositionLocalOf<MentionSpanUpdater> { NoOpMentionSpanUpdater }
