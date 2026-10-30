package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.ui.EntrySheet
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Tabs
import com.lfmpixel.lfm.ui.components.Tag
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val tabs = listOf(EntryKind.Note to "Notes", EntryKind.Idea to "Ideas", EntryKind.Journal to "Journal")

@Composable
fun LibraryScreen(g: AppGraph, nav: Nav, tab: EntryKind, onMic: (EntryKind) -> Unit, listening: Boolean) {
    val c = Lfm.c
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Entry>?>(null) }
    LaunchedEffect(query) {
        results = FtsQuery.from(query)?.let { g.db.entries().search(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Library", LfmType.title2, modifier = Modifier.weight(1f))
                IconButtonPlain("plus", { nav.entrySheet = EntrySheet(kind = tab) }, desc = "New ${tab.label.lowercase()}")
                IconButtonPlain(if (searching) "x" else "search", { searching = !searching; query = "" }, desc = "Search")
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (searching) {
                    val shape = RoundedCornerShape(8.dp)
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 44.dp).background(c.fill, shape).border(1.dp, c.control, shape)
                        .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LIcon("search", size = 18.dp, tint = c.text2)
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Txt("Search notes, ideas, journal, to-dos", LfmType.body, c.text2)
                            BasicTextField(query, { query = it }, singleLine = true, textStyle = LfmType.body.copy(color = c.text),
                                cursorBrush = SolidColor(c.text), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search" })
                        }
                    }
                }
                if (!searching || query.isBlank()) {
                    Tabs(tabs.map { it.second }, tabs.first { it.first == tab }.second, { t -> nav.go(Route.Library(tabs.first { it.second == t }.first)) })
                }
            }
            val r = results
            if (searching && query.isNotBlank() && r != null) {
                SearchResults(r, nav)
            } else when (tab) {
                EntryKind.Idea -> IdeasList(g, nav)
                EntryKind.Journal -> JournalList(g, nav, onMic, listening)
                else -> NotesList(g, nav)
            }
        }
        if (tab != EntryKind.Journal) {
            MicButton({ onMic(tab) }, Modifier.align(Alignment.BottomEnd).padding(16.dp), size = 56.dp, listening = listening)
        }
    }
}

@Composable
private fun SearchResults(found: List<Entry>, nav: Nav) {
    val c = Lfm.c
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { Label("${found.size} found", Modifier.padding(vertical = 10.dp)) }
        items(found, key = { it.id }) { e -> NoteRow(e, showKind = true) { nav.entrySheet = EntrySheet(e.id, e.kind) } }
        if (found.isEmpty()) item { Txt("Nothing matches. Search looks at whole words and word starts.", LfmType.small, c.text2) }
    }
}

@Composable
private fun NotesList(g: AppGraph, nav: Nav) {
    val notes by remember { g.db.entries().byKind(EntryKind.Note) }.collectAsState(null)
    val list = notes ?: return
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (list.isEmpty()) item { Empty("No notes yet", "Tap the mic and talk, or type with +. Long notes and voice memos land here.") }
        items(list, key = { it.id }) { e -> NoteRow(e) { nav.entrySheet = EntrySheet(e.id, e.kind) } }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
fun NoteRow(e: Entry, showKind: Boolean = false, onClick: () -> Unit) {
    val c = Lfm.c
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(e.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.weight(1f), maxLines = 1,
                decoration = if (e.done) TextDecoration.LineThrough else null)
            Txt(shortDay(e.createdAt), LfmType.meta, c.text2)
        }
        if (e.body.isNotEmpty() && e.body != e.title) Txt(e.body, LfmType.small.copy(fontSize = LfmType.small.fontSize * 0.93f), c.text2,
            Modifier.padding(top = 3.dp, bottom = 7.dp), maxLines = 2)
        val tags = buildList {
            if (showKind) add(e.kind.label to null)
            if (e.voiceSeconds > 0) add("%d:%02d".format(e.voiceSeconds / 60, e.voiceSeconds % 60) to "wave")
            else if (e.source == "voice") add("voice" to "wave")
            e.tags.split(' ').filter { it.isNotBlank() }.forEach { add(it to null) }
            if (e.list.isNotEmpty()) add(e.list to null)
        }
        if (tags.isNotEmpty()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (t, i) -> Tag(t, i) } }
    }
    com.lfmpixel.lfm.ui.components.Divider()
}

