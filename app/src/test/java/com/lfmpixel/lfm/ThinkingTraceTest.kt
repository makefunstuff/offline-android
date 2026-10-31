package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.thinksFor
import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.settings.DEFAULT_THINK_BUDGET
import com.lfmpixel.lfm.settings.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thinking traces as each chat backend writes them, after the prompt's open `<think>`. */
class ThinkingTraceTest {
    // Raw outputs below were saved by the app on the emulator (LP-31), llama.cpp CPU and LiteRT-LM CPU.
    @Test fun llamaCppTrace() {
        val raw = "We need to compute 17 * 23. That's a simple arithmetic question.\n\nLet me calculate: 17 * 23.\n17 * 20 = 340\n" +
            "17 * 3 = 51\nTotal = 340 + 51 = 391.\n\nSo answer is 391.\n\nI'll just state it.</think>17 times 23 is **391**."
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertTrue(out.thinking.startsWith("We need to compute 17 * 23."))
        assertTrue(out.thinking.endsWith("I'll just state it."))
        assertEquals("17 times 23 is **391**.", out.answer)
        assertFalse(out.stillThinking)
        // llama.cpp renders special tokens as text: an end of turn that slips through is not shown.
        assertEquals("17 times 23 is **391**.", ModelOutput.parse("$raw<|im_end|>", thinkingOn = true).answer)
    }

    @Test fun liteRtTrace() {
        val raw = "We need to compute 19 times 21. That's a simple arithmetic question. 19 * 21 = 399. Let's verify: 20*21=420, " +
            "minus 21 = 399. Yes. So I'll say 399. No tool needed.</think>19 × 21 = 399."
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertTrue(out.thinking.startsWith("We need to compute 19 times 21.") && out.thinking.endsWith("No tool needed."))
        assertEquals("19 × 21 = 399.", out.answer)
    }

    @Test fun aLiveTraceShowsWhileItThinks() {
        val out = ModelOutput.parse("Let me work out the date of next Tuesday", thinkingOn = true)
        assertTrue(out.stillThinking)
        assertEquals("Let me work out the date of next Tuesday", out.thinking)
        assertEquals("", out.answer)
    }

    @Test fun budgetRunOutKeepsTheTraceAndTheAnswer() {
        // Out of budget, the Assistant closes the block and the model answers (on LiteRT-LM, on a fresh engine).
        val raw = "We need to plan a week of dinners for two. The instruction says \"Answer briefly and plainly.\" So" +
            ModelOutput.OUT_OF_BUDGET + "1. Lemon garlic salmon\n2. Vegetable stir-fry"
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertEquals("We need to plan a week of dinners for two. The instruction says \"Answer briefly and plainly.\" So", out.thinking)
        assertEquals("1. Lemon garlic salmon\n2. Vegetable stir-fry", out.answer)
        // While the answer streams after the close, the trace stays whole and is no longer live.
        val partial = ModelOutput.parse("Thinking on" + ModelOutput.OUT_OF_BUDGET + "1.", thinkingOn = true)
        assertFalse(partial.stillThinking)
        assertEquals("Thinking on", partial.thinking)
    }

    @Test fun aSecondEndTagAfterTheBudgetMovesItsThoughtIntoTheTrace() {
        // Closed for it, the model may go on thinking and close the block again itself.
        val raw = "First part of the thought" + "\n</think>\n" + "and the rest of it.</think>\n\nThe answer."
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertEquals("First part of the thought\n\nand the rest of it.", out.thinking)
        assertEquals("The answer.", out.answer)
        assertFalse(out.answer.contains("think"))
    }

    @Test fun aReopenedThinkTagIsNotShown() {
        val out = ModelOutput.parse("<think>\nPlanning.</think>Done.", thinkingOn = true)
        assertEquals("Planning.", out.thinking)
        assertEquals("Done.", out.answer)
    }

    @Test fun aToolCallAfterTheThoughtKeepsBoth() {
        val raw = "They want a reminder at six.</think>\n<tool_call>\n<function=set_reminder>\n<parameter=text>\nCall Sam\n</parameter>\n" +
            "</function>\n</tool_call><|im_end|>"
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertEquals("They want a reminder at six.", out.thinking)
        assertEquals("set_reminder", out.toolCalls.single().name)
        assertEquals("", out.answer)
    }

    @Test fun aSavedAnswerGivesItsThoughtBack() {
        val raw = PromptFormat.assistantPrefix(true) + "Thinking it over.\n</think>\n\nYes."
        assertEquals("Thinking it over.", ModelOutput.thoughtOf(raw))
        assertEquals("", ModelOutput.thoughtOf(PromptFormat.assistantPrefix(false) + "Yes."))
    }

    @Test fun thinkingIsOnByDefaultForTypedChats() {
        val p = Prefs()
        assertTrue(p.thinking)
        assertEquals(DEFAULT_THINK_BUDGET, p.thinkBudget)
        assertTrue(thinksFor(p, 1, "typed"))
        // Spoken captures stay quick.
        assertFalse(thinksFor(p, 1, "voice"))
    }

    @Test fun aChatsOwnThinkWinsOverTheDefault() {
        val p = Prefs(thinking = true, chatThinking = mapOf(2L to false, 3L to true))
        assertFalse(thinksFor(p, 2, "typed"))
        assertTrue(thinksFor(p, 1, "typed"))
        assertTrue(thinksFor(p.copy(thinking = false), 3, "typed"))
        assertFalse(thinksFor(p.copy(thinking = false), 1, "typed"))
    }
}
