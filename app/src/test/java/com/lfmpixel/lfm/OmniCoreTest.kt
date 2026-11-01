package com.lfmpixel.lfm

import com.lfmpixel.lfm.voice.CodeWindow
import com.lfmpixel.lfm.voice.CodecStream
import com.lfmpixel.lfm.voice.Maskgit
import com.lfmpixel.lfm.voice.OmniPrompt
import com.lfmpixel.lfm.voice.OmniRng
import com.lfmpixel.lfm.voice.OmniSampler
import com.lfmpixel.lfm.voice.OmniTokens
import com.lfmpixel.lfm.voice.SlotPlan
import com.lfmpixel.lfm.voice.Utf8Stream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OmniVoice's plain-Kotlin parts (voice/OmniCore.kt), against runner/speech.py and runner/prompt.py's rules. */
class OmniCoreTest {
    @Test fun repetitionPenaltyDividesPositiveAndMultipliesNegative() {
        val l = floatArrayOf(2f, -2f, 1f, 4f)
        OmniSampler.penalize(l, intArrayOf(0, 1, 0), 2f)
        assertArrayEquals(floatArrayOf(1f, -4f, 1f, 4f), l, 0f)
    }

    @Test fun greedyTextRespectsSuppression() {
        val l = floatArrayOf(0f, 5f, 3f)
        assertEquals(2, OmniSampler.sampleText(l, intArrayOf(1), 0f, 50, 0.9f, 0.5))
    }

    @Test fun sampledTextStaysInTopPAndFollowsTheDraw() {
        // softmax of [10, 9, 0, 0]: 0.73, 0.27, ~0, ~0; top-p 0.9 keeps the first two
        val l = { floatArrayOf(10f, 9f, 0f, 0f) }
        assertEquals(0, OmniSampler.sampleText(l(), IntArray(0), 1f, 50, 0.9f, 0.0))
        assertEquals(1, OmniSampler.sampleText(l(), IntArray(0), 1f, 50, 0.9f, 0.999))
        // top-k 1 is greedy whatever the draw
        assertEquals(0, OmniSampler.sampleText(l(), IntArray(0), 1f, 1, 1f, 0.999))
    }

    @Test fun topIndicesAreLargestFirst() {
        assertArrayEquals(intArrayOf(3, 1, 0), OmniSampler.topIndices(floatArrayOf(1f, 5f, 0f, 9f), 3))
    }

    @Test fun maskgitRevealsFourCodebooksPerPassInOrder() {
        val order = OmniRng(7).permutation(12)
        assertEquals((0 until 12).toList(), order.sorted())
        val rows = Maskgit.reveal(order)
        assertEquals(3, rows.size)
        for (p in 0 until 3) {
            assertEquals(4, rows[p].count { it == 1f })
            for (j in 4 * p until 4 * p + 4) assertEquals(1f, rows[p][order[j]], 0f)
        }
        // every codebook revealed exactly once
        for (k in 0 until 12) assertEquals(1f, rows.sumOf { it[k].toDouble() }.toFloat(), 0f)
    }

