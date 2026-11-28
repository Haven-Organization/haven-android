/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.preferences.tweaks

import androidx.compose.runtime.Composable
import io.element.android.features.preferences.impl.labs.LabsEvent
import io.element.android.features.preferences.impl.labs.LabsPresenter
import io.element.android.libraries.designsystem.components.ProgressDialog
import io.element.android.libraries.designsystem.components.list.ListItemContent
import io.element.android.libraries.designsystem.components.list.SwitchListItem

/**
 * Haven: Element's lab features (threads, gallery messages, ask to join), at the top of Haven's Labs screen, which
 * replaces Element's own Labs screen so all of them are in one place.
 */
@Composable
internal fun HavenElementLabs(presenter: LabsPresenter) {
    val state = presenter.present()
    if (state.isApplyingChanges) {
        ProgressDialog()
    }
    state.features.forEach { feature ->
        SwitchListItem(
            leadingContent = feature.icon?.let { ListItemContent.Icon(it) },
            headline = feature.title,
            supportingText = feature.description,
            value = feature.isEnabled,
            onChange = { state.eventSink(LabsEvent.ToggleFeature(feature)) },
        )
    }
}
