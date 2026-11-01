package com.lfmpixel.lfm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.SkyBand
import com.lfmpixel.lfm.ui.components.WashCard
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.assistant.DumpSort
import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.NextAction
import com.lfmpixel.lfm.assistant.Sizes
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Focus (notebook-spec A.1, proposal 5): the one thing, large, and a disc of highlighter that shrinks with the minutes.
 * When it runs out, one soft notification; Done walks on to the next step.
 */
@Composable
fun FocusScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val run by g.focus.run.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    // Time made visible stays visible: the screen stays on while Focus is open.
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val entry by remember(run?.id) { run?.id?.let { g.db.entries().watch(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val parent by remember(entry?.parentId) { entry?.parentId?.let { g.db.entries().watch(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val open by remember { g.db.entries().open() }.collectAsState(null)
    var finished by remember { mutableStateOf<Entry?>(null) }

    Column(Modifier.fillMaxSize()) {
    Header("Focus", { nav.back() }, leftIcon = "x", backDesc = "Close Focus (the timer keeps going)") {
        if (run != null) Box(Modifier.clip(LfmShape.control).clickable(role = androidx.compose.ui.semantics.Role.Button) { g.focus.stop(); nav.back() }.heightIn(min = 44.dp).padding(horizontal = 12.dp, vertical = 12.dp)) {
            Txt("Stop timer", LfmType.button, Lfm.c.text2)
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val r = run
        val done = finished
        if (r == null || done != null) {
            // Done: the next thing, offered, never pushed.
            val bounds = DayBounds()
            val next = open?.let { NextAction.pick(it.filter { e -> e.id != done?.id }, now, bounds.start, bounds.end, nav.skipped.toList()) }
            Spacer(Modifier.height(48.dp))
            Box(Modifier.size(96.dp).clip(LfmShape.round).background(c.highlight), contentAlignment = Alignment.Center) {
                LIcon("check", size = 44.dp, tint = c.onHighlight, stroke = 2.5f)
            }
            Txt(if (done != null) "Done: ${done.title}" else "No timer running", LfmType.header, c.text, Modifier.padding(top = 18.dp), textAlign = TextAlign.Center)
            if (next != null) {
                Txt("Next, if you like", LfmType.label, c.text2, Modifier.padding(top = 28.dp))
                Txt(next.entry.title, LfmType.answer, c.text, Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
                Txt("about ${Sizes.label(next.minutes)}", LfmType.small, c.text2)
                FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                    Btn("Start", { finished = null; g.focus.start(next.entry.id, next.entry.title, next.minutes) }, kind = BtnKind.Primary)
                    Btn("Back to today", { nav.back() })
                }
            } else {
                Txt("Nothing else is waiting. The rest is yours.", LfmType.voiceBody, c.text, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
                Btn("Back to today", { nav.back() }, Modifier.padding(top = 20.dp))
            }
            return@Column
        }
        val left = r.leftMs(now)
        val up = left == 0L
        parent?.let { Txt(it.title, LfmType.label, c.text2, Modifier.padding(top = 12.dp), maxLines = 1) }
        Txt(entry?.title ?: r.title, LfmType.title2, c.text,
            Modifier.padding(top = 6.dp).semantics { heading() }, textAlign = TextAlign.Center, maxLines = 4)
        // The disc: highlighter yellow for the time left, shrinking clockwise like a Time Timer.
        val frac = r.fraction(now)
        val reduced = LfmMotion.reduced()
        val shown by androidx.compose.animation.core.animateFloatAsState(frac,
            if (reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.LinearEasing), label = "disc")
        val mins = (left + 59_999) / 60_000
        Box(Modifier.padding(top = 24.dp).size(248.dp).semantics(mergeDescendants = true) {
            contentDescription = if (up) "Time's up" else if (left < 60_000) "${left / 1000} seconds left" else "$mins minutes left"
        }, contentAlignment = Alignment.Center) {
            val track = c.fill; val ink = c.highlight; val line = c.line
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(track)
                drawArc(ink, -90f, 360f * shown, useCenter = true, topLeft = Offset.Zero, size = Size(size.width, size.height))
                drawCircle(line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            }
            Box(Modifier.size(128.dp).clip(LfmShape.round).background(c.bg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(if (up) "0" else if (left < 60_000) "${left / 1000}" else "$mins", LfmType.timer, c.text)
                    Txt(if (up) "time's up" else if (left < 60_000) "sec left" else if (mins == 1L) "min left" else "min left", LfmType.meta, c.text2)
                }
            }
        }
        Txt(when {
            up -> "Done, or 5 more minutes? Both are fine."
            r.paused -> "Paused. Take the time you need."
            else -> "of ${r.totalMs / 60_000} min"
        }, LfmType.small, c.text2, Modifier.padding(top = 14.dp).semantics { liveRegion = LiveRegionMode.Polite }, textAlign = TextAlign.Center)
        FlowRow(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Done", {
                val e = entry ?: return@Btn
                Organize(g, nav).done(e)
                g.focus.stop()
                finished = e
            }, icon = "check", kind = BtnKind.Primary)
            Btn("+5 min", { g.focus.plus(5) }, icon = "plus")
            if (!up) Btn(if (r.paused) "Resume" else "Pause", { if (r.paused) g.focus.resume() else g.focus.pause() },
                icon = if (r.paused) "play" else "pause")
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** Saves [text] as a dump note (new when [id] is null); returns its id, or null when there was nothing to keep. */
suspend fun saveDump(g: AppGraph, id: Long?, text: String): Long? = withContext(NonCancellable) {
    val t = text.trim()
    val old = id?.let { g.db.entries().get(it) }
    if (t.isEmpty()) {
        if (old != null) g.db.entries().delete(old.id)
        return@withContext null
    }
    val title = t.lineSequence().first().let { if (it.length > 60) it.take(59).trimEnd() + "…" else it }
    if (old != null) {
        if (old.body != t) g.db.entries().update(old.copy(title = title, body = t, updatedAt = System.currentTimeMillis()))
        old.id
    } else g.db.entries().insert(Entry(kind = EntryKind.Note, title = title, body = t, tags = "dump", source = "dump"))
}

/**
 * Brain dump (proposal 3): one page for everything at once, kept as it is typed. Sort it splits it into small to-dos
 * with sizes, ideas and notes (the model, or the phone's rules), each kept or left out before Add.
 */
@Composable
fun DumpScreen(g: AppGraph, nav: Nav, route: Route.Dump, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    var text by rememberSaveable(route.opened) { mutableStateOf("") }
    var id by rememberSaveable(route.opened) { mutableStateOf(route.id) }
    var loaded by remember(route.opened) { mutableStateOf(route.id == null) }
    var saved by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(route.opened) {
        if (!loaded) { route.id?.let { g.db.entries().get(it) }?.let { if (text.isEmpty()) text = it.body.ifEmpty { it.title } }; loaded = true }
        if (route.id == null) runCatching { focus.requestFocus() }
    }
    LaunchedEffect(nav.heard) { nav.heard?.let { h -> text = (text.trimEnd() + "\n" + h).trim(); nav.heard = null } }
    // Saved half a second after typing stops, and on the way out.
    LaunchedEffect(text, loaded) {
        if (!loaded) return@LaunchedEffect
        saved = false
        delay(500)
        id = saveDump(g, id, text)
        saved = true
    }
    DisposableEffect(route.opened) { onDispose { if (loaded) { val t = text; val i = id; g.scope.launch { saveDump(g, i, t) } } } }

    val job = remember { OrgJob(g) }
    val items = remember { mutableStateListOf<DumpSort.Item>() }
    val keep = remember { mutableStateListOf<Boolean>() }
    var byRules by remember { mutableStateOf(false) }
    var sorted by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(false) }
    fun show(list: List<DumpSort.Item>) { items.clear(); items.addAll(list); keep.clear(); keep.addAll(list.map { true }); sorted = true }
    LaunchedEffect(job.finished) {
        if (!job.finished) return@LaunchedEffect
        val parsed = DumpSort.parse(job.text)
        byRules = parsed.isEmpty()
        show(parsed.ifEmpty { DumpSort.rules(text) })
    }
    DisposableEffect(job) { onDispose { job.stop() } }

    Column(Modifier.fillMaxSize()) {
        SkyBand {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButtonPlain("back", { nav.back() }, desc = "Back")
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Txt("Brain dump", LfmType.header, modifier = Modifier.semantics { heading() })
                    Txt(if (text.isBlank()) "Kept as you type" else if (saved) "Saved" else "Saving…", LfmType.meta, Lfm.c.text2)
                }
                MicButton(onMic, listening = listening)
                com.lfmpixel.lfm.ui.components.SunMark(Modifier.padding(start = 4.dp, end = 10.dp))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Box(Modifier.padding(top = 14.dp)) {
                if (text.isEmpty()) Txt("Everything on your mind, in any order. One thing per line, or just keep going. Sort it when you're ready.",
                    LfmType.bodyLarge, c.text3)
                BasicTextField(text, { text = it; sorted = false }, textStyle = LfmType.bodyLarge.copy(color = c.text), cursorBrush = SolidColor(c.text),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).focusRequester(focus).semantics { contentDescription = "Brain dump text" })
            }
            AnimatedVisibility(sorted || job.running, enter = LfmMotion.appear(), exit = LfmMotion.leave()) {
                Column(Modifier.padding(top = 12.dp, bottom = 16.dp)) {
                    Label(when {
                        job.running -> "Sorting on this phone…"
                        items.isEmpty() -> "Nothing to sort yet"
                        byRules && job.failed -> "No model could answer just now · sorted on this phone by simple rules"
                        byRules -> "Sorted on this phone by simple rules · keep what fits"
                        else -> "Sorted on this phone · keep what fits"
                    })
                    if (job.running) DumpSort.parse(job.text).forEach { DumpRow(it, true, null, null) }
                    // A tap acts only while row i is still this item: a sort that ended (or ran again) meanwhile may have
                    // made the list shorter.
                    else items.toList().forEachIndexed { i, it ->
                        DumpRow(it, keep.getOrElse(i) { true }, onToggle = { if (i in keep.indices && items.getOrNull(i) == it) keep[i] = !keep[i] }, onKind = {
                            val next = when (it.kind) { InkKind.Todo -> InkKind.Idea; InkKind.Idea -> InkKind.Note; else -> InkKind.Todo }
                            if (items.getOrNull(i) == it) items[i] = it.copy(kind = next, minutes = if (next == InkKind.Todo) Sizes.guess(it.text) else 0)
                        })
                    }
                }
            }
        }
        Divider()
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val chosen = items.indices.filter { keep.getOrElse(it) { true } }
            when {
                job.running -> Btn("Stop", { job.stop() }, icon = "stop")
                sorted && chosen.isNotEmpty() -> {
                    Btn("Add ${chosen.size}", add@{
                        // Once: a second tap before the screen has gone would add them twice and go back twice.
                        if (added) return@add
                        added = true
                        val list = chosen.mapNotNull { items.getOrNull(it) }
                        g.scope.launch {
                            val today = DayBounds().at(LocalDate.now())
                            val ids = list.map { item ->
                                g.db.entries().insert(Entry(kind = when (item.kind) { InkKind.Todo -> EntryKind.Todo; InkKind.Idea -> EntryKind.Idea; InkKind.Journal -> EntryKind.Journal; else -> EntryKind.Note },
                                    title = item.text.take(120), body = if (item.text.length > 120) item.text else "", minutes = item.minutes,
                                    dueAt = if (item.kind == InkKind.Todo) today else null, source = "dump"))
                            }
                            // The dump stays as written, marked sorted, so nothing is lost if the sort was wrong.
                            id?.let { d -> g.db.entries().get(d)?.let { e -> if (!e.tags.contains("sorted")) g.db.entries().update(e.copy(tags = "dump sorted")) } }
                            nav.show(if (ids.size == 1) "1 added to today" else "${ids.size} added to today") { ids.forEach { g.db.entries().delete(it) } }
                        }
                        nav.back()
                    }, icon = "plus", kind = BtnKind.Primary)
                    Btn("Keep as a note", { nav.back() })
                }
                else -> {
                    Btn("Sort it", {
                        keyboard?.hide()
                        if (g.engine.hasText()) { sorted = false; job.start(DumpSort.ask(text), DumpSort.MAX_TOKENS) }
                        else { byRules = true; show(DumpSort.rules(text)) }
                    }, icon = "sparkle", kind = if (text.isNotBlank()) BtnKind.Primary else BtnKind.Secondary)
                    Btn("Done", { nav.back() })
                }
            }
        }
    }
}

@Composable
private fun DumpRow(item: DumpSort.Item, kept: Boolean, onToggle: (() -> Unit)?, onKind: (() -> Unit)?) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).let { m -> onToggle?.let { m.clickable(onClickLabel = if (kept) "Leave out" else "Keep", onClick = it) } ?: m },
        verticalAlignment = Alignment.CenterVertically) {
        // Kept: on the wash, in ink; left out: only an outline.
        Box(Modifier.size(36.dp).clip(LfmShape.round).background(if (kept) c.wash else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, if (kept) c.wash else c.control, LfmShape.round)
            .let { m -> onKind?.let { m.clickable(onClickLabel = "Change kind", onClick = it) } ?: m }, contentAlignment = Alignment.Center) {
            LIcon(when (item.kind) { InkKind.Todo -> "todo"; InkKind.Idea -> "bulb"; InkKind.Journal -> "book"; else -> "note" }, size = 17.dp, tint = if (kept) c.text else c.text3)
        }
        Txt(item.text, LfmType.body, if (kept) c.text else c.text3, Modifier.weight(1f).padding(start = 10.dp), maxLines = 3,
            decoration = if (kept) null else androidx.compose.ui.text.style.TextDecoration.LineThrough)
        if (item.minutes > 0) Txt(Sizes.label(item.minutes), LfmType.meta, c.text2, Modifier.padding(start = 6.dp))
        Spacer(Modifier.width(4.dp))
    }
}
