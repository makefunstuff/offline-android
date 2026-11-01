package com.lfmpixel.lfm.memory

import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.HybridSearch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Wren's memory (notebook-spec section 6): what it learns from the owner's notes, chats and Talk, kept on the
 * phone. This file is the pure part (what a fact is, how it is found, merged, ranked and put in a prompt), so
 * MemoryTest runs it without a phone; MemoryStore.kt keeps it, sealed, and runs the nightly pass.
 */

enum class FactKind(val label: String) {
    Preference("How you like things done"), Person("People"), Place("Places"), Routine("Routines"),
    Project("Projects"), Goal("Goals"), Like("Likes and dislikes"), Other("Other");

    companion object {
        fun of(word: String): FactKind? = when (word.trim().lowercase().removeSuffix("s")) {
            "preference", "how" -> Preference
            "person", "people" -> Person
            "place" -> Place
            "routine", "habit" -> Routine
            "project" -> Project
            "goal", "aim" -> Goal
            "like", "dislike" -> Like
            "other", "fact" -> Other
            else -> null
        }
    }
}

/** Where a fact came from: [ref] is "entry:12", "chat:40", "talk" or "owner"; [label] what the screen shows. */
data class Source(val ref: String, val label: String, val at: Long)

/**
 * One durable thing about the owner, in a short sentence ("Ana is your sister"). [key] ties facts about the same
 * slot ("person:dentist"): a newer one replaces the older. [mentions] counts how often it was seen; [edited] is set
 * once the owner changed it by hand, and then nothing automatic changes it again. [journal] facts stay out of every
 * prompt while the journal is locked; [sensitive] ones (health, money…) never go in the standing profile. When a
 * newer fact took the slot ("you moved to York"), [was] keeps what it said before and [changedAt] when.
 */
data class Fact(
    val id: Long = 0,
    val kind: FactKind,
    val text: String,
    val key: String = "",
    val source: Source,
    val firstSeen: Long = source.at,
    val lastSeen: Long = source.at,
    val mentions: Int = 1,
    val edited: Boolean = false,
    val journal: Boolean = false,
    val sensitive: Boolean = Sensitive.looks(text),
    val vec: FloatArray? = null,
    val was: String = "",
    val changedAt: Long = 0,
) {
    fun toJson(): String = JSONObject().put("kind", kind.name).put("text", text).put("key", key).put("was", was).put("changed", changedAt)
        .put("ref", source.ref).put("label", source.label).put("at", source.at).put("first", firstSeen).put("last", lastSeen)
        .put("n", mentions).put("edited", edited).put("journal", journal).put("sensitive", sensitive)
        .put("vec", vec?.let { java.util.Base64.getEncoder().encodeToString(HybridSearch.pack(it)) } ?: "").toString()

    companion object {
        fun fromJson(id: Long, s: String): Fact? = runCatching {
            val o = JSONObject(s)
            val v = o.optString("vec")
            Fact(id, FactKind.valueOf(o.getString("kind")), o.getString("text"), o.optString("key"),
                Source(o.optString("ref"), o.optString("label"), o.optLong("at")), o.optLong("first"), o.optLong("last"),
                o.optInt("n", 1), o.optBoolean("edited"), o.optBoolean("journal"), o.optBoolean("sensitive"),
                if (v.isEmpty()) null else HybridSearch.unpack(java.util.Base64.getDecoder().decode(v)), o.optString("was"), o.optLong("changed"))
        }.getOrNull()
    }
}

/** What the owner fixed by hand: the Ink line's kind, an entry's kind, or a to-do's list. */
enum class CorrectionKind { Kind, List }

/** "[text] was guessed [from], the owner made it [to]." */
data class Correction(val id: Long = 0, val kind: CorrectionKind, val text: String, val from: String, val to: String, val at: Long) {
    fun toJson(): String = JSONObject().put("kind", kind.name).put("text", text).put("from", from).put("to", to).put("at", at).toString()

    companion object {
        fun fromJson(id: Long, s: String): Correction? = runCatching {
            val o = JSONObject(s)
            Correction(id, CorrectionKind.valueOf(o.getString("kind")), o.getString("text"), o.optString("from"), o.getString("to"), o.optLong("at"))
        }.getOrNull()
    }
}

/**
 * The phone's rule for sensitive words: health, money, passwords, legal trouble, intimate matters (the same topics as
 * Labels' and d1's "sensitive", LP-45). A fact or entry that looks sensitive is kept out of the standing profile and
 * out of the nightly pass on the server; it is used only when a question is about it.
 */
object Sensitive {
    private val words = Regex("""\b(?:doctor|gp|hospital|clinic|diagnos\w*|medic\w*|pills?|prescription|therap\w*|symptoms?|illness|disease|surgery|""" +
        """depress\w*|anxiety|adhd meds|bank|account number|iban|salary|debt|loan|mortgage|tax(?:es)?|(?-i:bills?)|credit card|""" +
        """password|passcode|(?-i:pin)|login|lawyer|court|speeding fine|parking fine|police|divorce|sex\w*|dating)\b""", RegexOption.IGNORE_CASE)

    fun looks(text: String) = words.containsMatchIn(text)

    /** A secret is never kept at all, even on the phone: a password, a PIN or a card number. */
    private val secret = Regex("""\b(?:password|passcode|pin|cvv)\b\s*(?:is|:|=)\s*\S+|\b(?:\d[ -]?){13,19}\b""", RegexOption.IGNORE_CASE)
    fun secret(text: String) = secret.containsMatchIn(text)
}

/**
 * Facts found by rules, with no model: the clear first-person sentences ("my sister Ana…", "I live in Leeds",
 * "I prefer mornings", "remember that…"). Cheap enough to run on every saved entry and chat message. The nightly pass
 * adds what the small model finds.
 */
