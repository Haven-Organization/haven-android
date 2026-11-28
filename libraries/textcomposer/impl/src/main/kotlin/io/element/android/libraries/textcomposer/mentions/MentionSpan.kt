/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.mentions

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ReplacementSpan
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.core.text.getSpans
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomIdOrAlias
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.wysiwyg.view.spans.CustomMentionSpan
import kotlin.math.ceil

/**
 * A span that represents a mention (user, room, etc.) in text.
 * @param type The type of mention this span represents.
 */
class MentionSpan(
    val type: MentionType,
) : ReplacementSpan() {
    private val backgroundPaint = Paint()
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val avatarMatrix = Matrix()

    private var backgroundColor: Int = 0
    private var textColor: Int = 0
    private var startPadding: Int = 0
    private var endPadding: Int = 0
    private var typeface: Typeface = Typeface.DEFAULT

    /**
     * The widest the pill is allowed to be, in pixels. A pill wider than this is truncated, so that
     * [getSize] never reports a width the line can't accommodate: the layout would then be unable to
     * move the pill to the next line and would leave it overflowing the end of the current one.
     */
    private var maxWidth: Int = NO_MAX_WIDTH

    // The formatted display text, will be set by the formatter
    var displayText: CharSequence = ""
        private set

    var trailingText: String? = null
        private set

    /** Haven: the avatar drawn at the start of a user pill, like Haven Desktop's pills. */
    private var avatar: Bitmap? = null

    /**
     * Updates the visual properties of this span.
     */
    fun updateTheme(mentionSpanTheme: MentionSpanTheme) {
        val isCurrentUser = when (type) {
            is MentionType.User -> type.userId == mentionSpanTheme.currentUserId
            else -> false
        }

        backgroundColor = when (type) {
            is MentionType.User -> if (isCurrentUser) mentionSpanTheme.currentUserBackgroundColor else mentionSpanTheme.otherBackgroundColor
            is MentionType.Everyone -> mentionSpanTheme.otherBackgroundColor
            is MentionType.Room -> mentionSpanTheme.otherBackgroundColor
            is MentionType.Message -> mentionSpanTheme.otherBackgroundColor
        }

        textColor = when (type) {
            is MentionType.User -> if (isCurrentUser) mentionSpanTheme.currentUserTextColor else mentionSpanTheme.otherTextColor
            is MentionType.Everyone -> mentionSpanTheme.otherTextColor
            is MentionType.Room -> mentionSpanTheme.otherTextColor
            is MentionType.Message -> mentionSpanTheme.otherTextColor
        }

        val (startPaddingPx, endPaddingPx) = mentionSpanTheme.paddingValuesPx.value
        startPadding = startPaddingPx
        endPadding = endPaddingPx
        typeface = mentionSpanTheme.typeface.value
    }

    /**
     * Updates the display text using a formatter.
     */
    fun updateDisplayText(formatter: MentionSpanFormatter) {
        displayText = formatter.formatDisplayText(type)
    }

    fun updateTrailingText(trailingText: String?) {
        this.trailingText = trailingText
    }

    /** Haven: sets the avatar drawn at the start of the pill, or none. */
    fun updateAvatar(avatar: Bitmap?) {
        if (avatar === this.avatar) return
        this.avatar = avatar
        avatarPaint.shader = avatar?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    }

    /**
     * Sets the widest this pill may be. Pass [NO_MAX_WIDTH] to leave it unbounded.
     */
    fun updateMaxWidth(maxWidthPx: Int) {
        maxWidth = maxWidthPx
    }

    /**
     * Measures the pill with [paint], truncating the display text so the result never exceeds
     * [maxWidth].
     *
     * Both [getSize] and [draw] go through here: the layout reserves exactly what is painted. They
     * can't share a cached measurement instead, because the platform skips [getSize] before [draw]
     * for the last span of a line, and the same [MentionSpan] instance is reused across ranges and
     * across layouts.
     */
    private fun measure(paint: Paint): Measurements {
        textPaint.set(paint)
        textPaint.typeface = typeface
        val trailing = trailingText?.let { " $it" }
        val trailingWidth = trailing?.let { textPaint.measureText(it) } ?: 0f
        // Haven: the avatar is as tall as the text, a little in from the pill's rounded start
        val avatarSize = if (avatar != null) textPaint.fontMetrics.let { it.descent - it.ascent } else 0f
        val leading = if (avatar != null) startPadding / 2f + avatarSize + startPadding else startPadding.toFloat()
        val availableForText = if (maxWidth == NO_MAX_WIDTH) {
            Float.MAX_VALUE
        } else {
            (maxWidth - leading - endPadding - trailingWidth).coerceAtLeast(0f)
        }
        val fullTextWidth = textPaint.measureText(displayText, 0, displayText.length)
        return if (fullTextWidth <= availableForText) {
            Measurements(displayText, fullTextWidth, trailing, trailingWidth, leading, avatarSize)
        } else {
            val truncated = TextUtils.ellipsize(displayText, textPaint, availableForText, TextUtils.TruncateAt.END)
            Measurements(truncated, textPaint.measureText(truncated, 0, truncated.length), trailing, trailingWidth, leading, avatarSize)
        }
    }

    private inner class Measurements(
        val text: CharSequence,
        val textWidth: Float,
        val trailing: String?,
        val trailingWidth: Float,
        /** What comes before the text: the start padding, or the avatar and the space around it. */
        val leading: Float,
        val avatarSize: Float,
    ) {
        val width: Float get() = leading + textWidth + trailingWidth + endPadding
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return ceil(measure(paint).width).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val measurements = measure(paint)
        backgroundPaint.color = backgroundColor
        val rect = RectF(x, top.toFloat(), x + measurements.width, bottom.toFloat())
        val radius = rect.height() / 2
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)

        textPaint.set(paint)
        textPaint.color = textColor
        textPaint.typeface = typeface

        canvas.drawText(measurements.text, 0, measurements.text.length, x + measurements.leading, y.toFloat(), textPaint)
        measurements.trailing?.let { trailing ->
            canvas.drawText(trailing, x + measurements.leading + measurements.textWidth, y.toFloat(), textPaint)
        }
        avatar?.let { drawAvatar(canvas, it, x + startPadding / 2f, y, measurements.avatarSize) }
    }

    /** Haven: draws [bitmap] as a circle [size] wide from [left], centred on the text of the line with baseline [y]. */
    private fun drawAvatar(canvas: Canvas, bitmap: Bitmap, left: Float, y: Int, size: Float) {
        if (size <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
        val fontMetrics = textPaint.fontMetrics
        val centerY = y + (fontMetrics.ascent + fontMetrics.descent) / 2f
        val top = centerY - size / 2f
        avatarMatrix.setScale(size / bitmap.width, size / bitmap.height)
        avatarMatrix.postTranslate(left, top)
        avatarPaint.shader?.setLocalMatrix(avatarMatrix)
        canvas.drawCircle(left + size / 2f, centerY, size / 2f, avatarPaint)
    }

    companion object {
        const val NO_MAX_WIDTH = Int.MAX_VALUE
    }
}

