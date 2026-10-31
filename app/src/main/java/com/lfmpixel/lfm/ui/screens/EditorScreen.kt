package com.lfmpixel.lfm.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.lfmpixel.lfm.ui.components.Header
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.InputLine
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Tabs
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One draft in the editor. Saving is serialised, so the half-second autosave and the save on leaving never make two
 * entries; a new entry is created on its first character and removed if it is left empty.
 */
class Draft(private val g: AppGraph, var id: Long?, kind: EntryKind) {
    var entry by mutableStateOf(Entry(kind = kind, title = ""))
    var text by mutableStateOf(TextFieldValue(""))
    var extra by mutableStateOf("")      // tags (note), topic (idea), list (to-do)
    // Each change bumps [version]; a save records the version it wrote. Nothing is written unless something changed.
    private var version by mutableStateOf(0)
    private var savedVersion by mutableStateOf(0)
    val saved get() = version == savedVersion
    private val lock = Mutex()

    /** Body text as the owner wrote it, rebuilt from title and body the way they were stored. */
    fun load(e: Entry) {
        entry = e
        val full = when {
            e.body.isBlank() -> e.title
            e.kind == EntryKind.Note && bodyIsWhole(e.title, e.body) -> e.body
            e.kind == EntryKind.Note -> e.title + "\n" + e.body
            else -> e.body
        }
        text = TextFieldValue(full, TextRange(full.length))
        extra = when (e.kind) { EntryKind.Idea -> e.topic; EntryKind.Todo -> e.list; else -> e.tags }
    }

    fun changed() { version++ }

    /** Moves to another kind, keeping the tags, topic or list typed so far. */
    fun switchKind(k: EntryKind) {
        val e = foldExtra(entry)
        extra = when (k) { EntryKind.Idea -> e.topic; EntryKind.Todo -> e.list; EntryKind.Note -> e.tags; else -> "" }
        entry = e.copy(kind = k)
        changed()
    }

    private fun foldExtra(e: Entry) = when (e.kind) {
        EntryKind.Note -> e.copy(tags = extra.trim().lowercase())
        EntryKind.Idea -> e.copy(topic = extra.trim())
        EntryKind.Todo -> e.copy(list = extra.trim())
        else -> e
    }

    /**
     * Writes the draft if it changed. Leaving an entry that was emptied deletes it and returns what it was, for Undo.
     * Runs to the end even if its caller is cancelled, so a first insert is never done twice.
     */
    suspend fun save(leaving: Boolean = false): Entry? = withContext(NonCancellable) {
        lock.withLock {
            val at = version
            if (at == savedVersion) return@withLock null
            val body = text.text.trim()
            val current = id
            if (body.isEmpty()) {
                // While typing, an emptied entry is kept until the owner leaves; then it goes, with Undo.
                if (!leaving) return@withLock null
                val old = current?.let { g.db.entries().get(it) }
                if (current != null) g.db.entries().delete(current)
                id = null
                savedVersion = at
                return@withLock old
            }
            val e = toEntry(body)
            if (current == null) id = g.db.entries().insert(e.copy(id = 0)) else g.db.entries().update(e.copy(id = current))
            savedVersion = at
            null
        }
    }

    /** Deletes the entry (saving the latest text first, so Undo brings back exactly what was there). */
    suspend fun discard(): Entry? = withContext(NonCancellable) { lock.withLock {
        val body = text.text.trim()
        var current = id
        if (body.isNotEmpty()) toEntry(body).let { e -> if (current == null) current = g.db.entries().insert(e.copy(id = 0)) else g.db.entries().update(e.copy(id = current!!)) }
        val old = current?.let { g.db.entries().get(it) }
        current?.let { g.db.entries().delete(it) }
        id = null
        text = TextFieldValue("")
        savedVersion = version
        old
    } }

