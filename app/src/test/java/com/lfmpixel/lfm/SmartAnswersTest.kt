package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import com.lfmpixel.lfm.edge.CaptureLabel
import com.lfmpixel.lfm.edge.ChatPrompt
import com.lfmpixel.lfm.edge.ChatStream
import com.lfmpixel.lfm.edge.EdgeModels
import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.llm.ToolSpec
import com.lfmpixel.lfm.see.Look
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/** LP-45: the answer model's chat format both ways, the Ink line with the small model's label, Look's change test. */
@RunWith(RobolectricTestRunner::class)
class SmartAnswersTest {
    private val todo = ToolSpec("add_todo", "Add a to-do", listOf(ToolSpec.Param("text", "string", "What to do"),
        ToolSpec.Param("list", "string", "Which list", required = false)))

    @Test fun rawPromptBecomesChatWithTools() {
        val call = ChatPrompt.rawCall("add_todo", mapOf("text" to "Oat milk", "list" to "Shopping"))
        val prompt = PromptFormat.build("You are Wren.", listOf(todo.toJson()), listOf(
            PromptMessage(PromptMessage.Role.User, "add oat milk"),
            PromptMessage(PromptMessage.Role.Assistant, "<think></think>$call"),
            PromptMessage(PromptMessage.Role.Tool, "{\"ok\": true}"),
            PromptMessage(PromptMessage.Role.Assistant, "<think>it worked</think>Added."),
            PromptMessage(PromptMessage.Role.User, "thanks")), thinking = false)
        val chat = ChatPrompt.toChat(prompt)
        assertFalse(chat.thinking)
        assertEquals(1, chat.tools.length())
        assertEquals("add_todo", chat.tools.getJSONObject(0).getJSONObject("function").getString("name"))
        val m = chat.messages
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), (0 until m.length()).map { m.getJSONObject(it).getString("role") })
        assertEquals("You are Wren.", m.getJSONObject(0).getString("content"))
        val calls = m.getJSONObject(2).getJSONArray("tool_calls")
        val fn = calls.getJSONObject(0).getJSONObject("function")
        assertEquals("add_todo", fn.getString("name"))
        assertEquals("Shopping", JSONObject(fn.getString("arguments")).getString("list"))
        assertEquals(calls.getJSONObject(0).getString("id"), m.getJSONObject(3).getString("tool_call_id"))
        // Earlier thoughts are not sent back.
        assertEquals("Added.", m.getJSONObject(4).getString("content"))
    }

    @Test fun thinkingPromptAndBudgetContinuation() {
        val h = listOf(PromptMessage(PromptMessage.Role.User, "plan my week"))
        assertTrue(ChatPrompt.toChat(PromptFormat.build("", emptyList(), h, thinking = true)).thinking)
        // Thought ran out of budget: the prompt carries it on; the answer is asked for again, without thinking.
        val cont = PromptFormat.build("", emptyList(), h, thinking = true) + "first, Monday" + ModelOutput.OUT_OF_BUDGET
        val chat = ChatPrompt.toChat(cont)
        assertFalse(chat.thinking)
        assertEquals("user", chat.messages.getJSONObject(chat.messages.length() - 1).getString("role"))
    }

    private fun ev(delta: String) = JSONObject("""{"choices":[{"index":0,"delta":$delta}]}""")

    @Test fun streamedToolCallComesBackAsLfmRaw() {
        val s = ChatStream(thinking = false)
        val raw = StringBuilder()
        raw.append(s.feed(ev("""{"role":"assistant","content":null}""")))
        raw.append(s.feed(ev("""{"content":"On it. "}""")))
        raw.append(s.feed(ev("""{"tool_calls":[{"index":0,"id":"x","type":"function","function":{"name":"add_todo","arguments":"{\"text\":"}}]}""")))
        raw.append(s.feed(ev("""{"tool_calls":[{"index":0,"function":{"arguments":"\"Oat milk\",\"list\":\"Shopping\"}"}}]}""")))
        raw.append(s.finish())
        val out = ModelOutput.parse(raw.toString(), false)
        assertEquals("On it.", out.answer)
        assertEquals(listOf(ToolCall("add_todo", mapOf("text" to "Oat milk", "list" to "Shopping"))), out.toolCalls)
    }

    @Test fun aCallCutOffIsNotRun() {
        val s = ChatStream(thinking = false)
        val raw = s.feed(ev("""{"tool_calls":[{"index":0,"id":"x","type":"function","function":{"name":"add_todo","arguments":"{\"text\": \"Oat"}}]}""")) + s.finish()
        assertTrue(ModelOutput.parse(raw, false).toolCalls.isEmpty())
    }

    @Test fun streamedThoughtIsClosedBeforeTheAnswer() {
        val s = ChatStream(thinking = true)
        val raw = s.feed(ev("""{"reasoning_content":"Short answer."}""")) + s.feed(ev("""{"content":"Paris."}""")) + s.finish()
        val out = ModelOutput.parse(raw, true)
        assertEquals("Short answer.", out.thinking)
        assertEquals("Paris.", out.answer)
        // A model that never thought still closes the block.
        val quiet = ChatStream(thinking = true)
        val r2 = quiet.feed(ev("""{"content":"Hi"}""")) + quiet.finish()
        assertEquals("Hi", ModelOutput.parse(r2, true).answer)
    }

    @Test fun modelLabels() {
        assertEquals("Qwen3.8-27B", EdgeModels.label(EdgeModels.ANSWER))
        assertEquals("LFM3-1.7B", EdgeModels.label(EdgeModels.TEXT_LONG))
    }

    private fun label(kind: String, w: String = "") = CaptureLabel(kind, w, "soon", "low", false, 150)

    @Test fun inkLineTakesTheLabel() {
        val now = LocalDateTime.of(2026, 10, 31, 22, 50)
        // The rules call this a note; the label knows it is an appointment, with a time the phone can read.
        val g = InkLine.withLabel("dentist on tuesday at 3", label("event", "tuesday at 3"), now)
        assertEquals(InkKind.Reminder, g.kind)
        assertEquals("tuesday at 3", g.reminderAt)
        assertEquals("dentist", g.text)
        assertEquals(InkKind.Idea, InkLine.withLabel("I could sell the old bike", label("idea"), now).kind)
        // Words the line doesn't hold are not trusted; a question stays a question.
        assertEquals(InkKind.Todo, InkLine.withLabel("renew passport", label("todo", "before december"), now).kind)
        assertEquals(InkKind.Ask, InkLine.withLabel("what is on today?", label("note"), now).kind)
        // A time the rules read is kept, whatever the label says.
        assertEquals(InkKind.Reminder, InkLine.withLabel("remind me to call mom at 6", label("todo", ""), now).kind)
        val call = InkLine.call("dentist on tuesday at 3", InkKind.Reminder, now.toLocalDate(), g)!!
        assertEquals("set_reminder", call.name)
        assertEquals("tuesday at 3", call.args["at"])
    }

    @Test fun lookTellsANewViewFromTheSame() {
        val a = IntArray(108) { 100 }
        val b = IntArray(108) { 103 }
        val c = IntArray(108) { if (it < 54) 100 else 160 }
        assertTrue(Look.diff(a, b) < Look.SETTLED)
        assertTrue(Look.diff(a, c) > Look.CHANGED)
    }
}
