package com.lfmpixel.lfm.ui.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The motion tokens, one set for the whole app: Material 3 easings, 100–300 ms, springs without bounce for what
 * follows a finger, and nothing at all when the phone's "Remove animations" is on (animator duration scale 0).
 * The rules are in docs/ux-spec-v1.md section 7.
 */
object LfmMotion {
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Colour and state changes of small controls: a switch, a tab, a pressed button. */
    const val TINY = 100
    /** Something leaving; a check filling in. */
    const val SHORT = 150
    /** Something arriving in place; list moves; progress. */
    const val MEDIUM = 250
    /** Whole screens; the highlighter drawing in. */
    const val LONG = 300
    /** How far an arriving thing rises as it fades in. */
    val rise = 8.dp

    /** True when the owner turned animations off in Android's accessibility settings. */
    @Composable
    fun reduced(): Boolean {
        val ctx = LocalContext.current
        return remember { isReduced(ctx) }
    }

    fun isReduced(ctx: android.content.Context) =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** A tween with M3 easing, or a snap when animations are off. */
    @Composable
    fun <T> spec(ms: Int = MEDIUM, easing: Easing = standard): FiniteAnimationSpec<T> =
        if (reduced()) snap() else tween(ms, easing = easing)

    /** Following a finger (a swipe, a drag, predictive back): critically damped, so it settles without a bounce. */
    @Composable
    fun <T> drag(): FiniteAnimationSpec<T> =
        if (reduced()) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /** A quick spring for a level that moves all the time (the mic's pulse); no bounce. */
    @Composable
    fun <T> level(): FiniteAnimationSpec<T> =
        if (reduced()) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Something appears in place (a bubble, a row, a notice): fade and an 8 dp rise, emphasized decelerate. */
    @Composable
    fun appear(): EnterTransition = if (reduced()) EnterTransition.None else {
        val px = with(LocalDensity.current) { rise.roundToPx() }
        fadeIn(tween(MEDIUM, easing = emphasizedDecelerate)) + slideInVertically(tween(MEDIUM, easing = emphasizedDecelerate)) { px }
    }

    /** Something leaves: fade and fold its height away, emphasized accelerate. */
    @Composable
    fun leave(): ExitTransition = if (reduced()) ExitTransition.None else
        fadeOut(tween(SHORT, easing = emphasizedAccelerate)) + shrinkVertically(tween(SHORT, easing = emphasizedAccelerate))

    /**
     * Shared axis Z: the new screen grows in from 92 % as the old one fades, and the reverse going back.
     * Used for full-screen changes such as opening the camera.
     */
    fun sharedAxisZ(forward: Boolean, reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(snap()) togetherWith fadeOut(snap())
        val enter = fadeIn(tween(LONG - 60, delayMillis = 60, easing = emphasizedDecelerate)) +
            scaleIn(tween(LONG, easing = emphasizedDecelerate), initialScale = if (forward) 0.92f else 1.08f)
        val exit = fadeOut(tween(SHORT, easing = emphasizedAccelerate)) +
            scaleOut(tween(LONG, easing = emphasizedAccelerate), targetScale = if (forward) 1.08f else 0.92f)
        return enter togetherWith exit
    }

    /**
     * Shared axis X: a pushed screen comes in from 10 % to the right as it fades in; Back reverses it. Used for the
     * editor and the sub-screens of Settings.
     */
    fun sharedAxisX(forward: Boolean, reduced: Boolean): ContentTransform {
        if (reduced) return fadeIn(snap()) togetherWith fadeOut(snap())
        val enter = fadeIn(tween(LONG - 90, delayMillis = 90, easing = emphasizedDecelerate)) +
            slideInHorizontally(tween(LONG, easing = emphasizedDecelerate)) { w -> if (forward) w / 10 else -w / 10 }
        val exit = fadeOut(tween(90, easing = emphasizedAccelerate)) +
            slideOutHorizontally(tween(LONG, easing = emphasizedAccelerate)) { w -> if (forward) -w / 10 else w / 10 }
        return enter togetherWith exit
    }
}
