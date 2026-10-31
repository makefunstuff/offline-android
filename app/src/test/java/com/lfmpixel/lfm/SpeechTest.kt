package com.lfmpixel.lfm

import com.lfmpixel.lfm.voice.Endpointer
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.SpeechVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    @Test fun ttsPromptMatchesOmnisTemplate() {
        assertEquals("<|im_start|>system\nPerform TTS in British English using a female voice.<|im_end|>\n" +
            "<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>",
            SpeechFormat.ttsPrompt("Hello", SpeechLanguage.EnglishUK, SpeechVoice.Female))
        assertTrue(SpeechFormat.ttsPrompt("Hallo", SpeechLanguage.German, SpeechVoice.Male).contains("in German using a male voice"))
    }

    @Test fun cleanDropsMarkdownSourcesAndLinks() {
        val text = "<think>plan</think>## Boiling\n- **Six** minutes [1]\n- Then cold water [2, 3]\nSee [the guide](https://example.com/egg) or https://x.y/z"
        assertEquals("Boiling. Six minutes. Then cold water. See the guide or.", SpeechFormat.clean(text))
    }

    @Test fun chunksKeepSentencesAndStartShort() {
        val text = List(12) { "This is sentence number $it in the answer." }.joinToString(" ")
        val parts = SpeechFormat.chunks(text)
        assertTrue(parts.first().length <= 120)
        assertTrue(parts.all { it.length <= 240 })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test fun overlongSentenceIsCut() {
        val long = List(80) { "word" }.joinToString(" ")
        val parts = SpeechFormat.chunks(long, max = 100, first = 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(long, parts.joinToString(" "))
    }

    @Test fun talkHistoryKeepsTheLastExchanges() {
        val turns = List(9) { "q$it" to "a$it" }
        val h = SpeechFormat.talkHistory("sys", turns, "now?")
        assertFalse(h.contains("q2"))
        assertTrue(h.contains("q3") && h.contains("a8"))
        assertTrue(h.endsWith("<|im_start|>user\nnow?<|im_end|>\n"))
    }

    private fun feed(e: Endpointer, rms: Float, ms: Int): List<Endpointer.Event> = List(ms / 30) { e.feed(rms) }.filter { it != Endpointer.Event.None }

    @Test fun endpointerFindsATurn() {
        val e = Endpointer()
        assertEquals(emptyList<Endpointer.Event>(), feed(e, 0.003f, 1500)) // a quiet room
        assertEquals(listOf(Endpointer.Event.Started), feed(e, 0.08f, 1200)) // speech
        assertEquals(listOf(Endpointer.Event.Ended), feed(e, 0.003f, 900)) // a pause ends it
    }

    @Test fun shortPauseInsideSpeechDoesNotEndIt() {
        val e = Endpointer()
        feed(e, 0.003f, 600)
        feed(e, 0.08f, 600)
        assertEquals(emptyList<Endpointer.Event>(), feed(e, 0.003f, 400))
        assertTrue(e.inSpeech)
    }

    @Test fun aClickIsNotATurn() {
        val e = Endpointer()
        feed(e, 0.003f, 600)
        val events = feed(e, 0.2f, 120) + feed(e, 0.003f, 900)
        assertFalse(Endpointer.Event.Ended in events)
    }

    @Test fun noisyRoomRaisesTheBar() {
        val e = Endpointer()
        assertEquals(emptyList<Endpointer.Event>(), feed(e, 0.03f, 3000)) // steady fan noise is learnt, not speech
        assertEquals(listOf(Endpointer.Event.Started), feed(e, 0.2f, 600))
    }

    @Test fun silenceForLongPauses() {
        val e = Endpointer(idleMs = 3000)
        assertEquals(listOf(Endpointer.Event.Idle), feed(e, 0.003f, 3000))
    }
}
