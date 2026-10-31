package com.lfmpixel.lfm.llm

/** One turn as the model sees it. Assistant turns keep the raw text the model wrote, thinking included. */
data class PromptMessage(val role: Role, val content: String) {
    enum class Role(val tag: String) { User("user"), Assistant("assistant"), Tool("tool") }
}

/**
 * Builds LFM3 prompts the way its chat template does (ChatML with `<think>` and `<tools>`), with
 * `preserve_thinking` on, so a new prompt starts with exactly the tokens already in the context
 * and llama.cpp only has to read the new turn.
 */
object PromptFormat {
    const val IM_START = "<|im_start|>"
    const val IM_END = "<|im_end|>"

    fun system(text: String, toolsJson: List<String>): String {
        val body = if (toolsJson.isEmpty()) text else
            text + (if (text.isEmpty()) "" else "\n\n") + "<tools>\n" + toolsJson.joinToString("\n") + "\n</tools>"
        return if (body.isEmpty()) "" else "${IM_START}system\n$body$IM_END\n"
    }

    /** The prefix the assistant turn starts with; the model writes everything after it. */
    fun assistantPrefix(thinking: Boolean) = if (thinking) "<think>" else "<think></think>"

    /**
     * [messages] with the thinking taken out of the answers before the last user message, as the template does with
     * `preserve_thinking` off: for a prompt that has to be shortened anyway (so the context is read again regardless).
     * Answers in the current turn keep theirs, as the model wrote them before its tool calls.
     */
    fun withoutOldThoughts(messages: List<PromptMessage>): List<PromptMessage> {
        val lastUser = messages.indexOfLast { it.role == PromptMessage.Role.User }
        return messages.mapIndexed { i, m ->
            if (i < lastUser && m.role == PromptMessage.Role.Assistant && m.content.startsWith("<think>") && m.content.contains("</think>"))
                m.copy(content = "<think></think>" + m.content.substringAfter("</think>").trimStart())
            else m
        }
    }

    fun build(system: String, toolsJson: List<String>, messages: List<PromptMessage>, thinking: Boolean): String {
        val sb = StringBuilder(system(system, toolsJson))
        var pendingTool = mutableListOf<String>()
        for ((i, m) in messages.withIndex()) {
            when (m.role) {
                PromptMessage.Role.User -> sb.append(IM_START).append("user\n").append(m.content).append(IM_END).append('\n')
                PromptMessage.Role.Assistant -> {
                    val content = if (m.content.startsWith("<think>")) m.content else "<think></think>" + m.content
                    sb.append(IM_START).append("assistant\n").append(content).append(IM_END).append('\n')
                }
                PromptMessage.Role.Tool -> {
                    // Several results in a row go in one tool turn, as the template merges them.
                    pendingTool.add(m.content)
                    val next = messages.getOrNull(i + 1)
                    if (next?.role != PromptMessage.Role.Tool) {
                        sb.append(IM_START).append("tool\n")
                        sb.append(pendingTool.joinToString("\n") { "<tool_response>\n$it\n</tool_response>" })
                        sb.append(IM_END).append('\n')
                        pendingTool = mutableListOf()
                    }
                }
            }
        }
        sb.append(IM_START).append("assistant\n").append(assistantPrefix(thinking))
        return sb.toString()
    }
}

/** A tool as the model sees it: name, what it does and its parameters. */
data class ToolSpec(val name: String, val description: String, val params: List<Param>) {
    data class Param(val name: String, val type: String, val description: String, val required: Boolean = true,
                     val enum: List<String> = emptyList())

    fun toJson(): String {
        val props = params.joinToString(", ") { p ->
            val e = if (p.enum.isEmpty()) "" else ", \"enum\": [" + p.enum.joinToString(", ") { Json.str(it) } + "]"
            "${Json.str(p.name)}: {\"type\": ${Json.str(p.type)}, \"description\": ${Json.str(p.description)}$e}"
        }
        val req = params.filter { it.required }.joinToString(", ") { Json.str(it.name) }
        return "{\"name\": ${Json.str(name)}, \"description\": ${Json.str(description)}, " +
            "\"parameters\": {\"type\": \"object\", \"properties\": {$props}, \"required\": [$req]}}"
    }
}

object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun obj(vararg pairs: Pair<String, Any?>): String = pairs.filter { it.second != null }.joinToString(", ", "{", "}") { (k, v) ->
        str(k) + ": " + when (v) {
            is Number, is Boolean -> v.toString()
            is List<*> -> v.joinToString(", ", "[", "]") { str(it.toString()) }
            else -> str(v.toString())
        }
    }
}
