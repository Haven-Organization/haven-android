/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package io.element.android.libraries.matrix.ui.formattedname

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import coil3.compose.AsyncImage
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap

private const val EMOJI_TAG_PREFIX = "haven_name_emoji_"
private const val EMOJI_THUMBNAIL_SIZE = 96L

/** Whether formatted names (display names and room names) are turned on (the Labs setting). */
@Composable
fun formattedDisplayNamesEnabled(): Boolean = ScPrefs.HAVEN_FORMATTED_DISPLAY_NAMES.value()

/** [name] as styled text, with a placeholder for each custom emoji (see [inlineContent]). */
fun FormattedName.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    runs.forEachIndexed { index, run ->
        when (run) {
            is FormattedNameRun.Emoji -> appendInlineContent("$EMOJI_TAG_PREFIX$index", run.description.ifEmpty { "�" })
            is FormattedNameRun.Text -> {
                val decorations = listOfNotNull(
                    TextDecoration.Underline.takeIf { run.underline },
                    TextDecoration.LineThrough.takeIf { run.strikethrough },
                )
                withStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold.takeIf { run.bold },
                        fontStyle = FontStyle.Italic.takeIf { run.italic },
                        textDecoration = decorations.takeIf { it.isNotEmpty() }?.let { TextDecoration.combine(it) },
                    )
                ) { append(run.text) }
            }
        }
    }
}

/** The custom emoji of [FormattedName.toAnnotatedString], drawn a little taller than the text (like Haven Desktop's). */
fun FormattedName.inlineContent(): ImmutableMap<String, InlineTextContent> = runs.withIndex()
    .filter { it.value is FormattedNameRun.Emoji }
    .associate { (index, run) ->
        "$EMOJI_TAG_PREFIX$index" to InlineTextContent(
            Placeholder(width = 1.25.em, height = 1.25.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
        ) {
            AsyncImage(
                model = MediaRequestData(MediaSource((run as FormattedNameRun.Emoji).url), MediaRequestData.Kind.Thumbnail(EMOJI_THUMBNAIL_SIZE)),
                contentDescription = run.description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    .toImmutableMap()

/**
 * A display name: [formatted] when there is one and formatted display names are turned on, else the plain [name].
 * Takes the same styling as [Text], which it is.
 */
@Composable
fun DisplayNameText(
    name: String,
    formatted: FormattedName?,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val shown = formatted?.takeIf { formattedDisplayNamesEnabled() }
    val textStyle = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    if (shown == null) {
        Text(
            text = name,
            modifier = modifier,
            color = color,
            style = textStyle,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
        )
    } else {
        val text = remember(shown) { shown.toAnnotatedString() }
        val inlineContent = remember(shown) { shown.inlineContent() }
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = textStyle,
            textAlign = textAlign,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
            inlineContent = inlineContent,
        )
    }
}
