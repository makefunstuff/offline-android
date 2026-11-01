package com.lfmpixel.lfm.tools

import android.Manifest
import android.media.AudioManager
import android.provider.Settings
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.Reminder
import com.lfmpixel.lfm.llm.Json
import com.lfmpixel.lfm.llm.ToolSpec
import com.lfmpixel.lfm.llm.ToolSpec.Param
import com.lfmpixel.lfm.settings.ToolPolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the tools reach on the phone. [now] is a function so tests can fix the time. */
class ToolEnv(
    val db: LfmDatabase,
    val reminders: Reminders?,
    val calendar: LocalCalendar?,
    val contacts: Contacts?,
    val controls: PhoneControls?,
    val launcher: ExternalLauncher?,
    val focus: com.lfmpixel.lfm.assistant.Focus? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    val now: () -> LocalDateTime = { LocalDateTime.now() },
    /** The journal is locked: library searches leave it out. */
    val hideJournal: () -> Boolean = { false },
) {
    fun whenOf(args: Map<String, String>, key: String): When =
        args[key]?.let { TimeArgs.parse(it, now()) } ?: throw ToolArgsException("I couldn't read the time \"${args[key] ?: ""}\"")
    fun label(w: When) = TimeArgs.label(w, now().toLocalDate())
    fun millis(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()
    fun nowMillis() = millis(now())
}

internal fun Map<String, String>.need(key: String): String =
    this[key]?.trim()?.takeIf { it.isNotEmpty() } ?: throw ToolArgsException("Missing $key")

internal fun Map<String, String>.opt(key: String): String = this[key]?.trim().orEmpty()

internal fun firstLine(text: String, max: Int = 60): String {
    val line = text.trim().lineSequence().first().trim()
    return if (line.length <= max) line else line.take(max).substringBeforeLast(' ') + "…"
}

// --- Own data: notes, ideas, journal, to-dos (no permission, no confirm, undo deletes) ------------

class AddEntryTool(private val env: ToolEnv, private val kind: EntryKind) : Tool {
    override val spec = when (kind) {
        EntryKind.Note -> ToolSpec("add_note", "Save a note in the owner's library.", listOf(
            Param("text", "string", "The note, in the owner's words"),
            Param("title", "string", "A short title", required = false),
            Param("tags", "string", "One to three single-word tags, space separated, like work home reading", required = false)))
        EntryKind.Idea -> ToolSpec("add_idea", "Save an idea.", listOf(
            Param("text", "string", "The idea"),
            Param("topic", "string", "A short topic to group it under, like Side projects, Home, Writing", required = false)))
        EntryKind.Journal -> ToolSpec("add_journal", "Add a line to today's journal.", listOf(
            Param("text", "string", "The journal entry, in the owner's words"),
            Param("mood", "string", "How the owner feels", required = false, enum = listOf("good", "low", "neutral"))))
        EntryKind.Todo -> ToolSpec("add_todo", "Add a to-do. Use for things to buy or do; for a time-based nudge use set_reminder.", listOf(
            Param("text", "string", "What to do, short"),
            Param("list", "string", "A list name like Shopping, Work, Home", required = false),
            Param("due", "string", "When it is due: ISO date 2026-10-31 or date and time 2026-10-31T17:00", required = false)))
    }
    override val label = when (kind) { EntryKind.Note -> "Add notes"; EntryKind.Idea -> "Add ideas"; EntryKind.Journal -> "Add journal lines"; EntryKind.Todo -> "Add to-dos" }
    override val defaultPolicy = ToolPolicy.Do

    override suspend fun preview(args: Map<String, String>): Preview {
        val text = args.need("text")
        val fields = mutableListOf(Field("text", "Text", text))
        if (kind == EntryKind.Todo) {
            args.opt("list").takeIf { it.isNotEmpty() }?.let { fields += Field("list", "List", it) }
            args.opt("due").takeIf { it.isNotEmpty() }?.let { fields += Field("due", "Due", env.label(env.whenOf(args, "due")), highlight = true) }
        }
        return Preview("Add ${kind.label.lowercase()}?", "note", "Add", fields)
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val text = args.need("text")
        val due = if (kind == EntryKind.Todo && args.opt("due").isNotEmpty()) env.whenOf(args, "due") else null
        val title = args.opt("title").ifEmpty { firstLine(text) }
        val e = Entry(
            kind = kind,
            title = (if (kind == EntryKind.Note) title else firstLine(text, 120)).replaceFirstChar { it.uppercase() },
            body = if (kind == EntryKind.Note || text.length > 120) text else "",
            tags = args.opt("tags").lowercase().replace(",", " ").split(' ').filter { it.isNotBlank() }.take(3).joinToString(" "),
            topic = args.opt("topic").replaceFirstChar { it.uppercase() },
            list = args.opt("list").replaceFirstChar { it.uppercase() },
            mood = when (args.opt("mood")) { "good" -> 1; "low" -> 2; else -> 0 },
            dueAt = due?.epochMillis(env.zone),
            dueHasTime = due?.time != null,
            source = args["_source"] ?: "chat",
        )
        val id = env.db.entries().insert(e)
        val label = when (kind) {
            EntryKind.Todo -> if (e.list.isNotEmpty()) "Added to ${e.list}" else "To-do added"
            EntryKind.Note -> "Note saved"
            EntryKind.Idea -> "Idea saved"
            EntryKind.Journal -> "Added to journal"
        }
        return ToolResult(true, Json.obj("saved" to kind.label.lowercase(), "id" to id), label, e.title,
            due?.let { env.label(it) } ?: e.topic, undo = "entry:$id")
    }
}

class SearchLibraryTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("search_library", "Search the owner's notes, ideas, journal and to-dos by keywords.",
        listOf(Param("query", "string", "Keywords")))
    override val label = "Search your library"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override suspend fun preview(args: Map<String, String>) = Preview("Search your library?", "search", "Search", listOf(Field("query", "Words", args.need("query"))))
    override suspend fun run(args: Map<String, String>): ToolResult {
        val q = FtsQuery.from(args.need("query")) ?: return ToolResult(true, "[]")
        val hide = env.hideJournal()
        val found = env.db.entries().search(q, if (hide) 24 else 6).filter { !hide || it.kind != EntryKind.Journal }.take(6)
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        val lines = found.joinToString("\n") { e ->
            val day = Instant.ofEpochMilli(e.createdAt).atZone(env.zone).toLocalDate().format(fmt)
            "- ${e.kind.label} ($day): ${e.title}${if (e.body.isNotEmpty()) ": " + e.body.take(300) else ""}"
        }
        return ToolResult(true, lines.ifEmpty { "Nothing found." }, "Searched your library", args.need("query"), "${found.size} found")
    }
}

