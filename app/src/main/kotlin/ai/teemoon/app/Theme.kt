// design: teemoon-ios docs/DESIGN.md @ v1.0.1, teemoon/Settings/Appearance.swift @ v1.0.1 (PlatformColors) — REBUILD in Compose.
//         The iOS semantic palette and type scale expressed as a Material 3 color scheme plus the palette the kit reads
//         directly. Dark-first: the chat sits on the system background (black / white), grouped screens on the grouped
//         ground (black / #F2F2F7) with raised cards (#1C1C1E / white). The tint is the user's appearance choice; orange
//         is the default. TEE-verified green is semantic and never the tint.
package ai.teemoon.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * Follow the phone's light/dark setting (the maintainer 2026-10-28: light is required in 1.1.0, "as aesthetic as the dark mode",
 * because Android gives an app no way to ask for a dark keyboard and a light Gboard under a dark-only teemoon "looks
 * horrible"). The switch is Android's counterpart of iOS's `UIUserInterfaceStyle: Dark` plist key: false keeps the app
 * pinned dark, as it shipped through 1.0.6. Turning it on is the theme work's LAST commit, after the maintainer has seen the
 * light screenshots; until then every light change lands behind it and dark is untouched. No in-app toggle (pm's
 * light-theme-colors entry): the system setting is the control.
 */
const val FOLLOWS_SYSTEM_APPEARANCE = false

/**
 * The theme's colours that differ by role, light and dark — iOS `Palette` (teemoon/Settings/Appearance.swift @ 65b00fc).
 * Change a value here, never at a use site; PaletteContrastTest keeps every text colour at 4.5:1 on its ground. Light values are pm's, approved as final (review entry light-theme-colors, the maintainer 2026-10-28).
 * Dark values are what Android drew before, so dark is unchanged. One known gap: iOS's dark verified green is #5cc78c
 * (shipped since 08-01), Android's #30d158; moving it waits on the maintainer (review entry android-dark-green).
 */
object Palette {
    data class Pair(val light: Color, val dark: Color) { fun of(dark: Boolean) = if (dark) this.dark else light }

    /** Fills and icons: the brand orange in both appearances. Not for text on white (2.6:1). */
    val accentFill = Pair(light = Color(0xFFFF7A1A), dark = Color(0xFFFF7A1A))
    /** Text, links and the tint. */
    val accentText = Pair(light = Color(0xFFC2410C), dark = Color(0xFFFF7A1A))
    /** TEE-verified. iOS's systemGreen fails as text on white. */
    val positiveText = Pair(light = Color(0xFF15803D), dark = Color(0xFF30D158))
    val warningText = Pair(light = Color(0xFFB45309), dark = Color(0xFFFF9F0A))

    /** Every pair drawn as text, for the contrast test. */
    val textPairs = listOf("accentText" to accentText, "positiveText" to positiveText, "warningText" to warningText)
}

/**
 * PlatformColors, resolved for one appearance. Backgrounds run one way in dark mode
 * (system #000 < secondary #1C1C1E < tertiary #2C2C2E — tertiary is the LIGHTEST), which
 * is the ladder the raised state of a chip depends on; see Appearance.swift's note.
 */
