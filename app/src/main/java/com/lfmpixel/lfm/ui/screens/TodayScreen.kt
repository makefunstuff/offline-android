package com.lfmpixel.lfm.ui.screens

import androidx.compose.animation.AnimatedContent
import com.lfmpixel.lfm.ui.components.SkyBand
import com.lfmpixel.lfm.ui.components.MenuItem
import com.lfmpixel.lfm.ui.components.Menu
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.assistant.InkGuess
import com.lfmpixel.lfm.assistant.InkKind
import com.lfmpixel.lfm.assistant.InkLine
import com.lfmpixel.lfm.assistant.Sizes
import androidx.compose.ui.focus.focusRequester
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.Card
import com.lfmpixel.lfm.ui.components.CheckBox
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.Pill
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One line of a day's timeline. */
data class AgendaItem(val at: Long?, val title: String, val detail: String, val onClick: (() -> Unit)? = null, val count: Int? = null)

/**
 * Home: the Daily Page (notebook-spec proposal 1). Today's page, its timeline, yesterday's open to-dos and every line
 * written today; swipe sideways to turn to another day. The Ink line at the bottom writes on today's page.
 */
@Composable
fun TodayScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); today = LocalDate.now() } }
    // The page shown, as days before today (kept by Nav, so a share can turn back to today): today's page turns with
    // midnight, and a cold start opens on today.
    val shown = today.toEpochDay() - nav.daysBack.coerceAtLeast(0)
    // The Ink line's text and picked kind live here, so turning to another day and back keeps a half-written line.
    val draft = rememberSaveable { mutableStateOf("") }
    val picked = rememberSaveable { mutableStateOf<String?>(null) }
    val reduced = LfmMotion.reduced()
    val turn: (LocalDate) -> Unit = { d -> if (!d.isAfter(today)) nav.daysBack = today.toEpochDay() - d.toEpochDay() }
    val turnNow by rememberUpdatedState(turn)
    val shownNow by rememberUpdatedState(shown)
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    // Bumped when the Ink line is tapped: the page shows its last lines above it, as the keyboard comes up.
    var toEnd by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        // A sideways swipe turns the page: to the left for the day after, to the right for the day before.
        AnimatedContent(shown, Modifier.weight(1f).pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                val d = LocalDate.ofEpochDay(shownNow)
                if (total < -threshold) turnNow(d.plusDays(1)) else if (total > threshold) turnNow(d.minusDays(1))
            }) { change, drag -> total += drag; change.consume() }
        }, transitionSpec = {
            if (reduced) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else LfmMotion.sharedAxisX(targetState > initialState, false)
        }, label = "day") { day ->
            DayPage(g, nav, LocalDate.ofEpochDay(day), today, turn, toEnd)
        }
        if (shown == today.toEpochDay()) InkLineBar(g, nav, today, draft, picked, onMic, listening) { toEnd++ }
        else Column {
            Divider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                Btn("Back to today", { turn(today) }, icon = "sun")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DayPage(g: AppGraph, nav: Nav, day: LocalDate, today: LocalDate, turn: (LocalDate) -> Unit, toEnd: Int) {
    val c = Lfm.c
    val zone = ZoneId.systemDefault()
    val isToday = day == today
    val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // Daylight's clock, read again at each new minute.
    val now by com.lfmpixel.lfm.daylight.rememberNow()
    // Null until first read, so the page shows once known rather than "blank" for a moment first.
    val linesRead by remember(start) { g.db.entries().day(start, end) }.collectAsState(null)
    val remindersRead by remember(start) { g.db.reminders().between(start, end) }.collectAsState(null)
    val carried by remember(start, isToday) { if (isToday) g.db.entries().carried(start) else flowOf(emptyList()) }.collectAsState(emptyList())
    val eventsRead by produceState<List<com.lfmpixel.lfm.tools.CalendarEvent>?>(null, start, remindersRead) {
        // The calendar provider can be busy or updating; Today is the screen Inkling opens on, so a failed read is no events.
        value = runCatching { g.calendar.between(start, end) }.getOrDefault(emptyList()) }
    val sent by remember(start) { g.db.netLog().since(start) }.collectAsState(emptyList())
    val prefs by g.settings.prefs.collectAsState(null)
    val hideJournal = journalLocked(g)
    val actions = remember { EntryActions(g, nav) }
    val open: (Entry) -> Unit = { if (it.isDump()) nav.go(Route.Dump(it.id)) else nav.go(Route.Editor(it.id, it.kind)) }

    // The organizer (notebook-spec A.1): open to-dos for Now and the plan, and the sheets it opens.
    val openRead by remember(isToday) { if (isToday) g.db.entries().open() else flowOf(null) }.collectAsState(null)
    var stepsFor by remember { mutableStateOf<Entry?>(null) }
    var planning by remember { mutableStateOf(false) }
    val bounds = remember(now, day) { DayBounds(day, now) }

    // Steps live under their to-do, not on the page.
    val lines = (linesRead ?: emptyList()).filter { it.parentId == null }
    val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    fun planned(e: Entry) = e.kind == EntryKind.Todo && e.planOrder > 0 && (e.dueAt ?: -1) in start until end
    val plan = lines.filter(::planned).sortedBy { it.planOrder }
    val next = openRead?.let { com.lfmpixel.lfm.assistant.NextAction.pick(it, nowMs, start, end, nav.skipped.toList()) }
    val stepsLeft = openRead.orEmpty().filter { it.parentId != null }.groupingBy { it.parentId!! }.eachCount()
    // Where was I: shown once after 20 minutes or more away, until closed.
    val leftAt = g.focus.leftAt
    var whereSeen by remember { mutableStateOf(g.focus.seenFor) }
    val away = System.currentTimeMillis() - leftAt
    val showWhere = isToday && leftAt > 0 && away >= 20 * 60_000 && whereSeen != leftAt
    // Timed to-dos due this day sit in the timeline; the page keeps the rest.
    fun timed(e: Entry) = e.kind == EntryKind.Todo && !e.done && e.dueHasTime && (e.dueAt ?: 0) in start until end
    val timeline = buildList {
        eventsRead.orEmpty().forEach { add(AgendaItem(it.start, it.title, listOf(it.location, minutes(it.end - it.start)).filter { s -> s.isNotEmpty() }.joinToString(" · "))) }
        remindersRead.orEmpty().filter { !isToday || !it.fired }.forEach { add(AgendaItem(it.at, it.text, "Reminder")) }
        lines.filter(::timed).forEach { e -> add(AgendaItem(e.dueAt, e.title, "To-do", onClick = { open(e) })) }
    }.sortedBy { it.at }
    val page = lines.filterNot(::timed).filterNot(::planned)
    val shownLines = if (hideJournal) page.filter { it.kind != EntryKind.Journal } else page
    val lockedLines = page.size - shownLines.size
    val nextTimed = if (isToday) timeline.indexOfFirst { (it.at ?: 0) >= nowMs - 15 * 60_000 } else -1
    val known = linesRead != null && remindersRead != null && eventsRead != null && (!isToday || openRead != null)

    val list = rememberLazyListState()
    // A line just written shows at once, above the Ink line (a to-do moved here from an earlier day doesn't scroll).
    var newest by remember(start) { mutableStateOf<Long?>(null) }
    val latest = shownLines.maxOfOrNull { it.createdAt } ?: 0L
    LaunchedEffect(latest, known) {
        if (!known) return@LaunchedEffect
        val before = newest
        newest = latest
        if (isToday && before != null && latest > before) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    // The keyboard coming up for the Ink line: the page shows its last lines above it, like a chat.
    val imeUp = WindowInsets.isImeVisible
    LaunchedEffect(toEnd, imeUp) {
        if (!imeUp || !isToday) return@LaunchedEffect
        // After the keyboard has risen and the page has its new height.
        delay(LfmMotion.LONG.toLong())
        list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    LazyColumn(Modifier.fillMaxSize(), state = list) {
        if (isToday) item(key = "sky") {
            SkyHead(g, now, com.lfmpixel.lfm.daylight.skyAt(now), timeline, page.count { it.kind == EntryKind.Todo && !it.done },
                onDayBefore = { turn(day.minusDays(1)) }, onDump = { nav.go(Route.Dump()) }, onSettings = { nav.go(Route.Settings) },
                onLook = { nav.go(Route.Camera) })
        }
        else item(key = "head") {
            SkyBand {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Label(when (day) {
                        today -> "Today · " + day.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
                        today.minusDays(1) -> "Yesterday · " + day.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))
                        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "MMMM" else "MMMM yyyy", Locale.ENGLISH))
                    })
                    Txt(day.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)), LfmType.title2, maxLines = 1)
                }
                IconButtonPlain("back", { turn(day.minusDays(1)) }, desc = "The day before")
                if (!isToday) IconButtonPlain("right", { turn(day.plusDays(1)) }, desc = "The day after")
                if (isToday) IconButtonPlain("cloud", { nav.go(Route.Dump()) }, desc = "Brain dump")
                IconButtonPlain("user", { nav.go(Route.Settings) }, desc = "Settings")
                com.lfmpixel.lfm.ui.components.SunMark(Modifier.padding(start = 4.dp, end = 10.dp))
            }
            }
        }
        item(key = "seal") {
            Column(Modifier.padding(horizontal = 24.dp)) {
                // The seal (proposal 6): what left the phone that day, counted from the network log.
                val left = sent.count { it.at < end }
                val lockOn = prefs?.lockMode == com.lfmpixel.lfm.settings.LockMode.Journal
                val seal = "Sealed · " + when {
                    left == 0 && isToday -> "nothing left this phone today"
                    left == 0 -> "nothing left this phone that day"
                    left == 1 -> "1 web request ${if (isToday) "today" else "that day"}"
                    else -> "$left web requests ${if (isToday) "today" else "that day"}"
                } + if (lockOn && hideJournal) " · journal locked" else ""
                Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(LfmShape.control).clickable(role = Role.Button, onClickLabel = "See what left the phone") { nav.go(Route.Privacy) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LIcon("shield", size = 15.dp, tint = c.text2)
                    Txt(seal, LfmType.meta, c.text2, Modifier.weight(1f), maxLines = 1)
                    val heat by g.heat.heat.collectAsState()
                    if (heat != com.lfmpixel.lfm.device.Heat.Normal) Pill(heat.label.substringBefore(" ·"), "warn")
                }
                // Today opens with the sky's sentence; other days keep their line of counts.
                if (known && !isToday) Txt(brief(timeline.size, page.count { it.kind == EntryKind.Todo && !it.done }, carried.size, page.size, isToday),
                    LfmType.small, c.text2)
            }
        }
        if (showWhere && known) item(key = "where") {
            // Now's Start (or Plan my day) is the page's one primary when it shows.
            WhereWasI(g, nav, away, lines, primary = next == null && plan.isNotEmpty()) { g.focus.seenFor = leftAt; whereSeen = leftAt }
        }
        if (isToday && known) {
            item(key = "now") { NowCard(g, nav, next, plan.isEmpty(), onSteps = { stepsFor = it }, onPlan = { planning = true }) }
            item(key = "plan") { PlanBlock(g, nav, plan, stepsLeft, bounds) { planning = true } }
        }
        if (timeline.isNotEmpty()) {
            item(key = "tl-label") { PageLabel(if (isToday) "Today" else "That day") }
            // Two reminders can share a time and words: the place in the list keeps keys apart.
            itemsIndexed(timeline, key = { i, it -> "tl-$i-${it.at}" }) { i, it -> TimelineRow(it, i == nextTimed, zone) }
        }
        if (carried.isNotEmpty()) item(key = "carried") { Carried(g, nav, carried, today, zone) }
        item(key = "page-label") { PageLabel("The page") }
        if (known && shownLines.isEmpty() && lockedLines == 0) item(key = "empty") {
            Txt(if (isToday) "A clean page. Write a line below and I'll file it: a to-do, an idea, a journal line or a note."
                else "Nothing was written on this day.", LfmType.voiceBody, c.text2, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        items(shownLines, key = { it.id }) { e -> PageLine(e, open, actions, Modifier.animateItem(), onSteps = { stepsFor = it }) }
        if (lockedLines > 0) item(key = "locked") {
            Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Library(LibFilter.Journal)) }.heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LIcon("lock", size = 16.dp, tint = c.text2)
                Txt(if (lockedLines == 1) "1 journal line, locked" else "$lockedLines journal lines, locked", LfmType.small, c.text2)
            }
        }
        if (isToday && !g.engine.canChat()) item(key = "nomodel") {
            Card(Modifier.padding(16.dp).fillMaxWidth(), fill = c.wash, onClick = { nav.go(Route.Models) }) {
                Column {
                    Txt("No model yet", LfmType.rowTitle)
                    Txt("The page works without one. Get LFM3-1.7B to ask questions and use Ink in the margin.", LfmType.small, c.text2)
                }
            }
        }
        item(key = "end") { Spacer(Modifier.height(16.dp)) }
    }
    stepsFor?.let { t -> StepsSheet(g, nav, t) { stepsFor = null } }
    if (planning) PlanSheet(g, nav, openRead.orEmpty()) { planning = false }
}

