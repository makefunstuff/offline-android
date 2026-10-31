package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import androidx.compose.foundation.horizontalScroll
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Card
import com.lfmpixel.lfm.ui.components.Composer
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.Pill
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One line of today's agenda. */
data class AgendaItem(val at: Long?, val title: String, val detail: String, val onClick: (() -> Unit)? = null, val count: Int? = null)

@Composable
fun TodayScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    val zone = ZoneId.systemDefault()
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
    val end = start + 86_400_000L
    val reminders by remember(start) { g.db.reminders().between(start, end) }.collectAsState(emptyList())
    val todos by remember { g.db.entries().todos() }.collectAsState(emptyList())
    val latestAll by remember { g.db.entries().latest(3) }.collectAsState(emptyList())
    val hideJournal = journalLocked(g)
    val latest = if (hideJournal) latestAll.filter { it.kind != EntryKind.Journal } else latestAll
    val prefs by g.settings.prefs.collectAsState(null)
    val events by produceState(emptyList<com.lfmpixel.lfm.tools.CalendarEvent>(), start, reminders) { value = g.calendar.between(start, end) }
    var draft by remember { mutableStateOf("") }

    val dueToday = todos.filter { !it.done && it.dueAt != null && it.dueAt < end }
    val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    val items = buildList {
        events.forEach { add(AgendaItem(it.start, it.title, listOf(it.location, minutes(it.end - it.start)).filter { s -> s.isNotEmpty() }.joinToString(" · "))) }
        reminders.filter { !it.fired }.forEach { add(AgendaItem(it.at, it.text, "Reminder")) }
        dueToday.filter { it.dueHasTime && it.dueAt!! >= start }.forEach { add(AgendaItem(it.dueAt, it.title, "To-do")) }
    }.sortedBy { it.at }
    val untimed = dueToday.filter { !it.dueHasTime || it.dueAt!! < start }
    val next = items.firstOrNull { (it.at ?: 0) >= nowMs - 15 * 60_000 }
    val count = items.size + if (untimed.isNotEmpty()) 1 else 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)), Modifier.weight(1f))
            Box(Modifier.size(36.dp).border(1.dp, c.control, RoundedCornerShape(6.dp)).clickable { nav.go(Route.Settings) }
                .semantics { contentDescription = "Settings" }, contentAlignment = Alignment.Center) {
                LIcon("user", size = 18.dp, tint = c.text2)
            }
        }
        Txt(greeting(now), LfmType.title, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp).semantics { heading() })
        Column(Modifier.padding(horizontal = 16.dp)) {
            Composer(draft, { draft = it }, "Ask, capture or do…", onSend = {
                g.assistant.send(draft); draft = ""; nav.go(Route.Chat)
            }, onMic = onMic, listening = listening, onCamera = { nav.go(Route.Camera) })
            // One tap to write any kind, keyboard up (ux-spec section 4).
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Talk", { nav.go(Route.Talk) }, small = true, icon = "wave")
                listOf(EntryKind.Note, EntryKind.Idea, EntryKind.Journal, EntryKind.Todo).forEach { k ->
                    Btn(k.label, { nav.go(Route.Editor(kind = k)) }, small = true, icon = k.icon())
                }
            }
            Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("On this phone", "shield")
                ModelPill(g) { nav.go(Route.Models) }
                Pill("Search: ${prefs?.searchMode?.label?.lowercase() ?: "ask first"}", "globe")
                val heat by g.heat.heat.collectAsState()
                if (heat != com.lfmpixel.lfm.device.Heat.Normal) Pill(heat.label.substringBefore(" ·"), "warn")
            }
            SectionLabel(if (count == 0) "Today · nothing planned" else "Today · $count ${if (count == 1) "thing" else "things"}")
            items.forEach { AgendaRow(it, it == next, zone) }
            if (untimed.isNotEmpty()) {
                AgendaRow(AgendaItem(null, if (untimed.size == 1) "To-do due today" else "To-dos due today",
                    untimed.take(3).joinToString(", ") { it.title }, onClick = { nav.go(Route.Todos) }, count = untimed.size), false, zone)
            }
            if (count > 0) Divider()
            if (count == 0) Txt("Say or type what's coming up; reminders and events show here.", LfmType.small, c.text2, Modifier.padding(vertical = 8.dp))
            if (latest.isNotEmpty()) {
                SectionLabel("Just captured")
                latest.forEach { e -> CaptureCard(e) { nav.go(Route.Editor(e.id, e.kind)) } ; Spacer(Modifier.height(8.dp)) }
            }
            if (!g.engine.hasText()) {
                Spacer(Modifier.height(8.dp))
                Card(onClick = { nav.go(Route.Models) }) {
                    Column {
                        Txt("No model yet", LfmType.rowTitle)
                        Txt("Get LFM3-1.7B to chat and let it sort what you capture. Until then, simple rules sort it.", LfmType.small, c.text2)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AgendaRow(item: AgendaItem, isNext: Boolean, zone: ZoneId) {
    val c = Lfm.c
    Column {
        Divider()
        Row(Modifier.fillMaxWidth().let { m -> item.onClick?.let { m.clickable(onClick = it) } ?: m }.padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(76.dp)) {
                val t = item.count?.toString() ?: item.at?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: ""
                if (isNext) Marked(t, LfmType.big) else Txt(t, LfmType.big)
            }
            Column(Modifier.weight(1f)) {
                Txt(item.title, LfmType.rowTitle, maxLines = 1)
                if (item.detail.isNotEmpty()) Txt(item.detail, LfmType.label.copy(fontWeight = null), c.text2, maxLines = 1)
            }
            if (isNext) Txt("Next", LfmType.meta.copy(fontWeight = LfmType.label.fontWeight), c.text2)
            if (item.onClick != null) LIcon("right", tint = c.text3)
        }
    }
}

@Composable
fun CaptureCard(e: Entry, onClick: () -> Unit) {
    val c = Lfm.c
    Card(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(e.title, LfmType.bodyLarge.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 2)
            val time = Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            Txt(listOfNotNull(e.kind.label, time, e.source.takeIf { it == "voice" }, e.list.takeIf { it.isNotEmpty() }).joinToString(" · "),
                LfmType.label.copy(fontWeight = null), c.text2)
        }
    }
}

private fun greeting(now: LocalDateTime) = when (now.hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

private fun minutes(ms: Long): String {
    val m = ms / 60_000
    return when {
        m <= 0 -> ""
        m < 60 -> "$m min"
        m % 60 == 0L -> "${m / 60} h"
        else -> "${m / 60} h ${m % 60} min"
    }
}

fun EntryKind.icon() = when (this) { EntryKind.Note -> "note"; EntryKind.Idea -> "bulb"; EntryKind.Journal -> "book"; EntryKind.Todo -> "todo" }

/** The chat model's state in a word, for Today and the chat header: "ready", "loading…", "not loaded". */
@Composable
fun modelState(g: AppGraph): String {
    val st by g.engine.status.collectAsState()
    val installed by g.models.installed.collectAsState()
    val prefs by g.settings.prefs.collectAsState(null)
    val chat = installed.firstOrNull { it.file.name == (st.textFile ?: prefs?.chatModel) && it.known.role == com.lfmpixel.lfm.llm.ModelRole.Text }
        ?: g.models.text()
    val name = chat?.let { "${it.known.family} ${it.known.quant}" } ?: return "No chat model"
    return name + " · " + when (st.text) {
        com.lfmpixel.lfm.llm.LoadState.Ready -> "ready"
        com.lfmpixel.lfm.llm.LoadState.Loading -> "loading…"
        com.lfmpixel.lfm.llm.LoadState.Failed -> "failed to load"
        com.lfmpixel.lfm.llm.LoadState.None -> "not loaded"
    }
}

@Composable
private fun ModelPill(g: AppGraph, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick)) { Pill(modelState(g), "chip") }
}
