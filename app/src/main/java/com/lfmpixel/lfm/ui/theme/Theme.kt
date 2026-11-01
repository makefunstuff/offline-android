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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Daylight's page (docs/ux-spec.md, "Palette by hour"): paper and ink under the hour's sky, the highlighter yellow as the
 * one accent, and [wash] for Inkling's own cards (Now, the plan, Where was I), the pale of the day sky's horizon.
 * The sky's colours are in daylight/Sky.kt; these are the only other colours the app draws with (DaylightGuardTest).
 */
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
    val wash: Color,
)

val LightColors = LfmColors(
    bg = Color(0xFFFFFFFF), text = Color(0xFF0B0B0C), text2 = Color(0xFF5C5C62), text3 = Color(0xFF6E6E75),
    line = Color(0xFFE4E4E7), fill = Color(0xFFF4F4F5), control = Color(0xFF8B8B92),
    highlight = Color(0xFFFFD400), onHighlight = Color(0xFF0B0B0C), scrim = Color(0xFF0B0B0C), dark = false,
    wash = Color(0xFFE9F4FC),
)

val DarkColors = LfmColors(
    bg = Color(0xFF09090A), text = Color(0xFFF2F2F3), text2 = Color(0xFF9D9DA4), text3 = Color(0xFF86868D),
    line = Color(0xFF27272A), fill = Color(0xFF161618), control = Color(0xFF6A6A72),
    highlight = Color(0xFFFFD400), onHighlight = Color(0xFF09090A), scrim = Color(0xFF000000), dark = true,
    wash = Color(0xFF111824),
)

/**
 * The camera, the one screen without the sky (docs/ux-spec.md, "Look"): full-bleed black, white ink, and dark glass
 * behind what sits on the picture. The same in both themes.
 */
object CameraColors {
    val ground = Color(0xFF000000)
    val ink = Color(0xFFFFFFFF)
    /** Behind the viewfinder before the first frame. */
    val frame = Color(0xFF161618)
    /** Glass behind a label on the picture. */
    val glass = Color(0xAA000000)
    /** Darker glass behind a longer answer. */
    val glassStrong = Color(0xCC000000)
    /** An off toggle on the picture. */
    val glassLight = Color(0x66000000)
}

/** Inkling's own calendar in the phone's Calendar app: the page's ink, as ARGB. */
const val CALENDAR_ARGB = 0xFF0B0B0C.toInt()

/** The paint the line icons are built with; [androidx.compose.material3.Icon] tints it over, so it never shows. */
val IconMask = Color(0xFF000000)

val LocalLfm = staticCompositionLocalOf { LightColors }

object Lfm {
    val c: LfmColors @Composable get() = LocalLfm.current
}

