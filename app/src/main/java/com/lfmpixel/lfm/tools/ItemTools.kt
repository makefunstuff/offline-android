package com.lfmpixel.lfm.tools

import android.Manifest
import com.lfmpixel.lfm.assistant.NextAction
import com.lfmpixel.lfm.assistant.Sizes
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.data.Reminder
import com.lfmpixel.lfm.llm.Json
import com.lfmpixel.lfm.llm.ToolSpec
import com.lfmpixel.lfm.llm.ToolSpec.Param
import com.lfmpixel.lfm.settings.ToolPolicy
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The tools that change or read back what is already there (to-dos, reminders, notes): done, move, delete, the day's
 * brief, the last note and Focus. The model names an item in a few words ("milk", "the dentist one", "last");
 * [ItemMatch] finds it, and asks back when two fit as well.
 */
object ItemMatch {
    private val filler = setOf("the", "a", "an", "my", "to", "for", "about", "that", "this", "one", "of", "on", "please",
        "reminder", "todo", "to-do", "do", "task", "note", "idea", "item")
    private val latest = setOf("last", "latest", "that", "it", "this", "newest", "recent", "just", "one", "the", "added", "set", "made")

    fun words(s: String): List<String> =
        s.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in filler }.distinct()

    /** "last", "that one", "the one I just added": the newest item. */
    fun meansLatest(query: String): Boolean {
        val all = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in setOf("reminder", "todo", "to", "do", "note", "idea", "i") }
        return all.isNotEmpty() && all.all { it in latest }
    }

    private fun same(q: String, t: String) = q == t || (minOf(q.length, t.length) >= 4 && (t.startsWith(q) || q.startsWith(t)))

    /** How much of [query] is in [title], 0..1: a word counts when it is the same or one starts the other ("dentist's"). */
    fun score(query: String, title: String): Double {
        val q = words(query)
        if (q.isEmpty()) return 0.0
        val t = words(title)
        return q.count { w -> t.any { same(w, it) } }.toDouble() / q.size
    }

    /** Every title word is in the query: "oat milk" for "Oat milk". */
    private fun whole(query: String, title: String): Boolean {
        val q = words(query); val t = words(title)
        return t.isNotEmpty() && t.all { w -> q.any { same(it, w) } }
    }

    sealed class Pick<out T> {
        data class One<T>(val item: T) : Pick<T>()
        data class Several<T>(val items: List<T>) : Pick<T>()
        object None : Pick<Nothing>()
    }

    fun <T> pick(query: String, items: List<T>, title: (T) -> String, newest: (T) -> Long): Pick<T> {
        if (items.isEmpty()) return Pick.None
        if (meansLatest(query)) return Pick.One(items.maxBy(newest))
        // Two thirds of the words: "call the bank" must not pick "Call mom" on the shared verb alone.
        val scored = items.map { it to score(query, title(it)) }.filter { it.second >= 0.66 }
        val best = scored.maxOfOrNull { it.second } ?: return Pick.None
        val top = scored.filter { it.second == best }.map { it.first }
        // Two with the same title (a to-do added twice): either is the one meant.
        fun oneTitle(l: List<T>) = l.isNotEmpty() && l.distinctBy { title(it).trim().lowercase(Locale.ROOT) }.size == 1
        if (oneTitle(top)) return Pick.One(top[0])
        val exact = top.filter { whole(query, title(it)) }
        return if (oneTitle(exact)) Pick.One(exact[0]) else Pick.Several(top.take(3))
    }
}

/** One of the owner's things the model named: a to-do (or note, idea) or a reminder. */
sealed class Item {
    abstract val title: String
    data class OfEntry(val e: Entry) : Item() { override val title get() = e.title }
    data class OfReminder(val r: Reminder) : Item() { override val title get() = r.text }
}

private suspend fun ToolEnv.openTodos(): List<Entry> = db.entries().open().first()

/** Reminders still to come, and those of the last day (to snooze one that just went off). */
private suspend fun ToolEnv.liveReminders(): List<Reminder> =
    db.reminders().between(nowMillis() - 86_400_000L, Long.MAX_VALUE).first().filter { !it.cancelled }

/**
 * Finds what [query] names among [kinds] ("todo", "reminder", "note", "idea"), or explains why not in words the model
 * can say back ("Which one: Oat milk, Milk chocolate?").
 */
