package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.llm.ToolCall
import java.time.LocalDate

/** Where a line written on the Daily Page goes (notebook-spec proposal 2). */
enum class InkKind(val label: String, val icon: String) {
    Note("Note", "note"), Todo("To-do", "todo"), Idea("Idea", "bulb"), Journal("Journal", "book"),
    Reminder("Reminder", "bell"), Ask("Ask Inkling", "chat"),
}

/** The Ink line's guess for a line: its kind, and the text that goes in once the signifier words are taken off. */
data class InkGuess(val kind: InkKind, val text: String, val reminderAt: String = "")

/**
 * The Ink line: write first, it sorts as you type. The guess is [CaptureSorter]'s rules, on the phone and without the
 * model, so the glyph can follow every key; a line ending in "?" is a question for the assistant.
 */
object InkLine {
    private val askStart = Regex("""^(?:ask|inkling)[:,]\s*""", RegexOption.IGNORE_CASE)
    /** A day told in the first person reads as a journal line, beyond the sorter's "I feel…". */
    private val diary = Regex("""^(?:felt|today was|today i|tonight i|this morning i|slept|woke up|i woke|grateful for|i'm grateful|i am grateful)\b""", RegexOption.IGNORE_CASE)

    fun guess(line: String): InkGuess {
        val t = line.trim()
        if (t.isEmpty()) return InkGuess(InkKind.Note, "")
        askStart.find(t)?.let { return InkGuess(InkKind.Ask, t.substring(it.range.last + 1).trim()) }
        val call = CaptureSorter.sort(t)
        // "What if …?" is an idea, however it ends; other questions go to the assistant.
        if (t.endsWith("?") && call.name != "add_idea") return InkGuess(InkKind.Ask, t)
        val text = call.args["text"].orEmpty().ifBlank { t }
        return when (call.name) {
            "set_reminder" -> InkGuess(InkKind.Reminder, text, call.args["at"].orEmpty())
            "add_todo" -> InkGuess(InkKind.Todo, text)
            "add_idea" -> InkGuess(InkKind.Idea, text)
            "add_journal" -> InkGuess(InkKind.Journal, text)
            else -> InkGuess(if (diary.containsMatchIn(t)) InkKind.Journal else InkKind.Note, text)
        }
    }

    /**
     * The guess with the small model's label ([com.lfmpixel.lfm.edge.Labels.capture], on the server): its kind wins over
     * the rules', except for a question or an "ask:" line, which stay with the rules. Words that say when, if the phone
     * can read them as a time (TimeArgs), make a to-do or event a reminder at that time.
     */
    fun withLabel(line: String, label: com.lfmpixel.lfm.edge.CaptureLabel, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): InkGuess {
        val rules = guess(line)
        if (rules.kind == InkKind.Ask) return rules
        val words = label.whenWords.takeIf { it.isNotEmpty() && line.contains(it, ignoreCase = true) }
        val timed = words?.let { com.lfmpixel.lfm.tools.TimeArgs.parse(it, now) }?.takeIf { it.time != null }
        val kind = when (label.kind) {
            "todo" -> if (timed != null) InkKind.Reminder else InkKind.Todo
            "event" -> if (timed != null) InkKind.Reminder else InkKind.Note
            "idea" -> InkKind.Idea
            "journal" -> InkKind.Journal
            "note" -> InkKind.Note
            else -> return rules
        }
        if (kind == rules.kind) return rules
        if (kind == InkKind.Reminder) {
            val i = line.indexOf(words!!, ignoreCase = true)
            val what = (line.substring(0, i) + line.substring(i + words.length)).replace(Regex("""\s+"""), " ")
                .trim(' ', ',', '.', '-').removeSuffix(" at").removeSuffix(" on").trim()
            return InkGuess(InkKind.Reminder, what.ifEmpty { line.trim() }, words)
        }
        return InkGuess(kind, line.trim())
    }

    /** The kinds a tap on the glyph steps through: a reminder only when the line names a time. */
    fun cycle(guessed: InkKind): List<InkKind> =
        listOf(InkKind.Note, InkKind.Todo, InkKind.Idea, InkKind.Journal) + listOfNotNull(InkKind.Reminder.takeIf { guessed == it }) + InkKind.Ask

    /** The kind after [current], for a tap on the glyph. */
    fun next(current: InkKind, guessed: InkKind): InkKind = cycle(guessed).let { it[(it.indexOf(current) + 1) % it.size] }

    /**
     * The tool call that puts [line] on the page as [kind], or null for a question (it goes to the chat). The words the
     * guess took off ("idea:", "to-do") stay off only when the kind is the guessed one; a to-do written here is due
     * [today], so an open one is carried over tomorrow.
     */
    fun call(line: String, kind: InkKind, today: LocalDate, g: InkGuess = guess(line)): ToolCall? {
        val text = if (kind == g.kind) g.text else line.trim()
        return when (kind) {
            InkKind.Ask -> null
            InkKind.Reminder -> ToolCall("set_reminder", mapOf("text" to g.text, "at" to g.reminderAt))
            InkKind.Todo -> ToolCall("add_todo", buildMap {
                put("text", text.replaceFirstChar { it.uppercase() })
                put("due", today.toString())
                // "buy …" lands on the Shopping list, as from voice.
                CaptureSorter.sort(line.trim()).args["list"]?.let { put("list", it) }
            })
            InkKind.Idea -> ToolCall("add_idea", mapOf("text" to text))
            InkKind.Journal -> ToolCall("add_journal", mapOf("text" to text))
            InkKind.Note -> ToolCall("add_note", mapOf("text" to text))
        }
    }

    /** When a reminder line will ring, as the Ink line shows it: "today 18:00", "Sun 09:00"; or what was typed. */
    fun whenLabel(at: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
        val w = com.lfmpixel.lfm.tools.TimeArgs.parse(at, now) ?: return at
        val day = when (w.date) {
            now.toLocalDate() -> "today"
            now.toLocalDate().plusDays(1) -> "tomorrow"
            else -> w.date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH))
        }
        return day + (w.time?.let { " " + it.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) } ?: "")
    }

    /** The question as it goes to the chat. */
    fun question(line: String): String = guess(line).let { if (it.kind == InkKind.Ask) it.text else line.trim() }
}
