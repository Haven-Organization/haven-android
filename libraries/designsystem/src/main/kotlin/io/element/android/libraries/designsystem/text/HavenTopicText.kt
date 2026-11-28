/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import io.element.android.compound.theme.ElementTheme

/*
 * Haven: room topics that are HTML, shown formatted rather than as raw tags. Bridged profiles and groups carry the remote
 * account's bio as their topic, and on the fediverse that's HTML (Mastodon's `summary`), so it's put in the plain topic
 * too, where Element X shows it as text.
 */

private val HTML_TAG = Regex("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^>]*)?/?>")

// Mastodon shortens the links in the HTML it sends with spans its own CSS hides (`invisible`) or ends with "…" (`ellipsis`)
private val INVISIBLE_SPAN = Regex("<span\\s+class=\"invisible\">[^<]*</span>")
private val ELLIPSIS_SPAN = Regex("<span\\s+class=\"ellipsis\">([^<]*)</span>")

/** Whether [topic] has HTML tags in it. */
fun topicLooksLikeHtml(topic: String): Boolean = HTML_TAG.containsMatchIn(topic)

/** [topic] as text: formatted (bold, italics, links, line breaks) when it's HTML, else as it is. */
fun topicText(topic: String, linkColor: Color = Color.Unspecified): AnnotatedString {
    if (!topicLooksLikeHtml(topic)) return AnnotatedString(topic)
    return runCatching {
        val html = topic.replace(INVISIBLE_SPAN, "").replace(ELLIPSIS_SPAN, "$1…")
        AnnotatedString.fromHtml(
            htmlString = html,
            linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
        ).trimmed()
    }.getOrElse { AnnotatedString(topic) }
}

/** [topicText] on one line, for lists. */
fun topicPlainText(topic: String): String =
    if (!topicLooksLikeHtml(topic)) topic else topicText(topic).text.replace(Regex("\\s+"), " ").trim()

/** [topicText], with the theme's link colour. */
@Composable
fun rememberTopicText(topic: String): AnnotatedString {
    val linkColor = ElementTheme.colors.textLinkExternal
    return remember(topic, linkColor) { topicText(topic, linkColor) }
}

/** Without the blank lines paragraphs leave at the start and end. */
private fun AnnotatedString.trimmed(): AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return subSequence(start, end)
}
