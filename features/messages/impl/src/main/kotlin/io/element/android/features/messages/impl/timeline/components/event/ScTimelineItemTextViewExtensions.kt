package io.element.android.features.messages.impl.timeline.components.event

import coil3.compose.AsyncImage
import androidx.compose.ui.unit.isSpecified
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import chat.schildi.lib.preferences.ScPrefs
import chat.schildi.lib.preferences.value
import chat.schildi.matrixsdk.containsOnlyEmojis
import chat.schildi.theme.scBubbleFont
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import com.beeper.android.messageformat.InlineImageInfo
import com.beeper.android.messageformat.MatrixBodyAnnotations
import com.beeper.android.messageformat.MatrixBodyParseResult
import com.beeper.android.messageformat.MatrixStyledFormattedText
import com.beeper.android.messageformat.toInlineContent
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.messages.impl.timeline.factories.event.LocalMatrixBodyDrawStyle
import io.element.android.features.messages.impl.timeline.factories.event.LocalMatrixBodyFormatter
import io.element.android.features.messages.impl.timeline.factories.event.matrixBodyDrawStyle
import io.element.android.features.messages.impl.timeline.factories.event.matrixBodyFormatter
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemEventContentWithAttachment
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemTextBasedContent
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrixmedia.api.MediaRequestData
import io.element.android.libraries.ui.common.layout.ContentAvoidingLayoutData
import io.element.android.wysiwyg.link.Link
import kotlin.math.roundToInt

// All values in DP here
private const val MAX_IMAGE_WIDTH = 250
private const val MAX_IMAGE_HEIGHT = 300
private const val MIN_IMAGE_WIDTH = 8
private const val MIN_IMAGE_HEIGHT = 8

@Composable
fun scRenderTimelineItemCaption(
    content: TimelineItemEventContent,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
): Boolean {
    if (ScPrefs.HAVEN_MESSAGE_RENDERER.value() != ScPrefs.MessageRenderer.SCHILDI || content !is TimelineItemEventContentWithAttachment) {
        return false
    }
    ScTimelineItemTextView(
        content = content,
        onLinkLongClick = onLinkLongClick,
        modifier = modifier,
        onContentLayoutChange = onContentLayoutChange,
    )
    return true
}

@Composable
fun ScTimelineItemTextView(
    content: TimelineItemTextBasedContent,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    ScTimelineItemTextView(
        content = content.formattedBodySc,
        onLinkLongClick = onLinkLongClick,
        modifier = modifier,
        onContentLayoutChange = onContentLayoutChange,
    )
}

@Composable
fun ScTimelineItemTextView(
    content: TimelineItemEventContentWithAttachment,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    ScTimelineItemTextView(
        content = content.formattedCaptionSc ?: MatrixBodyParseResult(content.caption ?: ""),
        onLinkLongClick = onLinkLongClick,
        modifier = modifier,
        onContentLayoutChange = onContentLayoutChange,
    )
}

@Composable
fun ScTimelineItemTextView(
    content: MatrixBodyParseResult,
    onLinkLongClick: (Link) -> Unit,
    modifier: Modifier = Modifier,
    onContentLayoutChange: (ContentAvoidingLayoutData) -> Unit = {},
) {
    // Haven: custom emoji count as emoji, so a message of only custom and/or Unicode emoji is shown large
    val emojiOnly = remember(content.text, content.inlineImages) { havenContainsOnlyEmojis(content.text, content.inlineImages) }
    val textStyle = when {
        emojiOnly -> ElementTheme.typography.fontHeadingXlRegular
        else -> ElementTheme.typography.scBubbleFont
    }
    val textColor = ElementTheme.colors.textPrimary
    val density = LocalDensity.current
    val actualImageSizes = remember(content.inlineImages) { mutableStateMapOf<String, IntSize>() }
    MatrixStyledFormattedText(
        content,
        color = textColor,
        style = if (content.inlineImages.isEmpty() && content.inlineTables.isEmpty()) {
            textStyle.copy(textDirection = TextDirection.Content)
        } else {
            // Allow inline images/content to increase the line height on demand by having this unspecified here
            textStyle.copy(lineHeight = TextUnit.Unspecified, textDirection = TextDirection.Content)
        },
        modifier = modifier,
        formatter = LocalMatrixBodyFormatter.current ?: matrixBodyFormatter(),
        drawStyle = LocalMatrixBodyDrawStyle.current ?: matrixBodyDrawStyle(),
        onTextLayout = { result ->
            val lastLine = if (result.lineCount > 0) {
                result.getLineRight(result.lineCount - 1).roundToInt()
            } else {
                result.size.width
            }
            onContentLayoutChange(
                ContentAvoidingLayoutData(
                    contentWidth = result.size.width,
                    contentHeight = result.size.height,
                    nonOverlappingContentWidth = lastLine,
                    nonOverlappingContentHeight = density.run { textStyle.lineHeight.roundToPx() },
                )
            )
        },
        inlineContent = { result ->
            // Haven: custom emoji as tall as the line in an emoji-only message (like Unicode emoji there), and a little taller
            // than the line among text; other images as before
            val lineHeight = textStyle.lineHeight.takeIf { it.isSpecified } ?: (textStyle.fontSize * 1.4f)
            val emoteHeight = if (emojiOnly) lineHeight else lineHeight * INLINE_EMOTE_SCALE
            fun Map<String, InlineImageInfo>.inline(defaultHeight: TextUnit) = toInlineContent(
                density = density,
                defaultHeight = defaultHeight,
                minWidth = MIN_IMAGE_WIDTH.dp,
                maxWidth = MAX_IMAGE_WIDTH.dp,
                minHeight = MIN_IMAGE_HEIGHT.dp,
                maxHeight = MAX_IMAGE_HEIGHT.dp,
                actualImageSizes = actualImageSizes,
            ) { info, modifier ->
                InlineImage(
                    info = info,
                    textStyle = textStyle,
                    textColor = textColor,
                    modifier = modifier,
                    onPainterSuccess = { result -> actualImageSizes[info.uri] = IntSize(result.image.width, result.image.height) },
                )
            }
            val (emotes, images) = result.inlineImages.entries.partition { it.value.isHavenEmote() }
            emotes.associate { it.key to it.value.copy(isEmote = true) }.inline(emoteHeight) +
                images.associate { it.key to it.value }.inline(lineHeight)
        },
        onLinkLongPress = { link ->
            (link as? LinkAnnotation.Url)?.url?.let { url ->
                onLinkLongClick(Link(url))
            }
        }
    )
}

