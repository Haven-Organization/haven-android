/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator

/**
 * A loading spinner that keeps turning when the system's animations are turned off (animator duration scale 0), where
 * the stock indeterminate spinner stands still and looks stuck. It's driven by frame times directly, which that
 * setting doesn't scale.
 */
@Composable
fun SocialSpinner(modifier: Modifier = Modifier) {
    val rotation = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { now -> rotation.floatValue = ((now - start) % 1_000L) * 0.36f }
        }
    }
    CircularProgressIndicator(
        progress = { 0.3f },
        trackColor = Color.Transparent,
        modifier = modifier.graphicsLayer { rotationZ = rotation.floatValue },
    )
}