// --- Reminders (LFM's own notification) -------------------------------------------------------------

class SetReminderTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_reminder",
        "Remind the owner at a time with a notification from this app. The default for 'remind me'.",
        listOf(Param("text", "string", "What to remind about, short"),
            Param("at", "string", "When, as local ISO time like 2026-10-30T18:00")))
    override val label = "Reminders"
    override val defaultPolicy = ToolPolicy.Do
    override val permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)

    override suspend fun preview(args: Map<String, String>): Preview {
        val w = env.whenOf(args, "at")
        return Preview("Set a reminder?", "bell", "Set", listOf(
            Field("text", "Remind me to", args.need("text")),
            Field("at", "When", env.label(w), highlight = true)))
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val text = args.need("text").replaceFirstChar { it.uppercase() }
        val w = env.whenOf(args, "at").let { if (it.time == null) When(it.date, java.time.LocalTime.of(9, 0)) else it }
        val at = w.epochMillis(env.zone)
        if (at < System.currentTimeMillis() - 60_000 && env.reminders != null) throw ToolArgsException("That time has passed: ${env.label(w)}")
        val id = env.db.reminders().insert(Reminder(text = text, at = at))
        env.reminders?.schedule(Reminder(id = id, text = text, at = at))
        return ToolResult(true, Json.obj("reminder_set" to TimeArgs.iso(w), "text" to text), "Reminder set", text, env.label(w), undo = "reminder:$id")
    }
}

// --- Clock app: alarms and timers (always ask: Android has no call to delete a set alarm) ----------

class SetAlarmTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_alarm", "Set an alarm in the Clock app. Only when the owner says alarm.",
        listOf(Param("time", "string", "Time of day, 24-hour HH:MM"), Param("label", "string", "A label", required = false)))
    override val label = "Alarms"
    override val defaultPolicy = ToolPolicy.Ask
    override val opensApp = true
    override suspend fun preview(args: Map<String, String>): Preview {
        val w = env.whenOf(args, "time")
        return Preview("Set an alarm?", "alarm", "Set", listOf(
            Field("time", "Time", w.time?.toString() ?: throw ToolArgsException("No time of day"), highlight = true),
            Field("label", "Label", args.opt("label"))),
            note = "Alarms can't be undone from here: the Clock app keeps them.")
    }
    override suspend fun run(args: Map<String, String>): ToolResult {
        val t = env.whenOf(args, "time").time ?: throw ToolArgsException("No time of day")
        val ok = env.launcher?.setAlarm(t.hour, t.minute, args.opt("label")) ?: false
        return if (ok) ToolResult(true, "Alarm set for $t in the Clock app.", "Alarm set", t.toString(), args.opt("label").ifEmpty { "Clock app" }, opened = true)
        else ToolResult.error("No Clock app took the alarm")
    }
}

class SetTimerTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_timer", "Start a countdown timer in the Clock app.",
        listOf(Param("duration", "string", "How long, like 10 min or 1h 30m"), Param("label", "string", "A label", required = false)))
    override val label = "Timers"
    override val defaultPolicy = ToolPolicy.Ask
    override val opensApp = true
    private fun duration(args: Map<String, String>): Duration =
        TimeArgs.parseDuration(args.need("duration"))?.takeIf { it.seconds in 1..86_400 } ?: throw ToolArgsException("I couldn't read the duration")
    private fun fmt(d: Duration) = buildString {
        if (d.toHours() > 0) append("${d.toHours()} h ")
        if (d.toMinutesPart() > 0) append("${d.toMinutesPart()} min ")
        if (d.toSecondsPart() > 0) append("${d.toSecondsPart()} s")
    }.trim()
    override suspend fun preview(args: Map<String, String>) = Preview("Start a timer?", "alarm", "Start", listOf(
        Field("duration", "Length", fmt(duration(args)), highlight = true), Field("label", "Label", args.opt("label"))))
    override suspend fun run(args: Map<String, String>): ToolResult {
        val d = duration(args)
        val ok = env.launcher?.setTimer(d.seconds.toInt(), args.opt("label")) ?: false
        return if (ok) ToolResult(true, "Timer started for ${fmt(d)}.", "Timer started", fmt(d), args.opt("label").ifEmpty { "Clock app" }, opened = true)
        else ToolResult.error("No Clock app took the timer")
    }
}

// --- Calendar --------------------------------------------------------------------------------------

class CalendarUpcomingTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("calendar_upcoming", "List calendar events from now on.",
        listOf(Param("days", "integer", "How many days ahead, 1 for today", required = false)))
    override val label = "Read your calendar"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override suspend fun preview(args: Map<String, String>) = Preview("Read your calendar?", "cal", "Read", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val days = args.opt("days").toLongOrNull()?.coerceIn(1, 31) ?: 1
        val start = env.now()
        val endDay = start.toLocalDate().plusDays(days).atStartOfDay()
        val events = env.calendar?.between(start.atZone(env.zone).toInstant().toEpochMilli(), endDay.atZone(env.zone).toInstant().toEpochMilli()).orEmpty()
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
        val text = events.joinToString("\n") { e ->
            "- ${Instant.ofEpochMilli(e.start).atZone(env.zone).format(fmt)}: ${e.title}${if (e.location.isNotEmpty()) " at ${e.location}" else ""}"
        }
        return ToolResult(true, text.ifEmpty { "No events." }, "Read your calendar", "${events.size} events", if (days == 1L) "Today" else "Next $days days")
    }
}

class AddEventTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("add_event", "Add an event to the calendar on this phone. No guests.", listOf(
        Param("title", "string", "What it is"),
        Param("start", "string", "Start, local ISO time like 2026-11-06T20:00"),
        Param("end", "string", "End, local ISO time; leave out for one hour", required = false),
        Param("location", "string", "Where", required = false)))
    override val label = "Add calendar events"
    override val defaultPolicy = ToolPolicy.Ask
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

    private fun span(args: Map<String, String>): Pair<When, When> {
        val s = env.whenOf(args, "start").let { if (it.time == null) When(it.date, java.time.LocalTime.of(9, 0)) else it }
        val e = args.opt("end").takeIf { it.isNotEmpty() }?.let { TimeArgs.parse(it, s.toDateTime()) }
            ?.let { if (it.time == null) When(it.date, s.time) else it }
            ?.takeIf { it.toDateTime().isAfter(s.toDateTime()) }
            ?: s.toDateTime().plusHours(1).let { When(it.toLocalDate(), it.toLocalTime()) }
        return s to e
    }

    override suspend fun preview(args: Map<String, String>): Preview {
        val (s, e) = span(args)
        return Preview("Add to calendar?", "cal", "Add", listOf(
            Field("title", "Title", args.need("title")),
            Field("start", "When", env.label(s) + " – " + e.time.toString(), highlight = true),
            Field("location", "Where", args.opt("location")),
            Field("calendar", "Calendar", LocalCalendar.NAME, editable = false)),
            note = "Calendar changes ask first (Settings → Tools).")
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val (s, e) = span(args)
        val cal = env.calendar ?: return ToolResult.error("No calendar")
        val id = cal.add(args.need("title"), s.epochMillis(env.zone), e.epochMillis(env.zone), args.opt("location"))
        return ToolResult(true, Json.obj("event_added" to TimeArgs.iso(s), "calendar" to LocalCalendar.NAME), "Added to calendar",
            args.need("title"), env.label(s), undo = "event:$id")
    }
}

// --- Contacts, messages, calls ---------------------------------------------------------------------

class FindContactTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("find_contact", "Look up a person in the owner's contacts: numbers and birthday.",
        listOf(Param("name", "string", "Name or part of it")))
    override val label = "Look up contacts"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override suspend fun preview(args: Map<String, String>) = Preview("Look up a contact?", "user", "Look up", listOf(Field("name", "Name", args.need("name"))))
    override suspend fun run(args: Map<String, String>): ToolResult {
        val found = env.contacts?.find(args.need("name")).orEmpty()
        val text = found.joinToString("\n") { c -> "- ${c.name}: ${c.numbers.joinToString(", ").ifEmpty { "no number" }}${c.birthday?.let { ", birthday $it" } ?: ""}" }
        return ToolResult(true, text.ifEmpty { "No contact found." }, "Looked up contacts", args.need("name"), "${found.size} found")
    }
}

/** Resolves "Sam" or "+44 7700 900123" to a name and number, through the contacts when it is a name. */
private suspend fun ToolEnv.resolvePerson(to: String): Pair<String, String> {
    if (to.count { it.isDigit() } >= 5 && to.all { it.isDigit() || it in "+ ()-." }) return to to to
    val c = contacts?.find(to, 1)?.firstOrNull() ?: throw ToolArgsException("No contact called $to")
    val number = c.numbers.firstOrNull() ?: throw ToolArgsException("${c.name} has no phone number")
    return c.name to number
}

class SendMessageTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("send_message", "Write a text message. Messages opens with it filled in; the owner presses Send.",
        listOf(Param("to", "string", "Contact name or phone number"), Param("text", "string", "The message")))
    override val label = "Text messages"
    override val defaultPolicy = ToolPolicy.Ask
    override val opensApp = true
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override suspend fun preview(args: Map<String, String>): Preview {
        val (name, number) = env.resolvePerson(args.need("to"))
        return Preview("Text $name?", "chat", "Open Messages", listOf(
            Field("to", "To", if (name == number) number else "$name · $number", editable = false),
            Field("text", "Message", args.need("text"), highlight = false)),
            note = "Messages opens with this filled in. You press Send.")
    }
    override suspend fun run(args: Map<String, String>): ToolResult {
        val (name, number) = env.resolvePerson(args.need("to"))
        val ok = env.launcher?.sms(number, args.need("text")) ?: false
        return if (ok) ToolResult(true, "Messages opened with the text for $name; the owner sends it.", "Message ready in Messages", name, args.need("text").take(60), opened = true)
        else ToolResult.error("No messaging app")
    }
}

class CallTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("call", "Call someone: opens the dialer with the number.", listOf(Param("to", "string", "Contact name or phone number")))
    override val label = "Calls"
    override val defaultPolicy = ToolPolicy.Ask
    override val opensApp = true
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override suspend fun preview(args: Map<String, String>): Preview {
        val (name, number) = env.resolvePerson(args.need("to"))
        return Preview("Call $name?", "phone", "Call", listOf(Field("to", "Number", number, editable = false)), note = "The dialer opens; you press call.")
    }
    override suspend fun run(args: Map<String, String>): ToolResult {
        val (name, number) = env.resolvePerson(args.need("to"))
        val ok = env.launcher?.dial(number) ?: false
        return if (ok) ToolResult(true, "Dialer opened for $name.", "Dialer opened", name, number, opened = true) else ToolResult.error("No dialer")
    }
}