data class TeemoonPalette(
    val dark: Boolean,
    val background: Color,
    val secondaryBackground: Color,
    val tertiaryBackground: Color,
    val groupedBackground: Color,
    val groupedCard: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val orange: Color,
    val green: Color,
    val blue: Color,
    val red: Color,
    val gray: Color,
    val tint: Color,
    /** [Palette.accentFill]: the orange a fill or icon keeps when the tint (text) darkens in light. */
    val accentFill: Color,
) {
    companion object {
        fun light(tint: AppTintColor) = TeemoonPalette(
            dark = false,
            background = Color(0xFFFFFFFF), secondaryBackground = Color(0xFFF2F2F7), tertiaryBackground = Color(0xFFFFFFFF),
            groupedBackground = Color(0xFFF2F2F7), groupedCard = Color(0xFFFFFFFF),
            label = Color(0xFF000000), secondaryLabel = Color(0xFF6D6D72), tertiaryLabel = Color(0xFFAEAEB2), separator = Color(0xFFD8D8DC),
            fill = Color(0x29787880),
            orange = Palette.warningText.light, green = Palette.positiveText.light, blue = Color(0xFF007AFF), red = Color(0xFFFF3B30), gray = Color(0xFF8E8E93),
            tint = tintColor(tint, dark = false), accentFill = Palette.accentFill.light,
        )
        fun dark(tint: AppTintColor) = TeemoonPalette(
            dark = true,
            background = Color(0xFF000000), secondaryBackground = Color(0xFF1C1C1E), tertiaryBackground = Color(0xFF2C2C2E),
            groupedBackground = Color(0xFF000000), groupedCard = Color(0xFF1C1C1E),
            label = Color(0xFFFFFFFF), secondaryLabel = Color(0xFF98989F), tertiaryLabel = Color(0xFF636366), separator = Color(0xFF38383A),
            fill = Color(0x5C787880),
            orange = Palette.warningText.dark, green = Palette.positiveText.dark, blue = Color(0xFF0A84FF), red = Color(0xFFFF453A), gray = Color(0xFF8E8E93),
            tint = tintColor(tint, dark = true), accentFill = Palette.accentFill.dark,
        )

        /** Appearance.swift's tint list, as the iOS system colors render in each appearance. */
        fun tintColor(tint: AppTintColor, dark: Boolean): Color = when (tint) {
            // ML.accent (#FF7A1A), the product orange in both appearances — NOT systemOrange (#FF9F0A / #FF9500), which
            // read yellower than every iOS capture until the numbers were compared (2026-09-05). The tint colours text and
            // links, so light takes the text shade (iOS: the window tint is Palette.accentText).
            AppTintColor.ORANGE -> Palette.accentText.of(dark)
            AppTintColor.BLUE -> if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
            AppTintColor.GREEN -> if (dark) Color(0xFF30D158) else Color(0xFF34C759)
            AppTintColor.RED -> if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
            AppTintColor.PINK -> if (dark) Color(0xFFFF375F) else Color(0xFFFF2D55)
            AppTintColor.PURPLE -> if (dark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
            AppTintColor.INDIGO -> if (dark) Color(0xFF5E5CE6) else Color(0xFF5856D6)
            AppTintColor.TEAL -> if (dark) Color(0xFF40C8E0) else Color(0xFF30B0C7)
            AppTintColor.MINT -> if (dark) Color(0xFF63E6E2) else Color(0xFF00C7BE)
            AppTintColor.YELLOW -> if (dark) Color(0xFFFFD60A) else Color(0xFFFFCC00)
            AppTintColor.BROWN -> if (dark) Color(0xFFAC8E68) else Color(0xFFA2845E)
            AppTintColor.GRAY -> Color(0xFF8E8E93)
            AppTintColor.MONOCHROME -> if (dark) Color(0xFFFFFFFF) else Color(0xFF000000)
        }
    }
}

/**
 * The ELEVATED palette: what UIKit resolves inside a presented sheet in dark mode — the whole ladder shifts one rung
 * (systemBackground #1C1C1E, secondary #2C2C2E, tertiary #3A3A3C; grouped ground #1C1C1E with #2C2C2E cards), so
 * a sheet reads as a lighter surface over the black chat. Light mode is unchanged: iOS elevates nothing there.
 * Measured on the tag's captures 2026-09-04 (sheet ground 28,28,30; card 44,44,46) after the port shipped sheets on
 * the base ladder (black ground, #1C1C1E cards) and nobody looked at the numbers.
 */
fun TeemoonPalette.elevated(): TeemoonPalette = if (!dark) this else copy(
    background = Color(0xFF1C1C1E), secondaryBackground = Color(0xFF2C2C2E), tertiaryBackground = Color(0xFF3A3A3C),
    groupedBackground = Color(0xFF1C1C1E), groupedCard = Color(0xFF2C2C2E),
)

val LocalTeemoonPalette = staticCompositionLocalOf { TeemoonPalette.dark(AppTintColor.ORANGE) }

/** The palette the kit reads directly — the semantic colors Material has no slot for. */
object TeemoonColors {
    val palette: TeemoonPalette @Composable get() = LocalTeemoonPalette.current
    val secondaryBackground: Color @Composable get() = palette.secondaryBackground
    val tertiaryBackground: Color @Composable get() = palette.tertiaryBackground
    val groupedBackground: Color @Composable get() = palette.groupedBackground
    val groupedCard: Color @Composable get() = palette.groupedCard
    /** A sheet's pinned band (the where sheet's reasoning bar): [sheetBand]. */
    val sheetBand: Color @Composable get() = sheetBand(palette)
    val background: Color @Composable get() = palette.background
    val label: Color @Composable get() = palette.label
    val secondaryLabel: Color @Composable get() = palette.secondaryLabel
    val tertiaryLabel: Color @Composable get() = palette.tertiaryLabel
    val separator: Color @Composable get() = palette.separator
    val fill: Color @Composable get() = palette.fill
    /**
     * A chip inside the composer's panel (COMPOSER_PANEL.md: plain capsules on the tertiary fill): the tertiary background
     * in the dark; in the light that rung is white, the chat's own ground, so the system fill stands in (as Capsule's raised).
     */
    val panelChip: Color @Composable get() = if (palette.dark) palette.tertiaryBackground else palette.fill
    /** TEE-verified. Semantic: never the tint, never composed into copy. */
    val verified: Color @Composable get() = palette.green
    val blue: Color @Composable get() = palette.blue
    val red: Color @Composable get() = palette.red
    val orange: Color @Composable get() = palette.orange
    val gray: Color @Composable get() = palette.gray
    val tint: Color @Composable get() = palette.tint
}

private fun TeemoonPalette.scheme(): ColorScheme {
    val onTint = if (tint == Color(0xFFFFCC00) || tint == Color(0xFFFFD60A) || tint == Color(0xFF63E6E2) || tint == Color(0xFF00C7BE) || (dark && tint == Color(0xFFFFFFFF))) Color.Black else Color.White
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = tint, onPrimary = onTint, primaryContainer = tint.copy(alpha = 0.18f), onPrimaryContainer = label,
        secondary = gray, onSecondary = Color.White, secondaryContainer = secondaryBackground, onSecondaryContainer = label,
        tertiary = orange, onTertiary = Color.White, tertiaryContainer = orange.copy(alpha = 0.18f), onTertiaryContainer = label,
        background = background, onBackground = label,
        surface = background, onSurface = label,
        surfaceVariant = secondaryBackground, onSurfaceVariant = secondaryLabel,
        surfaceContainerLowest = background, surfaceContainerLow = secondaryBackground, surfaceContainer = secondaryBackground,
        surfaceContainerHigh = tertiaryBackground, surfaceContainerHighest = tertiaryBackground,
        surfaceTint = Color.Transparent,
        outline = separator, outlineVariant = separator,
        error = red, onError = Color.White, errorContainer = red.copy(alpha = 0.18f), onErrorContainer = label,
        inverseSurface = label, inverseOnSurface = background, scrim = Color.Black,
    )
}