internal suspend fun ToolEnv.findItem(query: String, kinds: List<String>): Item {
    val items = mutableListOf<Item>()
    if ("todo" in kinds) items += openTodos().map { Item.OfEntry(it) }
    if ("reminder" in kinds) items += liveReminders().map { Item.OfReminder(it) }
    for (k in listOf("note" to EntryKind.Note, "idea" to EntryKind.Idea)) if (k.first in kinds) {
        val found = FtsQuery.from(query)?.let { db.entries().search(it, 20) }.orEmpty()
        val latest = if (ItemMatch.meansLatest(query) || found.isEmpty()) db.entries().byKind(k.second).first().take(20) else emptyList()
        items += (found + latest).distinctBy { it.id }.filter { it.kind == k.second }.map { Item.OfEntry(it) }
    }
    val what = kinds.joinToString(" or ") { if (it == "todo") "to-do" else it }
    return when (val p = ItemMatch.pick(query, items, { it.title }, { when (it) { is Item.OfEntry -> it.e.createdAt; is Item.OfReminder -> it.r.createdAt } })) {
        is ItemMatch.Pick.One -> p.item
        is ItemMatch.Pick.Several -> throw ToolArgsException("Several fit \"$query\": ${p.items.joinToString(", ") { it.title }}. Ask the owner which one.")
        ItemMatch.Pick.None -> throw ToolArgsException(if (items.isEmpty()) "There is no open $what" else "No $what matches \"$query\"")
    }
}

private fun kindsOf(arg: String, all: List<String>): List<String> = when (arg.trim().lowercase(Locale.ROOT)) {
    "", "any" -> all
    "todo", "to-do", "task" -> listOf("todo")
    "reminder" -> listOf("reminder")
    "note" -> listOf("note")
    "idea" -> listOf("idea")
    else -> throw ToolArgsException("Kind must be ${all.joinToString(", ")}")
}.filter { it in all }

private fun ToolEnv.dayLabel(millis: Long, hasTime: Boolean) =
    label(Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().let { When(it.toLocalDate(), if (hasTime) it.toLocalTime() else null) })

// --- Done -------------------------------------------------------------------------------------------

class CompleteTodoTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("complete_todo", "Tick off a to-do as done.",
        listOf(Param("todo", "string", "Which to-do, in a few words from its title, or last")))
    override val label = "Tick off to-dos"
    override val defaultPolicy = ToolPolicy.Do

    private suspend fun todo(args: Map<String, String>) = (env.findItem(args.need("todo"), listOf("todo")) as Item.OfEntry).e
    override suspend fun preview(args: Map<String, String>) = Preview("Mark done?", "check", "Done",
        listOf(Field("todo", "To-do", todo(args).title, editable = false)))

    override suspend fun run(args: Map<String, String>): ToolResult {
        val e = todo(args)
        val now = env.nowMillis()
        env.db.entries().update(e.copy(done = true, doneAt = now, updatedAt = now))
        if (env.focus?.run?.value?.id == e.id) env.focus.stop()
        val left = env.openTodos().count { it.parentId == null }
        return ToolResult(true, Json.obj("done" to e.title, "open_todos_left" to left), "Done", e.title,
            if (left == 0) "Nothing else open" else "$left still open", undo = "reopen:${e.id}")
    }
}

// --- Move, snooze ----------------------------------------------------------------------------------

class RescheduleTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("reschedule", "Move or snooze a to-do or reminder to a new time.", listOf(
        Param("what", "string", "Which one, in a few words from its title, or last"),
        Param("to", "string", "The new time: local ISO time like 2026-10-30T18:00, or in 10 minutes"),
        Param("kind", "string", "todo or reminder; leave out if not said", required = false, enum = listOf("todo", "reminder"))))
    override val label = "Move to-dos and reminders"
    override val defaultPolicy = ToolPolicy.Do

    private suspend fun target(args: Map<String, String>) = env.findItem(args.need("what"), kindsOf(args.opt("kind"), listOf("todo", "reminder")))

    override suspend fun preview(args: Map<String, String>): Preview {
        val item = target(args)
        val w = env.whenOf(args, "to")
        val was = when (item) {
            is Item.OfEntry -> item.e.dueAt?.let { env.dayLabel(it, item.e.dueHasTime) } ?: "No date"
            is Item.OfReminder -> env.dayLabel(item.r.at, true)
        }
        return Preview("Move it?", if (item is Item.OfReminder) "bell" else "cal", "Move", listOf(
            Field("what", if (item is Item.OfReminder) "Reminder" else "To-do", item.title, editable = false),
            Field("was", "Was", was, editable = false),
            Field("to", "To", env.label(w), highlight = true)))
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val item = target(args)
        val w = env.whenOf(args, "to")
        return when (item) {
            is Item.OfEntry -> {
                val e = item.e
                val now = env.nowMillis()
                // A step follows its to-do: give the to-do the date instead.
                env.db.entries().update(e.copy(dueAt = w.epochMillis(env.zone), dueHasTime = w.time != null, planOrder = 0, updatedAt = now))
                ToolResult(true, Json.obj("moved" to e.title, "to" to TimeArgs.iso(w)), "Moved", e.title, env.label(w),
                    undo = "due:${e.id}:${e.dueAt ?: ""}:${e.dueHasTime}:${e.planOrder}")
            }
            is Item.OfReminder -> {
                val r = item.r
                val at = (if (w.time == null) When(w.date, LocalTime.of(9, 0)) else w).epochMillis(env.zone)
                if (at < env.nowMillis() - 60_000) throw ToolArgsException("That time has passed: ${env.label(w)}")
                val moved = r.copy(at = at, fired = false)
                env.db.reminders().update(moved)
                env.reminders?.schedule(moved)
                ToolResult(true, Json.obj("moved" to r.text, "to" to TimeArgs.iso(w)), "Reminder moved", r.text, env.label(w),
                    undo = "remindAt:${r.id}:${r.at}:${r.fired}")
            }
        }
    }
}

// --- Delete (always asks first; undo puts it back) --------------------------------------------------

class DeleteItemTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("delete_item", "Delete a to-do, reminder, note or idea. The owner confirms first.", listOf(
        Param("what", "string", "Which one, in a few words from its title, or last"),
        Param("kind", "string", "What it is; leave out if not said", required = false, enum = listOf("todo", "reminder", "note", "idea"))))
    override val label = "Delete to-dos, reminders, notes"
    override val defaultPolicy = ToolPolicy.Ask

    private suspend fun target(args: Map<String, String>) =
        env.findItem(args.need("what"), kindsOf(args.opt("kind"), listOf("todo", "reminder", "note", "idea")))

    override suspend fun preview(args: Map<String, String>): Preview {
        val item = target(args)
        val kind = when (item) { is Item.OfReminder -> "Reminder"; is Item.OfEntry -> item.e.kind.label }
        val detail = when (item) {
            is Item.OfReminder -> Field("detail", "When", env.dayLabel(item.r.at, true), editable = false)
            is Item.OfEntry -> when {
                item.e.body.isNotEmpty() -> Field("detail", "Text", firstLine(item.e.body, 80), editable = false)
                item.e.list.isNotEmpty() -> Field("detail", "List", item.e.list, editable = false)
                else -> null
            }
        }
        return Preview("Delete this ${kind.lowercase()}?", "x", "Delete", listOfNotNull(
            Field("what", kind, item.title, editable = false), detail),
            note = "You can undo it from the card.")
    }

    override suspend fun run(args: Map<String, String>): ToolResult = when (val item = target(args)) {
        is Item.OfReminder -> {
            env.reminders?.cancel(item.r.id)
            env.db.reminders().update(item.r.copy(cancelled = true))
            ToolResult(true, Json.obj("deleted" to "reminder", "text" to item.r.text), "Reminder deleted", item.r.text, undo = "uncancel:${item.r.id}")
        }
        is Item.OfEntry -> {
            val e = item.e
            val steps = env.db.entries().stepsNow(e.id)
            val kept = Deleted.keep(JSONArray().put(EntryCodec.toJson(e)).also { a -> steps.forEach { a.put(EntryCodec.toJson(it)) } })
            env.db.entries().deleteSteps(e.id)
            env.db.entries().delete(e.id)
            if (env.focus?.run?.value?.id == e.id) env.focus.stop()
            ToolResult(true, Json.obj("deleted" to e.kind.label.lowercase(), "title" to e.title), "${e.kind.label} deleted", e.title,
                undo = "restore:$kept")
        }
    }
}

/**
 * What delete_item took away, kept in memory only for its undo: the action history (and so an export) never holds the
 * deleted text. Undo works until Inkling restarts.
 */
object Deleted {
    private val kept = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun keep(a: JSONArray): String = java.util.UUID.randomUUID().toString().also { kept[it] = a.toString() }
    fun take(token: String): JSONArray? = kept.remove(token)?.let { JSONArray(it) }
}

