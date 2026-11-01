package com.lfmpixel.lfm

import com.lfmpixel.lfm.daylight.Cue
import com.lfmpixel.lfm.daylight.DayPart
import com.lfmpixel.lfm.daylight.DaySentence
import com.lfmpixel.lfm.daylight.Feel
import com.lfmpixel.lfm.daylight.HorizonLayout
import com.lfmpixel.lfm.daylight.Persona
import com.lfmpixel.lfm.daylight.Plan
import com.lfmpixel.lfm.daylight.Skies
import com.lfmpixel.lfm.daylight.contrast
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.abs

class SkyTest {
    @Test fun followsTheHour() {
        assertEquals(DayPart.Night, Skies.at(LocalTime.of(2, 0)).part)
        assertEquals(DayPart.Dawn, Skies.at(LocalTime.of(6, 40)).part)
        assertEquals(DayPart.Day, Skies.at(LocalTime.of(12, 0)).part)
        assertEquals(DayPart.Dusk, Skies.at(LocalTime.of(18, 30)).part)
        assertEquals(DayPart.Night, Skies.at(LocalTime.of(23, 0)).part)
        assertTrue(Skies.at(LocalTime.of(23, 0)).lightInk)
        assertFalse(Skies.at(LocalTime.of(12, 0)).lightInk)
    }

    /** Every minute of the day, light and dark: the ink reads at 4.5:1 on both ends of the sky, the quieter ink at 3:1. */
    @Test fun alwaysReadable() {
        for (dim in listOf(false, true)) for (m in 0 until 24 * 60) {
            val s = Skies.at(LocalTime.of(m / 60, m % 60), dim)
            val worst = minOf(contrast(s.ink, s.top), contrast(s.ink, s.horizon))
            assertTrue("ink at $m dim=$dim: $worst", worst >= 4.5)
            val worst2 = minOf(contrast(s.ink2, s.top), contrast(s.ink2, s.horizon))
            assertTrue("ink2 at $m dim=$dim: $worst2", worst2 >= 3.0)
        }
    }

    /**
     * The sky changes gently: no jump of more than a few levels per channel from one minute to the next, except at the
     * two moments a day (first light, last light) when the ink turns and the sky settles with it.
     */
    @Test fun blendsByTheMinute() {
        var turns = 0
        for (m in 0 until 24 * 60 - 1) {
            val sa = Skies.at(LocalTime.of(m / 60, m % 60)); val sb = Skies.at(LocalTime.of((m + 1) / 60, (m + 1) % 60))
            if (sa.ink != sb.ink) { turns++; continue }
            val a = sa.top; val b = sb.top
            for (sh in listOf(0, 8, 16)) assertTrue("minute $m: %08x %08x".format(a, b), abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 3)
        }
        assertEquals(2, turns)
    }
}

class HorizonTest {
    private val dentist = Plan(15 * 60, "Dentist")
    private val call = Plan(9 * 60 + 30, "Call Sam")
    private val dinner = Plan(19 * 60, "Dinner with Ana")

    @Test fun nextAndPlaces() {
        val h = HorizonLayout(12 * 60, listOf(dinner, dentist, call))
        assertEquals(7 * 60, h.startMin)
        assertEquals(22 * 60, h.endMin)
        assertEquals(dentist, h.next)
        assertTrue(h.isPast(call))
        assertEquals(180, h.minutesTo(dentist))
        assertTrue(h.x(call.minute) < h.nowX && h.nowX < h.x(dentist.minute))
        assertEquals(1f, h.arc(0.5f), 1e-6f)
    }

    @Test fun stretchesForEarlyLateAndNow() {
        val h = HorizonLayout(2 * 60, listOf(Plan(6 * 60, "Flight"), Plan(23 * 60 + 30, "Night bus")))
        assertEquals(90, h.startMin)
        assertEquals(24 * 60, h.endMin)
        assertTrue(h.nowX > 0f)
        assertEquals("Flight", h.next?.title)
    }

    @Test fun nothingAhead() {
        val h = HorizonLayout(21 * 60, listOf(call))
        assertNull(h.next)
    }
}

class DaySentenceTest {
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 1, h, m)

    @Test fun sentencesNotCounts() {
        val plans = listOf(Plan(15 * 60, "dentist"), Plan(18 * 60, "Call Mum"))
        assertEquals("Morning. A light day: Dentist at 15:00, and one more later.", DaySentence.of(at(8), plans, 3))
        assertEquals("Afternoon. Dentist at 15:00, in 25 minutes. Then one more.", DaySentence.of(at(14, 35), plans, 3))
        assertEquals("Afternoon. Dentist now. Then one more.", DaySentence.of(at(15, 5), plans, 0))
        assertEquals("Evening. Call Mum at 18:00, in an hour.", DaySentence.of(at(17), plans, 0))
        assertEquals("Morning. Nothing at a set time; a few to-dos when you're ready.", DaySentence.of(at(9), emptyList(), 4))
        assertEquals("Afternoon. Nothing at a set time. The day is yours.", DaySentence.of(at(13), emptyList(), 0))
        assertEquals("Evening. Nothing left at a set time. The evening is yours.", DaySentence.of(at(19), plans, 0))
        assertEquals("Late now. Nothing left today. Tomorrow can wait.", DaySentence.of(at(23), plans, 5))
    }

    /** The no-nag rules: no digits counting things, nothing about what was missed. */
    @Test fun neverCountsOrScolds() {
        val plans = (8..20).map { Plan(it * 60, "Thing $it") }
        for (h in 0..23) for (todos in listOf(0, 1, 2, 9)) {
            val s = DaySentence.of(at(h), plans.map { it.copy(title = "x") }, todos)
            assertFalse(s, Regex("\\b\\d+ (to-dos?|things?|more)\\b").containsMatchIn(s))
            listOf("overdue", "missed", "late for", "behind", "!").forEach { assertFalse(s, s.contains(it)) }
        }
        assertEquals("1 h 20 min", DaySentence.span(80))
    }
}

class VoiceAndFeelTest {
    @Test fun oneVoiceInTalk() {
        assertTrue(SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "Sunday").contains(Persona.PROMPT))
        assertTrue(SpeechFormat.talkToolSystem(SpeechLanguage.EnglishUK).contains(Persona.PROMPT))
    }

    @Test fun softShortTones() {
        for (cue in listOf(Cue.Listen, Cue.Done, Cue.Undo)) {
            val pcm = Feel.tones(cue)
            assertTrue(pcm.size in 48_00..48_000 / 2)
            assertTrue("quiet", pcm.maxOf { abs(it) } <= 0.2f)
            // Fades out: no click at the end.
            assertTrue(abs(pcm.last()) < 0.02f)
        }
        assertEquals(0, Feel.tones(Cue.Tick).size)
    }
}