/** The iOS text scale (Dynamic Type "large"): 34/28/22/20/17/16/15/13/12/11, mapped onto the Material slots the screens use. */
fun teemoonTypography(family: FontFamily, scale: Float): Typography {
    fun style(size: Int, weight: FontWeight = FontWeight.Normal, line: Int = (size * 1.3f).toInt()) =
        TextStyle(fontFamily = family, fontSize = (size * scale).sp, fontWeight = weight, lineHeight = (line * scale).sp)
    return Typography(
        displayLarge = style(34, FontWeight.Bold), displayMedium = style(28, FontWeight.Bold), displaySmall = style(22, FontWeight.Bold),
        headlineLarge = style(28, FontWeight.Bold), headlineMedium = style(22, FontWeight.Bold), headlineSmall = style(20, FontWeight.SemiBold),
        titleLarge = style(20, FontWeight.SemiBold), titleMedium = style(17, FontWeight.SemiBold), titleSmall = style(15, FontWeight.SemiBold),
        bodyLarge = style(17), bodyMedium = style(16), bodySmall = style(13),
        labelLarge = style(15, FontWeight.Medium), labelMedium = style(13, FontWeight.Medium), labelSmall = style(12),
    )
}

fun AppFontDesign.family(width: AppFontWidth = AppFontWidth.STANDARD): FontFamily = when (this) {
    AppFontDesign.MONOSPACED -> FontFamily.Monospace
    AppFontDesign.SERIF -> FontFamily.Serif
    AppFontDesign.ROUNDED -> FontFamily.SansSerif   // Android's system font has no rounded cut
    // iOS applies `.fontWidth` to the standard design only (the width row is disabled for the others), and so does this
    AppFontDesign.STANDARD -> width.flexFamily() ?: FontFamily.SansSerif
}