object FactRules {
    private const val NAME = """\p{Lu}[\p{L}'-]+(?:\s\p{Lu}[\p{L}'-]+)?"""
    private const val RELATIONS = "wife|husband|partner|girlfriend|boyfriend|son|daughter|kids?|mum|mom|mother|dad|father|brother|sister|" +
        "grandma|grandmother|grandpa|grandfather|aunt|uncle|cousin|friend|best friend|boss|manager|colleague|flatmate|roommate|neighbou?r|" +
        "dentist|doctor|therapist|landlord|cat|dog"
    // Case matters for the name only: "my sister Ana", not "my sister on Sunday".
    private val relation = Regex("""\b(?i:my (?:new )?($RELATIONS)(?:'s name)?(?: is| was|,| called| named)? (?:now |called |named )?(?:dr\.? )?)($NAME)""")
    private val relationAfter = Regex("""\b($NAME),? (?:is )?my ($RELATIONS)\b""")
    private val birthday = Regex("""\b($NAME)'s birthday(?: is| on|:)? ([\p{L}\d ]{3,20}?)(?:[.,;!]|$)""")
    private val live = Regex("""\bi (?:live|moved) (?:in|to) ([\p{L}][\p{L} '-]{1,30}?)(?:[.,;!]|\band\b|$)""", RegexOption.IGNORE_CASE)
    private val work = Regex("""\bi (?:work|now work|started working|(?:started|got|have) a new job) (?:at|for) ([\p{L}\d][\p{L}\d &'-]{1,30}?)(?:[.,;!]|\band\b|\bas\b|$)""", RegexOption.IGNORE_CASE)
    private val like = Regex("""\bi (really |)(love|like|enjoy|adore|hate|dislike|can't stand|cannot stand|don't like|do not like) ([^.,;!?]{3,50})""", RegexOption.IGNORE_CASE)
    private val prefer = Regex("""\bi (?:prefer|would rather|'d rather) ([^.;!?]{3,60})""", RegexOption.IGNORE_CASE)
    private val routine = Regex("""\b(?:i (?:usually|always|normally|tend to)|every (?:morning|evening|night|day|weekday|weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday),? i) ([^.;!?]{3,60})""", RegexOption.IGNORE_CASE)
    private val goal = Regex("""\b(?:my goal is to|this year i want to|i'm saving (?:up )?for|i am saving (?:up )?for) ([^.,;!?]{3,60})""", RegexOption.IGNORE_CASE)
    private val project = Regex("""\bi(?:'m| am) (?:working on|building|writing|learning|training for) ([^.,;!?]{3,50})""", RegexOption.IGNORE_CASE)
    private val wants = Regex("""^(?:please |from now on,? |in future,? )?((?:always |never |don't |do not )?(?:keep|answer|reply|call me|use|write|give|remind me|speak|talk)\b[^.!?]{3,80})""", RegexOption.IGNORE_CASE)
    private val boring = Regex("""^(?:it|that|this|them|you|to)\b""", RegexOption.IGNORE_CASE)

    data class Found(val kind: FactKind, val text: String, val key: String)

    /** Facts in [text]. [chat] is a message to the assistant, where "keep it short" is how the owner wants answers. */
    fun find(text: String, chat: Boolean = false): List<Found> {
        if (Sensitive.secret(text) || MemoryCommand.parse(text) is MemoryCommand.Cmd.Forget) return emptyList()
        val out = mutableListOf<Found>()
        for (sentence in text.split(Regex("""(?<=[.!?])\s+|\n+""")).map { it.trim() }.filter { it.length >= 6 }) {
            relation.findAll(sentence).forEach { m ->
                val rel = m.groupValues[1].lowercase(); val name = m.groupValues[2].trim()
                if (name.lowercase() !in notNames) out += Found(FactKind.Person, "${name.cap()} is your $rel", personKey(rel, name))
            }
            relationAfter.findAll(sentence).forEach { m ->
                val name = m.groupValues[1].trim(); val rel = m.groupValues[2].lowercase()
                if (name.lowercase() !in notNames) out += Found(FactKind.Person, "$name is your $rel", personKey(rel, name))
            }
            birthday.find(sentence)?.let { m -> out += Found(FactKind.Person, "${m.groupValues[1]}'s birthday is ${m.groupValues[2].trim()}", "birthday:${m.groupValues[1].lowercase()}") }
            live.find(sentence)?.let { m -> out += Found(FactKind.Place, "You live in ${m.groupValues[1].trim().cap()}", "place:home") }
            work.find(sentence)?.let { m -> out += Found(FactKind.Place, "You work at ${m.groupValues[1].trim().cap()}", "place:work") }
            like.find(sentence)?.let { m ->
                val what = m.groupValues[3].trim()
                if (!boring.containsMatchIn(what)) {
                    val verb = when (m.groupValues[2].lowercase()) {
                        "love", "adore" -> "love"; "like", "enjoy" -> "like"
                        else -> "don't like"
                    }
                    out += Found(FactKind.Like, "You $verb $what", "like:" + Words.content(what).take(3).joinToString(" "))
                }
            }
            prefer.find(sentence)?.let { m -> out += Found(FactKind.Preference, "You prefer ${m.groupValues[1].trim()}", "prefer:" + Words.content(m.groupValues[1]).take(3).joinToString(" ")) }
            routine.find(sentence)?.let { m ->
                val head = m.value.substringBefore(m.groupValues[1]).trim().removeSuffix(",")
                val lead = if (head.lowercase().startsWith("every")) head.replace(Regex(""",?\s+i$""", RegexOption.IGNORE_CASE), "").cap() + ", you " else "You usually "
                out += Found(FactKind.Routine, lead + m.groupValues[1].trim(), "routine:" + Words.content(m.groupValues[1]).take(3).joinToString(" "))
            }
            goal.find(sentence)?.takeIf { !sentence.endsWith("?") && !MemoryCommand.timely(sentence) }?.let { m ->
                val saving = m.value.lowercase().contains("saving")
                out += Found(FactKind.Goal, (if (saving) "You're saving for " else "You want to ") + you(m.groupValues[1].trim()), "goal:" + Words.content(m.groupValues[1]).take(3).joinToString(" "))
            }
            project.find(sentence)?.let { m -> out += Found(FactKind.Project, "You're working on ${m.groupValues[1].trim()}", "project:" + Words.content(m.groupValues[1]).take(3).joinToString(" ")) }
            if (chat) {
                // "Remember that…" as MemoryCommand reads it: never a reminder ("remember to…", "…tomorrow at 3").
                (MemoryCommand.parse(sentence) as? MemoryCommand.Cmd.Remember)?.let { r -> out += Found(FactKind.Other, you(r.text).cap(), "") }
                    ?: wants.find(sentence)?.let { m -> if (!sentence.endsWith("?") && !MemoryCommand.timely(m.value)) out += Found(FactKind.Preference, "You asked: " + m.groupValues[1].trim().replaceFirstChar { it.lowercase() }, "wants:" + Words.content(m.groupValues[1]).take(3).joinToString(" ")) }
            }
        }
        return out.distinctBy { it.text.lowercase() }
    }