    private fun toEntry(body: String): Entry {
        val k = entry.kind
        val first = body.lineSequence().first().trim()
        // A first line too long for a title is shortened with "…" there, and the whole text kept in the body.
        val long = first.length > 120
        return foldExtra(entry).copy(
            title = if (long) first.take(119).trimEnd() + "…" else first,
            body = when {
                long -> body
                // Title plus the rest, unless the rest would read back as the whole text: then keep it all.
                k == EntryKind.Note -> body.lineSequence().drop(1).joinToString("\n").trim().let { rest -> if (bodyIsWhole(first, rest)) body else rest }
                body.contains('\n') -> body
                else -> ""
            },
            updatedAt = System.currentTimeMillis(),
        )
    }
}

/** Notes from the assistant keep the whole text in the body, its first line as the title (shortened with "…"). */
private fun bodyIsWhole(title: String, body: String) = body.isNotEmpty() && body.lineSequence().first().let { first ->
    first.equals(title, ignoreCase = true) || (title.endsWith("…") && first.startsWith(title.removeSuffix("…"), ignoreCase = true))
}

private val kinds = listOf(EntryKind.Note, EntryKind.Idea, EntryKind.Journal, EntryKind.Todo)

/** Full-screen editor for all four kinds (ux-spec section 5): no Save button; Back, leaving the app and a pause save. */
@Composable
fun EditorScreen(g: AppGraph, nav: Nav, route: Route.Editor) {
    val c = Lfm.c
    // Keyed by when the editor was opened, not by the route: a new entry's route takes its id once saved (below),
    // and the same draft carries on.
    val draft = remember(route.opened) { Draft(g, route.id, route.kind) }
    var loaded by remember(route.opened) { mutableStateOf(route.id == null) }
    val focus = remember { FocusRequester() }
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current

    LaunchedEffect(route.opened) {
        route.id?.let { id -> g.db.entries().get(id)?.let { draft.load(it) } ?: nav.back() }
        loaded = true
        if (route.id == null) runCatching { focus.requestFocus() }
    }
    // Autosave half a second after the last change.
    LaunchedEffect(draft.text.text, draft.extra, draft.entry) {
        if (!loaded || draft.saved) return@LaunchedEffect
        delay(500)
        g.scope.launch {
            draft.save()
            // A new entry's editor becomes that entry's, so coming back to it (after a lock, say) opens the entry.
            val id = draft.id
            if (route.id == null && id != null && nav.current === route) nav.replace(route.copy(id = id))
        }
    }
    // Leaving the screen (Back, a tab, a swipe) or the app saves at once, in the app's scope so it outlives the screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(draft) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP && loaded) g.scope.launch { draft.save() } }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (loaded) g.scope.launch { draft.save(leaving = true)?.let { old -> nav.show("Empty ${old.kind.label.lowercase()} deleted") { g.db.entries().insert(old) } } }
        }
    }

    val e = draft.entry
    // A journal entry behind the lock (Journal only) shows only once unlocked: whatever route it came by, and also
    // when it was written here and the lock closed meanwhile. A new, unsaved line can always be written.
    if (e.kind == EntryKind.Journal && draft.id != null && journalLocked(g)) {
        Column(Modifier.fillMaxSize()) {
            Header("Journal", onBack = { nav.back() })
            Locked(g, "This entry is locked", "Unlock with your fingerprint, face or screen lock to read it.", "Unlock your journal", ask = true)
        }
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButtonPlain("back", { nav.back() }, desc = "Back")
            Txt(if (draft.saved) (if (draft.id == null) "" else "Saved") else "Saving…", LfmType.meta, c.text2, Modifier.weight(1f).padding(start = 4.dp))
            IconButtonPlain("pin", { draft.entry = e.copy(pinned = !e.pinned); draft.changed() }, tint = if (e.pinned) c.text else c.text3,
                desc = if (e.pinned) "Unpin" else "Pin")
            IconButtonPlain("trash", {
                g.scope.launch { draft.discard()?.let { old -> nav.show("${old.kind.label} deleted") { g.db.entries().insert(old) } } }
                nav.back()
            }, desc = "Delete")
        }
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Tabs(kinds.map { it.label }, e.kind.label, { l ->
                val k = kinds.first { it.label == l }
                if (k != e.kind) draft.switchKind(k)
            }, Modifier.padding(top = 0.dp))
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (loaded) BasicTextField(draft.text, { v ->
                    // A tap or a cursor move is not a change; only the text is.
                    val edited = v.text != draft.text.text
                    draft.text = v
                    if (edited) draft.changed()
                },
                textStyle = (if (e.kind == EntryKind.Todo) LfmType.answer else LfmType.bodyLarge).copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 14.dp, bottom = 8.dp).focusRequester(focus)
                    .semantics { contentDescription = "${e.kind.label} text" },
                decorationBox = { inner ->
                    Box {
                        if (draft.text.text.isEmpty()) Txt(placeholder(e.kind), (if (e.kind == EntryKind.Todo) LfmType.answer else LfmType.bodyLarge), c.text3)
                        inner()
                    }
                })
            when (e.kind) {
                EntryKind.Todo -> {
                    Label("Due", Modifier.padding(top = 8.dp))
                    val today = LocalDate.now()
                    val dueDate = e.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                    val picked = when (dueDate) {
                        null -> "No date"; today -> "Today"; today.plusDays(1) -> "Tomorrow"; today.plusWeeks(1) -> "Next week"
                        else -> dueDate.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                    }
                    val options = listOf("Today", "Tomorrow", "Next week", "No date", if (picked !in setOf("Today", "Tomorrow", "Next week", "No date")) picked else "Pick…")
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Tabs(options, picked, { o ->
                            // A time set before (by voice or chat) is kept when only the day changes.
                            val time = e.dueAt?.takeIf { e.dueHasTime }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
                            fun set(d: LocalDate?) {
                                draft.entry = e.copy(dueAt = d?.atTime(time ?: java.time.LocalTime.of(9, 0))?.atZone(zone)?.toInstant()?.toEpochMilli(),
                                    dueHasTime = d != null && time != null); draft.changed()
                            }
                            when (o) {
                                "Today" -> set(today); "Tomorrow" -> set(today.plusDays(1)); "Next week" -> set(today.plusWeeks(1)); "No date" -> set(null)
                                else -> {
                                    val d = dueDate ?: today
                                    DatePickerDialog(ctx, { _, y, m, day -> set(LocalDate.of(y, m + 1, day)) }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                                }
                            }
                        })
                    }
                    Label("List")
                    InputLine(draft.extra, { draft.extra = it; draft.changed() }, "Add to a list, like Shopping", "list", Modifier.padding(top = 6.dp), imeAction = ImeAction.Done)
                }
                EntryKind.Idea -> {
                    Label("Topic", Modifier.padding(top = 8.dp, bottom = 6.dp))
                    InputLine(draft.extra, { draft.extra = it; draft.changed() }, "Group it under a topic, like Side projects", "bulb", imeAction = ImeAction.Done)
                }
                EntryKind.Note -> {
                    Label("Tags", Modifier.padding(top = 8.dp, bottom = 6.dp))
                    InputLine(draft.extra, { draft.extra = it; draft.changed() }, "Add tags, like work or home", "list", imeAction = ImeAction.Done)
                }
                EntryKind.Journal -> {
                    Label("Mood", Modifier.padding(top = 8.dp))
                    val moods = listOf("None", "Good", "Low")
                    Tabs(moods, moods[e.mood.coerceIn(0, 2)], { m -> draft.entry = e.copy(mood = moods.indexOf(m)); draft.changed() })
                }
            }
            Spacer(Modifier.height(24.dp))
            if (draft.id != null) Txt("Created " + Instant.ofEpochMilli(e.createdAt).atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)) +
                (if (e.source == "voice") " · from voice" else if (e.source == "chat") " · from chat" else ""), LfmType.meta, c.text2)
            Spacer(Modifier.width(1.dp).height(24.dp))
        }
    }
}

private fun placeholder(k: EntryKind) = when (k) {
    EntryKind.Note -> "Note. The first line is its title."
    EntryKind.Idea -> "An idea, in a line or two"
    EntryKind.Journal -> "How was today?"
    EntryKind.Todo -> "What needs doing?"
}
