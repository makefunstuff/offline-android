package com.lfmpixel.lfm.edge

import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * The answer model (Qwen on the owner's server) takes chat messages and tools natively, through the server's
 * /v1/chat/completions; the phone builds LFM3's raw ChatML. These two turn one into the other, so the Assistant's loop,
 * its stored raw answers and the phone's fallback stay as they are:
 *
 * - [toChat]: a raw prompt (PromptFormat.build) into messages and tools; tool calls written in earlier turns become
 *   `tool_calls`, tool responses `tool` messages with the same ids.
 * - [ChatStream]: the streamed reply back into LFM3's raw text (thought, answer, `<tool_call>` blocks), which
 *   ModelOutput parses as it would the phone's own model.
 */
object ChatPrompt {
    /** What the raw prompt holds: [thinking] when it ends with an open `<think>` for the model to fill. */
    class Chat(val messages: JSONArray, val tools: JSONArray, val thinking: Boolean)

    fun toChat(prompt: String): Chat {
        val messages = JSONArray()
        val tools = JSONArray()
        var thinking = false
        var lastIds = emptyList<String>()
        var nextId = 0
        val turns = prompt.split(PromptFormat.IM_START).drop(1)
        turns.forEachIndexed { i, turn ->
            val role = turn.substringBefore('\n')
            val closed = turn.contains(PromptFormat.IM_END)
            var body = turn.substringAfter('\n').substringBefore(PromptFormat.IM_END)
            when {
                role == "system" -> {
                    val at = body.indexOf("<tools>")
                    if (at >= 0) {
                        body.substring(at + "<tools>".length).substringBefore("</tools>").lines().map { it.trim() }.filter { it.startsWith("{") }
                            .forEach { line -> runCatching { tools.put(JSONObject().put("type", "function").put("function", JSONObject(line))) } }
                        body = body.substring(0, at).trim()
                    }
                    if (body.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", body.trim()))
                }
                role == "assistant" && !closed && i == turns.lastIndex -> {
                    // The turn the model writes next: "<think>" asks it to think first. Anything already in it (a thought
                    // closed for running out of budget) is not sent: the answer is asked for again, without thinking.
                    thinking = body == "<think>"
                }
                role == "assistant" -> {
                    val out = if (body.startsWith("<think>")) ModelOutput.parse(body.removePrefix("<think>"), true) else ModelOutput.parse(body, false)
                    val m = JSONObject().put("role", "assistant").put("content", out.answer)
                    lastIds = out.toolCalls.map { "call_" + nextId++ }
                    if (out.toolCalls.isNotEmpty()) m.put("tool_calls", JSONArray().apply {
                        out.toolCalls.forEachIndexed { k, c -> put(callJson(lastIds[k], c)) }
                    })
                    messages.put(m)
                }
                role == "tool" -> {
                    val results = Regex("<tool_response>\n?(.*?)\n?</tool_response>", RegexOption.DOT_MATCHES_ALL).findAll(body).map { it.groupValues[1] }.toList()
                        .ifEmpty { listOf(body.trim()) }
                    results.forEachIndexed { k, r ->
                        val m = JSONObject().put("role", "tool").put("content", r)
                        lastIds.getOrNull(k)?.let { m.put("tool_call_id", it) }
                        messages.put(m)
                    }
                }
                else -> messages.put(JSONObject().put("role", role).put("content", body.trim()))
            }
        }
        return Chat(messages, tools, thinking)
    }

    private fun callJson(id: String, c: ToolCall): JSONObject {
        val args = JSONObject()
        c.args.forEach { (k, v) -> args.put(k, v) }
        return JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", c.name).put("arguments", args.toString()))
    }

    /** One tool call in LFM3's own format, as ModelOutput reads it. */
    fun rawCall(name: String, args: Map<String, String>): String = buildString {
        append("<tool_call>\n<function=").append(name).append(">\n")
        args.forEach { (k, v) -> append("<parameter=").append(k).append(">\n").append(v).append("\n</parameter>\n") }
        append("</function>\n</tool_call>")
    }

    /** A tool call's JSON arguments as text values; nested values as their JSON. */
    fun args(json: String): Map<String, String> {
        val o = runCatching { JSONObject(json.ifBlank { "{}" }) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.get(k).let { if (it == JSONObject.NULL) "" else it.toString() } }
            .filterValues { it.isNotEmpty() }
    }
}

/**
 * The streamed chat reply as LFM3 raw text: [feed] each event's JSON, append what it returns; [finish] at the end adds
 * the tool calls, which only parse once whole. With [thinking], the thought comes first and is closed with
 * `</think>` before the answer, as the raw prompt's open `<think>` expects.
 */
class ChatStream(private val thinking: Boolean) {
    private var thoughtOpen = thinking
    private val names = sortedMapOf<Int, String>()
    private val argText = sortedMapOf<Int, StringBuilder>()

    fun feed(ev: JSONObject): String {
        val d = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return ""
        val sb = StringBuilder()
        val reasoning = d.optString("reasoning_content").takeIf { !d.isNull("reasoning_content") }.orEmpty()
        if (reasoning.isNotEmpty() && thoughtOpen) sb.append(reasoning)
        val content = d.optString("content").takeIf { !d.isNull("content") }.orEmpty()
        val calls = d.optJSONArray("tool_calls")
        if ((content.isNotEmpty() || calls != null) && thoughtOpen) { sb.append("\n</think>\n\n"); thoughtOpen = false }
        sb.append(content)
        if (calls != null) for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            val idx = c.optInt("index", i)
            c.optJSONObject("function")?.let { f ->
                f.optString("name").takeIf { it.isNotEmpty() && !f.isNull("name") }?.let { names[idx] = it }
                if (!f.isNull("arguments")) argText.getOrPut(idx) { StringBuilder() }.append(f.optString("arguments"))
            }
        }
        return sb.toString()
    }

    fun finish(): String {
        val sb = StringBuilder()
        if (thoughtOpen) { sb.append("\n</think>\n\n"); thoughtOpen = false }
        names.forEach { (i, name) ->
            // A call cut off mid-way (out of tokens) is not run with half its arguments.
            val json = argText[i]?.toString().orEmpty().ifBlank { "{}" }
            if (runCatching { JSONObject(json) }.isFailure) return@forEach
            sb.append(if (sb.isEmpty()) "" else "\n").append(ChatPrompt.rawCall(name, ChatPrompt.args(argText[i]?.toString().orEmpty())))
        }
        return sb.toString()
    }
}