@Composable
private fun IdeasList(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val ideas by remember { g.db.entries().byKind(EntryKind.Idea) }.collectAsState(null)
    val list = ideas ?: return
    val groups = list.groupBy { it.topic.ifBlank { "Other" } }.toList().sortedByDescending { (_, v) -> v.maxOf { it.createdAt } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (list.isEmpty()) item { Empty("No ideas yet", "Say “idea:” and the thought; the model groups them by topic.") }
        groups.forEachIndexed { gi, (topic, items) ->
            item(key = "t-$topic") { Label(topic, Modifier.padding(top = if (gi == 0) 0.dp else 20.dp, bottom = 6.dp)) }
            items.chunked(2).forEach { pair ->
                item(key = "r-${pair.first().id}") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { e ->
                            IdeaCard(e, Modifier.weight(1f), onExpand = {
                                g.assistant.send("Expand this idea into a few concrete next steps: ${e.title}"); nav.go(Route.Chat)
                            }) { nav.entrySheet = EntrySheet(e.id, e.kind) }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(90.dp)) }
    }
}

@Composable
private fun IdeaCard(e: Entry, modifier: Modifier, onExpand: () -> Unit, onClick: () -> Unit) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    Column(modifier.heightIn(min = 92.dp).border(1.dp, c.line, shape).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Txt(e.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 3)
        Column {
            Txt(listOfNotNull(shortDay(e.createdAt), e.source.takeIf { it == "voice" }).joinToString(" · "), LfmType.meta, c.text2,
                Modifier.padding(top = 8.dp))
            Row(Modifier.clickable(onClick = onExpand).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("Expand", LfmType.meta.copy(fontWeight = LfmType.label.fontWeight), c.text, decoration = TextDecoration.Underline)
                LIcon("right", size = 14.dp, tint = c.text)
            }
        }
    }
}

private val prompts = listOf(
    "What gave you energy this week?", "What are you putting off, and why?", "What went better than expected today?",
    "Who would you like to see more of?", "What did you learn today?", "What would make tomorrow easier?",
    "What's taking up space in your head?",
)

@Composable
private fun JournalList(g: AppGraph, nav: Nav, onMic: (EntryKind) -> Unit, listening: Boolean) {
    val c = Lfm.c
    val entries by remember { g.db.entries().byKind(EntryKind.Journal) }.collectAsState(null)
    val list = entries ?: return
    val zone = ZoneId.systemDefault()
    val byDay = list.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.toList().sortedByDescending { it.first }
    val prompt = prompts[LocalDate.now().dayOfYear % prompts.size]
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            val shape = RoundedCornerShape(8.dp)
            Column(Modifier.fillMaxWidth().background(c.fill, shape).border(1.dp, c.line, shape).padding(16.dp)) {
                Label("Today's prompt")
                Txt(prompt, LfmType.answer.copy(fontSize = LfmType.answer.fontSize * 1.1f, lineHeight = LfmType.answer.lineHeight * 1.0f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicButton({ onMic(EntryKind.Journal) }, listening = listening)
                    Box(Modifier.background(c.bg, RoundedCornerShape(6.dp))) {
                        Btn("Write", { nav.entrySheet = EntrySheet(kind = EntryKind.Journal, text = "") }, icon = "edit", small = true)
                    }
                    Txt("or tap the mic to speak", LfmType.label.copy(fontWeight = null), c.text2)
                }
            }
        }
        if (list.isEmpty()) item { Empty("No entries yet", "A line a day is enough. Only you can read this; it never leaves the phone.") }
        byDay.forEach { (day, items) ->
            item(key = "d-$day") {
                Column(Modifier.padding(top = 18.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        Txt(day.dayOfMonth.toString(), LfmType.bigger)
                        Label(dayName(day))
                    }
                    com.lfmpixel.lfm.ui.components.Divider()
                }
            }
            items(items, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { nav.entrySheet = EntrySheet(e.id, e.kind) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val dot = Modifier.padding(top = 6.dp).size(9.dp)
                    when (e.mood) {
                        1 -> Box(dot.background(c.text, RoundedCornerShape(2.dp)))
                        2 -> Box(dot.border(1.dp, c.text2, RoundedCornerShape(2.dp)))
                        else -> Box(dot)
                    }
                    Txt(e.body.ifBlank { e.title }, LfmType.body)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LIcon("lock", size = 16.dp, tint = c.text2)
                Txt("  Kept only on this phone, never backed up", LfmType.meta, c.text2)
            }
        }
    }
}

@Composable
fun Empty(title: String, body: String) {
    val c = Lfm.c
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Txt(title, LfmType.rowTitle)
        Txt(body, LfmType.small, c.text2)
    }
}

fun shortDay(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val days = ChronoUnit.DAYS.between(d.toLocalDate(), LocalDate.now())
    return when {
        days == 0L -> d.format(DateTimeFormatter.ofPattern("HH:mm"))
        days < 7 -> d.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH))
        else -> d.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
    }
}

fun dayName(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
}