/** Daylight's corners: square-ish controls, slightly softer cards, round only for marks that are circles. */
object LfmShape {
    /** Buttons, fields, tabs, switches, chips. */
    val control = RoundedCornerShape(6.dp)
    /** Cards, groups, the composer, the snackbar, the big buttons. */
    val card = RoundedCornerShape(8.dp)
    /** Tags and small badges. */
    val tag = RoundedCornerShape(4.dp)
    /** Tiny marks: a status dot, a bar, the grab handle. */
    val mark = RoundedCornerShape(2.dp)
    /** A switch's thumb. */
    val thumb = RoundedCornerShape(3.dp)
    /** Bottom sheets: their top corners. */
    val sheet = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    /** Circles: a progress disc, a dot, a numbered step. */
    val round = RoundedCornerShape(50)
    /** A chat bubble: the corner by the speaker is tucked in. */
    val bubble = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
    /** A note in the margin: square on the page side. */
    val margin = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
    /** The screen under a predictive back swipe: its corners round as the swipe goes, 0..1. */
    fun backPreview(progress: Float) = RoundedCornerShape(24.dp * progress)
    /** The shutter's inner mark, round for a photo and squaring off while it records. */
    fun shutter(corner: androidx.compose.ui.unit.Dp) = RoundedCornerShape(corner)
    /** The camera's viewfinder: rounded where it meets the controls. */
    val viewfinder = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
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
    val buttonSmall = button.copy(fontSize = 12.sp)
    /** A tab's or a segment's word. */
    val tab = TextStyle(fontFamily = f, fontSize = 13.5.sp, lineHeight = 20.sp)
    val segment = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 20.sp)
    /** A tag, and a list row's preview of its body. */
    val tag = TextStyle(fontFamily = f, fontSize = 11.5.sp, lineHeight = 16.sp)
    val preview = TextStyle(fontFamily = f, fontSize = 13.sp, lineHeight = 18.sp)

    // Daylight (docs/ux-spec.md, "Type"): Inkling's own words in the phone's serif (Noto Serif, nothing bundled);
    // the time large and light, with even figures.
    private val serif = FontFamily.Serif
    /** One sentence of the day; Inkling's line in a caption. */
    val voice = TextStyle(fontFamily = serif, fontSize = 21.sp, lineHeight = 28.sp, letterSpacing = (-0.005).em)
    /** Talk's caption of what Inkling says now. */
    val caption = TextStyle(fontFamily = serif, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.01).em)
    val clock = TextStyle(fontFamily = f, fontSize = 64.sp, lineHeight = 66.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
    /** Inkling's longer words: a chat answer, a recap, a web answer. */
    val voiceBody = TextStyle(fontFamily = serif, fontSize = 17.sp, lineHeight = 26.sp)
    /** Inkling's one big sentence on a screen of its own: first run, Privacy. */
    val voiceTitle = TextStyle(fontFamily = serif, fontSize = 30.sp, lineHeight = 37.sp, letterSpacing = (-0.015).em)
    /** Focus's minutes in the middle of the disc. */
    val timer = TextStyle(fontFamily = f, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.03).em, fontFeatureSettings = "tnum")
    /** A citation's number in an answer, and the box it sits in. */
    val cite = TextStyle(fontFamily = f, fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold)
    val citeWidth = 18.sp
    val citeWidthWide = 22.sp
    val citeHeight = 15.sp

    /** Material's own type scale, for the few stock pieces drawn inside the shared components (menus, the date picker). */
    val material = Typography(
        displaySmall = clock.copy(fontSize = 36.sp, lineHeight = 44.sp), headlineLarge = title, headlineMedium = title2,
        headlineSmall = big.copy(fontFeatureSettings = null), titleLarge = header, titleMedium = rowTitle, titleSmall = button,
        bodyLarge = bodyLarge, bodyMedium = body, bodySmall = meta, labelLarge = button, labelMedium = label, labelSmall = meta,
    )
}

@Composable
fun LfmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    // Material You is off on purpose: a fixed scheme keeps the look stable.
    val scheme = if (dark) darkColorScheme(
        primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2, outline = c.control, outlineVariant = c.line,
        surfaceContainer = c.bg, surfaceContainerLow = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        surfaceContainerLowest = c.bg, primaryContainer = c.highlight, onPrimaryContainer = c.onHighlight,
        secondary = c.text, onSecondary = c.bg, secondaryContainer = c.fill, onSecondaryContainer = c.text,
        tertiary = c.text, onTertiary = c.bg, error = c.text, onError = c.bg, scrim = c.scrim,
    ) else lightColorScheme(
        primary = c.text, onPrimary = c.bg, background = c.bg, onBackground = c.text, surface = c.bg, onSurface = c.text,
        surfaceVariant = c.fill, onSurfaceVariant = c.text2, outline = c.control, outlineVariant = c.line,
        surfaceContainer = c.bg, surfaceContainerLow = c.bg, surfaceContainerHigh = c.bg, surfaceContainerHighest = c.fill,
        surfaceContainerLowest = c.bg, primaryContainer = c.highlight, onPrimaryContainer = c.onHighlight,
        secondary = c.text, onSecondary = c.bg, secondaryContainer = c.fill, onSecondaryContainer = c.text,
        tertiary = c.text, onTertiary = c.bg, error = c.text, onError = c.bg, scrim = c.scrim,
    )
    CompositionLocalProvider(LocalLfm provides c) {
        MaterialTheme(colorScheme = scheme, typography = LfmType.material, shapes = androidx.compose.material3.Shapes(
            extraSmall = LfmShape.control, small = LfmShape.control, medium = LfmShape.card, large = LfmShape.card, extraLarge = LfmShape.card)) {
            // Ripples in the ink of the page, a touch stronger than Material's default so a tap shows on the light greys.
            CompositionLocalProvider(androidx.compose.material3.LocalRippleConfiguration provides androidx.compose.material3.RippleConfiguration(
                color = c.text, rippleAlpha = androidx.compose.material.ripple.RippleAlpha(0.14f, 0.12f, 0.08f, 0.16f)), content = content)
        }
    }
}
