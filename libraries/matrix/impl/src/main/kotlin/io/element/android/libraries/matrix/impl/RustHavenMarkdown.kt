/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.matrix.api.HavenMarkdown
import org.matrix.rustcomponents.sdk.markdownToHtml

@ContributesBinding(AppScope::class)
class RustHavenMarkdown : HavenMarkdown {
    override fun toHtml(markdown: String): String? = markdownToHtml(markdown)
}
