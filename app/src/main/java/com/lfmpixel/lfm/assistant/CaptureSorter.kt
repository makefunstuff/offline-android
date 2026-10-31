package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.llm.ToolCall

/**
 * Sorts a capture without the model, by simple rules: used when no text model is loaded yet, so typing or
 * speaking a thought still lands in the right place. The model does this better; one tap moves it if wrong.
 */
object CaptureSorter {
    private val remind = Regex("""^(?:please\s+)?remind me(?: to)?\s+(.+?)\s+(at|on|in|tomorrow|tonight|today)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val todo = Regex("""^(?:to-?do:?|buy|get|pick up|call|email|e-mail|pay|book|renew|fix|send|need to|i need to|don't forget to)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val idea = Regex("""^(?:idea:?|what if|maybe (?:i|we) could)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val journal = Regex("""^(?:journal:?|dear diary|today i (?:felt|feel)|i feel|i felt|feeling)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val note = Regex("""^note:?\s*(.*)$""", RegexOption.IGNORE_CASE)

    fun sort(text: String): ToolCall {
        val t = text.trim().trimEnd('.')
        remind.find(t)?.let { m ->
            // "in 10 minutes" keeps its "in"; "at 6" and "on Friday" lose theirs; "tomorrow 9:00" keeps the day.
            val word = m.groupValues[2].lowercase()
            val whenText = (if (word in setOf("tomorrow", "tonight", "today", "in")) "$word " else "") + m.groupValues[3]
            return ToolCall("set_reminder", mapOf("text" to m.groupValues[1], "at" to whenText.trim()))
        }
        note.find(t)?.let { return ToolCall("add_note", mapOf("text" to it.groupValues[1].ifBlank { t })) }
        idea.find(t)?.let { m -> return ToolCall("add_idea", mapOf("text" to (if (m.value.lowercase().startsWith("idea")) m.groupValues[1] else t).replaceFirstChar { it.uppercase() })) }
        journal.find(t)?.let { m -> return ToolCall("add_journal", mapOf("text" to (if (m.value.lowercase().startsWith("journal") || m.value.lowercase().startsWith("dear")) m.groupValues[1] else t))) }
        todo.find(t)?.let { m ->
            val prefix = t.substringBefore(' ').lowercase()
            val isShopping = prefix == "buy" || prefix == "get" || t.lowercase().startsWith("pick up")
            val text = if (prefix.startsWith("to-do") || prefix.startsWith("todo") || t.lowercase().startsWith("need to") ||
                t.lowercase().startsWith("i need to") || t.lowercase().startsWith("don't forget to")) m.groupValues[1] else t
            val args = mutableMapOf("text" to text.replaceFirstChar { it.uppercase() })
            if (isShopping) args["list"] = "Shopping"
            return ToolCall("add_todo", args)
        }
        return ToolCall("add_note", mapOf("text" to t))
    }
}
