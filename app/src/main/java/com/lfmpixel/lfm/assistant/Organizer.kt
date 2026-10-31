package com.lfmpixel.lfm.assistant

import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The ADHD organizer's model jobs (notebook-spec A.1, proposal 8): each is one short instruction with no history and
 * no thinking, a few hundred tokens out, in a line format the phone parses. Each also has an answer by rules, for
 * when there is no model; every result is a proposal the owner accepts.
 */
object Organizer {
    private const val SYSTEM = "You are Inkling, a calm planner in the owner's private notebook on their phone. " +
        "The owner has ADHD: keep every step small, concrete and kind. Write plain text lines only: no headings, no bold."

    fun prompt(user: String): String =
        PromptFormat.build(SYSTEM, emptyList(), listOf(PromptMessage(PromptMessage.Role.User, user)), thinking = false)

    /** The answer so far, each time it grows, cleaned like a margin answer. */
    fun run(engine: ModelManager, user: String, maxTokens: Int): Flow<String> = flow {
        val session = engine.textSession()
        val raw = StringBuilder()
        session.generate(prompt(user), maxTokens, temp = 0.3f).collect { piece ->
            raw.append(piece)
            emit(Margin.clean(raw.toString()))
        }
        emit(Margin.clean(raw.toString()))
    }

    /** "- Find the letter (5 min)", "1. Call Ana - 10 minutes", "* Wipe the counter": the words and the minutes. */
    private val item = Regex("""^(?:[-*•]|\d+[.)])\s+(.+)$""")
    private val mins = Regex("""[\s,–—-]*[(\[]?\s*(?:about\s+|~\s*)?(\d{1,3})\s*(?:-\s*\d{1,3}\s*)?(?:min(?:ute)?s?|m)\b\.?\s*[)\]]?\s*\.?$""", RegexOption.IGNORE_CASE)
    private val hours = Regex("""[\s,–—-]*[(\[]?\s*(?:about\s+|~\s*)?(\d(?:\.\d)?)\s*(?:h|hours?|hrs?)\b\.?\s*[)\]]?\s*\.?$""", RegexOption.IGNORE_CASE)

    /** One line of a list answer: its words with the size taken off, and the size (0 when none was given). */
    fun sized(text: String): Sized {
        val t = text.trim().removeSuffix(".").trim()
        mins.find(t)?.let { m -> return Sized(t.substring(0, m.range.first).trim().removeSuffix(":").trim(), m.groupValues[1].toInt().coerceIn(1, 240)) }
        hours.find(t)?.let { m -> return Sized(t.substring(0, m.range.first).trim().removeSuffix(":").trim(), (m.groupValues[1].toDouble() * 60).toInt().coerceIn(1, 240)) }
        return Sized(t, 0)
    }

    data class Sized(val text: String, val minutes: Int)

    /** The list lines of an answer, sized; "None" and empty lines are left out. */
    fun items(answer: String, max: Int): List<Sized> = answer.lines().map { it.trim() }
        .mapNotNull { l -> item.find(l)?.groupValues?.get(1) }
        .map { sized(it) }
        .map { it.copy(text = it.text.trim().trim('"').replace("**", "").replaceFirstChar { c -> c.uppercase() }) }
        .filter { it.text.isNotEmpty() && !it.text.equals("none", ignoreCase = true) && it.text.length <= 160 }
        .distinctBy { it.text.lowercase() }
        .take(max)
}

/** How long a to-do takes, as the page shows it: "5 min", "1 h", "1 h 30". */
object Sizes {
    val choices = listOf(5, 15, 30, 60)

    fun label(minutes: Int): String = when {
        minutes <= 0 -> ""
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60}"
    }

    private val quick = Regex("""^(?:call|ring|text|email|e-mail|message|reply|send|pay|book|order|check|ask|tell|remind|cancel|renew|sign|post|print|text)\b""", RegexOption.IGNORE_CASE)
    private val long = Regex("""\b(?:write|clean|tidy|sort|study|learn|plan|prepare|prep|fix|build|paint|organi[sz]e|declutter|draft|research|finish|taxes|report|essay)\b""", RegexOption.IGNORE_CASE)

    /** A guess on the phone, without the model, from the first words: quick messages 5, chores and writing 30. */
    fun guess(title: String): Int = when {
        quick.containsMatchIn(title.trim()) -> 5
        long.containsMatchIn(title) -> 30
        else -> 15
    }

    /** Its size, or the guess when none was set. */
    fun of(e: Entry): Int = if (e.minutes > 0) e.minutes else guess(e.title)
}

/** Smaller steps (proposal 2): the model breaks a to-do into 3–6 tiny steps, each with minutes. */
object Steps {
    const val MAX_TOKENS = 220