/** An entry as JSON, to put a deleted one back exactly (same id) on undo. */
object EntryCodec {
    fun toJson(e: Entry): JSONObject = JSONObject().put("id", e.id).put("kind", e.kind.name).put("title", e.title).put("body", e.body)
        .put("tags", e.tags).put("topic", e.topic).put("list", e.list).put("source", e.source).put("mood", e.mood)
        .put("dueAt", e.dueAt ?: JSONObject.NULL).put("dueHasTime", e.dueHasTime).put("done", e.done).put("doneAt", e.doneAt ?: JSONObject.NULL)
        .put("voiceSeconds", e.voiceSeconds).put("createdAt", e.createdAt).put("updatedAt", e.updatedAt).put("pinned", e.pinned)
        .put("parentId", e.parentId ?: JSONObject.NULL).put("minutes", e.minutes).put("planOrder", e.planOrder)

    fun fromJson(o: JSONObject): Entry {
        fun long(k: String) = if (o.isNull(k)) null else o.getLong(k)
        return Entry(o.getLong("id"), EntryKind.valueOf(o.getString("kind")), o.getString("title"), o.optString("body"), o.optString("tags"),
            o.optString("topic"), o.optString("list"), o.optString("source", "typed"), o.optInt("mood"), long("dueAt"), o.optBoolean("dueHasTime"),
            o.optBoolean("done"), long("doneAt"), o.optInt("voiceSeconds"), o.getLong("createdAt"), o.getLong("updatedAt"), o.optBoolean("pinned"),
            long("parentId"), o.optInt("minutes"), o.optInt("planOrder"))
    }
}

// --- Read back: the day, the last note -------------------------------------------------------------

class DailyBriefTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("daily_brief", "What's on today or tomorrow: the next thing, to-dos, reminders, events. For what's on, what's next, what's left.",
        listOf(Param("day", "string", "Which day; today if not said", required = false, enum = listOf("today", "tomorrow"))))
    override val label = "Today's brief"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override suspend fun preview(args: Map<String, String>) = Preview("Read today's brief?", "cal", "Read", emptyList())

    override suspend fun run(args: Map<String, String>): ToolResult {
        val tomorrow = args.opt("day").lowercase(Locale.ROOT) == "tomorrow"
        val now = env.now()
        val day = now.toLocalDate().plusDays(if (tomorrow) 1 else 0)
        val start = env.millis(day.atStartOfDay()); val end = env.millis(day.plusDays(1).atStartOfDay())
        val todayStart = env.millis(now.toLocalDate().atStartOfDay())
        val open = env.openTodos()
        val top = open.filter { it.parentId == null }
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(env.zone).format(hm)
        val due = top.filter { (it.dueAt ?: -1) in start until end }.sortedWith(compareBy({ it.planOrder == 0 }, { it.planOrder }, { it.dueAt }))
        // Gentle words (notebook-spec A.1, 6): earlier to-dos are "still open", never "overdue".
        val still = if (tomorrow) emptyList() else top.filter { (it.dueAt ?: Long.MAX_VALUE) < todayStart }.sortedBy { it.dueAt }
        val from = if (tomorrow) start else env.nowMillis()
        val reminders = env.liveReminders().filter { !it.fired && it.at in from until end }.sortedBy { it.at }
        val events = env.calendar?.between(from, end).orEmpty()
        val next = if (tomorrow) null else NextAction.pick(open, env.nowMillis(), todayStart, end)
        val lines = buildList {
            next?.let { add("Next: ${it.entry.title} (about ${Sizes.label(it.minutes)})") }
            env.focus?.run?.value?.let { add("Focus running: ${it.title}, ${it.leftMs(System.currentTimeMillis()) / 60_000} min left") }
            due.forEach { add("To-do: ${it.title}" + (if (it.dueHasTime) " at ${clock(it.dueAt!!)}" else "")) }
            still.take(3).forEach { add("Still open from ${env.dayLabel(it.dueAt!!, false)}: ${it.title}") }
            if (still.size > 3) add("${still.size - 3} more still open from earlier days")
            reminders.forEach { add("Reminder ${clock(it.at)}: ${it.text}") }
            events.forEach { add("Event ${clock(it.start)}: ${it.title}" + if (it.location.isNotEmpty()) " at ${it.location}" else "") }
            val undated = top.count { it.dueAt == null }
            if (undated > 0) add("$undated to-dos with no date")
        }
        val name = if (tomorrow) "Tomorrow" else "Today"
        val count = due.size + reminders.size + events.size
        return ToolResult(true, lines.joinToString("\n").ifEmpty { "Nothing planned ${name.lowercase()}." }, "$name's brief",
            next?.entry?.title ?: "Nothing next", "$count planned" + if (still.isNotEmpty()) " · ${still.size} still open" else "")
    }
}

class ReadLatestTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("read_latest", "Read back the last note, idea, journal line or to-do the owner saved.", listOf(
        Param("kind", "string", "What to read; any if not said", required = false, enum = listOf("note", "idea", "journal", "todo", "any")),
        Param("count", "integer", "How many, 1 to 5; 1 if not said", required = false)))
    override val label = "Read back the last note"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override suspend fun preview(args: Map<String, String>) = Preview("Read your last note?", "note", "Read", emptyList())

    override suspend fun run(args: Map<String, String>): ToolResult {
        val n = args.opt("count").toIntOrNull()?.coerceIn(1, 5) ?: 1
        val kind = when (args.opt("kind").lowercase(Locale.ROOT)) {
            "note" -> EntryKind.Note; "idea" -> EntryKind.Idea; "journal" -> EntryKind.Journal; "todo", "to-do" -> EntryKind.Todo
            "", "any" -> null
            else -> throw ToolArgsException("Kind must be note, idea, journal, todo or any")
        }
        val hide = env.hideJournal()
        if (kind == EntryKind.Journal && hide) return ToolResult(true, "The journal is locked. Unlock it in Inkling to hear it.", "Journal locked")
        val all = if (kind != null) env.db.entries().byKind(kind).first() else env.db.entries().latest(30).first()
        val found = all.filter { it.parentId == null && !(hide && it.kind == EntryKind.Journal) }.sortedByDescending { it.createdAt }.take(n)
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        val text = found.joinToString("\n") { e ->
            "- ${e.kind.label}, ${Instant.ofEpochMilli(e.createdAt).atZone(env.zone).format(fmt)}: ${e.title}" +
                (if (e.body.isNotEmpty() && e.body != e.title) ": " + e.body.take(400) else "")
        }
        return ToolResult(true, text.ifEmpty { "Nothing saved yet." }, "Read back", found.firstOrNull()?.title ?: "Nothing yet",
            found.firstOrNull()?.kind?.label.orEmpty())
    }
}

// --- Focus (notebook-spec A.1, 5) ------------------------------------------------------------------

class FocusTimerTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("focus_timer", "Start or stop a Focus timer on one to-do, optionally with Do Not Disturb on.", listOf(
        Param("action", "string", "start or stop; start if not said", required = false, enum = listOf("start", "stop")),
        Param("task", "string", "The to-do to focus on, in a few words; leave out for the next thing", required = false),
        Param("minutes", "integer", "How long; leave out for the to-do's own size", required = false),
        Param("quiet", "boolean", "true to also turn on Do Not Disturb", required = false)))
    override val label = "Focus timer"
    override val defaultPolicy = ToolPolicy.Do
    override val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)

    private fun stopping(args: Map<String, String>) = args.opt("action").lowercase(Locale.ROOT) in setOf("stop", "end", "cancel")
    private fun quiet(args: Map<String, String>) = args.opt("quiet").lowercase(Locale.ROOT) in setOf("true", "on", "yes", "1")
    private fun minutes(args: Map<String, String>, e: Entry?) =
        (args.opt("minutes").toDoubleOrNull()?.toInt() ?: TimeArgs.parseDuration(args.opt("minutes"))?.toMinutes()?.toInt())
            ?.takeIf { it in 1..240 } ?: e?.let { Sizes.of(it) } ?: 25

    /** The to-do named, the Now card's next thing when none is, or null for a new to-do from [task]. */
    private suspend fun target(args: Map<String, String>): Entry? {
        val task = args.opt("task")
        if (task.isEmpty()) {
            val now = env.now()
            return NextAction.pick(env.openTodos(), env.nowMillis(), env.millis(now.toLocalDate().atStartOfDay()),
                env.millis(now.toLocalDate().plusDays(1).atStartOfDay()))?.entry
                ?: throw ToolArgsException("Nothing is open to focus on. Ask the owner what to focus on.")
        }
        return try { (env.findItem(task, listOf("todo")) as Item.OfEntry).e } catch (e: ToolArgsException) {
            if (e.message?.startsWith("Several") == true) throw e else null
        }
    }

    override suspend fun preview(args: Map<String, String>): Preview {
        if (stopping(args)) return Preview("Stop Focus?", "alarm", "Stop", listOf(Field("task", "Focus", env.focus?.run?.value?.title ?: "None running", editable = false)))
        val e = target(args)
        return Preview("Start Focus?", "alarm", "Start", listOfNotNull(
            Field("task", "On", e?.title ?: args.need("task").replaceFirstChar { it.uppercase() }, editable = false),
            Field("minutes", "Minutes", minutes(args, e).toString(), highlight = true),
            if (quiet(args)) Field("quiet", "Do Not Disturb", "On", editable = false) else null))
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val focus = env.focus ?: return ToolResult.error("No Focus timer here")
        if (stopping(args)) {
            val r = focus.run.value ?: return ToolResult(true, "No Focus timer was running.", "Focus", "None running")
            focus.stop()
            return ToolResult(true, "Focus stopped on ${r.title}.", "Focus stopped", r.title)
        }
        var e = target(args)
        var made = ""
        if (e == null) {
            val title = firstLine(args.need("task"), 120).replaceFirstChar { it.uppercase() }
            val id = env.db.entries().insert(Entry(kind = EntryKind.Todo, title = title, source = args["_source"] ?: "chat"))
            e = env.db.entries().get(id)!!
            made = id.toString()
        }
        val m = minutes(args, e)
        val c = env.controls
        var dndBefore = ""
        var dndNote = ""
        if (quiet(args) && c != null) {
            if (c.hasDndAccess()) { dndBefore = c.dndOn.toString(); c.setDnd(true) }
            else dndNote = " Do Not Disturb needs access first: Settings, Apps, Inkling."
        }
        focus.start(e.id, e.title, m)
        val started = focus.run.value?.startedAt ?: 0
        return ToolResult(true, "Focus started: ${e.title}, $m min." + (if (dndBefore.isNotEmpty()) " Do Not Disturb on." else "") + dndNote,
            "Focus started", e.title, "$m min" + if (dndBefore.isNotEmpty()) " · Do Not Disturb" else "", undo = "focus:$dndBefore:$made:$started")
    }
}

