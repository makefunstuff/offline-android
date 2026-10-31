package com.lfmpixel.lfm

import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.ui.screens.thoughtLabel
import com.lfmpixel.lfm.ui.screens.wordCount
import org.junit.Assert.assertEquals
import org.junit.Test

class ThoughtTest {
    @Test fun storedAnswerGivesBackItsThinking() {
        val raw = PromptFormat.assistantPrefix(true) + "Rayleigh scattering, short.\n</think>\n\nThe sky is blue because…"
        assertEquals("Rayleigh scattering, short.", ModelOutput.thoughtOf(raw))
    }

    @Test fun noThinkingWhenItWasOff() {
        assertEquals("", ModelOutput.thoughtOf(PromptFormat.assistantPrefix(false) + "Hello"))
        assertEquals("", ModelOutput.thoughtOf("<think></think>Answer from Talk"))
        assertEquals("", ModelOutput.thoughtOf(""))
    }

    @Test fun labels() {
        assertEquals("Thought", thoughtLabel(0))
        assertEquals("Thought for 1 s", thoughtLabel(300))
        assertEquals("Thought for 3 s", thoughtLabel(3_400))
        assertEquals("Thought for 1 min 15 s", thoughtLabel(75_000))
    }
}

class EditorBarTest {
    @Test fun countsWords() {
        assertEquals(0, wordCount(""))
        assertEquals(0, wordCount("  \n "))
        assertEquals(4, wordCount("Boiler service\n  on Friday"))
    }
}
