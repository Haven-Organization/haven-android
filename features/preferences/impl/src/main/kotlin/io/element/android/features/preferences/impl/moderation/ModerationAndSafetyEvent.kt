/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.preferences.impl.moderation

import io.element.android.libraries.matrix.api.media.MediaPreviewValue

sealed interface ModerationAndSafetyEvent {
    // Haven: separate toggles instead of upstream's single "Share presence"
    data class SetSendReadReceiptsEnabled(val enabled: Boolean) : ModerationAndSafetyEvent
    data class SetSendTypingNotificationsEnabled(val enabled: Boolean) : ModerationAndSafetyEvent
    data class SetHideInviteAvatars(val value: Boolean) : ModerationAndSafetyEvent
    data class SetTimelineMediaPreviewValue(val value: MediaPreviewValue) : ModerationAndSafetyEvent
}
