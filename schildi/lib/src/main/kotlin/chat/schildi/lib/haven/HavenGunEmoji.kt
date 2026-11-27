/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.lib.haven

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle as PlatformFontStyle
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import chat.schildi.lib.R
import chat.schildi.lib.preferences.ScPrefs.GunEmojiStyle
import java.util.concurrent.ConcurrentHashMap

/**
 * Haven Desktop's "Gun emoji" setting: 🔫 drawn as a revolver or a handgun instead of the water pistol every emoji font
 * has now. Like Desktop, it's a font containing only that one emoji (the Twemoji artwork, as a bitmap font like the
 * system's own emoji font) put ahead of the normal fonts, so every other character falls through to them as before.
 *
 * Needs Android 10 (a typeface with its own fallback chain); older versions keep the water pistol.
 */
object HavenGunEmoji {
    private fun fontRes(style: String): Int? = when (style) {
        GunEmojiStyle.REVOLVER -> R.font.haven_gun_revolver
        GunEmojiStyle.HANDGUN -> R.font.haven_gun_handgun
        else -> null
    }

    fun isSupported(style: String) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && fontRes(style) != null

    private val baseTypefaces = ConcurrentHashMap<String, Typeface>()
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    /** The gun emoji font, then the system's default font (and its fallbacks), in [weight] and italic or not. */
    fun typeface(context: Context, style: String, weight: Int = 400, italic: Boolean = false): Typeface? {
        if (!isSupported(style)) return null
        val res = fontRes(style)!!
        return typefaces.getOrPut("$style/$weight/$italic") {
            val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { buildExact(context, res, weight.coerceIn(1, 1000), italic) }.getOrNull()
            } else {
                null
            }
            exact ?: run {
                val base = baseTypefaces.getOrPut(style) { buildBase(context, res) }
                // Keeps the fallback chain, with the weight and slant the fallback fonts are picked by
                Typeface.create(base, weight.coerceIn(1, 1000), italic)
            }
        }
    }

    /**
     * The gun font, then exactly the font the default typeface draws text with in this weight (asked from the system by
     * shaping a letter with it, so a variable font comes with the same axis values), then the system's fallbacks. Text
     * then looks just as it does with the default typeface, apart from the gun.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun buildExact(context: Context, res: Int, weight: Int, italic: Boolean): Typeface {
        val paint = android.graphics.Paint().apply { typeface = Typeface.create(Typeface.DEFAULT, weight, italic) }
        val defaultFont = android.graphics.text.TextRunShaper.shapeTextRun("a", 0, 1, 0, 1, 0f, 0f, false, paint).getFont(0)
        val gunFont = Font.Builder(context.resources, res).build()
        val systemFamily = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Typeface.DEFAULT.systemFontFamilyName
        } else {
            null
        } ?: "sans-serif"
        return Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(gunFont).build())
            .addCustomFallback(android.graphics.fonts.FontFamily.Builder(defaultFont).build())
            .setSystemFallback(systemFamily)
            .setStyle(PlatformFontStyle(weight, if (italic) PlatformFontStyle.FONT_SLANT_ITALIC else PlatformFontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun buildBase(context: Context, res: Int): Typeface {
        val gunFont = Font.Builder(context.resources, res).build()
        return Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(gunFont).build())
            .setSystemFallback("sans-serif")
            .setStyle(PlatformFontStyle(PlatformFontStyle.FONT_WEIGHT_NORMAL, PlatformFontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }

    /**
     * A Compose font family for [style] that draws like [FontFamily.Default] in every weight, apart from the gun emoji.
     * Null when there's nothing to change (the water pistol, or too old an Android).
     */
    @OptIn(ExperimentalTextApi::class)
    fun fontFamily(style: String): FontFamily? {
        if (!isSupported(style)) return null
        val fonts = (100..900 step 100).flatMap { weight ->
            listOf(FontStyle.Normal, FontStyle.Italic).map { fontStyle -> GunFont(style, FontWeight(weight), fontStyle) }
        }
        return FontFamily(fonts)
    }

    @OptIn(ExperimentalTextApi::class)
    private class GunFont(
        val gunStyle: String,
        override val weight: FontWeight,
        override val style: FontStyle,
    ) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
        override fun equals(other: Any?) =
            other is GunFont && other.gunStyle == gunStyle && other.weight == weight && other.style == style

        override fun hashCode() = (gunStyle.hashCode() * 31 + weight.hashCode()) * 31 + style.hashCode()
    }

    @OptIn(ExperimentalTextApi::class)
    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
            val gunFont = font as GunFont
            return typeface(context, gunFont.gunStyle, gunFont.weight.weight, gunFont.style == FontStyle.Italic)
        }

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? = loadBlocking(context, font)
    }
}