/**
 * Undo for the tools in this file (Assistant.undo calls it for the kinds it doesn't know). Only what is still as the
 * tool left it is put back. Null: not one of these kinds.
 */
suspend fun undoItem(env: ToolEnv, kind: String, value: String): Boolean? {
    val entries = env.db.entries()
    return when (kind) {
        "reopen" -> {
            val e = entries.get(value.toLong()) ?: return true
            if (e.done) entries.update(e.copy(done = false, doneAt = null, updatedAt = env.nowMillis()))
            true
        }
        "due" -> {
            val (id, due, hasTime, plan) = value.split(':')
            val e = entries.get(id.toLong()) ?: return true
            entries.update(e.copy(dueAt = due.toLongOrNull(), dueHasTime = hasTime.toBoolean(), planOrder = plan.toIntOrNull() ?: 0, updatedAt = env.nowMillis()))
            true
        }
        "remindAt" -> {
            val (id, at, fired) = value.split(':')
            val r = env.db.reminders().get(id.toLong()) ?: return true
            val back = r.copy(at = at.toLong(), fired = fired.toBoolean())
            env.db.reminders().update(back)
            env.reminders?.cancel(r.id)
            if (!back.fired && !back.cancelled && back.at > env.nowMillis()) env.reminders?.schedule(back)
            true
        }
        "uncancel" -> {
            val r = env.db.reminders().get(value.toLong()) ?: return false
            val back = r.copy(cancelled = false)
            env.db.reminders().update(back)
            if (!back.fired && back.at > env.nowMillis()) env.reminders?.schedule(back)
            true
        }
        "restore" -> {
            val a = Deleted.take(value) ?: return false
            for (i in 0 until a.length()) {
                val e = EntryCodec.fromJson(a.getJSONObject(i))
                if (entries.get(e.id) == null) entries.insert(e)
            }
            true
        }
        "focus" -> {
            val (dnd, made, run) = (value.split(':') + "").let { Triple(it[0], it[1], it[2]) }
            // Only the run this started, the Do Not Disturb it turned on, and the to-do it made if still untouched.
            val running = env.focus?.run?.value
            if (running != null && (run.isEmpty() || running.startedAt.toString() == run)) env.focus.stop()
            if (dnd.toBooleanStrictOrNull() == false && env.controls?.dndOn == true) env.controls.setDnd(false)
            made.toLongOrNull()?.let { id ->
                entries.get(id)?.takeIf { !it.done && it.updatedAt <= it.createdAt + 1_000 && entries.stepsNow(id).isEmpty() }
                    ?.let { entries.delete(it.id) }
            }
            true
        }
        else -> null
    }
}
