package com.lfmpixel.lfm.voice

import com.lfmpixel.lfm.llm.PromptFormat

/** The voices and languages Omni speaks (its "tts" chat template). */
enum class SpeechVoice(val label: String) { Female("Female"), Male("Male") }

enum class SpeechLanguage(val code: String, val label: String, val asr: String) {
    EnglishUK("en-gb", "English (UK)", "English"),
    EnglishUS("en-us", "English (US)", "English"),
    German("de", "German", "German"),
    French("fr", "French", "French"),
    Spanish("es", "Spanish", "Spanish"),
    Italian("it", "Italian", "Italian"),
    Dutch("nl", "Dutch", "Dutch"),
    Polish("pl", "Polish", "Polish"),
    Portuguese("pt", "Portuguese", "Portuguese"),
    Swedish("sv", "Swedish", "Swedish"),
    Japanese("ja", "Japanese", "Japanese"),
    Chinese("zh", "Chinese", "Chinese");

    /** The name Omni's template uses for the language. */
    val ttsName get() = when (this) { EnglishUK -> "British English"; EnglishUS -> "American English"; else -> label }
}

/**
 * What Omni is asked to say, and how a written answer becomes something worth hearing: no Markdown marks, no
 * source numbers, no web addresses, in pieces short enough to start speaking soon.
 */
object SpeechFormat {
    /** Omni's "tts" chat template, filled in (llama.cpp adds the start token). */
    fun ttsPrompt(text: String, language: SpeechLanguage, voice: SpeechVoice): String =
        "${PromptFormat.IM_START}system\nPerform TTS in ${language.ttsName} using a ${voice.name.lowercase()} voice.${PromptFormat.IM_END}\n" +
            "${PromptFormat.IM_START}user\n$text${PromptFormat.IM_END}\n" +
            "${PromptFormat.IM_START}assistant\n<think></think><|audio_start|>"

    /** Talk's instructions: short spoken answers, in the owner's language. */
    fun talkSystem(language: SpeechLanguage, today: String) =
        "You are Inkling, a private assistant that runs on the owner's phone. You are talking out loud with the owner. " +
            "Answer in ${language.ttsName}, in one to three short spoken sentences, without lists, Markdown or web links. " +
            "If you do not know, say so. Today is $today."

    /**
     * Talk with the answer model and the app's tools (Edge mode, Qwen): it may add, remind, note and plan by calling a
     * tool, then says so in a few words. The day only: the time and the camera's view go with each turn ([talkUser]), so
     * the server can keep this start read in.
     */
    fun talkToolSystem(language: SpeechLanguage, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()) =
        "You are Inkling, the owner's private assistant and notebook. You are talking out loud with the owner. " +
            "Answer in ${language.ttsName}, in one to three short spoken sentences, without lists, Markdown or web links. " +
            "When they ask you to remember, note, add, remind, plan, set or change something, call a tool; \"remind me\" is " +
            "set_reminder. For something already there (done, move, snooze, delete) use complete_todo, reschedule or " +
            "delete_item; for what's on the day, daily_brief; for any sum or unit conversion, calculate. Write dates and times as local ISO time like 2026-10-30T18:00. After a tool runs, say what was " +
            "done in a few words. Never claim something was done unless a tool did it. Never say what is in the notebook, on " +
            "the phone or what a sum comes to without calling its tool in this turn. If you do not know, say so: do not guess. " +
            "Today is " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", java.util.Locale.ENGLISH)) +
            ", time zone ${now.zone.id}."

