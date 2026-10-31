package com.lfmpixel.lfm

import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.PromptMessage.Role
import com.lfmpixel.lfm.llm.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAndOutputTest {
    private val spec = ToolSpec("set_reminder", "Remind the owner.", listOf(
        ToolSpec.Param("text", "string", "What"), ToolSpec.Param("at", "string", "When", required = false)))

    @Test fun toolJsonMatchesTheTemplateShape() {
        assertEquals(
            "{\"name\": \"set_reminder\", \"description\": \"Remind the owner.\", \"parameters\": {\"type\": \"object\", \"properties\": " +
                "{\"text\": {\"type\": \"string\", \"description\": \"What\"}, \"at\": {\"type\": \"string\", \"description\": \"When\"}}, \"required\": [\"text\"]}}",
            spec.toJson())
    }

    @Test fun promptFollowsLfm3ChatTemplate() {
        val p = PromptFormat.build("Be brief.", listOf(spec.toJson()), listOf(
            PromptMessage(Role.User, "Remind me at six"),
            PromptMessage(Role.Assistant, "<think>user wants a reminder</think><tool_call>\n<function=set_reminder>\n<parameter=text>\ncall\n</parameter>\n</function>\n</tool_call>"),
            PromptMessage(Role.Tool, "{\"ok\": true}"),
            PromptMessage(Role.Tool, "{\"also\": 1}"),
        ), thinking = true)
        val expected = "<|im_start|>system\nBe brief.\n\n<tools>\n${spec.toJson()}\n</tools><|im_end|>\n" +
            "<|im_start|>user\nRemind me at six<|im_end|>\n" +
            "<|im_start|>assistant\n<think>user wants a reminder</think><tool_call>\n<function=set_reminder>\n<parameter=text>\ncall\n</parameter>\n</function>\n</tool_call><|im_end|>\n" +
            "<|im_start|>tool\n<tool_response>\n{\"ok\": true}\n</tool_response>\n<tool_response>\n{\"also\": 1}\n</tool_response><|im_end|>\n" +
            "<|im_start|>assistant\n<think>"
        assertEquals(expected, p)
    }

    @Test fun thinkingOffClosesTheThinkBlock() {
        val p = PromptFormat.build("", emptyList(), listOf(PromptMessage(Role.User, "hi"), PromptMessage(Role.Assistant, "Hello."),
            PromptMessage(Role.User, "again")), thinking = false)
        assertEquals("<|im_start|>user\nhi<|im_end|>\n<|im_start|>assistant\n<think></think>Hello.<|im_end|>\n" +
            "<|im_start|>user\nagain<|im_end|>\n<|im_start|>assistant\n<think></think>", p)
    }

    @Test fun parsesThinkingAnswerAndToolCalls() {
        val raw = "The owner wants a reminder at 18:00.</think>Sure.\n<tool_call>\n<function=set_reminder>\n<parameter=text>\nCall the plumber\n</parameter>\n" +
            "<parameter=at>\n2026-10-30T18:00\n</parameter>\n</function>\n</tool_call>"
        val out = ModelOutput.parse(raw, thinkingOn = true)
        assertEquals("The owner wants a reminder at 18:00.", out.thinking)
        assertEquals("Sure.", out.answer)
        assertEquals(1, out.toolCalls.size)
        assertEquals("set_reminder", out.toolCalls[0].name)
        assertEquals(mapOf("text" to "Call the plumber", "at" to "2026-10-30T18:00"), out.toolCalls[0].args)
    }

    @Test fun parsesSeveralCallsAndMultilineValues() {
        val raw = "<tool_call>\n<function=add_todo>\n<parameter=text>\nOat milk\n</parameter>\n<parameter=list>\nShopping\n</parameter>\n</function>\n</tool_call>" +
            "\n\n<tool_call>\n<function=add_note>\n<parameter=text>\nline one\nline two\n</parameter>\n</function>\n</tool_call>"
        val out = ModelOutput.parse(raw, thinkingOn = false)
        assertEquals(listOf("add_todo", "add_note"), out.toolCalls.map { it.name })
        assertEquals("line one\nline two", out.toolCalls[1].args["text"])
        assertEquals("", out.answer)
    }

    @Test fun stillThinkingWhileTheThinkBlockIsOpen() {
        val out = ModelOutput.parse("Let me work out the date", thinkingOn = true)
        assertTrue(out.stillThinking)
        assertEquals("", out.answer)
    }

    @Test fun ignoresAHalfWrittenCall() {
        val out = ModelOutput.parse("</think>On it.<tool_call>\n<function=add_todo>\n<parameter=text>\nOat", thinkingOn = true)
        assertEquals(0, out.toolCalls.size)
        assertEquals("On it.", out.answer)
    }

    @Test fun readsJsonStyleCalls() {
        val out = ModelOutput.parse("<tool_call>{\"name\": \"set_torch\", \"arguments\": {\"on\": true}}</tool_call>", thinkingOn = false)
        assertEquals("set_torch", out.toolCalls.single().name)
        assertEquals("true", out.toolCalls.single().args["on"])
    }

    @Test fun strayEndOfThoughtWithThinkingOffKeepsTheAnswerAfterIt() {
        // Seen from LFM3 on llama.cpp and LiteRT-LM alike with thinking off.
        val out = ModelOutput.parse("17 + 25 = 42.</think>42", thinkingOn = false)
        assertEquals("42", out.answer)
        assertEquals("", out.thinking)
        // A tool call written before a stray tag is kept.
        assertEquals(1, ModelOutput.parse("<tool_call>\n<function=f>\n</function>\n</tool_call></think>", thinkingOn = false).toolCalls.size)
        assertEquals("Tokyo", ModelOutput.parse("Tokyo</think>Tokyo", thinkingOn = false).answer)
        // A normal answer is untouched.
        assertEquals("Paris.", ModelOutput.parse("Paris.", thinkingOn = false).answer)
    }
}
