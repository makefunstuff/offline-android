package com.lfmpixel.lfm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Calm Signal, Ink + highlighter (docs/spec.md section 7). True neutral greys, yellow as a marker, soft tints for the organizer. */
@Immutable
data class LfmColors(
    val bg: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val line: Color,
    val fill: Color,
    val control: Color,
    val highlight: Color,
    val onHighlight: Color,
    val scrim: Color,
    val dark: Boolean,
    /**
     * The organizer's friendly tints (notebook-spec A.2): soft fills for its cards and the ink that sits on them.
     * Peach for Now, lavender for the plan, mint for done, sky for Focus. Never red, never loud.
     */
    val peach: Color = Color(0xFFFFE9DC), val onPeach: Color = Color(0xFF8A3B12),
    val lavender: Color = Color(0xFFEEE8FF), val onLavender: Color = Color(0xFF4B3A8C),
    val mint: Color = Color(0xFFE2F6EC), val onMint: Color = Color(0xFF1F6B47),
    val sky: Color = Color(0xFFE3F0FF), val onSky: Color = Color(0xFF1F4F8A),
)

val LightColors = LfmColors(
    bg = Color(0xFFFFFFFF), text = Color(0xFF0B0B0C), text2 = Color(0xFF5C5C62), text3 = Color(0xFF6E6E75),
    line = Color(0xFFE4E4E7), fill = Color(0xFFF4F4F5), control = Color(0xFF8B8B92),
    highlight = Color(0xFFFFD400), onHighlight = Color(0xFF0B0B0C), scrim = Color(0xFF0B0B0C), dark = false,
)

val DarkColors = LfmColors(
    bg = Color(0xFF09090A), text = Color(0xFFF2F2F3), text2 = Color(0xFF9D9DA4), text3 = Color(0xFF86868D),
    line = Color(0xFF27272A), fill = Color(0xFF161618), control = Color(0xFF6A6A72),
    highlight = Color(0xFFFFD400), onHighlight = Color(0xFF09090A), scrim = Color(0xFF000000), dark = true,
    peach = Color(0xFF2E1E17), onPeach = Color(0xFFFFB892),
    lavender = Color(0xFF221D33), onLavender = Color(0xFFC7B8FF),
    mint = Color(0xFF15261E), onMint = Color(0xFF8EDDB5),
    sky = Color(0xFF16212F), onSky = Color(0xFF9CC6FF),
)

val LocalLfm = staticCompositionLocalOf { LightColors }

object Lfm {
    val c: LfmColors @Composable get() = LocalLfm.current
    val corner = 6.dp
    val cornerLarge = 8.dp
    val sheetCorner = 12.dp
}

/** Roboto is the system font on the Pixel; FontFamily.Default is it. */
object LfmType {
    private val f = FontFamily.Default
    val title = TextStyle(fontFamily = f, fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.025).em)
    val title2 = TextStyle(fontFamily = f, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em)
    val header = TextStyle(fontFamily = f, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val big = TextStyle(fontFamily = f, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.02).em, fontFeatureSettings = "tnum")
    val bigger = TextStyle(fontFamily = f, fontSize = 30.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
    val answer = TextStyle(fontFamily = f, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.01).em)
    val bodyLarge = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 22.sp)
    val rowTitle = TextStyle(fontFamily = f, fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontFamily = f, fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
    val meta = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp)
    val button = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    // Daylight (docs/ux-spec.md, "Type"): Inkling's own words in the phone's serif (Noto Serif, nothing bundled);
    // the time large and light, with even figures.
    private val serif = FontFamily.Serif
    /** One sentence of the day; Inkling's line in a caption. */
    val voice = TextStyle(fontFamily = serif, fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.005).em)
    /** Talk's caption of what Inkling says now. */
    val caption = TextStyle(fontFamily = serif, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em)
    val clock = TextStyle(fontFamily = f, fontSize = 64.sp, lineHeight = 66.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
}

@Composable
fun LfmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    // Material You is off on purpose: a fixed scheme keeps the look stable.
    val scheme = if (dark) darkColorScheme(
        primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2, outline = c.control, outlineVariant = c.line,
        surfaceContainer = c.bg, surfaceContainerLow = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        secondary = c.text, tertiary = c.text, scrim = c.scrim,
    ) else lightColorScheme(
        primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2, outline = c.control, outlineVariant = c.line,
        surfaceContainer = c.bg, surfaceContainerLow = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        secondary = c.text, tertiary = c.text, scrim = c.scrim,
    )
    CompositionLocalProvider(LocalLfm provides c) {
        MaterialTheme(colorScheme = scheme, typography = Typography()) {
            // Ripples in the ink of the page, a touch stronger than Material's default so a tap shows on the light greys.
            CompositionLocalProvider(androidx.compose.material3.LocalRippleConfiguration provides androidx.compose.material3.RippleConfiguration(
                color = c.text, rippleAlpha = androidx.compose.material.ripple.RippleAlpha(0.14f, 0.12f, 0.08f, 0.16f)), content = content)
        }
    }
}