@Composable
private fun InlineImage(
    info: InlineImageInfo,
    textStyle: TextStyle,
    textColor: Color,
    modifier: Modifier = Modifier,
    onPainterSuccess: (SuccessResult) -> Unit = {},
) {
    val context = LocalContext.current
    val model = remember(info) {
        val data = MediaRequestData(MediaSource(info.uri), MediaRequestData.Kind.Content)
        // Non-square custom emotes may unexpectedly resize once we know the measures, and become blurry.
        // For huge inline images it's probably safer to keep not loading the full original size.
        if (info.isEmote && info.width == null) {
            ImageRequest.Builder(context)
                .data(data)
                .size(Size.ORIGINAL)
                .build()
        } else {
            data
        }
    }
    // Haven: drawn with a plain AsyncImage, which plays animated images (GIF, WebP, APNG); the fallback text shows until
    // the image is in
    var loaded by remember(info) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!loaded) {
            Text(
                info.alt ?: info.title ?: "\uFFFD",
                style = textStyle,
                color = textColor,
            )
        }
        AsyncImage(
            model = model,
            contentDescription = info.alt ?: info.title,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            modifier = Modifier.matchParentSize(),
            onSuccess = { state ->
                loaded = true
                onPainterSuccess(state.result)
            },
        )
    }
}

/** Haven: how much taller than the line a custom emoji among text is. */
private const val INLINE_EMOTE_SCALE = 1.2f

/** Haven: a custom emoji: marked as one (`data-mx-emoticon`), or named by a `:shortcode:` as Matrix clients do. */
private fun InlineImageInfo.isHavenEmote(): Boolean =
    isEmote || listOfNotNull(alt, title).any { SHORTCODE.matches(it) }

private val SHORTCODE = Regex("^:[^\\s:]+:$")

/** The annotation Compose's appendInlineContent puts on inline content. */
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/**
 * Haven: whether [text] is only emoji (at most 50): Unicode emoji and custom emoji, the inline images in [inlineImages]
 * that are custom emoji; any other image means it isn't. Whitespace and zero-width characters don't count.
 */
internal fun havenContainsOnlyEmojis(text: AnnotatedString, inlineImages: Map<String, InlineImageInfo>): Boolean {
    if (inlineImages.values.any { !it.isHavenEmote() }) return false
    val imageRanges = text.getStringAnnotations(MatrixBodyAnnotations.INLINE_IMAGE, 0, text.length)
    val contentRanges = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
    val ranges = (imageRanges + contentRanges).map { it.start until it.end }
    // Each custom emoji has both annotations; count it once
    val emotes = maxOf(imageRanges.size, contentRanges.size)
    val rest = StringBuilder()
    text.text.forEachIndexed { index, char -> if (ranges.none { index in it }) rest.append(char) }
    val remaining = rest.toString().filterNot { it.isWhitespace() || it == '\u200B' || it == '\uFEFF' || it == '\u2060' }
    if (remaining.isEmpty()) return emotes > 0
    return remaining.containsOnlyEmojis(50 - emotes)
}

@Composable
internal fun containsOnlyEmojisOrEmotes(text: AnnotatedString, inlineImages: Map<String, InlineImageInfo>): Boolean {
    return remember(text) { text.containsOnlyEmojis(inlineImages, 50) }
}

fun AnnotatedString.containsOnlyEmojis(
    inlineImages: Map<String, InlineImageInfo> = emptyMap(),
    maxEmojis: Int = Integer.MAX_VALUE,
): Boolean {
    return if (inlineImages.isNotEmpty()) {
        if (inlineImages.values.any { !it.isEmote }) {
            return false
        }
        // If all is custom emotes, those also count as emojis.
        val customEmotes = getStringAnnotations(MatrixBodyAnnotations.INLINE_IMAGE, 0, length)
            .sortedByDescending { it.start }
        var toCheck: CharSequence = this
        customEmotes.forEach {
            toCheck = toCheck.removeRange(it.start, it.end)
        }
        toCheck.toString().replace(" ", "").containsOnlyEmojisOrIsEmpty(maxEmojis - customEmotes.size)
    } else {
        toString().replace(" ", "").containsOnlyEmojis(maxEmojis)
    }
}

fun String.containsOnlyEmojisOrIsEmpty(maxEmojis: Int = Integer.MAX_VALUE, throwOnError: Boolean = false) = isEmpty() || containsOnlyEmojis(maxEmojis, throwOnError)