@Composable
private fun PageLabel(text: String) = Label(text, Modifier.padding(start = 24.dp, end = 16.dp, top = 28.dp, bottom = 4.dp))

/** "2 events · 3 to-dos · 1 carried over": the day in a line. */
internal fun brief(timed: Int, openTodos: Int, carried: Int, lines: Int, isToday: Boolean): String {
    val parts = buildList {
        if (timed > 0) add(if (timed == 1) "1 thing at a set time" else "$timed things at set times")
        if (openTodos > 0) add(if (openTodos == 1) "1 to-do" else "$openTodos to-dos")
        if (carried > 0) add("$carried carried over")
        if (!isToday && lines > 0) add(if (lines == 1) "1 line written" else "$lines lines written")
    }
    return parts.joinToString(" · ").ifEmpty { if (isToday) "Nothing planned" else "A quiet day" }
}

@Composable
private fun TimelineRow(item: AgendaItem, isNext: Boolean, zone: ZoneId) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().let { m -> item.onClick?.let { m.clickable(onClick = it) } ?: m }.heightIn(min = 44.dp).padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(56.dp)) {
            val t = item.at?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: ""
            if (isNext) Marked(t, LfmType.rowTitle.copy(fontFeatureSettings = "tnum")) else Txt(t, LfmType.rowTitle.copy(fontFeatureSettings = "tnum"))
        }
        Column(Modifier.weight(1f)) {
            Txt(item.title, LfmType.body, maxLines = 1)
            val detail = listOfNotNull("Next".takeIf { isNext }, item.detail.ifEmpty { null }).joinToString(" · ")
            if (detail.isNotEmpty()) Txt(detail, LfmType.meta, c.text2, maxLines = 1)
        }
    }
}