    fun ask(task: String, notes: String = ""): String =
        "Break this task into 3 to 6 tiny first steps. Each step must take 2 to 15 minutes and start with a verb. " +
            "One step per line, each line like: - Find last year's letter (5 min)\n\nTask: ${task.trim()}" +
            (notes.trim().takeIf { it.isNotEmpty() }?.let { "\nNotes: ${it.take(600)}" } ?: "")

    fun parse(answer: String): List<Organizer.Sized> =
        Organizer.items(answer, 6).map { it.copy(minutes = if (it.minutes > 0) it.minutes.coerceAtMost(60) else Sizes.guess(it.text).coerceAtMost(15)) }

    /** Without a model: a start that always fits, so the task is never just stuck. */
    fun starter(task: String): List<Organizer.Sized> {
        val t = task.trim().replaceFirstChar { it.lowercase() }
        return listOf(
            Organizer.Sized("Get out what you need for it", 5),
            Organizer.Sized("Do the first 10 minutes of: $t", 10),
            Organizer.Sized("Write down what's left", 2),
        )
    }
}

/** A calm plan for today (proposal 4): at most three open to-dos that fit the time left. */
object DayPlan {
    const val MAX = 3
    const val MAX_TOKENS = 120

    data class Pick(val entry: Entry, val minutes: Int)

    /** The to-dos the plan may choose from: due today or earlier first, then a few with no date, at most twelve. */
    fun candidates(open: List<Entry>, endOfToday: Long): List<Entry> {
        val top = open.filter { it.parentId == null }
        val due = top.filter { (it.dueAt ?: Long.MAX_VALUE) < endOfToday }.sortedBy { it.dueAt }
        val someday = top.filter { it.dueAt == null }.sortedByDescending { it.createdAt }.take(4)
        return (due + someday).take(12)
    }

    fun ask(list: List<Pair<Entry, Int>>, minutesLeft: Int): String =
        "Here are open to-dos, numbered, with about how long each takes:\n" +
            list.mapIndexed { i, (e, m) -> "${i + 1}. ${e.title.take(80)} (${m} min)" + if (e.dueHasTime) " [has a set time]" else "" }.joinToString("\n") +
            "\n\nThere are about $minutesLeft minutes left today. Pick at most $MAX to do today that fit in that time: " +
            "anything with a set time first, then the easiest. Answer only with their numbers in order, one per line, like:\n2\n5"

    /** The numbers in the answer, in order, as picks: at most three, each once, and nothing that isn't on the list. */
    fun parse(answer: String, list: List<Pair<Entry, Int>>): List<Pick> = Regex("""\b(\d{1,2})\b""").findAll(answer)
        .map { it.groupValues[1].toInt() - 1 }.filter { it in list.indices }.distinct().take(MAX)
        .map { Pick(list[it].first, list[it].second) }.toList()

    /** By rules: set times first, then what was due earliest, then the smallest; stop when the time is used up. */
    fun rules(list: List<Pair<Entry, Int>>, minutesLeft: Int): List<Pick> {
        val order = list.sortedWith(compareBy<Pair<Entry, Int>>({ if (it.first.dueHasTime) 0 else 1 }, { if (it.first.dueAt != null) 0 else 1 }, { it.second }))
        val out = mutableListOf<Pick>()
        var used = 0
        for ((e, m) in order) {
            if (out.size == MAX) break
            if (out.isNotEmpty() && used + m > minutesLeft) continue
            out += Pick(e, m); used += m
        }
        return out
    }
}

/** Brain dump (proposal 3): one dump split into small to-dos with sizes, ideas and notes. */
object DumpSort {
    const val MAX_TOKENS = 300

    data class Item(val kind: InkKind, val text: String, val minutes: Int = 0)

    fun ask(dump: String): String =
        "Sort this brain dump into separate items. Turn each thing to do into a small next action that starts with a verb. " +
            "One item per line, starting with its kind, like:\n- todo: Call the bank about the fee (5 min)\n- idea: Herbs on the balcony\n" +
            "- note: Mum likes tulips\nKinds: todo, idea, note. Keep the owner's language.\n\nThe dump:\n\"\"\"\n${dump.trim().take(2500)}\n\"\"\""