    @Test fun uniformsGoToCodebooksInOrderAndAreCapped() {
        val order = intArrayOf(11, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val a = OmniRng(3)
        val draws = DoubleArray(12) { a.nextDouble() }
        val u = Maskgit.uniforms(order, OmniRng(3))
        assertEquals(minOf(draws[0].toFloat(), Maskgit.U_MAX), u[11], 0f)
        assertEquals(minOf(draws[1].toFloat(), Maskgit.U_MAX), u[0], 0f)
        assertTrue(u.all { it <= Maskgit.U_MAX })
    }

    @Test fun codeWindowForgetsFramesOlderThanTheWindow() {
        val w = CodeWindow(window = 2)
        w.add(IntArray(12) { 5 })
        w.add(IntArray(12) { 6 })
        w.add(IntArray(12) { 7 })
        val p = w.presence()
        assertEquals(0f, p[5], 0f)
        assertEquals(1f, p[6], 0f)
        assertEquals(1f, p[OmniTokens.CODEBOOK_SIZE * 11 + 7], 0f)
    }

    @Test fun interleavingRunsSixTextThenTwelveFramesUntilTextEnd() {
        val plan = SlotPlan(SlotPlan.Mode.INTERLEAVED, continuing = true)
        val kinds = StringBuilder()
        repeat(6) { assertFalse(plan.nextIsSpeech); kinds.append('t'); plan.onText(100) }
        repeat(12) { assertTrue(plan.nextIsSpeech); kinds.append('a'); plan.onFrame(false) }
        assertFalse(plan.nextIsSpeech)
        plan.onText(OmniTokens.TEXT_END) // the text is done: speech until its end
        repeat(30) { assertTrue(plan.nextIsSpeech); plan.onFrame(false) }
        assertFalse(plan.onFrame(true)) // end of audio: Talk goes on in text
        assertFalse(plan.nextIsSpeech)
        assertTrue(plan.onText(OmniTokens.IM_END))
    }

    @Test fun readAloudEndsAtTheEndOfAudio() {
        val plan = SlotPlan(SlotPlan.Mode.AUDIO, continuing = false)
        assertTrue(plan.nextIsSpeech)
        assertFalse(plan.onFrame(false))
        assertTrue(plan.onFrame(true))
    }

    @Test fun textModeSwitchesOnItsMarkers() {
        val plan = SlotPlan(SlotPlan.Mode.TEXT, continuing = true)
        assertFalse(plan.onText(OmniTokens.AUDIO_START))
        assertTrue(plan.nextIsSpeech)
        assertTrue(SlotPlan(SlotPlan.Mode.TEXT, true).onText(OmniTokens.TEXT_END))
    }

    @Test fun overlapAddMatchesTheReferenceOnAConstantSignal() {
        // a flat window and frames of ones: the envelope-normalised sum is 1 everywhere
        val win = FloatArray(1280) { 1f }
        val s = CodecStream(win)
        val a = s.push(FloatArray(6 * 1280) { 1f }, 6, finalize = false)
        // first call: (6 - 1) * 320 + 1280 = 2880 samples, 480 trimmed in front, 960 held back
        assertEquals(2880 - 480 - 960, a.size)
        assertTrue(a.all { kotlin.math.abs(it - 1f) < 1e-6f })
        val b = s.push(FloatArray(6 * 1280) { 1f }, 6, finalize = true)
        // second: 2880 samples, 480 dropped at the end
        assertEquals(2880 - 480, b.size)
        assertEquals(0, s.finish().size)
        // in total: 12 frames x 320 hop, as the codec makes them
        assertEquals(12 * 320, a.size + b.size)
    }

    @Test fun overlapAddTailComesOutOnFinish() {
        val s = CodecStream(FloatArray(1280) { 1f })
        // one codec frame = 6 time frames
        val a = s.push(FloatArray(6 * 1280) { 1f }, 6, finalize = false)
        val t = s.finish()
        assertEquals(2880 - 480 - 960, a.size)
        assertEquals(960 - 480, t.size)
        assertEquals(6 * 320, a.size + t.size)
    }

    @Test fun audioPlaceholdersFollowTheProcessor() {
        assertEquals(1, OmniPrompt.audioTokens(100))
        assertEquals(1, OmniPrompt.audioTokens(400))
        // 1 s: 1 + (16000 - 400) / 160 = 98.5 -> padded to 16080: 98 frames -> 13 tokens
        assertEquals(13, OmniPrompt.audioTokens(16000))
        // 30 s: 2998 frames -> 375 tokens, the encoder's rows
        assertEquals(375, OmniPrompt.audioTokens(480000))
    }

    @Test fun promptIdsGetTheStartTokenOnceAndTheAudioRun() {
        assertArrayEquals(intArrayOf(OmniTokens.BOS, 1, 2), OmniPrompt.withBos(intArrayOf(1, 2)))
        assertArrayEquals(intArrayOf(OmniTokens.BOS, 1), OmniPrompt.withBos(intArrayOf(OmniTokens.BOS, 1)))
        val a = OmniTokens.AUDIO
        val (ids, at) = OmniPrompt.withAudio(intArrayOf(7, OmniTokens.VISION_START, a, OmniTokens.VISION_END, 9), 3)
        assertEquals(2, at)
        assertArrayEquals(intArrayOf(7, OmniTokens.VISION_START, a, a, a, OmniTokens.VISION_END, 9), ids)
        assertTrue(OmniPrompt.asrText("Perform ASR in English.").startsWith("<|startoftext|><|im_start|>system\nPerform ASR in English."))
    }

    @Test fun utf8StreamHoldsBackSplitCharacters() {
        val u = Utf8Stream()
        val e = "é".toByteArray() // 2 bytes
        assertEquals("a", u.feed(byteArrayOf('a'.code.toByte(), e[0])))
        assertEquals("é", u.feed(byteArrayOf(e[1])))
        assertEquals("", u.flush())
    }
}