/** Open to-dos from earlier days (proposal 4): tick, Today or Later each, or all to today. */
@Composable
private fun Carried(g: AppGraph, nav: Nav, list: List<Entry>, today: LocalDate, zone: ZoneId) {
    val c = Lfm.c
    val shape = LfmShape.card
    fun moveTo(e: Entry, d: LocalDate?) = e.copy(dueAt = d?.atTime(9, 0)?.atZone(zone)?.toInstant()?.toEpochMilli(), dueHasTime = false,
        updatedAt = System.currentTimeMillis())
    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 18.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Still open · no rush", Modifier.weight(1f).padding(start = 12.dp))
            if (list.size > 1) Btn("All to today", {
                g.scope.launch {
                    list.forEach { g.db.entries().update(moveTo(it, today)) }
                    nav.show("${list.size} to-dos moved to today") { list.forEach { g.db.entries().update(it) } }
                }
            }, small = true)
        }
        // A few at a time: a long backlog is a calm line to Plans, not a wall on the home page.
        list.take(3).forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(false) { EntryActions(g, nav).done(e, true) }
                Column(Modifier.weight(1f).clickable { nav.go(Route.Editor(e.id, e.kind)) }.padding(vertical = 4.dp)) {
                    Txt(e.title, LfmType.body, maxLines = 2)
                    val from = Instant.ofEpochMilli(e.dueAt ?: e.createdAt).atZone(zone).toLocalDate()
                    Txt("from " + when (ChronoUnit.DAYS.between(from, today)) {
                        1L -> "yesterday"
                        in 2L..6L -> from.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))
                        else -> from.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                    } + if (e.list.isNotEmpty()) " · ${e.list}" else "", LfmType.meta, c.text2)
                }
                Btn("Today", {
                    g.scope.launch { g.db.entries().update(moveTo(e, today)); nav.show("Moved to today") { g.db.entries().update(e) } }
                }, small = true)
                Spacer(Modifier.width(6.dp))
                var notToday by remember { mutableStateOf(false) }
                Box {
                    Btn("Not today", { notToday = true }, small = true)
                    NotTodayMenu(notToday, { notToday = false }) { d, label ->
                        g.scope.launch { g.db.entries().update(moveTo(e, d).copy(planOrder = 0)); nav.show("Moved to $label") { g.db.entries().update(e) } }
                    }
                }
            }
        }
        if (list.size > 3) Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Todos) }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${list.size - 3} more in Plans", LfmType.small, c.text2, Modifier.weight(1f).padding(start = 12.dp))
            LIcon("right", tint = c.text3)
        }
    }
}

