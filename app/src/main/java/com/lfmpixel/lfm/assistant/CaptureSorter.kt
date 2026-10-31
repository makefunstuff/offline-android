package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.llm.ToolCall

/**
 * Sorts a capture without the model, by simple rules: used when no text model is loaded yet, so typing or
 * speaking a thought still lands in the right place. The model does this better; one tap moves it if wrong.
 */
object CaptureSorter {
    private val todo = Regex("""^(?:to-?do:?|buy|get|pick up|call|email|e-mail|pay|book|renew|fix|send|need to|i need to|don't forget to)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val idea = Regex("""^(?:idea:?|what if|maybe (?:i|we) could)\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val journal = Regex("""^(?:journal:?|dear diary|today i (?:felt|feel)|i feel|i felt|feeling)\b\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val note = Regex("""^note:?\s*(.*)$""", RegexOption.IGNORE_CASE)

    private val remindStart = Regex("""^(?:please\s+)?remind me(?: to)?\s+""", RegexOption.IGNORE_CASE)
    private val timeWord = Regex("""(?:^|\s)(at|on|in|tomorrow|tonight|today)\b""", RegexOption.IGNORE_CASE)

    /**
     * "remind me to X <when>": the first "at", "on", "in", "today"… that starts a time [TimeArgs] can read, so
     * "check in with Sam at 6" splits at "at 6", not at "in". Falls back to the first such word.
     */
    private fun remindTime(t: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): Pair<String, String>? {
        val start = remindStart.find(t) ?: return null
        val rest = t.substring(start.range.last + 1)
        val splits = timeWord.findAll(rest).toList()
        fun at(m: MatchResult): Pair<String, String> {
            // "in 10 minutes" keeps its "in"; "at 6" and "on Friday" lose theirs; "tomorrow 9:00" keeps the day.
            val word = m.groupValues[1].lowercase()
            val keep = if (word in setOf("tomorrow", "tonight", "today", "in")) "$word " else ""
            val tail = rest.substring(m.range.last + 1).trim()
            // The time first: "remind me in 10 minutes to check the oven", or just "remind me at 6".
            if (m.range.first == 0) {
                val parts = tail.split(Regex("""\s+to\s+""", RegexOption.IGNORE_CASE), limit = 2)
                return parts.getOrElse(1) { "Reminder" }.trim() to (keep + parts[0]).trim()
            }
            return rest.substring(0, m.range.first).trim() to (keep + tail).trim()
        }
        val good = splits.map(::at).firstOrNull { (what, w) -> what.isNotEmpty() && com.lfmpixel.lfm.tools.TimeArgs.parse(w, now) != null }
        return good ?: splits.firstOrNull()?.let(::at)?.takeIf { it.first.isNotEmpty() }
    }

    fun sort(text: String): ToolCall {
        val t = text.trim().trimEnd('.')
        remindTime(t)?.let { (what, whenText) -> return ToolCall("set_reminder", mapOf("text" to what, "at" to whenText)) }
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
