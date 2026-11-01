package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Fab
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.InputLine
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.SwipeAction
import com.lfmpixel.lfm.ui.components.SwipeRow
import com.lfmpixel.lfm.ui.components.Tabs
import com.lfmpixel.lfm.ui.components.Tag
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The Library's filter chips; [kind] is what + and the mic make there. */
enum class LibFilter(val label: String, val kind: EntryKind) {
    All("All", EntryKind.Note), Notes("Notes", EntryKind.Note), Ideas("Ideas", EntryKind.Idea),
    Journal("Journal", EntryKind.Journal), Reading("Reading", EntryKind.Note), Pinned("Pinned", EntryKind.Note)
}

/** Library actions shared by swipes, the long-press menu and TalkBack: each one says what it did and can be undone. */
class EntryActions(private val g: AppGraph, private val nav: Nav) {
    fun pin(e: Entry) = g.scope.launch {
        g.db.entries().update(e.copy(pinned = !e.pinned))
        nav.show(if (e.pinned) "Unpinned" else "Pinned") { g.db.entries().update(e) }
    }

    fun delete(e: Entry) = g.scope.launch {
        // A to-do's steps go with it, and come back with Undo.
        val steps = if (e.kind == EntryKind.Todo) g.db.entries().stepsNow(e.id) else emptyList()
        g.db.entries().deleteSteps(e.id)
        g.db.entries().delete(e.id)
        nav.show("${e.kind.label} deleted") { g.db.entries().insert(e); steps.forEach { g.db.entries().insert(it) } }
    }

    fun move(e: Entry, to: EntryKind) = g.scope.launch {
        g.db.entries().update(e.copy(kind = to, updatedAt = System.currentTimeMillis()))
        nav.show("Moved to ${to.place()}") { g.db.entries().update(e) }
    }

    fun done(e: Entry, done: Boolean = !e.done) = g.scope.launch {
        g.db.entries().update(e.copy(done = done, doneAt = if (done) System.currentTimeMillis() else null))
        nav.show(if (done) "Done: ${e.title}" else "Not done: ${e.title}") { g.db.entries().update(e) }
    }
}

@Composable
fun LibraryScreen(g: AppGraph, nav: Nav, filter: LibFilter, onMic: (EntryKind) -> Unit, listening: Boolean) {
    val c = Lfm.c
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Entry>?>(null) }
    val all by remember { g.db.entries().library() }.collectAsState(null)
    val todos by remember { g.db.entries().todos() }.collectAsState(null)
    // Search again whenever entries change, so a swipe in the results shows at once. Keyword hits show at once; once
    // typing pauses, matches by meaning join them (SemanticIndex).
    LaunchedEffect(query, all, todos) {
        results = FtsQuery.from(query)?.let { g.db.entries().search(it) }
        if (results != null) { kotlinx.coroutines.delay(300); results = g.semantic.search(query) }
    }
    val actions = remember { EntryActions(g, nav) }
    val open: (Entry) -> Unit = { nav.go(Route.Editor(it.id, it.kind)) }
    // Journal only lock: its entries are left out of every list and search until it is unlocked.
    val hideJournal = journalLocked(g)
    fun List<Entry>.visible() = if (hideJournal) filter { it.kind != EntryKind.Journal } else this

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Txt("Notebook", LfmType.title2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)
                .semantics { heading() })
            InputLine(query, { query = it }, "Search notes, ideas, quotes, journal, to-dos", "search", Modifier.padding(horizontal = 16.dp)) {
                if (query.isNotEmpty()) IconButtonPlain("x", { query = "" }, desc = "Clear search")
            }
            val r = results
            if (query.isNotBlank() && r != null) {
                SearchResults(r.visible(), query, open, actions, hiddenJournal = hideJournal)
                return@Column
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Tabs(LibFilter.entries.map { it.label }, filter.label, { l -> nav.go(Route.Library(LibFilter.entries.first { it.label == l })) })
            }
            val list = all?.visible() ?: return@Column
            when (filter) {
                LibFilter.Ideas -> IdeasList(g, nav, list.filter { it.kind == EntryKind.Idea }, open, actions)
                LibFilter.Journal -> if (hideJournal) Locked(g, "Your journal is locked", "Unlock with your fingerprint, face or screen lock to read it. A new line can be written from Today.",
                    "Unlock your journal")
                    else JournalList(list.filter { it.kind == EntryKind.Journal }, nav, onMic, listening, open, actions)
                else -> {
                    val shown = when (filter) {
                        LibFilter.Notes -> list.filter { it.kind == EntryKind.Note }
                        LibFilter.Pinned -> list.filter { it.pinned }
                        LibFilter.Reading -> list.filter { it.isQuote() || it.tags.split(' ').contains("reading") }
                        else -> list
                    }
                    val state = androidx.compose.foundation.lazy.rememberLazyListState()
                    val firstId = shown.firstOrNull()?.id
                    // A newly pinned or new entry goes to the top: show it if the list was at the top.
                    LaunchedEffect(firstId) { if (state.firstVisibleItemIndex <= 1) state.animateScrollToItem(0) }
                    LazyColumn(Modifier.fillMaxSize(), state = state) {
                        if (shown.isEmpty()) item {
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                when (filter) {
                                    LibFilter.Pinned -> Empty("Nothing pinned", "Swipe a note right to pin it; pinned ones stay on top.")
                                    LibFilter.Reading -> Empty("No quotes yet", "Select text in any app, tap Share, then Inkling: it lands on today's page as a highlight, and here.")
                                    else -> Empty("No notes yet", "Tap + to write, or the mic to talk. Notes, ideas and journal lines all land here.")
                                }
                            }
                        }
                        items(shown, key = { it.id }) { e -> EntryRow(e, showKind = filter != LibFilter.Notes, onClick = { open(e) }, actions = actions) }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp).padding(bottom = snackLift(nav)), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fab("plus", { nav.go(Route.Editor(kind = filter.kind)) }, "New ${filter.kind.label.lowercase()}")
            MicButton({ onMic(filter.kind) }, size = 56.dp, listening = listening)
        }
    }
}

