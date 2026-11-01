package com.lfmpixel.lfm.ui.screens

import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextLayoutResult
import com.lfmpixel.lfm.ui.theme.LfmMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
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
import androidx.compose.runtime.collectAsState
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

    /** The entry as it was opened: saving it with another kind or list is a correction the memory keeps. */
    private var opened: Entry? = null

    /** Body text as the owner wrote it, rebuilt from title and body the way they were stored. */

    fun load(e: Entry, caret: IntArray = intArrayOf()) {
        entry = e
        if (opened == null) opened = e
        val full = when {
            e.body.isBlank() -> e.title
            e.kind == EntryKind.Note && bodyIsWhole(e.title, e.body) -> e.body
            e.kind == EntryKind.Note -> e.title + "\n" + e.body
            else -> e.body
        }
        // The caret goes back where it was when this editor was last on screen, else to the end.
        text = TextFieldValue(full, if (caret.size == 2 && caret.all { it in 0..full.length }) TextRange(caret[0], caret[1]) else TextRange(full.length))
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
            opened?.let { o -> learnFrom(o, e) }
            null
        }
    }

    /**
     * The owner moved an entry that was sorted for them (from the Ink line, chat or voice) to another kind, or a to-do
     * to another list: the memory keeps it, so the next one like it is sorted that way. Compared with the entry as it
     * was opened at every save (the half-second autosave writes most changes); a later save of the same line replaces
     * the correction, so typing a list name letter by letter leaves only the last.
     */
    private suspend fun learnFrom(o: Entry, e: Entry) {
        if (o.source == "share") return
        val text = e.title
        if (o.kind != e.kind) g.memory.corrected(com.lfmpixel.lfm.memory.CorrectionKind.Kind, text, o.kind.name, e.kind.name)
        if (o.kind == EntryKind.Todo && e.kind == EntryKind.Todo && !o.list.equals(e.list, true) && e.list.isNotBlank())
            g.memory.corrected(com.lfmpixel.lfm.memory.CorrectionKind.List, text, o.list, e.list)
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

/** Full-screen editor for all four kinds (ux-spec-v1 section 5): no Save button; Back, leaving the app and a pause save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(g: AppGraph, nav: Nav, route: Route.Editor) {
    val c = Lfm.c
    // Keyed by when the editor was opened, not by the route: a new entry's route takes its id once saved (below),
    // and the same draft carries on.
    val draft = remember(route.opened) { Draft(g, route.id, route.kind) }
    // For the journal lock: whether this was an existing entry, and how often the lock had closed when it opened.
    val startedWith = remember(route.opened) { route.id?.takeIf { route.kind == EntryKind.Journal } }
    val closingsAtStart = remember(route.opened) { g.lock.closings.value }
    val closings by g.lock.closings.collectAsState()
    var loaded by remember(route.opened) { mutableStateOf(route.id == null) }
    val focus = remember { FocusRequester() }
    val zone = ZoneId.systemDefault()
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val page = remember { KeyboardPage() }
    var writing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    // The caret and the place on the page outlive the screen leaving composition (a lock, say), and are put back once
    // the entry is loaded again; the scroll alone would be cut to the empty page's height before the text is in.
    var caretAt by rememberSaveable(route.opened) { mutableStateOf(intArrayOf()) }
    var pageAt by rememberSaveable(route.opened) { mutableIntStateOf(0) }
    KeepPlaceThroughKeyboard(scroll, page) { draft.text.selection.end.takeIf { writing } }
    // Ink in the margin (notebook-spec proposal 3): one answer at a time, stopped when the editor goes.
    val margin = remember(route.opened) { MarginRun(g) }
    var inkSheet by remember { mutableStateOf(false) }
    var stepsSheet by remember { mutableStateOf(false) }
    val marginView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    DisposableEffect(margin) { onDispose { margin.stop() } }
    LaunchedEffect(margin.ask) { if (margin.ask != null) { delay(LfmMotion.LONG.toLong()); runCatching { marginView.bringIntoView() } } }

    LaunchedEffect(route.opened) {
        route.id?.let { id -> g.db.entries().get(id)?.let { draft.load(it, caretAt) } ?: nav.back() }
        loaded = true
        if (route.id == null) runCatching { focus.requestFocus() }
        if (pageAt > 0) withTimeoutOrNull(1000) { snapshotFlow { scroll.maxValue }.first { it >= pageAt } }?.let { scroll.scrollTo(pageAt) }
        snapshotFlow { scroll.value }.collect { pageAt = it }
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
    // A journal entry behind the lock (Journal only) shows only once unlocked: an existing one opened while locked,
    // or one being written when the lock closed (a minute away). A new line can always be written, locked or not.
    if (e.kind == EntryKind.Journal && draft.id != null && journalLocked(g) && (startedWith != null || closings != closingsAtStart)) {
        Column(Modifier.fillMaxSize()) {
            Header("Journal", onBack = { nav.back() })
            Locked(g, "This entry is locked", "Unlock with your fingerprint, face or screen lock to read it.", "Unlock your journal", ask = true)
        }
        return
    }
    // The page sits above the keyboard (edge to edge, so the window itself never resizes): the header stays put,
    // only the page's height follows the keyboard, frame by frame, and the caret stays in view.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButtonPlain("back", { nav.back() }, desc = "Back")
            Txt(if (draft.saved) (if (draft.id == null) "" else "Saved") else "Saving…", LfmType.meta, c.text2, Modifier.weight(1f).padding(start = 4.dp))
            if (draft.text.text.isNotBlank()) IconButtonPlain("ink", { keyboard?.hide(); inkSheet = true }, tint = c.text, desc = "Ink in the margin")
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
        Column(Modifier.weight(1f).onPlaced { page.viewport = it }.then(with(page) { Modifier.watchFingers(scroll) }).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            if (loaded) BasicTextField(draft.text, { v ->
                    // A tap or a cursor move is not a change; only the text is.
                    val edited = v.text != draft.text.text
                    draft.text = v
                    caretAt = intArrayOf(v.selection.start, v.selection.end)
                    if (edited) draft.changed()
                },
                textStyle = (if (e.kind == EntryKind.Todo) LfmType.answer else LfmType.bodyLarge).copy(color = c.text),
                cursorBrush = SolidColor(c.text),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                onTextLayout = { page.layout = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).padding(top = 14.dp, bottom = 8.dp).onPlaced { page.field = it }
                    .focusRequester(focus).onFocusChanged { writing = it.isFocused }
                    .semantics { contentDescription = "${e.kind.label} text" },
                decorationBox = { inner ->
                    Box {
                        if (draft.text.text.isEmpty()) Txt(placeholder(e.kind), (if (e.kind == EntryKind.Todo) LfmType.answer else LfmType.bodyLarge), c.text3)
                        inner()
                    }
                })
            androidx.compose.animation.AnimatedVisibility(margin.ask != null, enter = LfmMotion.appear() + expandVertically(LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate)),
                exit = LfmMotion.leave() + shrinkVertically(LfmMotion.spec(LfmMotion.SHORT, LfmMotion.emphasizedAccelerate))) {
                MarginBlock(margin, nav, onAddToNote = { add ->
                    val old = draft.text.text.trimEnd()
                    val next = if (old.isEmpty()) add else old + "\n\n" + add
                    draft.text = TextFieldValue(next, TextRange(next.length))
                    draft.changed()
                }, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp).bringIntoViewRequester(marginView))
            }
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
                    // The organizer (notebook-spec A.1): how long it takes, and its steps.
                    if (e.parentId == null) {
                        Label("About how long", Modifier.padding(top = 14.dp, bottom = 6.dp))
                        SizeChips(e.minutes, com.lfmpixel.lfm.assistant.Sizes.guess(draft.text.text)) { m -> draft.entry = e.copy(minutes = m); draft.changed() }
                        draft.id?.let { id ->
                            Label("Steps", Modifier.padding(top = 14.dp, bottom = 2.dp))
                            StepsList(g, nav, e.copy(id = id)) { keyboard?.hide(); g.scope.launch { draft.save(); stepsSheet = true } }
                        }
                    }
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
        // While writing, a slim bar rides on the keyboard: how long the text is, and Done to put the keyboard away
        // (the caret stays where it was, so tapping the text carries on there).
        val keyboardUp = WindowInsets.isImeVisible
        // Its height comes and goes smoothly too, so the page's edge never jumps (ux-spec-v1 section 7: appear, leave).
        androidx.compose.animation.AnimatedVisibility(keyboardUp && writing,
            enter = fadeIn(LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate)) + expandVertically(LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate)),
            exit = fadeOut(LfmMotion.spec(LfmMotion.SHORT, LfmMotion.emphasizedAccelerate)) + shrinkVertically(LfmMotion.spec(LfmMotion.SHORT, LfmMotion.emphasizedAccelerate))) {
            Column {
                Divider()
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    val words by remember { derivedStateOf { wordCount(draft.text.text) } }
                    Txt(if (words == 1) "1 word" else "$words words", LfmType.meta, c.text2, Modifier.weight(1f))
                    Box(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Hide the keyboard") { keyboard?.hide() }
                        .padding(horizontal = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Txt("Done", LfmType.button, c.text) }
                }
            }
        }
    }
    if (stepsSheet) draft.id?.let { id -> StepsSheet(g, nav, draft.entry.copy(id = id, title = draft.text.text.lineSequence().firstOrNull()?.take(120) ?: draft.entry.title)) { stepsSheet = false } }
    if (inkSheet) MarginSheet(g, nav, onPick = { what, q -> inkSheet = false; margin.start(draft.text.text, what, q) }, onClose = { inkSheet = false })
}

/** Words in the text, as the keyboard bar counts them. */
internal fun wordCount(text: String) = text.split(Regex("\\s+")).count { it.isNotEmpty() }

/** Where the editor's page and caret are, to put the page back when the keyboard goes. */
private class KeyboardPage {
    var viewport: LayoutCoordinates? = null
    var field: LayoutCoordinates? = null
    var layout: TextLayoutResult? = null
    /** The page's place when a finger last went down with the keyboard away: where it goes back to. */
    var resting = 0
    var keyboardUp = false
    /** Set when the owner scrolls the page by hand while the keyboard is up: then the page stays where they left it. */
    var dragged = false

    /**
     * Watches fingers on the page without taking them. A tap that brings the keyboard notes the place first: the text
     * field scrolls the caret into view before the keyboard even starts to rise, so the place can't be read later.
     */
    fun Modifier.watchFingers(scroll: ScrollState) = pointerInput(scroll) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!keyboardUp) resting = scroll.value
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) break
                if (keyboardUp && (c.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
            }
        }
    }

    /** The caret's top and bottom on the page (0 at the top of the scrolled content), once laid out. */
    fun caret(offset: Int, scrolled: Int): Pair<Float, Float>? {
        val v = viewport?.takeIf { it.isAttached } ?: return null
        val f = field?.takeIf { it.isAttached } ?: return null
        val l = layout ?: return null
        val r = l.getCursorRect(offset.coerceIn(0, l.layoutInput.text.length))
        val top = v.localPositionOf(f, Offset(0f, r.top)).y + scrolled
        return top to top + r.height
    }
}

