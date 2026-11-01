package com.lfmpixel.lfm.daylight

import java.time.LocalDateTime

/**
 * Wren's one voice (docs/ux-spec.md, "Voice"): the same few lines open every prompt that speaks to the owner, in
 * Talk and in the chat, and the app's own copy follows them.
 */
object Persona {
    const val PROMPT = "Your manner: warm, calm and brief, like a friend who keeps good time. Plain words; no exclamation " +
        "marks, flattery, emoji or pep talk. Never scold, never count what was missed: offer one next step instead. If you " +
        "do not know, say so plainly."
}

/**
 * Today's first line: the day in one sentence, not counts (no-nag rules: nothing counted against the owner, nothing
 * red, nothing about what was missed). [plans] are today's timed things; [openTodos] the to-dos without a time.
 */
object DaySentence {
    fun greeting(t: LocalDateTime): String = when (t.hour) {
        in 5..11 -> "Morning."
        in 12..16 -> "Afternoon."
        in 17..21 -> "Evening."
        else -> "Late now."
    }

    fun of(t: LocalDateTime, plans: List<Plan>, openTodos: Int): String {
        val now = t.hour * 60 + t.minute
        val left = plans.sortedBy { it.minute }.filter { it.minute >= now - 15 }
        val g = greeting(t)
        val next = left.firstOrNull()
        if (next != null) {
            val more = left.size - 1
            val then = when (more) { 0 -> ""; 1 -> " Then one more."; else -> " Then ${words(more)} more." }
            val until = next.minute - now
            return when {
                until <= 0 -> "$g ${title(next)} now.$then"
                until <= 90 -> "$g ${title(next)} at ${clock(next.minute)}, in ${span(until)}.$then"
                else -> {
                    val weight = when (left.size) { 1, 2 -> "A light day"; 3, 4 -> "A steady day"; else -> "A full day" }
                    val rest = when (more) { 0 -> ""; 1 -> ", and one more later"; else -> ", and ${words(more)} more later" }
                    "$g $weight: ${title(next)} at ${clock(next.minute)}$rest."
                }
            }
        }
        val late = t.hour >= 22 || t.hour < 5
        return when {
            late -> "$g Nothing left today. Tomorrow can wait."
            openTodos == 0 -> if (t.hour >= 17) "$g Nothing left at a set time. The evening is yours." else "$g Nothing at a set time. The day is yours."
            else -> "$g Nothing at a set time; ${todos(openTodos)} when you're ready."
        }
    }

    private fun title(p: Plan) = p.title.trim().let { if (it.length > 40) it.take(39).trimEnd() + "…" else it }
        .replaceFirstChar { it.uppercase() }

    fun clock(minute: Int) = "%02d:%02d".format(minute / 60 % 24, minute % 60)

    /** "25 minutes", "an hour", "1 h 20 min". */
    fun span(minutes: Int): String = when {
        minutes <= 1 -> "a minute"
        minutes < 60 -> "$minutes minutes"
        minutes == 60 -> "an hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    private fun todos(n: Int) = when (n) { 1 -> "one to-do"; 2 -> "two to-dos"; else -> "a few to-dos" }

    fun words(n: Int) = listOf("no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").getOrElse(n) { "a few" }
}