    /** Relations one has one of at a time: a new name replaces the old. Others (friends, kids, cats) add up. */
    private val single = setOf("wife", "husband", "partner", "girlfriend", "boyfriend", "mum", "mom", "mother", "dad", "father",
        "boss", "manager", "dentist", "doctor", "therapist", "landlord")
    private fun personKey(rel: String, name: String) = if (rel in single) "person:$rel" else "person:$rel:${name.lowercase()}"

    private val notNames = setOf("is", "was", "the", "a", "and", "i", "me", "my", "he", "she", "they", "it", "said", "came", "called")
    private fun String.cap() = replaceFirstChar { it.uppercase() }

    /** "I take the bus" said to the assistant reads "you take the bus" in a fact about the owner. */
    fun you(s: String): String = s.replace(Regex("""\bI am\b|\bI'm\b"""), "you're").replace(Regex("""\bI\b"""), "you")
        .replace(Regex("""\bmy\b""", RegexOption.IGNORE_CASE), "your").replace(Regex("""\bme\b""", RegexOption.IGNORE_CASE), "you")
}

/** Words for matching, with the little ones taken out. */
object Words {
    private val stop = setOf("a", "an", "the", "and", "or", "to", "of", "in", "on", "at", "for", "with", "is", "are", "was", "be",
        "my", "your", "you", "i", "me", "it", "that", "this", "do", "does", "did", "what", "when", "where", "who", "how", "which",
        "should", "could", "would", "can", "will", "about", "from", "up", "some", "any", "get", "go", "s", "t", "have", "has")

    fun all(s: String): List<String> = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
    fun content(s: String): List<String> = all(s).filter { it !in stop && it.length > 1 }

    /** Shared content words over all content words, 0..1, with a light stem (plural s). */
    fun overlap(a: String, b: String): Double {
        val x = content(a).map(::stem).toSet(); val y = content(b).map(::stem).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / x.union(y).size
    }

    fun stem(w: String) = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w
}

/**
 * Keeping the memory tidy: a found fact joins one already there with the same key (the newer words win, unless the
 * owner wrote the old one) or nearly the same words (seen again: its count goes up); otherwise it is new.
 */
object FactMerge {
    const val SAME = 0.75
    /** Facts not seen again for this long, seen only once and never edited, are let go by the nightly pass. */
    const val STALE_DAYS = 120L
    const val MAX = 400

    fun merge(facts: List<Fact>, found: Fact): List<Fact> {
        val i = facts.indexOfFirst { (found.key.isNotEmpty() && it.key == found.key) || Words.overlap(it.text, found.text) >= SAME }
        if (i < 0) return facts + found
        val old = facts[i]
        val same = Words.overlap(old.text, found.text) >= SAME
        val newer = found.source.at >= old.lastSeen
        val merged = when {
            // What the owner told Wren to remember about a slot ("I live in Leeds") still moves when they say it changed.
            old.edited && old.source.ref == "owner" && old.key.isNotEmpty() && old.key == found.key && !same && newer ->
                old.copy(text = found.text, source = found.source, lastSeen = found.source.at, mentions = 1, edited = false,
                    journal = found.journal, sensitive = found.sensitive, vec = null, was = old.text, changedAt = found.source.at)
            old.edited -> old.copy(mentions = old.mentions + 1, lastSeen = maxOf(old.lastSeen, found.source.at))
            // Same slot, other words, said later: "my dentist is now Dr Park" replaces "Dr Lee".
            !same && newer -> old.copy(text = found.text, source = found.source, lastSeen = found.source.at, mentions = 1,
                journal = found.journal, sensitive = found.sensitive, vec = null, was = old.text, changedAt = found.source.at)
            else -> old.copy(mentions = old.mentions + if (old.source.ref == found.source.ref) 0 else 1, lastSeen = maxOf(old.lastSeen, found.source.at))
        }
        return facts.toMutableList().also { it[i] = merged }
    }

    /** Duplicates merged (same key, or nearly the same words): the nightly pass. */
    fun dedupe(facts: List<Fact>): List<Fact> = facts.sortedBy { it.firstSeen }.fold(emptyList()) { acc, f ->
        val j = acc.indexOfFirst { (f.key.isNotEmpty() && it.key == f.key) || Words.overlap(it.text, f.text) >= SAME || sameMeaning(it, f) }
        if (j < 0) acc + f else acc.toMutableList().also { list ->
            val a = acc[j]
            // The owner's own words win; else the newer fact's, with both counts.
            val keep = if (a.edited || (!f.edited && a.lastSeen >= f.lastSeen)) a else f
            val drop = if (keep === a) f else a
            list[j] = keep.copy(id = if (a.id != 0L) a.id else f.id, mentions = a.mentions + f.mentions,
                firstSeen = minOf(a.firstSeen, f.firstSeen), lastSeen = maxOf(a.lastSeen, f.lastSeen), edited = a.edited || f.edited,
                vec = keep.vec ?: drop.vec.takeIf { keep.text == drop.text })
        }
    }

    /** Near-duplicates by meaning ("You love oat flat whites" and "You like oat milk flat whites"): same kind, vectors this close. */
    const val SAME_MEANING = 0.93f
    fun sameMeaning(a: Fact, b: Fact): Boolean =
        a.kind == b.kind && a.vec != null && b.vec != null && a.vec.size == b.vec.size && !(a.edited && b.edited) &&
            // "Ana is your friend" and "Mia is your friend" read alike but are two people.
            !(a.key.isNotEmpty() && b.key.isNotEmpty() && a.key != b.key) && names(a.text) == names(b.text) &&
            HybridSearch.dot(a.vec, b.vec) >= SAME_MEANING

