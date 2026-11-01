package com.lfmpixel.lfm.assistant

/**
 * Wren's one voice (docs/ux-spec.md, "Voice"), written once: the chat, Ask and Talk all open their prompts with it, and
 * the app's own copy follows it. Two sizes: [CORE] for the answer models with room (Qwen on the server, LFM3 in the
 * chat), [SHORT] for Omni on the phone, whose context is small and whose start must stay the same to stay read in.
 *
 * Tools are for what the owner asks ([TOOLS]): an assistant that offers a reminder after every answer is a nag, and
 * the owner said so ("mostly suggesting notifications").
 */
object Persona {
    /** Who Wren is, for a model with room. */
    const val CORE = "You talk like a thoughtful friend: warm, curious, a little playful, and honest. Be direct: say what " +
        "you think, and when asked for an opinion, give one and say why. Talk like a person, not a help desk: no \"Great " +
        "question\", no \"I'm here to help\", no \"As an AI\", no flattery, pep talk or exclamation marks. Use what you " +
        "know about the owner the way a friend remembers, naturally and without reciting it. Now and then, when it fits, " +
        "ask one natural follow-up question; not every time. If you do not know, say so plainly. Be gentle, never naggy: " +
        "never scold, never count what was missed. Help pick one next step only when the owner asks what to do or wants " +
        "help getting going. Warm, never flirty."

    /** Who Wren is, in a few lines: Omni on the phone (small context, no tools). */
    const val SHORT = "You talk like a thoughtful friend: warm, curious, a little playful, honest and direct; give your " +
        "opinion when asked. Plain words; no \"Great question\", flattery or exclamation marks. Sometimes ask one natural " +
        "follow-up. Be gentle, never naggy. If you do not know, say so."

    /** For a model with no tools (Omni on the phone): it must not promise what it can't do. */
    const val NO_TOOLS = "You can't set reminders or change anything from here, so never offer to; if asked, say it " +
        "works in the chat."

    /** When a model with tools uses them. */
    const val TOOLS = "Most of what the owner says is just conversation: answer it, with no tool. Call a tool only when " +
        "they clearly ask for that action (remind me, add, note, set, text, move, delete) or when the answer needs it " +
        "(their notes, their day, a sum, today's facts). Never offer reminders, notes, to-dos or notifications, and never " +
        "end with an offer like \"Want me to…\": the owner asks when they want one. The one exception: they mention a " +
        "specific thing they must do at a set time and didn't ask; then, rarely, one short offer."

    /** How a spoken answer sounds, in [language] (Omni's name for it, "British English"). */
    fun spoken(language: String) = "You are talking out loud. Answer in $language, usually in one to three short " +
        "sentences; more only when asked. No lists, Markdown, emoji or links: just speech."

    /** How a written answer looks, in the chat. */
    const val WRITTEN = "Keep answers short unless asked for more; plain text, and a short list only when it really helps."

    /** [CORE], or [SHORT] for a model whose context is [contextTokens] when that is small (the phone's 4K window). */
    fun forContext(contextTokens: Int) = if (contextTokens >= 8_000) CORE else SHORT
}

/**
 * The answer's last sentence offering a reminder, a note or a to-do nobody asked for ("Want me to set a reminder?"):
 * dropped from what Talk says on the server, where the prompt alone did not stop it. Kept when the owner's own words
 * named a time for something they must do ([timed]): then a rare offer is the exception [Persona.TOOLS] allows.
 */
object Offers {
    private val opt = setOf(RegexOption.IGNORE_CASE)
    private val offer = Regex("""^(?:(?:so|and|also|oh|by the way),?\s+)?(?:do you want|would you like|want|shall|should|can|could|may|how about|if you like)\b""" +
        """.*\b(?:remind|reminders?|notifications?|notify|nudge|alarms?|add (?:it|that|this) to|(?:a|on|to) (?:your )?to-?do|put (?:it|that|this) (?:on|in))\b.*\?$""", opt)
    /** A time in what was said: "at six", "5pm", "tomorrow", "on Friday". */
    private val timing = Regex("""\b(?:remind|at (?:\d|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|noon|midnight)|\d\s*(?:am|pm|o'clock)|\d[:.]\d\d|tomorrow|tonight|on (?:mon|tue|wed|thu|fri|sat|sun)\w*|this (?:afternoon|evening|morning)|by (?:mon|tue|wed|thu|fri|sat|sun|\d))""", opt)

    fun isOffer(sentence: String): Boolean = offer.matches(sentence.trim())

    /** Something the owner has to do, not just something happening: "I need to", "I have to", "don't forget". */
    private val task = Regex("""\b(?:remind|need to|needs to|have to|has to|got to|gotta|must|should|don't forget|mustn't forget|supposed to)\b""", opt)

    /** Whether [said] named a time for something the owner must do: then an offer may stay. */
    fun timed(said: String): Boolean = timing.containsMatchIn(said) && task.containsMatchIn(said)

    /** [answer] without trailing offers, unless [said] was [timed]. */
    fun strip(answer: String, said: String): String {
        if (timed(said)) return answer
        val sentences = Regex("(?<=[.!?…])\\s+").split(answer.trim()).toMutableList()
        while (sentences.size > 1 && isOffer(sentences.last())) sentences.removeAt(sentences.lastIndex)
        return sentences.joinToString(" ")
    }
}

/**
 * [Offers] for speech as it comes: each piece ready to say ([com.lfmpixel.lfm.voice.SpeechChunker]) passes, except a
 * sentence that is an unasked offer. A piece that starts like one waits for its sentence to end before it is judged.
 */
class OfferFilter(said: String) {
    private val allowed = Offers.timed(said)
    private val held = StringBuilder()
    private val opener = Regex("""^(?:(?:so|and|also|oh|by the way),?\s+)?(?:do you want|would you like|want|shall|should|can|could|may|how about|if you like)\b""", RegexOption.IGNORE_CASE)

    fun feed(piece: String): List<String> {
        if (allowed) return listOf(piece)
        if (held.isEmpty() && !opener.containsMatchIn(piece.trim())) return listOf(piece)
        if (held.isNotEmpty()) held.append(' ')
        held.append(piece.trim())
        if (held.last() !in ".!?…") return emptyList()
        return flush()
    }

    /** What is still held, once the answer is over: said unless it is an offer. */
    fun flush(): List<String> {
        val s = held.toString().also { held.clear() }
        return if (s.isBlank() || Offers.isOffer(s)) emptyList() else listOf(s)
    }
}
