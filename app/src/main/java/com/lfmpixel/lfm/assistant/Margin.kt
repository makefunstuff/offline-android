package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.ModelOutput
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** What Ink in the margin can do with a note (notebook-spec proposal 3). [Ask] is the owner's own question. */
enum class MarginAsk(val label: String, val icon: String, val heading: String) {
    Sum("Sum it up", "list", "Summed up"),
    Todos("Pull out to-dos", "todo", "To-dos in this note"),
    Questions("Questions to think about", "bulb", "Questions to think about"),
    Continue("Keep going", "edit", "Kept going"),
    Ask("Ask about this note", "chat", "Answer"),
}

/**
 * Ink in the margin: the assistant writes beside a note, on the phone, without thinking aloud (a quick answer, not a
 * chat). Its words are only a proposal: they reach the note or the page when the owner taps Add.
 */
object Margin {
    /** LiteRT-LM's context is 4096 tokens: the note is cut to leave room for the answer. */
    const val MAX_NOTE_CHARS = 6000
    const val MAX_TOKENS = 400

    private const val SYSTEM = "You are Inkling, writing in the margin of the owner's private notebook on their phone. " +
        "Be brief, concrete and kind. Write plain text: no headings, no bold, no tables. Use the language of the note."

    fun instruction(ask: MarginAsk, question: String = "") = when (ask) {
        MarginAsk.Sum -> "Sum up this note in two or three short sentences."
        MarginAsk.Todos -> "List the concrete things to do in this note, one per line, each line starting with \"- \". " +
            "Keep each short and start it with a verb. If there is nothing to do, write only: None"
        MarginAsk.Questions -> "Write three short questions that would help the owner think this note further. " +
            "One per line, each starting with \"- \"."
        MarginAsk.Continue -> "Continue this note with two to four sentences in the same voice. Write only the continuation."
        MarginAsk.Ask -> question.trim()
    }

    fun prompt(note: String, ask: MarginAsk, question: String = ""): String {
        val body = note.trim().let { if (it.length > MAX_NOTE_CHARS) it.take(MAX_NOTE_CHARS) + "…" else it }
        val user = "The note:\n\"\"\"\n$body\n\"\"\"\n\n" + instruction(ask, question)
        return PromptFormat.build(SYSTEM, emptyList(), listOf(PromptMessage(PromptMessage.Role.User, user)), thinking = false)
    }

    /** The answer so far, each time it grows; the model's raw output is cleaned of tags and stray markdown. */
    fun write(engine: ModelManager, note: String, ask: MarginAsk, question: String = ""): Flow<String> = flow {
        val session = engine.textSession()
        val raw = StringBuilder()
        session.generate(prompt(note, ask, question), MAX_TOKENS, temp = 0.3f).collect { piece ->
            raw.append(piece)
            emit(clean(raw.toString()))
        }
        emit(clean(raw.toString()))
    }

    fun clean(raw: String): String = ModelOutput.parse(raw, thinkingOn = false).answer
        .substringBefore("<tool_call>").replace("**", "").lines().joinToString("\n") { it.trimEnd().removePrefix("#").removePrefix("# ") }.trim()

    /** The to-dos of a "Pull out to-dos" answer: its "- " lines (also "* " and "1."), at most ten. */
    fun todos(answer: String): List<String> = answer.lines().map { it.trim() }
        .mapNotNull { l -> Regex("""^(?:[-*•]|\d+[.)])\s+(.+)$""").find(l)?.groupValues?.get(1)?.trim()?.removeSuffix(".") }
        .filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }.take(10)

    /** The answer as it goes into the note when the owner taps Add: set apart, and marked as Inkling's. */
    fun forNote(ask: MarginAsk, answer: String): String = if (ask == MarginAsk.Continue) answer.trim() else "Inkling, ${ask.heading.lowercase()}:\n" + answer.trim()
}
