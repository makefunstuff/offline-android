package com.lfmpixel.lfm.daylight

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Daylight (docs/ux-spec.md): home is a sky that follows the hour. Everything here is plain maths on ARGB ints, so the
 * app (Compose), the widget (a bitmap) and the unit tests share it.
 */

/** The four parts of the day the sky is drawn for; the colours in between are blended minute by minute. */
enum class DayPart(val label: String) { Dawn("Dawn"), Day("Day"), Dusk("Dusk"), Night("Night") }

/**
 * The sky at one moment: [top] fades to [horizon]; [sun] is the sun (or the moon at night); [ink] is the text drawn on
 * it, [ink2] the quieter text. [ink] always meets 4.5:1 against [top] and against [horizon].
 */
data class Sky(val part: DayPart, val top: Int, val horizon: Int, val sun: Int, val ink: Int, val ink2: Int, val night: Boolean) {
    /** True when text on the sky is light (dusk and night): status bar icons go light too. */
    val lightInk get() = luminance(ink) > 0.5
}

object Skies {
    private class Key(val minute: Int, val part: DayPart, val top: Int, val horizon: Int, val sun: Int)

    // A Daylight day: deep blue night, a peach dawn, a clear day with the highlighter-yellow sun, an apricot dusk.
    private val NIGHT = Key(0, DayPart.Night, 0xFF0E1630.toInt(), 0xFF26305A.toInt(), 0xFFDCE3F5.toInt())
    private val DAWN = Key(0, DayPart.Dawn, 0xFF9FB4D9.toInt(), 0xFFF8CDB0.toInt(), 0xFFFF9D5C.toInt())
    private val DAY = Key(0, DayPart.Day, 0xFF8EC5F0.toInt(), 0xFFE9F4FC.toInt(), 0xFFFFD400.toInt())
    private val DUSK = Key(0, DayPart.Dusk, 0xFF3E3A78.toInt(), 0xFFF09A6B.toInt(), 0xFFFF7A45.toInt())

    /** Minutes of the day where each look is reached; between two, the sky blends. */
    private val keys = listOf(
        at(0, NIGHT), at(5 * 60, NIGHT), at(6 * 60 + 30, DAWN), at(8 * 60 + 30, DAY),
        at(16 * 60 + 30, DAY), at(18 * 60 + 30, DUSK), at(20 * 60 + 30, NIGHT), at(24 * 60, NIGHT),
    )

    private fun at(minute: Int, k: Key) = Key(minute, k.part, k.top, k.horizon, k.sun)

    const val DARK_INK = 0xFF0B1A2B.toInt()
    const val LIGHT_INK = 0xFFF6F3EE.toInt()

    /** The sky at [time]. [dim] (the phone's dark theme) deepens it, so a bright noon sky doesn't glare at night-mode eyes. */
    fun at(time: LocalTime, dim: Boolean = false): Sky {
        val m = time.hour * 60 + time.minute
        val i = keys.indexOfLast { it.minute <= m }.coerceAtMost(keys.size - 2)
        val a = keys[i]; val b = keys[i + 1]
        val f = if (b.minute == a.minute) 0f else (m - a.minute).toFloat() / (b.minute - a.minute)
        // The part is the nearer of the two looks.
        val part = if (f < 0.5f) a.part else b.part
        var top = mix(a.top, b.top, f)
        var horizon = mix(a.horizon, b.horizon, f)
        val sun = mix(a.sun, b.sun, f)
        if (dim) { top = mix(top, 0xFF05070D.toInt(), 0.45f); horizon = mix(horizon, 0xFF05070D.toInt(), 0.5f) }
        // Dark ink wherever it reads at 4.5:1 on both ends of the sky as it is; elsewhere light ink, with the sky leaning a
        // little darker where needed (the blend between a light and a dark look). Always the same way, so the sky never
        // jumps when the ink changes.
        val ink = if (minOf(contrast(DARK_INK, top), contrast(DARK_INK, horizon)) >= 4.6) DARK_INK else LIGHT_INK
        var step = 0
        while (ink == LIGHT_INK && step < 100 && minOf(contrast(ink, top), contrast(ink, horizon)) < 4.6) {
            if (contrast(ink, top) < 4.6) top = mix(top, 0xFF000000.toInt(), 0.01f)
            if (contrast(ink, horizon) < 4.6) horizon = mix(horizon, 0xFF000000.toInt(), 0.01f)
            step++
        }
        // The quieter ink leans toward the sky, but never so far that it reads below 4.5:1 on either end (small text sits on it).
        var lean = 0.22f
        var ink2 = mix(ink, mix(top, horizon, 0.5f), lean)
        while (lean > 0f && minOf(contrast(ink2, top), contrast(ink2, horizon)) < 4.5) {
            lean -= 0.01f
            ink2 = mix(ink, mix(top, horizon, 0.5f), lean.coerceAtLeast(0f))
        }
        return Sky(part, top, horizon, sun, ink, ink2, night = part == DayPart.Night)
    }

    fun at(t: LocalDateTime, dim: Boolean = false) = at(t.toLocalTime(), dim)

    /** The dark or the light ink, whichever reads better on both ends of the sky. */
    fun bestInk(top: Int, horizon: Int): Int {
        val dark = min(contrast(DARK_INK, top), contrast(DARK_INK, horizon))
        val light = min(contrast(LIGHT_INK, top), contrast(LIGHT_INK, horizon))
        return if (dark >= light) DARK_INK else LIGHT_INK
    }
}

/** Linear blend of two ARGB colours. */
fun mix(a: Int, b: Int, f: Float): Int {
    val t = f.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(c: Int, alpha: Float) = (c and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

/** WCAG relative luminance. */
fun luminance(c: Int): Double {
    fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
}

fun contrast(a: Int, b: Int): Double {
    val la = luminance(a); val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * The clock Daylight reads. Debug builds can move it (DEBUG_CLOCK) to see the sky at any hour; release builds never
 * change [offsetMs].
 */
object DayClock {
    val offset = kotlinx.coroutines.flow.MutableStateFlow(0L)
    fun millis(): Long = System.currentTimeMillis() + offset.value
    fun now(): LocalDateTime = LocalDateTime.now().plusNanos(offset.value * 1_000_000)
}
