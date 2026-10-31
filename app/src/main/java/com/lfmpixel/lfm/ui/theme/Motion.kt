package com.lfmpixel.lfm.ui.theme

import android.provider.Settings
import androidx.compose.animation.ContentTransform
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
 * Material 3 motion, kept small: three easings, 100–300 ms, and nothing at all when the phone's
 * "Remove animations" is on (animator duration scale 0). The rules are in docs/ux-spec.md section 7.
 */
object LfmMotion {
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val TINY = 100
    const val SHORT = 150
    const val MEDIUM = 250
    const val LONG = 300

    /** True when the owner turned animations off in Android's accessibility settings. */
    @Composable
    fun reduced(): Boolean {
        val ctx = LocalContext.current
        return remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    }

    /** A tween with M3 easing, or a snap when animations are off. */
    @Composable
    fun <T> spec(ms: Int = MEDIUM, easing: Easing = standard): FiniteAnimationSpec<T> =
        if (reduced()) snap() else tween(ms, easing = easing)

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