// --- Phone settings ----------------------------------------------------------------------------------

class TorchTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_torch", "Turn the torch (flashlight) on or off.", listOf(Param("on", "boolean", "true for on")))
    override val label = "Torch"
    override val defaultPolicy = ToolPolicy.Do
    private fun on(args: Map<String, String>) = args.need("on").lowercase() in setOf("true", "on", "yes", "1")
    override suspend fun preview(args: Map<String, String>) = Preview("Turn the torch ${if (on(args)) "on" else "off"}?", "flash", "OK", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No torch")
        val before = c.torchOn
        return if (c.setTorch(on(args))) ToolResult(true, "Torch ${if (on(args)) "on" else "off"}.", "Torch", if (on(args)) "On" else "Off", undo = "torch:$before")
        else ToolResult.error("This phone has no torch")
    }
}

class SoundModeTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_sound_mode", "Set the ringer to ring, vibrate or silent. Not for music: that is media_control.",
        listOf(Param("mode", "string", "The mode", enum = listOf("ring", "vibrate", "silent"))))
    override val label = "Ring, vibrate, silent"
    override val defaultPolicy = ToolPolicy.Do
    private fun mode(args: Map<String, String>) = when (args.need("mode").lowercase()) {
        "ring", "normal", "sound" -> AudioManager.RINGER_MODE_NORMAL
        "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
        "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
        else -> throw ToolArgsException("Mode must be ring, vibrate or silent")
    }
    override suspend fun preview(args: Map<String, String>) = Preview("Set sound to ${args.need("mode")}?", "speaker", "Set", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No audio")
        val m = mode(args)
        if (m == AudioManager.RINGER_MODE_SILENT && !c.hasDndAccess()) {
            env.launcher?.panel(c.dndSettingsAction())
            return ToolResult(false, "Silent needs Do Not Disturb access; the settings page is open for the owner.", "Needs Do Not Disturb access", "Allow Inkling in the page that opened")
        }
        val before = c.ringerMode
        return if (c.setRingerMode(m)) ToolResult(true, "Sound set to ${args.need("mode")}.", "Sound mode", args.need("mode").replaceFirstChar { it.uppercase() }, undo = "ringer:$before")
        else ToolResult.error("Android refused the change")
    }
}

/** Media, ring, alarm or call volume, as a share of its range, or a step louder or quieter. Undo puts it back. */
class VolumeTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_volume", "Set the phone's volume: media (music, videos, Inkling's voice), ring, alarm or call.", listOf(
        Param("level", "string", "0 to 100 (percent), or up, down, mute, max"),
        Param("stream", "string", "Which volume; media if not said", required = false, enum = listOf("media", "ring", "alarm", "call"))))
    override val label = "Volume"
    override val defaultPolicy = ToolPolicy.Do

    companion object {
        fun stream(name: String): Int = when (name.trim().lowercase(Locale.ROOT)) {
            "", "media", "music", "sound" -> AudioManager.STREAM_MUSIC
            "ring", "ringer", "ringtone", "notifications" -> AudioManager.STREAM_RING
            "alarm", "alarms" -> AudioManager.STREAM_ALARM
            "call", "voice call", "calls" -> AudioManager.STREAM_VOICE_CALL
            else -> throw ToolArgsException("Volume can be media, ring, alarm or call")
        }

        /** The new level for [level] from [now] (both 0..100): "40", "40%", "up", "down", "mute", "max". */
        fun target(level: String, now: Int): Int {
            val l = level.trim().lowercase(Locale.ROOT).removeSuffix("%").trim()
            return when (l) {
                "up", "louder", "higher" -> now + 20
                "down", "quieter", "lower", "softer" -> now - 20
                "mute", "off", "silent" -> 0
                "max", "maximum", "full", "loudest" -> 100
                else -> l.toDoubleOrNull()?.let { v -> if (v in 0.0..1.0 && l.contains('.')) v * 100 else v }?.toInt()
                    ?: throw ToolArgsException("I couldn't read the volume \"$level\"")
            }.coerceIn(0, 100)
        }

        fun streamLabel(s: Int) = when (s) {
            AudioManager.STREAM_RING -> "Ring volume"; AudioManager.STREAM_ALARM -> "Alarm volume"
            AudioManager.STREAM_VOICE_CALL -> "Call volume"; else -> "Media volume"
        }
    }

    override suspend fun preview(args: Map<String, String>): Preview {
        val s = stream(args.opt("stream"))
        val to = target(args.need("level"), env.controls?.volumePercent(s) ?: 50)
        return Preview("Set ${streamLabel(s).lowercase()} to $to%?", "speaker", "Set", listOf(Field("level", "Level", "$to%", highlight = true)))
    }

    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No audio")
        val s = stream(args.opt("stream"))
        val before = c.volumePercent(s)
        val to = target(args.need("level"), before)
        if (s == AudioManager.STREAM_RING && to == 0 && !c.hasDndAccess()) {
            return ToolResult(false, "Muting the ringer needs Do Not Disturb access. Suggest set_sound_mode vibrate instead.",
                "Needs Do Not Disturb access", "Ring volume", "Try “vibrate” instead")
        }
        return if (c.setVolumePercent(s, to)) ToolResult(true, "${streamLabel(s)} set to ${c.volumePercent(s)}%.", streamLabel(s), "${c.volumePercent(s)}%",
            "was $before%", undo = "volume:$s:$before")
        else ToolResult.error("Android refused the change")
    }
}

class DndTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("set_do_not_disturb", "Turn Do Not Disturb on or off.", listOf(Param("on", "boolean", "true for on")))
    override val label = "Do Not Disturb"
    override val defaultPolicy = ToolPolicy.Do
    private fun on(args: Map<String, String>) = args.need("on").lowercase() in setOf("true", "on", "yes", "1")
    override suspend fun preview(args: Map<String, String>) = Preview("Do Not Disturb ${if (on(args)) "on" else "off"}?", "bell", "OK", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No phone controls")
        if (!c.hasDndAccess()) {
            env.launcher?.panel(c.dndSettingsAction())
            return ToolResult(false, "Needs Do Not Disturb access; the settings page is open for the owner.", "Needs Do Not Disturb access", "Allow Inkling in the page that opened")
        }
        val before = c.dndOn
        return if (c.setDnd(on(args))) ToolResult(true, "Do Not Disturb ${if (on(args)) "on" else "off"}.", "Do Not Disturb", if (on(args)) "On" else "Off", undo = "dnd:$before")
        else ToolResult.error("Android refused the change")
    }
}

class OpenPanelTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("open_settings_panel", "Apps can't switch Wi-Fi, Bluetooth or mobile data: open the panel for the owner to tap.",
        listOf(Param("panel", "string", "Which one", enum = listOf("internet", "wifi", "bluetooth", "volume"))))
    override val label = "Open Wi-Fi, Bluetooth panels"
    override val defaultPolicy = ToolPolicy.Do
    override val opensApp = true
    override suspend fun preview(args: Map<String, String>) = Preview("Open ${args.need("panel")} settings?", "sliders", "Open", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val action = when (args.need("panel").lowercase()) {
            "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI
            "internet", "data", "mobile data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "volume" -> Settings.Panel.ACTION_VOLUME
            else -> throw ToolArgsException("Unknown panel")
        }
        val ok = env.launcher?.panel(action) ?: false
        return if (ok) ToolResult(true, "Opened the ${args.need("panel")} panel.", "Opened settings", args.need("panel").replaceFirstChar { it.uppercase() }, opened = true)
        else ToolResult.error("Could not open it")
    }
}

class MediaTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("media_control", "Pause, play or skip music, podcasts or videos playing on the phone.",
        listOf(Param("action", "string", "What to do", enum = listOf("play", "pause", "next", "previous"))))
    override val label = "Play and pause media"
    override val defaultPolicy = ToolPolicy.Do
    private fun key(args: Map<String, String>) = when (args.need("action").lowercase(Locale.ROOT)) {
        "play", "resume", "start" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
        "pause", "stop" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        "next", "skip", "forward" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        "previous", "back", "prev" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> throw ToolArgsException("Action must be play, pause, next or previous")
    }
    private fun word(code: Int) = when (code) {
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"; android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
        android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> "Next"; else -> "Previous"
    }
    override suspend fun preview(args: Map<String, String>) = Preview("${word(key(args))}?", "play", "OK", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No media controls")
        val k = key(args)
        val undo = when (k) { android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> "media:play"; android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> "media:pause"; else -> "" }
        return if (c.mediaKey(k)) ToolResult(true, "${word(k)} sent to the media app.", "Media", word(k), undo = undo)
        else ToolResult.error("No app is playing media")
    }
}

