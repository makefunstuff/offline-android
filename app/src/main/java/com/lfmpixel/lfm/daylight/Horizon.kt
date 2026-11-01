package com.lfmpixel.lfm.daylight

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Something on today's horizon: an event, a reminder or a timed to-do. [minute] is minutes since midnight. */
data class Plan(val minute: Int, val title: String, val detail: String = "", val key: String = "$minute:$title")

/**
 * Where things sit on the horizon. The day runs left to right across [startMin]..[endMin]: the waking day, 07:00 to
 * 22:00, stretched to take in every plan and now. The sun (the now-marker) walks an arc over it toward [next].
 */
class HorizonLayout(val nowMin: Int, plans: List<Plan>) {
    val plans = plans.sortedBy { it.minute }
    val startMin: Int = min(7 * 60, min(this.plans.firstOrNull()?.minute?.minus(30) ?: Int.MAX_VALUE, nowMin - 30)).coerceAtLeast(0)
    val endMin: Int = max(22 * 60, max(this.plans.lastOrNull()?.minute?.plus(30) ?: 0, nowMin + 30)).coerceAtMost(24 * 60)

    /** 0..1 across the horizon. */
    fun x(minute: Int): Float = ((minute - startMin).toFloat() / (endMin - startMin)).coerceIn(0f, 1f)

    val nowX get() = x(nowMin)

    /** How high the sun stands on its arc, 0..1: highest mid-window, on the horizon at either end. */
    fun arc(x: Float): Float = sin(PI * x).toFloat()

    /** The next plan still ahead (or under way for up to 15 minutes), if any. */
    val next: Plan? = this.plans.firstOrNull { it.minute >= nowMin - 15 }

    fun isPast(p: Plan) = p.minute < nowMin - 15

    /** Minutes from now until [p]; negative once it has begun. */
    fun minutesTo(p: Plan) = p.minute - nowMin
}

/**
 * Draws the sky, the horizon, the plans on it and the sun, with android.graphics so the app and the widget's bitmap
 * look the same. Nothing moves by itself: the picture changes only when the minute does.
 */
object SkyPainter {
    /** Horizon line height as a share of the picture. */
    const val HORIZON = 0.80f

