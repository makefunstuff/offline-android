package com.lfmpixel.lfm

import com.lfmpixel.lfm.voice.Endpointer
import com.lfmpixel.lfm.voice.Jitter
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.simulatePlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceTest {
    private val rate = 24_000

    /** Speech-like audio: words of [wordMs] with [gapMs] of quiet between them. */
    private fun speech(ms: Int, wordMs: Int = 320, gapMs: Int = 80): FloatArray {
        val n = rate * ms / 1000
        return FloatArray(n) { i ->
            val t = (i * 1000L / rate).toInt() % (wordMs + gapMs)
            if (t < wordMs) (0.1 * sin(2 * PI * 180 * i / rate)).toFloat() else 0f
        }
    }

    /** The model's output: 80 ms frames, each made in 80 ms × [rtf], after [firstMs]. */
    private fun frames(pcm: FloatArray, rtf: Double, firstMs: Double = 300.0): List<Pair<Double, FloatArray>> {
        val f = rate * 80 / 1000
        return (0 until pcm.size / f).map { k -> firstMs + k * 80 * rtf to pcm.copyOfRange(k * f, (k + 1) * f) }
    }

    @Test fun fastSpeechStartsAtOnceAndNeverWaits() {
        val sim = simulatePlayback(Jitter(shared = Jitter.Prior()), frames(speech(8000), 0.5))
        assertEquals(0, sim.underruns)
        assertEquals(0, sim.pauses)
        // The first frame comes at 300 ms; it starts once 240 ms is waiting.
        assertTrue("starts ${sim.startMs}", sim.startMs < 600)
    }

    @Test fun slowSpeechWaitsUpFrontAndNeverRunsDryMidWord() {
        val pcm = speech(8000)
        val j = Jitter(shared = Jitter.Prior())
        // It learns the speed in the first answer...
        simulatePlayback(j, frames(pcm, 1.4)) { 8000.0 }
        assertTrue("rtf ${j.rtf}", j.rtf > 1.2)
        // ...and with the answer's length known, the next plays through without running dry mid-word.
        val sim = simulatePlayback(j, frames(pcm, 1.4)) { 8000.0 }
        assertEquals(0, sim.underruns)
        assertTrue("starts ${sim.startMs}", sim.startMs > 1000)
    }

    @Test fun slowSpeechWithoutALengthWaitsOnlyAtPauses() {
        val pcm = speech(12000)
        val j = Jitter(shared = Jitter.Prior())
        simulatePlayback(j, frames(pcm, 1.3))
        val sim = simulatePlayback(j, frames(pcm, 1.3))
        assertEquals("underruns", 0, sim.underruns)
        assertTrue("waits ${sim.pauses}", sim.pauses >= 1)
    }

    @Test fun slowSpeechWithTextJustAheadPlaysThroughOnTheNextAnswer() {
        // As on the emulator: speech made at 1.8x real time, its text known only 1.7 s ahead of the voice.
        val pcm = speech(9000)
        val j = Jitter(shared = Jitter.Prior())
        val made = { t: Double -> ((t - 300) / 1.8).coerceAtLeast(0.0) }
        simulatePlayback(j, frames(pcm, 1.8)) { t -> minOf(9000.0, made(t) + 1700) }
        val sim = simulatePlayback(j, frames(pcm, 1.8)) { t -> minOf(9000.0, made(t) + 1700) }
        assertEquals("underruns", 0, sim.underruns)
    }

    @Test fun readAloudWaitsPerPieceAndDoesNotTeachTalkItsLength() {
        val shared = Jitter.Prior(rtf = 1.5, usualMs = 6000.0)
        val j = Jitter(shared = shared, learnLength = false)
        j.begin()
        j.expectMore(3000.0)
        // Only this piece's share is waited for, not a whole long text's.
        assertTrue("need ${j.needMs(0.0)}", j.needMs(0.0) in 1000.0..1500.0)
        j.ended(60000.0)
        assertEquals(6000.0, shared.usualMs, 0.0)
    }

    @Test fun afterRunningDryAFastPlayerResumesOnTwoFrames() {
        val j = Jitter(shared = Jitter.Prior(rtf = 0.5))
        j.begin()
        assertEquals(Jitter.RESUME_MS, j.resumeMs(1000.0), 0.0)
    }

    @Test fun anUnderrunsRaisedWaitLastsAsSpeechKeepsComing() {
        val shared = Jitter.Prior(rtf = 0.9)
        val j = Jitter(shared = shared)
        j.begin()
        // Speech at 0.95x real time: 80 ms frames every 76 ms; then the buffer ran dry twice anyway (bursts).
        var t = 0.0
        repeat(20) { j.arrived(1920, t); t += 76 }
        j.underrun(100.0); j.underrun(100.0)
        repeat(40) { j.arrived(1920, t); t += 76 }
        assertTrue("rtf ${j.rtf}", j.rtf > 1.0)
        assertTrue("waits up front: ${j.needMs(0.0)}", j.needMs(0.0) > Jitter.BASE_MS)
    }

    @Test fun anUnderrunRaisesTheWait() {
        val j = Jitter(shared = Jitter.Prior())
        val before = j.needMs(0.0)
        j.underrun(100.0)
        assertTrue(j.needMs(0.0) > before)
        assertEquals(1, j.underruns)
    }

    @Test fun talkPrefixStaysTheSameForMostTurns() {
        val turns = List(14) { "q$it" to "a$it" }
        // Each next turn's history starts with the one before's, except when the oldest three are dropped.
        val hits = (1 until turns.size).count { n ->
            val next = SpeechFormat.talkHistory("sys", turns.take(n + 1), "x")
            next.startsWith(SpeechFormat.talkPrefix("sys", turns.take(n)))
        }
        assertTrue("hits $hits", hits >= 10)
        assertTrue(SpeechFormat.talkHistory("sys", turns, "x").startsWith(SpeechFormat.talkPrefix("sys", turns)))
    }

    @Test fun endpointerTellsAPauseBeforeTheEnd() {
        val e = Endpointer()
        repeat(30) { e.feed(0.003f) }
        repeat(30) { e.feed(0.08f) }
        repeat(10) { e.feed(0.003f) }
        assertEquals(300, e.pausedMs)
        e.feed(0.08f)
        assertEquals(0, e.pausedMs)
    }
}