    private fun names(t: String) = Regex("""\b\p{Lu}[\p{L}'-]+""").findAll(t).map { it.value.removeSuffix("'s") }
        .filter { it !in setOf("You", "Your", "You're", "Every", "Dr") }.toSet()

    /**
     * What the nightly pass lets go: facts from notes since deleted (unless the owner edited them), and facts seen once,
     * never edited and not seen for [STALE_DAYS]. At most [MAX] are kept, the least used going first.
     */
    fun prune(facts: List<Fact>, liveEntries: Set<Long>, now: Long): List<Fact> {
        val staleBefore = now - STALE_DAYS * 86_400_000L
        return facts.filter { f ->
            val entry = f.source.ref.removePrefix("entry:").takeIf { f.source.ref.startsWith("entry:") }?.toLongOrNull()
            f.edited || ((entry == null || entry in liveEntries) && !(f.mentions <= 1 && f.lastSeen < staleBefore))
        }.sortedByDescending { score(it, now) }.take(MAX)
    }

    /** How much a fact matters for the profile: how it ranks, by kind, use and how recent. */
    fun score(f: Fact, now: Long): Double {
        val days = ((now - f.lastSeen) / 86_400_000.0).coerceAtLeast(0.0)
        return importance(f) - days / 60.0
    }

    /** How much a fact matters, whatever the question: its kind, the owner's own words, how often it came up. */
    fun importance(f: Fact): Double {
        val kind = when (f.kind) {
            FactKind.Preference -> 3.0; FactKind.Person -> 2.5; FactKind.Routine -> 2.0; FactKind.Project -> 2.0
            FactKind.Goal -> 2.0; FactKind.Place -> 1.8; FactKind.Like -> 1.5; FactKind.Other -> 1.2
        }
        return kind + (if (f.edited) 2.0 else 0.0) + kotlin.math.ln(1.0 + f.mentions)
    }

    /**
     * The facts [what] is about, for "Forget that…": nearly the same words, or most of its words, or (with vectors)
     * nearly the same meaning; "forget about Ana" takes every fact that names Ana. Best first.
     */
    fun matching(facts: List<Fact>, what: String, q: FloatArray? = null): List<Fact> {
        val words = Words.content(what).map(Words::stem).toSet()
        if (words.isEmpty()) return emptyList()
        val names = Regex("""\b\p{Lu}[\p{L}'-]+""").findAll(what).map { it.value.removeSuffix("'s").lowercase() }
            .filter { it !in setOf("i", "my", "that", "the", "forget", "please", "wren") }.toSet()
        val scored = facts.map { f ->
            val have = Words.content(f.text).map(Words::stem).toSet()
            val covered = words.count { it in have }.toDouble() / words.size
            val meaning = if (q != null && f.vec != null && q.size == f.vec.size) HybridSearch.dot(q, f.vec).toDouble() else 0.0
            val named = names.isNotEmpty() && names.size == words.size && names.all { n -> Words.all(f.text).any { it.removeSuffix("s") == n || it == n } }
            f to maxOf(Words.overlap(f.text, what), covered * 0.9, if (meaning >= 0.82) meaning else 0.0, if (named) 1.0 else 0.0)
        }.filter { it.second >= 0.6 }.sortedByDescending { it.second }
        return scored.map { it.first }
    }
}

/**
 * What goes into a prompt: a short profile (always, within its budget) and the facts and notes that fit the question.
 * Budgets are in characters, about 3.5 to a token, from the answering model's context: LiteRT's 4,096-token models get a
 * few hundred tokens, Qwen's 32k on the server a couple of thousand.
 */
object Recall {
    data class Budget(val profileChars: Int, val notesChars: Int, val notes: Int)

    fun budget(contextTokens: Int): Budget = when {
        contextTokens >= 16_000 -> Budget(2_400, 3_200, 5)
        contextTokens >= 8_000 -> Budget(1_200, 1_400, 4)
        else -> Budget(500, 700, 3)
    }