/**
 * The keyboard coming up shrinks the page and scrolls the caret into view; going down, the page glides back to where
 * it was before the tap, with the keyboard, frame by frame. Only while the caret would still show there and the owner did
 * not scroll by hand meanwhile; otherwise it stays where it is (the caret line keeps its place on screen). [caret] is
 * the text's caret, or null while another field has the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepPlaceThroughKeyboard(scroll: ScrollState, page: KeyboardPage, caret: () -> Int?) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    val goal = WindowInsets.imeAnimationTarget
    val bars = WindowInsets.navigationBars
    val caretNow by rememberUpdatedState(caret)
    LaunchedEffect(scroll, page) {
        var from = -1               // closing: the place it started from, or -2 when the page stays
        var fromIme = 0
        var lastIme = 0
        fun keeps(imeHeight: Int): Boolean {
            if (page.dragged) return false
            // Another field (tags, list, topic) brought the keyboard: it was in view at the place it goes back to.
            val offset = caretNow() ?: return true
            val (top, bottom) = page.caret(offset, scroll.value) ?: return false
            val v = page.viewport ?: return false
            val closed = v.size.height + (imeHeight - bars.getBottom(density)).coerceAtLeast(0)
            return top >= page.resting && bottom <= page.resting + closed
        }
        snapshotFlow { ime.getBottom(density) to goal.getBottom(density) }.collect { (now, target) ->
            when {
                target == 0 && page.keyboardUp -> {
                    val value = scroll.value
                    if (from == -1) {
                        fromIme = maxOf(lastIme, now)
                        from = if (keeps(fromIme)) value else -2
                    }
                    if (from >= 0) {
                        val to = if (fromIme == 0) page.resting else page.resting + ((from - page.resting) * now / fromIme.toFloat()).roundToInt()
                        if (to != value) scroll.scrollTo(to)
                    }
                    if (now == 0) { page.keyboardUp = false; page.dragged = false; from = -1 }
                }
                target > 0 || now > 0 -> { page.keyboardUp = true; from = -1 }
            }
            lastIme = now
        }
    }
}

private fun placeholder(k: EntryKind) = when (k) {
    EntryKind.Note -> "Note. The first line is its title."
    EntryKind.Idea -> "An idea, in a line or two"
    EntryKind.Journal -> "How was today?"
    EntryKind.Todo -> "What needs doing?"
}