    fun draw(canvas: Canvas, w: Float, h: Float, density: Float, sky: Sky, layout: HorizonLayout, selected: String? = null,
             pad: Float = 20f * density, horizonAt: Float = HORIZON, arcHeight: Float? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hy = h * horizonAt
        // The sky, top to horizon, then a quieter band of ground below it.
        p.shader = LinearGradient(0f, 0f, 0f, hy, sky.top, sky.horizon, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, hy, p)
        p.shader = LinearGradient(0f, hy, 0f, h, sky.horizon, mix(sky.horizon, sky.top, 0.35f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hy, w, h, p)
        p.shader = null

        // A few fixed stars at night: no twinkle, nothing to keep the screen busy.
        if (sky.night) {
            p.color = withAlpha(sky.sun, 0.55f)
            STARS.forEach { (sx, sy, r) -> canvas.drawCircle(sx * w, sy * hy * 0.8f, r * density, p) }
        }

        val span = w - 2 * pad
        fun xOf(f: Float) = pad + f * span
        val arcH = arcHeight ?: (hy * 0.55f)
        fun yOf(f: Float) = hy - arcH * layout.arc(f)

        // The day's arc, faint, dashed: the path the sun walks.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f * density
        p.color = withAlpha(sky.ink, 0.22f)
        p.pathEffect = DashPathEffect(floatArrayOf(2f * density, 5f * density), 0f)
        val arc = Path()
        for (i in 0..64) { val f = i / 64f; if (i == 0) arc.moveTo(xOf(f), yOf(f)) else arc.lineTo(xOf(f), yOf(f)) }
        canvas.drawPath(arc, p)
        p.pathEffect = null

        // Walked so far: from sunrise to now, a touch stronger; and the stretch still to walk to the next plan.
        val now = layout.nowX
        val walked = Path()
        for (i in 0..48) { val f = now * i / 48f; if (i == 0) walked.moveTo(xOf(f), yOf(f)) else walked.lineTo(xOf(f), yOf(f)) }
        p.color = withAlpha(sky.ink, 0.45f)
        p.strokeWidth = 1.6f * density
        canvas.drawPath(walked, p)
        layout.next?.let { n ->
            val to = layout.x(n.minute)
            if (to > now) {
                val ahead = Path()
                for (i in 0..32) { val f = now + (to - now) * i / 32f; if (i == 0) ahead.moveTo(xOf(f), yOf(f)) else ahead.lineTo(xOf(f), yOf(f)) }
                p.color = withAlpha(sky.sun, 0.9f)
                p.strokeWidth = 2.4f * density
                p.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(ahead, p)
            }
        }

        // The horizon.
        p.color = withAlpha(sky.ink, 0.38f)
        p.strokeWidth = 1f * density
        canvas.drawLine(0f, hy, w, hy, p)

        // Plans on the horizon: past ones faint, the next one a ring, the picked one filled.
        p.style = Paint.Style.FILL
        layout.plans.forEach { pl ->
            val cx = xOf(layout.x(pl.minute))
            val isNext = pl == layout.next
            val past = layout.isPast(pl)
            p.color = withAlpha(sky.ink, if (past) 0.35f else 0.9f)
            canvas.drawCircle(cx, hy, (if (isNext) 4.5f else 3f) * density, p)
            if (isNext || pl.key == selected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.5f * density
                p.color = if (pl.key == selected) sky.ink else withAlpha(sky.sun, 0.95f)
                canvas.drawCircle(cx, hy, 9f * density, p)
                p.style = Paint.Style.FILL
            }
        }

        // The sun (or the moon): a disc with a soft halo, on its arc at now.
        val sx = xOf(now); val sy = yOf(now)
        val r = 9f * density
        p.shader = RadialGradient(sx, sy, r * 3.2f, intArrayOf(withAlpha(sky.sun, 0.45f), withAlpha(sky.sun, 0f)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(sx, sy, r * 3.2f, p)
        p.shader = null
        p.color = sky.sun
        canvas.drawCircle(sx, sy, r, p)
        if (sky.night) {
            // A crescent: the sky's colour bites the disc.
            p.color = mix(sky.top, sky.horizon, (sy / hy).coerceIn(0f, 1f))
            canvas.drawCircle(sx + r * 0.45f, sy - r * 0.3f, r * 0.85f, p)
        }
    }

    /**
     * Horizon Almanac's dial (docs/ux-spec.md, "Today"): the waking day as a half dial standing on the horizon, an hour
     * tick for every hour, the plans as bold marks on it, and the sun riding it to now. The stretch the sun still has to
     * ride to the next plan is drawn in the sun's colour: time left, as a shape. The horizon is at [geo]'s centre; [sunAt]
     * 0..1 draws the sun that far along its way from the start of the day to now (Today's opening rise); 1 is now.
     */
    fun drawDial(canvas: Canvas, w: Float, h: Float, density: Float, sky: Sky, layout: HorizonLayout, geo: DialGeometry,
                 selected: String? = null, sunAt: Float = 1f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val hy = geo.cy
        p.shader = LinearGradient(0f, 0f, 0f, hy, sky.top, sky.horizon, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, hy, p)
        p.shader = LinearGradient(0f, hy, 0f, h, sky.horizon, mix(sky.horizon, sky.top, 0.25f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hy, w, h, p)
        p.shader = null
        if (sky.night) {
            p.color = withAlpha(sky.sun, 0.55f)
            STARS.forEach { (sx, sy, r) -> canvas.drawCircle(sx * w, sy * hy * 0.8f, r * density, p) }
        }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND

        // The dial: faint dashed half circle, an hour tick for each hour, longer at 06, 12 and 18.
        p.strokeWidth = 1.2f * density
        p.color = withAlpha(sky.ink, 0.22f)
        p.pathEffect = DashPathEffect(floatArrayOf(2f * density, 4f * density), 0f)
        val arc = Path()
        for (i in 0..96) { val (x, y) = geo.at(i / 96f); if (i == 0) arc.moveTo(x, y) else arc.lineTo(x, y) }
        canvas.drawPath(arc, p)
        p.pathEffect = null
        val firstHour = (layout.startMin + 59) / 60
        val lastHour = layout.endMin / 60
        for (hr in firstHour..lastHour) {
            val f = layout.x(hr * 60)
            val major = hr % 6 == 0
            val (x0, y0) = geo.at(f, -(if (major) 9f else 5f) * density)
            val (x1, y1) = geo.at(f)
            p.color = withAlpha(sky.ink, if (major) 0.55f else 0.28f)
            p.strokeWidth = (if (major) 1.4f else 1f) * density
            canvas.drawLine(x0, y0, x1, y1, p)
        }
        // 06, 12 and 18 by their ticks.
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(sky.ink, 0.7f); textSize = 10f * density; textAlign = Paint.Align.CENTER; typeface = CONDENSED
        }
        for ((hr, label) in HOUR_LABELS) if (hr * 60 in layout.startMin..layout.endMin) {
            val (x, y) = geo.at(layout.x(hr * 60), 10f * density)
            canvas.drawText(label, x, y + 3.5f * density, tp)
        }

        // Walked: dawn to now, a touch stronger. Ahead: now to the next plan, in the sun's colour.
        val now = layout.nowX * sunAt.coerceIn(0f, 1f)
        p.color = withAlpha(sky.ink, 0.4f)
        p.strokeWidth = 1.6f * density
        val walked = Path()
        for (i in 0..48) { val (x, y) = geo.at(now * i / 48f); if (i == 0) walked.moveTo(x, y) else walked.lineTo(x, y) }
        canvas.drawPath(walked, p)
        layout.next?.let { n ->
            val to = layout.x(n.minute)
            if (to > now) {
                val ahead = Path()
                for (i in 0..40) { val (x, y) = geo.at(now + (to - now) * i / 40f); if (i == 0) ahead.moveTo(x, y) else ahead.lineTo(x, y) }
                p.color = withAlpha(sky.sun, 0.95f)
                p.strokeWidth = 3f * density
                canvas.drawPath(ahead, p)
            }
        }

        // The horizon.
        p.color = withAlpha(sky.ink, 0.35f)
        p.strokeWidth = 1f * density
        canvas.drawLine(0f, hy, w, hy, p)

        // Plans: bold marks across the dial; past ones faint, the next one longer, the picked one with a dot.
        layout.plans.forEach { pl ->
            val f = layout.x(pl.minute)
            val isNext = pl == layout.next
            val past = layout.isPast(pl)
            val (x0, y0) = geo.at(f, -(if (isNext) 14f else 11f) * density)
            val (x1, y1) = geo.at(f, 2f * density)
            p.color = withAlpha(sky.ink, if (past) 0.35f else 0.95f)
            p.strokeWidth = (if (isNext) 3.2f else 2.4f) * density
            canvas.drawLine(x0, y0, x1, y1, p)
            if (pl.key == selected) {
                val (dx, dy) = geo.at(f, 10f * density)
                p.style = Paint.Style.FILL
                canvas.drawCircle(dx, dy, 3f * density, p)
                p.style = Paint.Style.STROKE
            }
        }

        // The sun (or the moon) on the dial at now, with a soft halo.
        p.style = Paint.Style.FILL
        val (sx, sy) = geo.at(now)
        val r = 9f * density
        p.shader = RadialGradient(sx, sy, r * 3f, intArrayOf(withAlpha(sky.sun, 0.4f), withAlpha(sky.sun, 0f)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(sx, sy, r * 3f, p)
        p.shader = null
        p.color = sky.sun
        canvas.drawCircle(sx, sy, r, p)
        if (sky.night) {
            p.color = mix(sky.top, sky.horizon, (sy / hy).coerceIn(0f, 1f))
            canvas.drawCircle(sx + r * 0.45f, sy - r * 0.3f, r * 0.85f, p)
        }
    }

    private val CONDENSED: android.graphics.Typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
    private val HOUR_LABELS = listOf(6 to "06", 12 to "12", 18 to "18")

    /** Where the dial sits: its centre on the horizon at ([cx], [cy]) and its radius [r], in pixels. */
    class DialGeometry(val cx: Float, val cy: Float, val r: Float) {
        /** The point [f] (0..1 across the day) on the dial, pushed [out] pixels outward. */
        fun at(f: Float, out: Float = 0f): Pair<Float, Float> {
            val a = PI * (1 - f)
            return (cx + (r + out) * kotlin.math.cos(a)).toFloat() to (cy - (r + out) * sin(a)).toFloat()
        }
    }

    /** Fixed star positions (x, y as shares of the sky; radius in dp). */
    private val STARS = listOf(
        Triple(0.08f, 0.22f, 1.1f), Triple(0.19f, 0.55f, 0.8f), Triple(0.31f, 0.12f, 1.0f), Triple(0.44f, 0.38f, 0.7f),
        Triple(0.57f, 0.18f, 1.2f), Triple(0.66f, 0.6f, 0.8f), Triple(0.78f, 0.27f, 0.9f), Triple(0.9f, 0.48f, 1.1f),
        Triple(0.95f, 0.1f, 0.7f), Triple(0.25f, 0.85f, 0.6f), Triple(0.72f, 0.9f, 0.6f),
    )
}
