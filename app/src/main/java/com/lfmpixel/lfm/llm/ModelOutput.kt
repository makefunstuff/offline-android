package com.lfmpixel.lfm.llm

/** A tool call the model wrote, with its arguments as text. */
data class ToolCall(val name: String, val args: Map<String, String>)

/**
 * What the model wrote after the assistant prefix, split into thinking, the visible answer and tool calls.
 * LFM3 writes calls as
 * ```
 * <tool_call>
 * <function=name>
 * <parameter=arg>
 * value
 * </parameter>
 * </function>
 * </tool_call>
 * ```
 */
data class ModelOutput(val thinking: String, val answer: String, val toolCalls: List<ToolCall>, val stillThinking: Boolean) {
    companion object {
        private val callBlock = Regex("""<tool_call>(.*?)(</tool_call>|$)""", RegexOption.DOT_MATCHES_ALL)
        private val function = Regex("""<function=([^>\s]+)>(.*?)(</function>|$)""", RegexOption.DOT_MATCHES_ALL)
        private val parameter = Regex("""<parameter=([^>\s]+)>\n?(.*?)\n?</parameter>""", RegexOption.DOT_MATCHES_ALL)
        // Some models write a JSON object instead: {"name": ..., "arguments": {...}}
        private val jsonName = Regex(""""name"\s*:\s*"([^"]+)"""")
        private val jsonArg = Regex(""""([A-Za-z_][A-Za-z0-9_]*)"\s*:\s*("((?:[^"\\]|\\.)*)"|-?[0-9.]+|true|false)""")

        /** The words that end a thought out of budget: never shown as part of it. */
        const val OUT_OF_BUDGET_WORDS = "Time is up; I will answer now."

        /**
         * Closes a thought that ran out of budget. A bare `</think>` mid-sentence leaves LFM3 thinking on in its
         * answer; a closing line of its own makes it answer.
         */
        const val OUT_OF_BUDGET = "\n\n$OUT_OF_BUDGET_WORDS\n</think>\n\n"

        /**
         * @param raw text after the prompt's `<think>` (thinking on) or `<think></think>` (thinking off).
         * @param thinkingOn whether the prompt left a `<think>` open.
         */
        fun parse(raw: String, thinkingOn: Boolean): ModelOutput {
            var text = raw
            var thinking = ""
            var stillThinking = false
            if (thinkingOn) {
                // The prompt opened the block; a model that opens it again just starts its thought.
                text = text.trimStart().removePrefix("<think>")
                val end = text.indexOf("</think>")
                if (end < 0) {
                    return ModelOutput(text.trim(), "", emptyList(), stillThinking = true)
                }
                thinking = text.substring(0, end).trim().removeSuffix(OUT_OF_BUDGET_WORDS).trim()
                text = text.substring(end + "</think>".length)
                // A stray tag after that (LFM3 writes them now and then, and may close a thought twice when its
                // budget ran out) just goes: the words around it stay in the answer, never hidden in the trace.
                text = text.replace("</think>", "\n").replace("<think>", "\n")
            } else if (text.startsWith("<think>")) {
                val end = text.indexOf("</think>")
                if (end < 0) return ModelOutput(text.removePrefix("<think>").trim(), "", emptyList(), true)
                thinking = text.substring("<think>".length, end).trim()
                text = text.substring(end + "</think>".length)
            } else if (text.contains("</think>") && !text.contains("<think>") && !text.substringBefore("</think>").contains("<tool_call>")) {
                // With thinking off LFM3 sometimes answers, closes a thought it never opened, then answers again
                // ("Tokyo</think>Tokyo"): the part after is the answer, and the part before is dropped (no thought shows).
                // A tag with nothing after it just goes.
                val after = text.substring(text.lastIndexOf("</think>") + "</think>".length)
                text = if (after.isBlank()) text.substring(0, text.lastIndexOf("</think>")) else after
            }
            val calls = mutableListOf<ToolCall>()
            for (block in callBlock.findAll(text)) {
                val body = block.groupValues[1]
                val fn = function.findAll(body).toList()
                if (fn.isNotEmpty()) {
                    for (f in fn) {
                        if (f.groupValues[3].isEmpty()) continue // still being written
                        val args = parameter.findAll(f.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2].trim() }
                        calls += ToolCall(f.groupValues[1].trim(), args)
                    }
                } else if (block.groupValues[2].isNotEmpty()) {
                    jsonName.find(body)?.let { n ->
                        val argsStart = body.indexOf("\"arguments\"").takeIf { it >= 0 } ?: body.indexOf("\"parameters\"")
                        val argsText = if (argsStart >= 0) body.substring(argsStart) else ""
                        val args = jsonArg.findAll(argsText).associate { m ->
                            m.groupValues[1] to (if (m.groupValues[2].startsWith("\"")) unescape(m.groupValues[3]) else m.groupValues[2])
                        }.filterKeys { it != "arguments" && it != "parameters" }
                        calls += ToolCall(n.groupValues[1], args)
                    }
                }
            }
            val answer = text.replace(callBlock, "").replace("<|im_end|>", "").trim()
            return ModelOutput(thinking, answer, calls, stillThinking)
        }

        /** What a stored answer thought, from its raw text (which starts with the assistant prefix); "" if nothing. */
        fun thoughtOf(raw: String): String = when {
            !raw.startsWith("<think>") || raw.startsWith("<think></think>") -> ""
            else -> parse(raw.removePrefix("<think>"), true).thinking
        }

        private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