/** A quote shared in from another app (proposal 5). */
internal fun Entry.isQuote() = source == "share" && kind != EntryKind.Todo

/** One line of the page: its glyph, then the words. Tap opens it; long-press for pin, move and delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageLine(e: Entry, open: (Entry) -> Unit, actions: EntryActions, modifier: Modifier = Modifier, onSteps: (Entry) -> Unit = {}) {
    val c = Lfm.c
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { open(e) }, onLongClick = { menu = true }, onLongClickLabel = "More")
            .heightIn(min = 48.dp).padding(end = 20.dp, start = 12.dp), verticalAlignment = Alignment.Top) {
            if (e.kind == EntryKind.Todo) CheckBox(e.done) { actions.done(e, it) }
            else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                LIcon(if (e.isQuote()) "quote" else if (e.isDump()) "cloud" else e.kind.icon(), size = 17.dp, tint = c.text2)
            }
            Column(Modifier.weight(1f).padding(top = 13.dp, bottom = 10.dp)) {
                // A note keeps its whole text in the body when its title was cut short: the page shows the whole line.
                val bodyLines = e.body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val whole = bodyLines.firstOrNull()?.startsWith(e.title.removeSuffix("…").trimEnd(), ignoreCase = true) == true
                val head = if (e.kind == EntryKind.Note && whole) bodyLines.first() else e.title
                when {
                    e.isQuote() -> Marked(com.lfmpixel.lfm.assistant.Quotes.words(e), LfmType.body, maxLines = 4)
                    e.isDump() -> Txt(e.title, LfmType.body, maxLines = 2)
                    e.kind == EntryKind.Journal -> Txt(e.title, LfmType.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), maxLines = 4)
                    else -> Txt(head, LfmType.body, if (e.done) c.text3 else c.text, maxLines = 3, decoration = if (e.done) TextDecoration.LineThrough else null)
                }
                val sub = when {
                    e.isDump() -> if (e.tags.contains("sorted")) "Brain dump · sorted" else "Brain dump · tap to sort"
                    e.isQuote() -> com.lfmpixel.lfm.assistant.Quotes.source(e)
                    e.kind == EntryKind.Note -> bodyLines.drop(if (whole) 1 else 0).firstOrNull().orEmpty()
                    e.kind == EntryKind.Idea -> e.topic
                    e.kind == EntryKind.Todo -> listOf(e.list, if (e.minutes > 0) Sizes.label(e.minutes) else "").filter { it.isNotEmpty() }.joinToString(" · ")
                    else -> ""
                }
                if (sub.isNotEmpty()) Txt(sub, LfmType.meta, c.text2, Modifier.padding(top = 2.dp), maxLines = 1)
            }
            if (e.pinned) Box(Modifier.padding(top = 14.dp)) { LIcon("pin", size = 14.dp, tint = c.text2) }
        }
        Menu(menu, { menu = false }) {
            if (e.kind == EntryKind.Todo && !e.done) MenuItem("Smaller steps", { menu = false; onSteps(e) }, icon = "steps")
            MenuItem(if (e.pinned) "Unpin" else "Pin", { menu = false; actions.pin(e) }, icon = "pin")
            EntryKind.entries.filter { it != e.kind }.forEach { k ->
                MenuItem("Make it ${if (k == EntryKind.Idea) "an idea" else if (k == EntryKind.Journal) "a journal line" else "a " + k.label.lowercase()}",
                    { menu = false; actions.move(e, k) }, icon = k.icon())
            }
            MenuItem("Delete", { menu = false; actions.delete(e) }, icon = "trash")
        }
    }
}

/**
 * The Ink line (proposal 2): one line at the bottom of today's page. The glyph shows where the line goes as it is
 * typed, guessed on the phone; a tap on it picks another kind. Enter puts it on the page and keeps the keyboard up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InkLineBar(g: AppGraph, nav: Nav, today: LocalDate, draftState: androidx.compose.runtime.MutableState<String>,
                       pickedState: androidx.compose.runtime.MutableState<String?>, onMic: () -> Unit, listening: Boolean, onFocus: () -> Unit) {
    val c = Lfm.c
    var draft by draftState
    var picked by pickedState
    var kinds by remember { mutableStateOf(false) }
    // The "Write a line" shortcut puts the caret here, with the keyboard up.
    val inkFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(nav.inkFocus) { if (nav.inkFocus > 0) { delay(LfmMotion.LONG.toLong()); runCatching { inkFocus.requestFocus() } } }
    // What the mic heard comes here, to be read (and its glyph seen) before it goes on the page.
    LaunchedEffect(nav.heard) { nav.heard?.let { draft = (draft.trimEnd() + " " + it).trim(); nav.heard = null } }
    // The phone's rules guess at once; in Edge mode the small model's label follows a moment after typing stops.
    var labelled by remember { mutableStateOf<Pair<String, InkGuess>?>(null) }
    LaunchedEffect(draft) {
        if (draft.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        g.labels.capture(draft.trim())?.let { labelled = draft to InkLine.withLabel(draft, it) }
    }
    // Where the owner put a line like this before (a correction, memory/Memory.kt) wins over the rules and the label.
    var learned by remember { mutableStateOf<Pair<String, InkKind>?>(null) }
    LaunchedEffect(draft) {
        val k = g.memory.learnedKind(draft.trim())?.let { runCatching { InkKind.valueOf(it) }.getOrNull() }
        learned = k?.let { draft to it }
    }
    val sorted = labelled?.takeIf { it.first == draft }?.second ?: remember(draft) { InkLine.guess(draft) }
    val guess = InkLine.withLearned(draft, sorted, learned?.takeIf { it.first == draft }?.second)
    val kind = picked?.let { InkKind.valueOf(it) }?.takeIf { it in InkLine.cycle(guess.kind) } ?: guess.kind
    fun commit() {
        val line = draft.trim()
        if (line.isEmpty()) return
        // Picked by hand over the guess: remembered, so a line like it goes there next time.
        if (picked != null && kind != guess.kind) g.scope.launch { g.memory.corrected(com.lfmpixel.lfm.memory.CorrectionKind.Kind, line, guess.kind.name, kind.name) }
        draft = ""; picked = null
        if (kind == InkKind.Ask) {
            // Without a model a question would be sorted into the notebook: it waits in the line instead.
            if (!g.engine.canChat()) { draft = line; nav.show("Asking needs a chat model: Settings → Models"); return }
            g.assistant.send(InkLine.question(line), source = "today"); nav.go(Route.Chat); return
        }
        InkLine.call(line, kind, today, guess)?.let { call ->
            g.assistant.runDirect(call, "typed") { r ->
                if (!r.ok) { draft = line; nav.show(listOf(r.cardLabel, r.cardDetail).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { "Not added" }) }
                else if (kind == InkKind.Reminder) nav.show("${r.cardLabel} · ${r.cardDetail}".trim(' ', '·'))
            }
        }
    }
    Column {
        AnimatedVisibility(draft.isNotBlank(), enter = LfmMotion.appear(), exit = LfmMotion.leave()) {
            val detail = when (kind) {
                InkKind.Todo -> "To-do · today"
                InkKind.Reminder -> "Reminder · " + InkLine.whenLabel(guess.reminderAt)
                InkKind.Ask -> "Ask Inkling · opens the chat"
                else -> kind.label
            }
            Txt("$detail  ·  tap the mark to change, hold to pick", LfmType.meta, c.text2, Modifier.padding(start = 16.dp, top = 6.dp))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val shape = LfmShape.control
            Box(Modifier.size(48.dp).clip(shape).combinedClickable(role = Role.Button, onClickLabel = "Change where it goes",
                    onLongClickLabel = "Pick where it goes", onLongClick = { kinds = true }) {
                    picked = InkLine.next(kind, guess.kind).name
                }.semantics { contentDescription = "Goes to: ${kind.label}" }, contentAlignment = Alignment.Center) {
                Menu(kinds, { kinds = false }) {
                    InkLine.cycle(guess.kind).forEach { k ->
                        MenuItem(k.label, { kinds = false; picked = k.name }, icon = k.icon)
                    }
                }
                Box(Modifier.size(36.dp).clip(LfmShape.round).background(c.wash), contentAlignment = Alignment.Center) {
                    AnimatedContent(kind, transitionSpec = {
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(LfmMotion.SHORT)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(LfmMotion.TINY))
                    }, label = "glyph") { k -> LIcon(k.icon, size = 18.dp, tint = c.text) }
                }
            }
            val fieldShape = LfmShape.card
            Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(fieldShape).background(c.wash).padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Txt("Write a line…", LfmType.body, c.text2, maxLines = 1)
                BasicTextField(draft, { draft = it; if (it.isBlank()) picked = null }, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send, capitalization = KeyboardCapitalization.Sentences),
                    keyboardActions = KeyboardActions(onSend = { commit() }), maxLines = 4,
                    modifier = Modifier.fillMaxWidth().focusRequester(inkFocus).onFocusChanged { if (it.isFocused) onFocus() }
                        .semantics { contentDescription = "Write a line on today's page" })
            }
            if (draft.isNotBlank()) IconButtonPlain("send", { commit() }, tint = c.text, desc = if (kind == InkKind.Ask) "Ask" else "Add to the page")
            if (draft.isBlank() || listening) MicButton(onMic, listening = listening)
        }
    }
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

/** The chat model's state in a word, for the chat header: "ready", "loading…", "not loaded". */
@Composable
fun modelState(g: AppGraph): String {
    val st by g.engine.status.collectAsState()
    val installed by g.models.installed.collectAsState()
    val prefs by g.settings.prefs.collectAsState(null)
    val edge by g.edge.state.collectAsState()
    if (edge is com.lfmpixel.lfm.edge.EdgeState.Online) return "LFM3-1.7B · on your server"
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
