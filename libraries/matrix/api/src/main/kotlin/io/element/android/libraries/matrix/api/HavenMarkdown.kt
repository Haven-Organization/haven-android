/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.api

/**
 * Haven: the SDK's markdown to HTML conversion (the same one used when a message is sent from markdown), for when the
 * HTML has to be built in the app, e.g. to add custom emoji images to it.
 */
fun interface HavenMarkdown {
    fun toHtml(markdown: String): String?
}
