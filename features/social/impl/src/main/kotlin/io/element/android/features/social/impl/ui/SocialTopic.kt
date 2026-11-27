/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.features.social.impl.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.social.impl.SocialDeps
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.htmlrenderer.api.ui.HtmlMessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.FormattedBody
import io.element.android.libraries.matrix.api.timeline.item.event.MessageFormat
import io.element.android.libraries.matrix.ui.messages.toHtmlDocument
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/*
 * Haven: bridged profiles and groups carry the remote account's bio as their topic, and on the fediverse that's HTML
 * (Mastodon's `summary`), so the topic is shown formatted rather than as raw tags.
 */

private val HTML_TAG = Regex("</?[a-zA-Z][a-zA-Z0-9]*(\\s[^>]*)?/?>")

internal fun looksLikeHtml(text: String): Boolean = HTML_TAG.containsMatchIn(text)

/**
 * Mastodon shortens links in HTML it sends with spans its own CSS hides (`invisible`) or ends with "…" (`ellipsis`).
 */
private fun Document.applyMastodonLinkClasses(): Document = apply {
    select("span.invisible").remove()
    select("span.ellipsis").append("…")
}

/** The topic as one line of plain text, for lists. */
internal fun topicPlainText(topic: String): String {
    if (!looksLikeHtml(topic)) return topic
    return runCatching { Jsoup.parse(topic).applyMastodonLinkClasses().text() }.getOrDefault(topic)
}

/** The topic in a profile or group header, formatted when it's HTML. */
@Composable
internal fun SocialTopicText(topic: String, deps: SocialDeps, modifier: Modifier = Modifier) {
    val documentNode = remember(topic) {
        if (!looksLikeHtml(topic)) return@remember null
        runCatching {
            FormattedBody(format = MessageFormat.HTML, body = topic).toHtmlDocument(deps.permalinkParser)
                ?.applyMastodonLinkClasses()
                ?.let(deps.htmlMessageParser::parse)
        }.getOrNull()
    }
    if (documentNode != null) {
        val uriHandler = LocalUriHandler.current
        HtmlMessageContent(
            node = documentNode,
            currentUserId = deps.matrixClient.sessionId,
            onLinkClick = { url, _ -> runCatching { uriHandler.openUri(url) } },
            modifier = modifier.padding(top = 8.dp),
        )
    } else {
        Text(
            text = topic,
            style = ElementTheme.typography.fontBodyMdRegular,
            color = ElementTheme.colors.textPrimary,
            modifier = modifier.padding(top = 8.dp),
        )
    }
}