    /** The profile: the best facts, one line each, then the patterns, cut at [chars]. */
    fun profile(facts: List<Fact>, patterns: List<String>, chars: Int, journalLocked: Boolean, now: Long): String {
        val lines = facts.asSequence().filter { !it.sensitive && !(journalLocked && it.journal) }
            .sortedByDescending { FactMerge.score(it, now) }.map { "- " + it.text }.toList()
        val sb = StringBuilder()
        for (l in lines + patterns.map { "- $it" }) {
            if (sb.length + l.length + 1 > chars) continue
            sb.append(l).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * The facts that fit [question] best, best first. How well each fits (by meaning when both have vectors, and by
     * shared words) decides whether it is in at all; then recency and importance lift it: a fact used often, in the
     * owner's own words, or seen this week comes before an old one-off that fits as well. Facts already in the
     * profile are skipped by the caller.
     */
    fun relevant(facts: List<Fact>, question: String, q: FloatArray?, journalLocked: Boolean, limit: Int = 5,
                 now: Long = System.currentTimeMillis()): List<Fact> =
        facts.asSequence().filter { !(journalLocked && it.journal) }.map { f -> f to fit(f, question, q) }
            .filter { it.second >= 0.2 }.map { (f, fit) -> f to rank(f, fit, now) }
            .sortedByDescending { it.second }.take(limit).map { it.first }.toList()

    /** How well [f] fits the question, 0 and up: meaning above the floor where unrelated texts sit, plus shared words. */
    fun fit(f: Fact, question: String, q: FloatArray?): Double {
        val byMeaning = if (q != null && f.vec != null && q.size == f.vec.size) HybridSearch.dot(q, f.vec).toDouble() else 0.0
        return maxOf(byMeaning - 0.55, 0.0) * 4 + Words.overlap(f.text, question)
    }

    /** [fit] lifted by up to a half for recency (a 90-day half-life of sorts) and up to a half for importance. */
    fun rank(f: Fact, fit: Double, now: Long): Double {
        val days = ((now - f.lastSeen) / 86_400_000.0).coerceAtLeast(0.0)
        val recency = kotlin.math.exp(-days / 90.0)
        val importance = (FactMerge.importance(f) / 7.0).coerceIn(0.0, 1.0)
        return fit * (1.0 + 0.5 * recency + 0.5 * importance)
    }

    /** The capitalised words in [facts] (names, mostly) that [question] doesn't have: more to look for in the notes. */
    fun names(facts: List<Fact>, question: String): List<String> {
        val asked = Words.all(question).toSet()
        return facts.flatMap { f -> Regex("""\b\p{Lu}[\p{L}'-]+""").findAll(f.text).map { it.value.removeSuffix("'s") }.toList() }
            .filter { it.lowercase() !in asked && it !in setOf("You", "Your", "Every", "Dr") }.distinct().take(2)
    }

    private val day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** Notes as the prompt shows them: "[Note · 3 Nov 2026] Dentist: moved to Park Street", cut to [chars] in all. */
    fun notes(entries: List<Entry>, chars: Int, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder()
        val each = (chars / entries.size.coerceAtLeast(1)).coerceAtLeast(120)
        for (e in entries) {
            val text = listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString(": ").replace(Regex("""\s+"""), " ")
            val line = "[${e.kind.label} · ${Instant.ofEpochMilli(e.createdAt).atZone(zone).format(day)}" +
                (if (e.kind == EntryKind.Todo) if (e.done) " · done" else " · open" else "") + "] " + text.take(each)
            if (sb.length + line.length + 1 > chars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** The block for the system prompt; empty when there is nothing. */
    fun systemBlock(profile: String): String = if (profile.isBlank()) "" else
        "\n\nWhat you know about the owner (learned from their notes, chats and Talk; they can see and edit it in What Wren knows). " +
            "Use it when it helps; never recite it:\n$profile"

    /** The block put after the owner's message: the facts, past conversations and notes that fit it. */
    fun turnBlock(facts: List<Fact>, notes: String, said: String = ""): String {
        val f = facts.joinToString("\n") { "- " + it.text + if (it.was.isNotEmpty()) " (before: ${it.was})" else "" }
        if (f.isEmpty() && notes.isEmpty() && said.isEmpty()) return ""
        return "\n\n(From your memory and notes, in case it helps; answer from these over guessing:" +
            (if (f.isNotEmpty()) "\n$f" else "") + (if (said.isNotEmpty()) "\nFrom past conversations:\n$said" else "") +
            (if (notes.isNotEmpty()) "\n$notes" else "") + ")"
    }
}

/**
 * Corrections: when the owner changes where a line went, the same kind of line goes there next time. A new line
 * matches a correction when they share most content words, or (with vectors) mean nearly the same; the newest
 * matching correction wins. The closest ones are also shown to the server's classifier as examples.
 */
object Corrections {
    /** At least this, and more than the first word in common: one correction of "buy milk" must not take every "buy …". */
    const val WORDS = 0.6

    fun match(text: String, list: List<Correction>, kind: CorrectionKind): Correction? =
        list.filter { it.kind == kind }.map { it to similarity(text, it.text) }.filter { it.second >= WORDS && Words.overlap(text, it.first.text) >= 0.3 }
            .maxWithOrNull(compareBy<Pair<Correction, Double>> { it.second }.thenBy { it.first.at })?.first

    /** Shared words, with the first word (the verb: "buy", "call") counted twice: it decides most kinds. */
    fun similarity(a: String, b: String): Double {
        val x = Words.content(a); val y = Words.content(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val head = if (Words.stem(x.first()) == Words.stem(y.first())) 0.25 else 0.0
        return (Words.overlap(a, b) + head).coerceAtMost(1.0)
    }

    /** The [n] corrections of [kind] closest to [text], as classifier examples. */
    fun examples(text: String, list: List<Correction>, kind: CorrectionKind, n: Int = 3): List<Correction> =
        list.filter { it.kind == kind }.sortedByDescending { similarity(text, it.text) }.take(n)
}

/**
 * Patterns over time, offered gently on the What Wren knows screen and in the profile; never as a notification.
 * Each needs enough to go on, or it says nothing.
 */
object Patterns {
    data class Found(val focusHours: IntRange?, val writingHours: IntRange?, val slipping: List<String>, val themes: List<String>) {
        fun lines(): List<String> = listOfNotNull(
            focusHours?.let { "Tends to get to-dos done around ${hours(it)}" },
            writingHours?.let { "Writes most around ${hours(it)}" },
            themes.takeIf { it.isNotEmpty() }?.let { "Keeps coming back to: ${it.joinToString(", ")}" },
            slipping.takeIf { it.isNotEmpty() }?.let { "Keeps moving: ${it.joinToString(", ")} (maybe smaller steps)" },
        )
    }

    fun hours(r: IntRange) = "%02d:00–%02d:00".format(r.first, (r.last + 1) % 24)

    fun find(entries: List<Entry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Found {
        val month = now - 30 * 86_400_000L
        val doneHours = entries.mapNotNull { it.doneAt?.takeIf { t -> t >= now - 60 * 86_400_000L } }.map { hourOf(it, zone) }
        val writeHours = entries.filter { it.createdAt >= month && it.source != "share" }.map { hourOf(it.createdAt, zone) }
        val slipping = entries.filter {
            it.kind == EntryKind.Todo && !it.done && it.parentId == null && it.dueAt != null &&
                it.dueAt < now - 3 * 86_400_000L && it.createdAt < now - 7 * 86_400_000L
        }.sortedBy { it.dueAt }.take(3).map { it.title }
        val words = entries.filter { it.createdAt >= month && it.kind != EntryKind.Todo }
            .flatMap { e -> (Words.content(e.title + " " + e.body) + e.tags.split(' ') + e.topic).filter { it.length > 3 }.map(Words::stem).toSet() }
            .filter { it.isNotBlank() && it !in common }
        val themes = words.groupingBy { it }.eachCount().filter { it.value >= 3 }.entries.sortedByDescending { it.value }.take(3).map { it.key }
        return Found(window(doneHours, 8), window(writeHours, 12), slipping, themes)
    }

    private val common = setOf("today", "tomorrow", "need", "want", "think", "just", "like", "really", "also", "about", "after",
        "before", "again", "still", "more", "much", "very", "some", "time", "thing", "things", "make", "made", "going", "felt", "feel")

    private fun hourOf(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).hour

    /** The busiest three-hour window, if at least [min] events and a third of them fall in it. */
    private fun window(hours: List<Int>, min: Int): IntRange? {
        if (hours.size < min) return null
        val count = IntArray(24).also { c -> hours.forEach { c[it]++ } }
        // The fullest window; of equals, the one with the most in its middle hour (the peak in the middle).
        val best = (0 until 24).maxBy { h -> (count[h] + count[(h + 1) % 24] + count[(h + 2) % 24]) * 1000 + count[(h + 1) % 24] }
        val n = count[best] + count[(best + 1) % 24] + count[(best + 2) % 24]
        return if (n * 3 >= hours.size) best..(best + 2) % 24 else null
    }
}

/** The nightly pass's job for the small model, and how its answer is read (notebook-spec A.1, 8: plain lines). */
object FactPrompt {
    const val SYSTEM = "You keep a short memory for the owner's private notebook. From the lines below, list lasting facts " +
        "about the owner that will help later: people (name and who they are to the owner), places, routines, projects, " +
        "likes and dislikes, and how the owner wants things done. One per line as `kind: fact [n]`, where kind is person, " +
        "place, routine, project, goal, like or preference, and n is the line's number. Write each fact as a short sentence to " +
        "the owner, like \"Ana is your sister\". Leave out one-off tasks, single events, health, money and passwords. " +
        "If there are none, write None."

    fun user(lines: List<Pair<Int, String>>) = lines.joinToString("\n") { (n, t) -> "[$n] ${t.replace('\n', ' ').take(300)}" }

    private val line = Regex("""^\s*(?:[-*•]\s*)?(\w+)\s*:\s*(.+?)\s*(?:\[(\d+)])?\s*\.?$""")

    /** Each fact with the number of the line it came from (0 if none was given); anything else is dropped. */
    fun parse(answer: String): List<Triple<FactKind, String, Int>> = answer.lines().mapNotNull { l ->
        val m = line.find(l) ?: return@mapNotNull null
        val kind = FactKind.of(m.groupValues[1]) ?: return@mapNotNull null
        val text = m.groupValues[2].trim().trim('"').removeSuffix(".")
        if (text.length !in 6..140 || text.equals("none", true) || Sensitive.secret(text)) return@mapNotNull null
        Triple(kind, text.replaceFirstChar { it.uppercase() }, m.groupValues[3].toIntOrNull() ?: 0)
    }
}

/**
 * "Remember that…" and "Forget that…", typed or said: done at once by the memory, not left to the model. "Remember
 * to…" is a reminder, not this; "forget everything" is only done on the What Wren knows screen.
 */
object MemoryCommand {
    sealed interface Cmd {
        data class Remember(val text: String) : Cmd
        /** [explicit]: "forget that…"; else "forget about…", which is only done when there is something to forget. */
        data class Forget(val what: String, val explicit: Boolean = true) : Cmd
        data object ForgetAll : Cmd
    }

    private const val LEAD = """^(?:(?:hey |ok |okay )?wren[,!]?\s+)?(?:please\s+)?(?:(?:can|could|will|would) you\s+(?:please\s+)?|i want you to\s+|i'd like you to\s+)?"""
    private val remember = Regex(LEAD + """(?:remember(?:\s+that|\s*[:,])|(?:don't|do not) forget\s+that)\s+([^?]{4,200}?)[\s.!]*$""", RegexOption.IGNORE_CASE)
    private val asking = Regex("""^(?:(?:hey |ok |okay )?wren[,!]?\s+)?(?:please\s+)?(?:can|could|will|would) you\b""", RegexOption.IGNORE_CASE)
    // "Remember I'm vegetarian", "remember my sister is Ana": the owner's own facts, without "that".
    private val rememberMine = Regex(LEAD + """remember\s+((?:i|i'm|i am|my|we|our)\b[^?]{3,200}?)[\s.!]*$""", RegexOption.IGNORE_CASE)
    // Only the whole message, one clause: "forget that I like coffee", not "forget it, tell me a joke".
    private val forget = Regex(LEAD + """(forget\s+that|forget\s+what i said about|forget\s+about|forget\s*:|forget\s+(?=my\b)|stop remembering|don't remember|do not remember|unlearn)\s*([^,;.!?]{2,160}?)[\s.!]*$""", RegexOption.IGNORE_CASE)
    /** "Forget that one", "forget that last question": about the conversation, not the memory. */
    private val aboutTheChat = Regex("""^(?:one|last\b|previous\b|this\b|it\b|answer\b|question\b|bit\b|part\b)""", RegexOption.IGNORE_CASE)
    /**
     * What a reminder or a to-do sounds like ("remember I have a meeting at 10 tomorrow", "remember my keys when I
     * leave"): left to the model and its tools, never kept as a lasting fact.
     */
    private val timely = Regex("""\b(?:today|tonight|tomorrow|this (?:morning|afternoon|evening|week|weekend)|next (?:week|month|monday|tuesday|wednesday|thursday|friday|saturday|sunday)|""" +
        """when i|before i|after i|parked|(?<!birthday is )on (?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|""" +
        """at \d|\d\s*(?:am|pm)\b|o'clock|(?:i'm|i am) (?:out|away|picking|seeing|meeting|going|leaving|flying|visiting|getting)\b|""" +
        """need to|have to|got to|must|appointment|meeting|in \d+ (?:minutes?|hours?|days?))\b""", RegexOption.IGNORE_CASE)
    private val all = Regex("""^(?:everything|all of it|all|everything you know|everything about me|all you know)$""", RegexOption.IGNORE_CASE)

    private val forgetAll = Regex(LEAD + """forget\s+(?:everything|all of it|all|everything you know|everything about me|all you know)[\s.!]*$""", RegexOption.IGNORE_CASE)

    fun timely(text: String) = timely.containsMatchIn(text)
    /** What reads as a lasting fact about the owner, said without "that". */
    private val aboutMe = Regex("""^(?:i'm|i am|i (?:like|love|prefer|hate|don't|do not|can't|never|always|have|take|eat|drink|own|use)\b|""" +
        """we (?:have|own|live)\b|my \w+(?: \w+)? (?:is|are|was)\b)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Cmd? {
        // "Can you remember that…?" asks for it all the same; any other question is just a question.
        val t = text.trim().replace('\u2019', '\'').let { if (asking.containsMatchIn(it)) it.trimEnd('?', ' ') else it }
        if (t.length > 240) return null
        if (forgetAll.matches(t)) return Cmd.ForgetAll
        forget.find(t)?.let { m ->
            val what = m.groupValues[2].trim()
            if (all.matches(what)) return Cmd.ForgetAll
            val how = m.groupValues[1].lowercase()
            if (Words.content(what).isNotEmpty() && !aboutTheChat.containsMatchIn(what.removePrefix("my ").removePrefix("My ")))
                return Cmd.Forget(what, explicit = !how.startsWith("forget about") && how.trim() != "forget")
        }
        if (t.endsWith("?")) return null
        (remember.find(t) ?: rememberMine.find(t))?.let { m ->
            val what = m.groupValues[1].trim()
            // A wrong "remember" costs more than a missed one (the reminder is never made, and the line stays as a
            // fact), so: never anything with a time or a plan in it, and without "that" only what reads as a fact
            // about the owner ("Remember, I'm vegetarian"), not a to-do ("Remember: call the bank", "remember my umbrella").
            val plain = Regex("""(?i)^\W*(?:\w+\W+){0,6}?(?:remember|forget)\s+that\b""").containsMatchIn(t)
            if (!timely.containsMatchIn(what) && (plain || aboutMe.containsMatchIn(what) || FactRules.find(what, chat = true).isNotEmpty())) return Cmd.Remember(what)
        }
        return null
    }

    /**
     * What a "remember" keeps: the fact the rules see in it ("my sister is Ana" → "Ana is your sister", a person), else
     * the owner's words turned to "you" ("I'm vegetarian" → "You're vegetarian").
     */
    fun fact(text: String): FactRules.Found {
        FactRules.find(text).firstOrNull()?.let { return it }
        val t = FactRules.you(text.trim().removeSuffix(".")).replaceFirstChar { it.uppercase() }
        val kind = when {
            Regex("""\b(?:prefer|rather|answers?|reply|replies|call me|short|brief)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> FactKind.Preference
            else -> FactKind.Other
        }
        return FactRules.Found(kind, t, "")
    }

    /** The line a Talk turn on the phone gets, so the voice confirms what was done in its own words. */
    fun note(reply: String) = "\n\n(Already done by Wren's memory: $reply Tell the owner so in one short sentence.)"
}

/** What the owner told Wren to forget: never learned again from the same words (a newer, different fact still is). */
data class Forgotten(val id: Long = 0, val text: String, val at: Long) {
    fun toJson(): String = JSONObject().put("text", text).put("at", at).toString()

    companion object {
        const val MAX = 200
        fun fromJson(id: Long, s: String): Forgotten? = runCatching { JSONObject(s).let { Forgotten(id, it.getString("text"), it.optLong("at")) } }.getOrNull()
        /**
         * Nearly the same words, or every word of the forgotten fact in the new one ("Your sister is called Ana" after
         * "Ana is your sister" was forgotten).
         */
        fun blocks(list: List<Forgotten>, text: String): Boolean {
            val have = Words.content(text).map(Words::stem).toSet()
            return list.any { f ->
                val gone = Words.content(f.text).map(Words::stem).toSet()
                Words.overlap(f.text, text) >= FactMerge.SAME || (gone.size >= 2 && have.containsAll(gone))
            }
        }
    }
}

/**
 * One conversation, chat or Talk: what was said ([lines], "You: …" and "Wren: …", cut short), when, and once the
 * model read it, a one-line [summary]. Kept so Wren can answer "what did I say about…"; the facts in it go to the
 * memory. Old ones keep only their summary, and only the newest [Episodes.MAX] are kept.
 */
data class Episode(val id: Long = 0, val kind: String, val at: Long, val lines: String, val summary: String = "",
                   val read: Boolean = false, val vec: FloatArray? = null) {
    fun toJson(): String = JSONObject().put("kind", kind).put("at", at).put("lines", lines).put("summary", summary).put("read", read)
        .put("vec", vec?.let { java.util.Base64.getEncoder().encodeToString(HybridSearch.pack(it)) } ?: "").toString()

    /** What the screen and search show: the summary, else the first thing the owner said. */
    fun title(): String = summary.ifEmpty { lines.lineSequence().firstOrNull { it.startsWith("You: ") }?.removePrefix("You: ")?.take(80).orEmpty() }

    companion object {
        fun fromJson(id: Long, s: String): Episode? = runCatching {
            val o = JSONObject(s)
            val v = o.optString("vec")
            Episode(id, o.getString("kind"), o.optLong("at"), o.optString("lines"), o.optString("summary"), o.optBoolean("read"),
                if (v.isEmpty()) null else HybridSearch.unpack(java.util.Base64.getDecoder().decode(v)))
        }.getOrNull()
    }
}

object Episodes {
    const val MAX = 150
    const val CHARS = 2_000
    /** Older than this, an episode read by the model keeps only its summary. */
    const val KEEP_LINES_DAYS = 30L
    /** A chat with nothing new for this long is over: it becomes an episode. */
    const val IDLE_MS = 10 * 60_000L

    /** An episode from (owner, Wren) pairs, or null when the owner said too little to keep. */
    fun of(kind: String, at: Long, all: List<Pair<String, String>>): Episode? {
        // "Remember that…" and "Forget that…" are done, not kept: a forgotten thing must not live on in its own words.
        val turns = all.filter { MemoryCommand.parse(it.first) == null }
        val owner = turns.joinToString(" ") { it.first }.trim()
        if (owner.length < 8 || turns.any { Sensitive.secret(it.first) || Sensitive.secret(it.second) }) return null
        val sb = StringBuilder()
        for ((you, it) in turns) {
            val a = "You: " + you.replace(Regex("""\s+"""), " ").trim().take(400)
            val b = if (it.isBlank()) "" else "Wren: " + it.replace(Regex("""\s+"""), " ").trim().take(240)
            if (sb.length + a.length + 1 > CHARS) break
            sb.append(a).append('\n')
            if (b.isNotEmpty() && sb.length + b.length + 1 <= CHARS) sb.append(b).append('\n')
        }
        return Episode(kind = kind, at = at, lines = sb.toString().trimEnd())
    }

    /** The owner's own lines in an episode, the text rules and search read. */
    fun owner(e: Episode): String = e.lines.lineSequence().filter { it.startsWith("You: ") }.joinToString("\n") { it.removePrefix("You: ") }

    private val asking = Regex("""\b(?:what|when|where|did|have|remind me what)\b.{0,30}?\bi\s+(?:say|said|tell|told|mention|mentioned|talk|talked|ask|asked)\b(?:\s+(?:you|to you|wren))?(?:\s+(?:about|regarding|on|that))?\s*(.*)""", RegexOption.IGNORE_CASE)
    private val earlier = Regex("""\b(?:we|you and i)\s+(?:talked|spoke|chatted)\s+about\s+(.*)""", RegexOption.IGNORE_CASE)

    /** The topic of "what did I say about the garden?" ("the garden"), or null when the question isn't about past talk. */
    fun asked(question: String): String? {
        val m = asking.find(question) ?: earlier.find(question) ?: return null
        val topic = m.groupValues[1].trim().trimEnd('?', '.', '!', ' ')
        return topic.takeIf { Words.content(it).isNotEmpty() }
    }

    /**
     * The episodes that talk about [topic], best first: most of its words in what was said, or (with vectors) close in
     * meaning to the summary. Newer first among equals.
     */
    fun search(episodes: List<Episode>, topic: String, q: FloatArray?, limit: Int = 3): List<Episode> {
        val words = Words.content(topic).map(Words::stem).toSet()
        if (words.isEmpty()) return emptyList()
        return episodes.map { e ->
            val have = Words.content(e.lines + " " + e.summary).map(Words::stem).toSet()
            val covered = words.count { it in have }.toDouble() / words.size
            val meaning = if (q != null && e.vec != null && q.size == e.vec.size) (HybridSearch.dot(q, e.vec) - 0.55).coerceAtLeast(0.0) * 4.0 else 0.0
            e to maxOf(covered, meaning)
        }.filter { it.second >= 0.5 }.sortedWith(compareByDescending<Pair<Episode, Double>> { it.second }.thenByDescending { it.first.at })
            .take(limit).map { it.first }
    }

    private val day = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** "[Talk · 3 Nov 2026] summary" and the owner's lines that name the topic, cut to [chars] in all. */
    fun block(found: List<Episode>, topic: String, chars: Int, zone: ZoneId = ZoneId.systemDefault()): String {
        val words = Words.content(topic).map(Words::stem).toSet()
        val sb = StringBuilder()
        for (e in found) {
            val said = owner(e).lines().filter { l -> Words.content(l).any { Words.stem(it) in words } }.take(3)
            val line = "[${e.kind.replaceFirstChar { it.uppercase() }} · ${Instant.ofEpochMilli(e.at).atZone(zone).format(day)}] " +
                (if (e.summary.isNotEmpty()) e.summary + (if (said.isNotEmpty()) " — " else "") else "") +
                said.joinToString(" / ") { "you said “${it.take(160)}”" }
            if (sb.length + line.length + 1 > chars) break
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * After "Forget that…": the lines (and summaries) that say any of [gone] are taken out, so "what did I say
     * about…" can't bring it back. An episode left with nothing the owner said is dropped (null).
     */
    fun scrub(e: Episode, gone: List<String>): Episode? {
        val sets = gone.map { g -> Words.content(g).map(Words::stem).toSet() }.filter { it.isNotEmpty() }
        fun says(line: String): Boolean { val have = Words.content(line).map(Words::stem).toSet(); return sets.any { have.containsAll(it) } }
        val lines = e.lines.lines().filter { it.isNotBlank() && !says(it) }.joinToString("\n")
        val summary = if (says(e.summary)) "" else e.summary
        if (lines == e.lines && summary == e.summary) return e
        if (lines.lineSequence().none { it.startsWith("You: ") } && summary.isEmpty()) return null
        return e.copy(lines = lines, summary = summary, vec = if (summary == e.summary) e.vec else null)
    }

    /**
     * Keeping the store small: episodes older than [KEEP_LINES_DAYS] that the model has read keep only their summary
     * (their facts are in the memory by then); only the newest [MAX] are kept.
     */
    fun compact(episodes: List<Episode>, now: Long): List<Episode> {
        val old = now - KEEP_LINES_DAYS * 86_400_000L
        return episodes.sortedByDescending { it.at }.take(MAX).map { e ->
            if (e.at < old && e.read && e.lines.isNotEmpty() && e.summary.isNotEmpty()) e.copy(lines = "") else e
        }
    }
}

/** The small model's job after a conversation: the lasting facts in it, and one line on what it was about. */
object EpisodePrompt {
    const val SYSTEM = "You keep a short memory for the owner of a private assistant. Below is one conversation between " +
        "the owner (You) and the assistant (Wren). List lasting facts about the owner that will help later: people (name " +
        "and who they are to the owner), places, routines, projects, goals, likes and dislikes, and how the owner wants " +
        "answers. Only what the owner said about themselves, not what Wren said. One per line as `kind: fact`, where kind " +
        "is person, place, routine, project, goal, like or preference, each a short sentence to the owner, like \"Ana is " +
        "your sister\". Leave out one-off requests, health, money and passwords. Then one last line `summary: …`: what " +
        "the conversation was about, in at most twelve words."

    private val summary = Regex("""^\s*summary\s*:\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)

    fun summary(answer: String): String = answer.lines().firstNotNullOfOrNull { summary.find(it)?.groupValues?.get(1) }
        ?.trim()?.trim('"')?.removeSuffix(".")?.take(120).orEmpty()
}