/**
 * SF's width cuts on the system's Roboto Flex: its `wdth` axis runs 25–151. Values pick the same relative widths as
 * SF Compressed / Condensed / Expanded against the regular cut.
 */
fun AppFontWidth.wdth(): Float? = when (this) {
    AppFontWidth.COMPRESSED -> 62.5f; AppFontWidth.CONDENSED -> 82f; AppFontWidth.EXPANDED -> 125f; AppFontWidth.STANDARD -> null
}

/** Android ships Roboto Flex in /system/fonts (API 33 on; both the AVD and the Pixel have it). Read in place — no bundled font. */
internal val ROBOTO_FLEX = File("/system/fonts/RobotoFlex-Regular.ttf")

/**
 * The standard design at [this] width, or null for STANDARD — and null where the system has no Roboto Flex (API < 33),
 * so the choice reads as the standard width there. The default `sans-serif` (Roboto) has a `wdth` axis too, but only
 * 75–100: it cannot expand.
 */
@OptIn(ExperimentalTextApi::class)
fun AppFontWidth.flexFamily(file: File = ROBOTO_FLEX): FontFamily? {
    val w = wdth() ?: return null
    if (!file.canRead()) return null
    return FontFamily(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(file, weight = weight, variationSettings = FontVariation.Settings(
            FontVariation.weight(weight.weight), FontVariation.width(w)))
    })
}

/**
 * The step on top of the phone's font scale, which `sp` already carries: iOS's Dynamic Type body sizes around "large"
 * (xSmall 14, small 15, medium 16, large 17, xLarge 19), so each choice draws as on iOS (407ea0b). Before 1.1.0 medium
 * was 1.0 here — iOS's large — one step above iOS's medium.
 */
fun AppFontSize.scale(): Float = when (this) {
    AppFontSize.XSMALL -> 14f / 17; AppFontSize.SMALL -> 15f / 17; AppFontSize.MEDIUM -> 16f / 17; AppFontSize.SYSTEM -> 1f; AppFontSize.XLARGE -> 19f / 17
}

/** Radii: 24 for the composer pill and buttons, 20 for grouped cards and bubbles, 12 for panels, 8 for small controls. */
val TeemoonShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TeemoonTheme(
    tint: AppTintColor = AppTintColor.ORANGE,
    fontDesign: AppFontDesign = AppFontDesign.STANDARD,
    fontSize: AppFontSize = AppFontSize.SYSTEM,
    fontWidth: AppFontWidth = AppFontWidth.STANDARD,
    /**
     * Dark, whatever the system says — iOS pins `UIUserInterfaceStyle: Dark` in Info.plist (teemoonApp.swift:613:
     * light mode "was never designed, only inherited"; the monochrome tint resolves to black on white and the Where
     * chip becomes the heaviest object on screen). The first port followed the system theme instead, so a Play
     * install on a light-mode Pixel opened white (the user, 2026-10-12). Light is being designed for 1.1.0 behind
     * [FOLLOWS_SYSTEM_APPEARANCE]; MainActivity passes the system's setting once that is on.
     */
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = if (dark) TeemoonPalette.dark(tint) else TeemoonPalette.light(tint)
    // The label color is provided here, not by a Surface: screens paint their own ground (chat on the system
    // background, lists on the grouped one), and text without an explicit color must still be the label in the dark.
    CompositionLocalProvider(LocalTeemoonPalette provides palette, LocalContentColor provides palette.label) {
        MaterialTheme(colorScheme = palette.scheme(), typography = teemoonTypography(remember(fontDesign, fontWidth) { fontDesign.family(fontWidth) }, fontSize.scale()), shapes = TeemoonShapes) {
            CompositionLocalProvider(LocalContentColor provides palette.label, content = content)
        }
    }
}

/**
 * A sheet's pinned band, the where sheet's reasoning bar (the maintainer 2026-11-13, "Do 1 and 3"): part of the sheet's frame, not
 * another row, so neither the sheet's ground nor the groups' fill — halfway between them, one step off each (dark
 * elevated: #1C1C1E ground, #2C2C2E cards → #242426; light: #F2F2F7, white → #F9F9FB). iOS draws its band in `.bar`.
 */
fun sheetBand(p: TeemoonPalette): Color = androidx.compose.ui.graphics.lerp(p.groupedBackground, p.groupedCard, 0.5f)
