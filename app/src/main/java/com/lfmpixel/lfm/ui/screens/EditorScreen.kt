package com.lfmpixel.lfm.ui.screens

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.delay
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
    var saved by mutableStateOf(true)
    private val lock = Mutex()

    /** Body text as the owner wrote it, rebuilt from title and body the way they were stored. */
    fun load(e: Entry) {
        entry = e
        val full = when {
            e.body.isBlank() -> e.title
            e.kind == EntryKind.Note && e.body.lineSequence().first().let { first ->
                first.equals(e.title, ignoreCase = true) || (e.title.endsWith("…") && first.startsWith(e.title.removeSuffix("…"), ignoreCase = true))
            } -> e.body
            e.kind == EntryKind.Note -> e.title + "\n" + e.body
            else -> e.body
        }
        text = TextFieldValue(full, TextRange(full.length))
        extra = when (e.kind) { EntryKind.Idea -> e.topic; EntryKind.Todo -> e.list; else -> e.tags }
    }

    fun changed() { saved = false }

    suspend fun save(leaving: Boolean = false) = lock.withLock {
        val body = text.text.trim()
        val current = id
        if (body.isEmpty()) {
            // Left empty: no blank entries. While typing, an emptied entry is kept until the owner leaves.
            if (leaving && current != null) g.db.entries().delete(current)
            if (leaving) id = null
            saved = true
            return@withLock
        }
        val e = toEntry(body)
        if (current == null) id = g.db.entries().insert(e.copy(id = 0)) else g.db.entries().update(e.copy(id = current))
        saved = true
    }

    /** Deletes the entry (saving the latest text first, so Undo brings back exactly what was there). */
    suspend fun discard(): Entry? = lock.withLock {
        val body = text.text.trim()
        var current = id
        if (body.isNotEmpty()) toEntry(body).let { e -> if (current == null) current = g.db.entries().insert(e.copy(id = 0)) else g.db.entries().update(e.copy(id = current!!)) }
        val old = current?.let { g.db.entries().get(it) }
        current?.let { g.db.entries().delete(it) }
        id = null
        text = TextFieldValue("")
        saved = true
        old
    }

    private fun toEntry(body: String): Entry {
        val k = entry.kind
        val first = body.lineSequence().first().trim()
        return entry.copy(
            title = first.take(120),
            body = if (k == EntryKind.Note) body.lineSequence().drop(1).joinToString("\n").trim() else if (body.length > 120 || body.contains('\n')) body else "",
            tags = if (k == EntryKind.Note) extra.trim().lowercase() else entry.tags,
            topic = if (k == EntryKind.Idea) extra.trim() else entry.topic,
            list = if (k == EntryKind.Todo) extra.trim() else entry.list,
            updatedAt = System.currentTimeMillis(),
        )
    }
}

private val kinds = listOf(EntryKind.Note, EntryKind.Idea, EntryKind.Journal, EntryKind.Todo)

/** Full-screen editor for all four kinds (ux-spec section 5): no Save button; Back, leaving the app and a pause save. */
@Composable
fun EditorScreen(g: AppGraph, nav: Nav, route: Route.Editor) {
    val c = Lfm.c
    val draft = remember(route) { Draft(g, route.id, route.kind) }
    var loaded by remember(route) { mutableStateOf(route.id == null) }
    val focus = remember { FocusRequester() }
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current

    LaunchedEffect(route) {
        route.id?.let { id -> g.db.entries().get(id)?.let { draft.load(it) } ?: nav.back() }
        loaded = true
        if (route.id == null) runCatching { focus.requestFocus() }
    }
    // Autosave half a second after the last change.
    LaunchedEffect(draft.text.text, draft.extra, draft.entry) {
        if (!loaded || draft.saved) return@LaunchedEffect
        delay(500)
        draft.save()
    }
    // Leaving the screen (Back, a tab, a swipe) or the app saves at once, in the app's scope so it outlives the screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(draft) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP && loaded) g.scope.launch { draft.save() } }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            if (loaded) g.scope.launch { draft.save(leaving = true) }
        }
    }

    val e = draft.entry
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
                if (k != e.kind) {
                    // Moving between kinds keeps the text; the extra field means something else per kind.
                    draft.extra = when (k) { EntryKind.Idea -> e.topic; EntryKind.Todo -> e.list; EntryKind.Note -> e.tags; else -> "" }
                    draft.entry = e.copy(kind = k)
                    draft.changed()
                }
            }, Modifier.padding(top = 0.dp))
        }
        Divider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (loaded) BasicTextField(draft.text, { draft.text = it; draft.changed() },
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
                            fun set(d: LocalDate?) {
                                draft.entry = e.copy(dueAt = d?.atTime(9, 0)?.atZone(zone)?.toInstant()?.toEpochMilli(), dueHasTime = false); draft.changed()
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