/** Battery, sound mode, Do Not Disturb, media volume and torch, read out. */
class PhoneStatusTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("phone_status", "Read the battery level and charging, sound mode, Do Not Disturb, volume and torch.", emptyList())
    override val label = "Battery and phone status"
    override val defaultPolicy = ToolPolicy.Do
    override val readOnly = true
    override suspend fun preview(args: Map<String, String>) = Preview("Read the phone's status?", "sliders", "Read", emptyList())
    override suspend fun run(args: Map<String, String>): ToolResult {
        val c = env.controls ?: return ToolResult.error("No phone controls")
        val (battery, charging) = c.battery()
        val ring = when (c.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "ring" }
        val text = listOfNotNull(
            if (battery >= 0) "Battery $battery%" + if (charging) ", charging" else ", not charging" else null,
            "Sound: $ring", "Do Not Disturb: ${if (c.dndOn) "on" else "off"}",
            "Media volume ${c.volumePercent(AudioManager.STREAM_MUSIC)}%", "Torch: ${if (c.torchOn) "on" else "off"}",
            if (c.musicActive) "Media is playing" else null,
        ).joinToString("\n")
        return ToolResult(true, text, "Phone status", if (battery >= 0) "Battery $battery%" else "Battery unknown",
            listOf(if (charging) "Charging" else "", ring.replaceFirstChar { it.uppercase() }).filter { it.isNotEmpty() }.joinToString(" · "))
    }
}

/** Opens another app on the phone by its name ("Spotify", "Maps"). Nothing is sent: the owner goes on there. */
class OpenAppTool(private val env: ToolEnv) : Tool {
    override val spec = ToolSpec("open_app", "Open an app on the phone by its name.", listOf(Param("app", "string", "The app's name, like Spotify or Maps")))
    override val label = "Open apps"
    override val defaultPolicy = ToolPolicy.Do
    override val opensApp = true
    private fun find(args: Map<String, String>): Pair<String, String> {
        val name = args.need("app")
        val apps = env.launcher?.apps().orEmpty()
        return when (val p = ItemMatch.pick(name, apps, { it.first }, { 0L })) {
            is ItemMatch.Pick.One -> p.item
            is ItemMatch.Pick.Several -> p.items.firstOrNull { it.first.equals(name.trim(), ignoreCase = true) }
                ?: throw ToolArgsException("Several apps fit \"$name\": ${p.items.joinToString(", ") { it.first }}. Ask the owner which one.")
            ItemMatch.Pick.None -> throw ToolArgsException("No app called $name on this phone")
        }
    }
    override suspend fun preview(args: Map<String, String>) = find(args).let { (label, _) -> Preview("Open $label?", "sliders", "Open", emptyList()) }
    override suspend fun run(args: Map<String, String>): ToolResult {
        val (label, pkg) = find(args)
        return if (env.launcher?.launch(pkg) == true) ToolResult(true, "Opened $label.", "Opened", label, opened = true)
        else ToolResult.error("Could not open $label")
    }
}

fun defaultTools(env: ToolEnv): List<Tool> = listOf(
    SetReminderTool(env),
    AddEntryTool(env, EntryKind.Todo),
    AddEntryTool(env, EntryKind.Note),
    AddEntryTool(env, EntryKind.Idea),
    AddEntryTool(env, EntryKind.Journal),
    CompleteTodoTool(env),
    RescheduleTool(env),
    DeleteItemTool(env),
    DailyBriefTool(env),
    ReadLatestTool(env),
    FocusTimerTool(env),
    SearchLibraryTool(env),
    AddEventTool(env),
    CalendarUpcomingTool(env),
    SetAlarmTool(env),
    SetTimerTool(env),
    FindContactTool(env),
    SendMessageTool(env),
    CallTool(env),
    TorchTool(env),
    SoundModeTool(env),
    VolumeTool(env),
    DndTool(env),
    OpenPanelTool(env),
    MediaTool(env),
    PhoneStatusTool(env),
    OpenAppTool(env),
    CalculateTool(),
)