/**
 * Sealed interface representing different types of mentions.
 */
sealed interface MentionType {
    data class User(val userId: UserId) : MentionType
    data class Room(val roomIdOrAlias: RoomIdOrAlias) : MentionType
    data class Message(val roomIdOrAlias: RoomIdOrAlias, val eventId: EventId) : MentionType
    data object Everyone : MentionType
}

/**
 * Extension function to get all MentionSpans from a CharSequence.
 */
fun CharSequence.getMentionSpans(start: Int = 0, end: Int = length): List<MentionSpan> {
    return if (this is android.text.Spanned) {
        // If we have custom mention spans created by the RTE, we need to extract the provided spans and filter them
        val customMentionSpans = getSpans<CustomMentionSpan>(start, end)
            .map { it.providedSpan }
            .filterIsInstance<MentionSpan>()
        // Collect all direct mention spans
        val directMentionSpans = getSpans<MentionSpan>(start, end)
        // Return the union of both
        customMentionSpans + directMentionSpans
    } else {
        emptyList()
    }
}

/**
 * Caps the [MentionSpan]s held by [text] to the width available to the text they are part of.
 *
 * A pill wider than a whole line can't be moved to the next line by the text layout, so it stays
 * overflowing the end of the line it landed on. Capping it makes [MentionSpan.getSize] report a width a line can hold, so the layout moves
 * the pill to the next line and the pill truncates its own text instead.
 */
fun Modifier.limitMentionPillWidth(text: CharSequence): Modifier = layout { measurable, constraints ->
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else MentionSpan.NO_MAX_WIDTH
    text.getMentionSpans().forEach { it.updateMaxWidth(maxWidth) }
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
