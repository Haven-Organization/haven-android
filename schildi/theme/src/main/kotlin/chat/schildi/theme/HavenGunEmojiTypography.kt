/*
 * Copyright (c) 2026 Haven
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package chat.schildi.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import io.element.android.compound.tokens.sc.ExposedTypographyTokens

/*
 * Haven: the theme's text styles with the gun emoji font family (see chat.schildi.lib.haven.HavenGunEmoji) in place of the
 * default font, so 🔫 is drawn as the chosen gun in all the app's text. Styles using a font of their own keep it.
 */

private fun TextStyle.withGunFont(family: FontFamily): TextStyle =
    if (fontFamily == null || fontFamily == FontFamily.Default || fontFamily == FontFamily.SansSerif) copy(fontFamily = family) else this

internal fun Typography.withGunFont(family: FontFamily) = copy(
    displayLarge = displayLarge.withGunFont(family),
    displayMedium = displayMedium.withGunFont(family),
    displaySmall = displaySmall.withGunFont(family),
    headlineLarge = headlineLarge.withGunFont(family),
    headlineMedium = headlineMedium.withGunFont(family),
    headlineSmall = headlineSmall.withGunFont(family),
    titleLarge = titleLarge.withGunFont(family),
    titleMedium = titleMedium.withGunFont(family),
    titleSmall = titleSmall.withGunFont(family),
    bodyLarge = bodyLarge.withGunFont(family),
    bodyMedium = bodyMedium.withGunFont(family),
    bodySmall = bodySmall.withGunFont(family),
    labelLarge = labelLarge.withGunFont(family),
    labelMedium = labelMedium.withGunFont(family),
    labelSmall = labelSmall.withGunFont(family),
)

internal class GunFontTypographyTokens(base: ExposedTypographyTokens, family: FontFamily) : ExposedTypographyTokens {
    override val fontBodyLgMedium = base.fontBodyLgMedium.withGunFont(family)
    override val fontBodyLgRegular = base.fontBodyLgRegular.withGunFont(family)
    override val fontBodyMdMedium = base.fontBodyMdMedium.withGunFont(family)
    override val fontBodyMdRegular = base.fontBodyMdRegular.withGunFont(family)
    override val fontBodySmMedium = base.fontBodySmMedium.withGunFont(family)
    override val fontBodySmRegular = base.fontBodySmRegular.withGunFont(family)
    override val fontBodyXsMedium = base.fontBodyXsMedium.withGunFont(family)
    override val fontBodyXsRegular = base.fontBodyXsRegular.withGunFont(family)
    override val fontHeadingLgBold = base.fontHeadingLgBold.withGunFont(family)
    override val fontHeadingLgRegular = base.fontHeadingLgRegular.withGunFont(family)
    override val fontHeadingMdBold = base.fontHeadingMdBold.withGunFont(family)
    override val fontHeadingMdRegular = base.fontHeadingMdRegular.withGunFont(family)
    override val fontHeadingSmMedium = base.fontHeadingSmMedium.withGunFont(family)
    override val fontHeadingSmRegular = base.fontHeadingSmRegular.withGunFont(family)
    override val fontHeadingXlBold = base.fontHeadingXlBold.withGunFont(family)
    override val fontHeadingXlRegular = base.fontHeadingXlRegular.withGunFont(family)
}