    /** What the owner said, with the time now and what the camera sees (Look), if it is on. */
    fun talkUser(said: String, seeing: String?, now: java.time.LocalTime = java.time.LocalTime.now()) =
        said + "\n\n(Now: " + now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) + ")" +
            (seeing?.let { "\n(The camera sees: $it)" } ?: "")

    /** The conversation so far for Talk, ending with the new user turn. */
    fun talkHistory(system: String, turns: List<Pair<String, String>>, user: String): String =
        talkPrefix(system, turns) + "${PromptFormat.IM_START}user\n$user${PromptFormat.IM_END}\n"

    /**
     * The conversation before the next user turn. Only the last few exchanges: a spoken conversation rarely needs
     * more, and a short prompt answers sooner. The oldest are dropped three at a time, so the start stays the same
     * for most turns and Omni can keep it read in (talk.cpp's history cache) instead of reading it all again.
     */
    fun talkPrefix(system: String, turns: List<Pair<String, String>>): String {
        val sb = StringBuilder(PromptFormat.system(system, emptyList()))
        val from = if (turns.size <= 6) 0 else (turns.size - 6 + 2) / 3 * 3
        for ((you, it) in turns.drop(from)) {
            sb.append(PromptFormat.IM_START).append("user\n").append(you).append(PromptFormat.IM_END).append('\n')
            sb.append(PromptFormat.IM_START).append("assistant\n<think></think>").append(it).append(PromptFormat.IM_END).append('\n')
        }
        return sb.toString()
    }

    const val REPLY_PREFIX = "${PromptFormat.IM_START}assistant\n<think></think>"

    /** A written answer as plain speech. */
    fun clean(text: String): String {
        var t = text
        t = t.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
        t = t.replace(Regex("\\[([^\\]]+)]\\((?:https?://)?[^)]+\\)"), "$1") // [label](link) → label
        t = t.replace(Regex("https?://\\S+"), "")
        t = t.replace(Regex("\\s?\\[\\d+(?:[,–-]\\s?\\d+)*]"), "") // source numbers [1], [1, 2]
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+"), "")
        t = t.replace(Regex("[*_`~]+"), "")
        t = t.replace(Regex("\\s*\\n+\\s*"), "\n").trim()
        // Each line ends as a sentence, so list items are not read as one run-on sentence.
        t = t.lines().filter { it.isNotBlank() }.joinToString(" ") { l -> if (l.last() in ".!?:;,") l else "$l." }
        return t.replace(Regex(" {2,}"), " ").trim()
    }

    /**
     * Splits speech into pieces of whole sentences, each at most [max] characters (a sentence longer than that is
     * cut at a comma or a space). The first piece is short, so the voice starts sooner.
     */
    fun chunks(text: String, max: Int = 240, first: Int = 120): List<String> {
        val sentences = Regex("(?<=[.!?…])\\s+|(?<=[。！？])").split(text.trim()).filter { it.isNotBlank() }.flatMap { split(it, max) }
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (s in sentences) {
            val limit = if (out.isEmpty()) first else max
            if (cur.isNotEmpty() && cur.length + 1 + s.length > limit) { out.add(cur.toString()); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun split(sentence: String, max: Int): List<String> {
        if (sentence.length <= max) return listOf(sentence)
        val out = mutableListOf<String>()
        var rest = sentence
        while (rest.length > max) {
            val window = rest.substring(0, max)
            val cut = window.lastIndexOf(", ").takeIf { it > max / 3 }?.plus(1) ?: window.lastIndexOf(' ').takeIf { it > 0 } ?: max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }
}

/**
 * Tells when the owner starts and stops speaking, from the loudness of each 30 ms block of the microphone. The
 * background level is learnt as it goes, so a quiet room and a noisy street both work. Pure logic, so it is tested
 * without a microphone.
 */
class Endpointer(
    private val blockMs: Int = 30,
    /** Silence after speech that ends a turn. */
    private val endSilenceMs: Int = 800,
    /** Speech shorter than this is a cough or a click, not a turn. */
    private val minSpeechMs: Int = 240,
    /** Nothing said for this long: Talk pauses, to save battery. */
    private val idleMs: Int = 20_000,
    /** The longest turn; it ends there. */
    private val maxTurnMs: Int = 30_000,
) {
    enum class Event { None, Started, Ended, Idle }

    private var noise = 0.004f
    /** Blocks heard so far for the first estimate of the background (the first 240 ms are never speech). */
    private var calibrated = 0
    private var speaking = false
    private var speechMs = 0
    private var silenceMs = 0
    private var waitedMs = 0
    private var voicedRun = 0
    val inSpeech get() = speaking
    /** Silence so far inside a turn: the owner may have finished (see [TurnRecorder]'s early hearing). */
    val pausedMs get() = if (speaking) silenceMs else 0
    /** How long a silence ends a turn. */
    val endMs get() = endSilenceMs

    private companion object { const val CALIBRATION_BLOCKS = 8 }

    fun reset() { speaking = false; speechMs = 0; silenceMs = 0; waitedMs = 0; voicedRun = 0 }

    /** Feeds one block's RMS level (0..1). */
    fun feed(rms: Float): Event {
        if (calibrated < CALIBRATION_BLOCKS) {
            noise = if (calibrated == 0) rms else (noise * calibrated + rms) / (calibrated + 1)
            calibrated++
            waitedMs += blockMs
            return Event.None
        }
        val threshold = maxOf(noise * 3.2f, 0.012f)
        val voiced = rms > threshold
        if (!speaking) {
            // The background level follows quiet blocks quickly and loud ones slowly.
            noise = if (rms < noise) noise * 0.9f + rms * 0.1f else noise * 0.995f + rms * 0.005f
            waitedMs += blockMs
            voicedRun = if (voiced) voicedRun + blockMs else 0
            if (voicedRun >= 90) { speaking = true; speechMs = voicedRun; silenceMs = 0; return Event.Started }
            return if (waitedMs >= idleMs) Event.Idle.also { waitedMs = 0 } else Event.None
        }
        speechMs += blockMs
        silenceMs = if (voiced) 0 else silenceMs + blockMs
        if (silenceMs >= endSilenceMs || speechMs >= maxTurnMs) {
            val enough = speechMs - silenceMs >= minSpeechMs
            reset()
            return if (enough) Event.Ended else Event.None
        }
        return Event.None
    }
}

/**
 * Cuts an answer into pieces to speak while it is still being written: each whole sentence as soon as it ends,
 * and a long one at a comma, so the voice starts after the first few words and the next piece is ready before
 * this one has been heard. [first] is how long the first piece may grow before it is cut at a comma or space.
 */
class SpeechChunker(private val first: Int = 60, private val max: Int = 220) {
    private val buf = StringBuilder()
    private var sent = 0

    /** Adds written text; returns the pieces now complete, ready to speak. */
    fun feed(text: String): List<String> {
        buf.append(text)
        val out = mutableListOf<String>()
        while (true) {
            val limit = if (sent == 0) first else max
            val s = buf.toString()
            val end = Regex("[.!?…](?=\\s)|[。！？]|\\n").find(s)?.range?.last
            val cut = when {
                end != null -> end + 1
                s.length > limit -> s.substring(0, limit).let { w -> w.lastIndexOf(", ").takeIf { it > limit / 3 }?.plus(1) ?: w.lastIndexOf(' ').takeIf { it > 0 } }
                else -> null
            } ?: break
            // clean() ends every line as a sentence; a piece cut mid-sentence must not fall in pitch as if it ended.
            val piece = SpeechFormat.clean(s.substring(0, cut)).let { if (end == null) it.removeSuffix(".") else it }
            buf.delete(0, cut)
            if (piece.isNotBlank()) { out += piece; sent++ }
        }
        return out
    }

    /** The rest, once the answer is complete. */
    fun flush(): List<String> = SpeechFormat.clean(buf.toString()).also { buf.clear() }.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
}