@Composable
private fun SearchResults(found: List<Entry>, query: String, open: (Entry) -> Unit, actions: EntryActions, hiddenJournal: Boolean) {
    val c = Lfm.c
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Label((if (found.isEmpty()) "" else "${found.size} found") + (if (hiddenJournal) (if (found.isEmpty()) "" else " · ") + "the journal is locked and not searched" else ""),
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
        items(found, key = { it.id }) { e -> EntryRow(e, showKind = true, onClick = { open(e) }, actions = actions) }
        if (found.isEmpty()) item {
            Txt("Nothing matches “$query”. Search looks at whole words and word starts.", LfmType.small, c.text2, Modifier.padding(horizontal = 16.dp))
        }
    }
}

/**
 * One entry in a list: swipe right to pin (to-dos: done), left to delete; long-press for the same and "move to".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.EntryRow(e: Entry, showKind: Boolean, onClick: () -> Unit, actions: EntryActions, trailing: @Composable () -> Unit = {}) {
    val c = Lfm.c
    var menu by remember { mutableStateOf(false) }
    val reduced = LfmMotion.reduced()
    val right = if (e.kind == EntryKind.Todo) SwipeAction(if (e.done) "Not done" else "Done", "check") { actions.done(e) }
        else SwipeAction(if (e.pinned) "Unpin" else "Pin", "pin") { actions.pin(e) }
    Box(Modifier.animateItem(
        // Rows fade in where they land and fade out as the rest close up (ux-spec section 7).
        fadeInSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate),
        fadeOutSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.SHORT, easing = LfmMotion.emphasizedAccelerate),
        placementSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate))) {
        SwipeRow(right, SwipeAction("Delete", "trash") { actions.delete(e) }) {
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(horizontal = 16.dp)) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showKind || e.isQuote()) LIcon(if (e.isQuote()) "quote" else e.kind.icon(), size = 16.dp, tint = c.text2)
                    // A quote keeps its highlighter here too.
                    if (e.isQuote()) Marked(com.lfmpixel.lfm.assistant.Quotes.words(e), LfmType.body, Modifier.weight(1f), maxLines = 2)
                    else Txt(e.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.weight(1f), maxLines = 1,
                        decoration = if (e.done) TextDecoration.LineThrough else null)
                    if (e.pinned) LIcon("pin", size = 14.dp, tint = c.text2)
                    Txt(shortDay(e.createdAt), LfmType.meta, c.text2)
                }
                // A quote shows where it came from; its words are the title.
                val body = if (e.isQuote()) com.lfmpixel.lfm.assistant.Quotes.source(e).ifEmpty { null } else e.body.takeIf { it.isNotEmpty() && it != e.title }
                if (body != null) Txt(body, LfmType.small.copy(fontSize = LfmType.small.fontSize * 0.93f), c.text2, Modifier.padding(top = 3.dp), maxLines = 2)
                val tags = buildList {
                    if (e.voiceSeconds > 0) add("%d:%02d".format(e.voiceSeconds / 60, e.voiceSeconds % 60) to "wave")
                    else if (e.source == "voice") add("voice" to "wave")
                    if (e.kind == EntryKind.Idea && e.topic.isNotEmpty() && showKind) add(e.topic to null)
                    e.tags.split(' ').filter { it.isNotBlank() }.forEach { add(it to null) }
                    if (e.list.isNotEmpty()) add(e.list to null)
                }
                if (tags.isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { (t, i) -> Tag(t, i) } }
                trailing()
                Spacer(Modifier.height(12.dp))
                Divider()
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Txt(right.label) }, { menu = false; right.run() }, leadingIcon = { LIcon(right.icon, size = 18.dp) })
            EntryKind.entries.filter { it != e.kind }.forEach { k ->
                DropdownMenuItem({ Txt("Move to ${k.place()}") },
                    { menu = false; actions.move(e, k) }, leadingIcon = { LIcon(k.icon(), size = 18.dp) })
            }
            DropdownMenuItem({ Txt("Delete") }, { menu = false; actions.delete(e) }, leadingIcon = { LIcon("trash", size = 18.dp) })
        }
    }
}

@Composable
private fun IdeasList(g: AppGraph, nav: Nav, list: List<Entry>, open: (Entry) -> Unit, actions: EntryActions) {
    val groups = list.groupBy { it.topic.ifBlank { "Other" } }.toList()
        .sortedWith(compareByDescending<Pair<String, List<Entry>>> { (_, v) -> v.any { it.pinned } }.thenByDescending { (_, v) -> v.maxOf { it.createdAt } })
    LazyColumn(Modifier.fillMaxSize()) {
        if (list.isEmpty()) item { Box(Modifier.padding(horizontal = 16.dp)) { Empty("No ideas yet", "Tap + or say “idea:” and the thought; the model groups them by topic.") } }
        groups.forEach { (topic, items) ->
            item(key = "t-$topic") { Label(topic, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 0.dp)) }
            items(items, key = { it.id }) { e ->
                EntryRow(e, showKind = false, onClick = { open(e) }, actions = actions) {
                    Row(Modifier.padding(top = 6.dp)) {
                        Btn("Expand with the assistant", { g.assistant.send("Expand this idea into a few concrete next steps: ${e.title}"); nav.go(Route.Chat) },
                            small = true, icon = "chat")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

private val prompts = listOf(
    "What gave you energy this week?", "What are you putting off, and why?", "What went better than expected today?",
    "Who would you like to see more of?", "What did you learn today?", "What would make tomorrow easier?",
    "What's taking up space in your head?",
)

@Composable
private fun JournalList(list: List<Entry>, nav: Nav, onMic: (EntryKind) -> Unit, listening: Boolean, open: (Entry) -> Unit, actions: EntryActions) {
    val c = Lfm.c
    val zone = ZoneId.systemDefault()
    val byDay = list.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }.toList().sortedByDescending { it.first }
    val prompt = prompts[LocalDate.now().dayOfYear % prompts.size]
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val shape = RoundedCornerShape(8.dp)
            Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(c.fill, shape).border(1.dp, c.line, shape).padding(16.dp)) {
                Label("Today's prompt")
                Txt(prompt, LfmType.answer.copy(fontSize = LfmType.answer.fontSize * 1.1f), modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicButton({ onMic(EntryKind.Journal) }, listening = listening)
                    Box(Modifier.background(c.bg, RoundedCornerShape(6.dp))) {
                        Btn("Write", { nav.go(Route.Editor(kind = EntryKind.Journal)) }, icon = "edit", small = true)
                    }
                    Txt("or tap the mic to speak", LfmType.label.copy(fontWeight = null), c.text2)
                }
            }
        }
        if (list.isEmpty()) item { Box(Modifier.padding(horizontal = 16.dp)) { Empty("No entries yet", "A line a day is enough. Only you can read this; it never leaves the phone.") } }
        byDay.forEach { (day, items) ->
            item(key = "d-$day") {
                Row(Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(day.dayOfMonth.toString(), LfmType.bigger)
                    Label(dayName(day))
                }
            }
            items(items, key = { it.id }) { e ->
                EntryRow(e, showKind = false, onClick = { open(e) }, actions = actions) {
                    if (e.mood != 0) Txt(if (e.mood == 1) "Good day" else "Low day", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                LIcon("lock", size = 16.dp, tint = c.text2)
                Txt("  Kept only on this phone, never backed up", LfmType.meta, c.text2)
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** How far the floating buttons rise while the undo bar shows, so it never covers them. */
@Composable
fun snackLift(nav: Nav): androidx.compose.ui.unit.Dp =
    androidx.compose.animation.core.animateDpAsState(if (nav.snack != null) 64.dp else 0.dp, LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate), label = "lift").value

/** Where a kind lives, as a place name: "Notes", "Ideas", "Journal", "To-dos". */
fun EntryKind.place() = when (this) { EntryKind.Note -> "Notes"; EntryKind.Idea -> "Ideas"; EntryKind.Journal -> "Journal"; EntryKind.Todo -> "To-dos" }

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
