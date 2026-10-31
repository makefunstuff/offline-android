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

    @Test fun aSecondEndTagAfterTheBudgetNeverHidesWords() {
        // Closed for it, the model may close the block again itself: the tag goes, every word stays in the answer.
        val out = ModelOutput.parse("First part" + ModelOutput.OUT_OF_BUDGET + "1. Salmon</think> 2. Stir-fry", thinkingOn = true)
        assertEquals("First part", out.thinking)
        assertEquals("1. Salmon\n 2. Stir-fry", out.answer)
        assertEquals("Done.", ModelOutput.parse("First" + ModelOutput.OUT_OF_BUDGET + "<think>Done.", true).answer)
    }

    @Test fun aStrayEndTagInTheAnswerKeepsTheAnswer() {
        // A stray tag after a whole answer (seen from LFM3 on both engines) never hides it in the trace.
        val budget = ModelOutput.parse("Thought" + ModelOutput.OUT_OF_BUDGET + "1. Salmon\n2. Stir-fry</think>", thinkingOn = true)
        assertEquals("Thought", budget.thinking)
        assertEquals("1. Salmon\n2. Stir-fry", budget.answer)
        // Without a budget close, a tag mid-answer just goes and the words stay.
        val mid = ModelOutput.parse("Thought</think>Here are three: 1. Salmon</think> 2. Stir-fry", thinkingOn = true)
        assertEquals("Thought", mid.thinking)
        assertEquals("Here are three: 1. Salmon\n 2. Stir-fry", mid.answer)
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
        // Spoken captures and Today's quick box stay quick.
        assertFalse(thinksFor(p, 1, "voice"))
        assertFalse(thinksFor(p, 1, "today"))
    }

    @Test fun aChatsOwnThinkWinsOverTheDefault() {
        val p = Prefs(thinking = true, chatThinking = mapOf(2L to false, 3L to true))
        assertFalse(thinksFor(p, 2, "typed"))
        assertTrue(thinksFor(p, 1, "typed"))
        assertTrue(thinksFor(p.copy(thinking = false), 3, "typed"))
        assertFalse(thinksFor(p.copy(thinking = false), 1, "typed"))
    }

    @Test fun aShortenedPromptDropsOldThoughtsFirst() {
        val u = com.lfmpixel.lfm.llm.PromptMessage.Role.User
        val a = com.lfmpixel.lfm.llm.PromptMessage.Role.Assistant
        val t = com.lfmpixel.lfm.llm.PromptMessage.Role.Tool
        fun m(r: com.lfmpixel.lfm.llm.PromptMessage.Role, c: String) = com.lfmpixel.lfm.llm.PromptMessage(r, c)
        val h = listOf(m(u, "hi"), m(a, "<think>long thought</think>Hello."), m(u, "remind me"),
            m(a, "<think>they want a reminder</think><tool_call>x</tool_call>"), m(t, "{}"))
        val out = PromptFormat.withoutOldThoughts(h)
        assertEquals("<think></think>Hello.", out[1].content)
        // The current turn keeps its thought; the rest is untouched.
        assertEquals(h.subList(2, 5), out.subList(2, 5))
    }
}