    private val kind = Regex("""^(todo|to-do|task|idea|note|journal)\s*[:\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)

    fun parse(answer: String): List<Item> = Organizer.items(answer, 12).mapNotNull { s ->
        val m = kind.find(s.text) ?: return@mapNotNull null
        val k = when (m.groupValues[1].lowercase()) { "idea" -> InkKind.Idea; "note" -> InkKind.Note; "journal" -> InkKind.Journal; else -> InkKind.Todo }
        val text = m.groupValues[2].trim().replaceFirstChar { it.uppercase() }
        if (text.isEmpty()) null else Item(k, text, if (k == InkKind.Todo) (if (s.minutes > 0) s.minutes else Sizes.guess(text)) else 0)
    }

    /** By rules: one item per line or sentence, sorted as the Ink line sorts a line; questions and reminders become notes. */
    fun rules(dump: String): List<Item> = dump.split(Regex("""\n+|(?<=[.!;])\s+|\s+(?:and also|also|plus)\s+""", RegexOption.IGNORE_CASE))
        .map { it.trim().trim('-', '•', '*', ',', ' ').trim() }.filter { it.length >= 3 }
        .map { line ->
            val g = InkLine.guess(line)
            val k = when (g.kind) { InkKind.Todo, InkKind.Idea, InkKind.Journal -> g.kind; else -> InkKind.Note }
            val text = (if (k == g.kind) g.text else line).replaceFirstChar { it.uppercase() }
            Item(k, text, if (k == InkKind.Todo) Sizes.guess(text) else 0)
        }.distinctBy { it.text.lowercase() }.take(12)
}

/** Where was I (proposal 7): the model sums up what the owner was on, in two sentences. */
object Recap {
    const val MAX_TOKENS = 120

    fun ask(focus: String?, lines: List<Entry>): String =
        "In two short sentences, tell the owner where they were today, kindly and concretely, so they can pick up again. " +
            "Speak to them as \"you\".\n" + (focus?.let { "They were focused on: $it\n" } ?: "") +
            "What they wrote or did today, oldest first:\n" +
            lines.takeLast(12).joinToString("\n") { "- " + (if (it.kind == EntryKind.Todo) (if (it.done) "[done] " else "[to-do] ") else "") + it.title.take(100) }

    /** Without a model: the same in plain counts. */
    fun rules(focus: String?, lines: List<Entry>): String {
        val done = lines.count { it.kind == EntryKind.Todo && it.done }
        val open = lines.count { it.kind == EntryKind.Todo && !it.done }
        val written = lines.count { it.kind != EntryKind.Todo }
        val parts = listOfNotNull(
            focus?.let { "You were on “$it”." },
            listOfNotNull(
                if (done > 0) "$done done" else null,
                if (open > 0) "$open still open" else null,
                if (written > 0) "$written ${if (written == 1) "line" else "lines"} written" else null,
            ).takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Today so far: $it." },
        )
        return parts.joinToString(" ").ifEmpty { "A quiet day so far." }
    }
}

/**
 * The Now card's one next thing (proposal 1). It picks the first open step of the first unfinished to-do in today's
 * plan; else the plan in order; else a to-do with a set time in the next hour; else the open to-dos of today and
 * earlier, oldest first. [skipped] ids ("Not now") go to the back, never away.
 */
object NextAction {
    data class Now(val entry: Entry, val parent: Entry? = null, val stepsLeft: Int = 0, val plan: Int = 0, val planSize: Int = 0) {
        val minutes get() = Sizes.of(entry)
    }

    fun pick(open: List<Entry>, now: Long, startOfToday: Long, endOfToday: Long, skipped: List<Long> = emptyList()): Now? {
        val top = open.filter { it.parentId == null }
        val stepsOf = open.filter { it.parentId != null }.groupBy { it.parentId }
        fun planned(e: Entry) = e.planOrder > 0 && (e.dueAt ?: -1) in startOfToday until endOfToday
        val plan = top.filter(::planned).sortedBy { it.planOrder }
        val soon = top.filter { !planned(it) && it.dueHasTime && (it.dueAt ?: 0) in (now - 15 * 60_000) until (now + 60 * 60_000) }.sortedBy { it.dueAt }
        val rest = top.filter { !planned(it) && it !in soon && (it.dueAt ?: Long.MAX_VALUE) < endOfToday }
            .sortedWith(compareBy({ it.dueAt }, { it.createdAt }))
        val order = (plan + soon + rest).distinctBy { it.id }
        // "Not now" sends a thing to the back, in the order it was skipped.
        val queue = order.filter { it.id !in skipped } + skipped.mapNotNull { id -> order.firstOrNull { it.id == id } }
        val first = queue.firstOrNull() ?: return null
        val planAt = plan.indexOfFirst { it.id == first.id }
        val steps = stepsOf[first.id].orEmpty().sortedBy { it.id }
        return if (steps.isNotEmpty()) Now(steps.first(), parent = first, stepsLeft = steps.size, plan = planAt + 1, planSize = plan.size)
        else Now(first, plan = planAt + 1, planSize = plan.size)
    }
}
