package com.lfmpixel.lfm.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.border
import com.lfmpixel.lfm.ui.components.WashCard
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.MenuItem
import com.lfmpixel.lfm.ui.components.Menu
import com.lfmpixel.lfm.ui.components.FlatSheet
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.assistant.DayPlan
import com.lfmpixel.lfm.assistant.NextAction
import com.lfmpixel.lfm.assistant.Organizer
import com.lfmpixel.lfm.assistant.Recap
import com.lfmpixel.lfm.assistant.Sizes
import com.lfmpixel.lfm.assistant.Steps
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.CheckBox
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.components.pressScale
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The day's edges in millis, and how many minutes are left of it (the day "ends" at 22:00, or midnight after that). */
class DayBounds(today: LocalDate = LocalDate.now(), now: LocalDateTime = LocalDateTime.now()) {
    private val zone = ZoneId.systemDefault()
    val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val endsAt: LocalTime = if (now.toLocalTime() < LocalTime.of(22, 0)) LocalTime.of(22, 0) else LocalTime.MAX
    val minutesLeft = java.time.Duration.between(now, today.atTime(endsAt)).toMinutes().toInt().coerceAtLeast(0)
    fun at(d: LocalDate) = d.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}

/** "6 h 40", "35 min": time left, as the plan shows it. */
internal fun leftLabel(minutes: Int) = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60}"
}

/** The organizer's writes, each with an Undo: ticking (a step ticks its to-do when it was the last), moving, planning. */
class Organize(private val g: AppGraph, private val nav: Nav) {
    /** Ticks [e]; the last open step also ticks its to-do. Says how the plan stands, never how far behind. */
    fun done(e: Entry, bounds: DayBounds = DayBounds()) = g.scope.launch {
        val now = System.currentTimeMillis()
        val ticked = e.copy(done = true, doneAt = now)
        g.db.entries().update(ticked)
        val parent = e.parentId?.let { g.db.entries().get(it) }
        if (parent != null && !parent.done && g.db.entries().stepsNow(parent.id).none { !it.done }) {
            g.db.entries().update(parent.copy(done = true, doneAt = now))
            nav.show("All steps done: ${parent.title}") { g.db.entries().update(e); g.db.entries().update(parent) }
            return@launch
        }
        val planned = (parent ?: e).takeIf { it.planOrder > 0 && (it.dueAt ?: -1) in bounds.start until bounds.end }
        val text = if (planned != null && parent == null) {
            val plan = g.db.entries().day(bounds.start, bounds.end).first()
                .filter { it.kind == EntryKind.Todo && it.parentId == null && it.planOrder > 0 && (it.dueAt ?: -1) in bounds.start until bounds.end }
            "Done. ${plan.count { it.done }} of ${plan.size} in today's plan"
        } else "Done: ${e.title}"
        nav.show(text) { g.db.entries().update(e); parent?.let { g.db.entries().update(it) } }
    }

    /** Moves [e] (or the to-do a step belongs to) to [day], or to Someday; it leaves today's plan. */
    fun moveTo(e: Entry, day: LocalDate?, label: String) = g.scope.launch {
        val target = e.parentId?.let { g.db.entries().get(it) } ?: e
        val moved = target.copy(dueAt = day?.let { DayBounds().at(it) }, dueHasTime = false, planOrder = 0, updatedAt = System.currentTimeMillis())
        g.db.entries().update(moved)
        nav.skipped.remove(e.id)
        nav.show("Moved to $label") { g.db.entries().update(target) }
    }

    /** Steps under [task]; its size becomes theirs when it had none. */
    fun addSteps(task: Entry, steps: List<Organizer.Sized>) = g.scope.launch {
        val ids = steps.map { s ->
            g.db.entries().insert(Entry(kind = EntryKind.Todo, title = s.text.take(120), parentId = task.id, minutes = s.minutes, source = "steps"))
        }
        // The to-do as it is now, not the copy the caller held (an open editor may have saved since): only its size changes.
        val current = g.db.entries().get(task.id)
        if (current != null && current.minutes == 0) g.db.entries().setMinutes(current.id, steps.sumOf { it.minutes }.coerceAtMost(240))
        nav.show(if (ids.size == 1) "1 step added" else "${ids.size} steps added") {
            ids.forEach { g.db.entries().delete(it) }
            if (current != null && current.minutes == 0) g.db.entries().setMinutes(current.id, 0)
        }
    }

    /** Makes [picks] today's plan, in order; what was planned for today before leaves it. */
    fun usePlan(picks: List<DayPlan.Pick>, bounds: DayBounds = DayBounds()) = g.scope.launch {
        val before = picks.map { it.entry.id }.mapNotNull { g.db.entries().get(it) }
        val oldPlan = g.db.entries().day(bounds.start, bounds.end).first().filter { it.planOrder > 0 }
        g.db.entries().unplanOthers(picks.map { it.entry.id }, bounds.start, bounds.end)
        picks.forEachIndexed { i, p ->
            val e = g.db.entries().get(p.entry.id) ?: return@forEachIndexed
            val keepTime = e.dueHasTime && (e.dueAt ?: -1) in bounds.start until bounds.end
            g.db.entries().update(e.copy(planOrder = i + 1, minutes = if (e.minutes > 0) e.minutes else p.minutes,
                dueAt = if (keepTime) e.dueAt else bounds.at(LocalDate.now()), dueHasTime = keepTime, updatedAt = System.currentTimeMillis()))
        }
        nav.skipped.clear()
        nav.show("Today's plan is set") { (oldPlan + before).forEach { g.db.entries().update(it) } }
    }
}

/** One small model job being run for a sheet or a card: its text so far, whether it runs, and what went wrong. */
class OrgJob(private val g: AppGraph) {
    var text by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    private var job: Job? = null

    fun start(user: String, maxTokens: Int) {
        job?.cancel()
        text = ""; finished = false; running = true
        job = g.scope.launch {
            try {
                Organizer.run(g.engine, user, maxTokens).collect { text = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                text = ""
            } finally {
                running = false; finished = true
            }
        }
    }

    fun stop() { job?.cancel(); job = null; running = false }
}

/**
 * Now (notebook-spec A.1, proposal 1): one next thing, large, with its size and where it comes from. Start opens
 * Focus; Done brings the next one in; Not now sends it to the back; ⋯ has smaller steps and other days.
 */
@Composable
internal fun NowCard(g: AppGraph, nav: Nav, now: NextAction.Now?, planEmpty: Boolean, onSteps: (Entry) -> Unit, onPlan: () -> Unit) {
    val c = Lfm.c
    val org = remember { Organize(g, nav) }
    val reduced = LfmMotion.reduced()
    WashCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        AnimatedContent(now?.entry?.id, transitionSpec = {
            if (reduced) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
            else (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(LfmMotion.MEDIUM, LfmMotion.SHORT, LfmMotion.emphasizedDecelerate)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(LfmMotion.MEDIUM, LfmMotion.SHORT, LfmMotion.emphasizedDecelerate)) { it / 6 }) togetherWith
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(LfmMotion.SHORT, easing = LfmMotion.emphasizedAccelerate))
        }, label = "now") { id ->
            val n = now?.takeIf { it.entry.id == id }
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
                if (n == null) {
                    Label("Now")
                    Txt("Nothing waiting on you. Jot a line below, or let me help plan the day.", LfmType.voiceBody, c.text, Modifier.padding(top = 4.dp))
                    if (planEmpty) Btn("Plan my day", onPlan, Modifier.padding(top = 10.dp), icon = "sparkle")
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Now · about ${Sizes.label(n.minutes)}", LfmType.label, c.text2, Modifier.weight(1f))
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButtonPlain("more", { menu = true }, tint = c.text2, desc = "More for this")
                        Menu(menu, { menu = false }) {
                            if (n.parent == null) MenuItem("Smaller steps", { menu = false; onSteps(n.entry) }, icon = "steps")
                            MenuItem("Tomorrow", { menu = false; org.moveTo(n.entry, LocalDate.now().plusDays(1), "tomorrow") }, icon = "right")
                            MenuItem("Someday", { menu = false; org.moveTo(n.entry, null, "Someday") }, icon = "cloud")
                            MenuItem("Open", { menu = false; nav.go(Route.Editor((n.parent ?: n.entry).id, EntryKind.Todo)) }, icon = "edit")
                        }
                    }
                }
                Txt(n.entry.title, LfmType.answer, c.text, Modifier.padding(end = 8.dp), maxLines = 3)
                val from = listOfNotNull(
                    n.parent?.let { (if (n.stepsLeft == 1) "Last step" else "${n.stepsLeft} steps left") + " · " + it.title },
                    if (n.plan > 0 && n.parent == null) "In today's plan · ${n.planSize} left" else null,
                ).joinToString(" · ")
                if (from.isNotEmpty()) Txt(from, LfmType.small, c.text2, Modifier.padding(top = 2.dp), maxLines = 2)
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Start", {
                        g.focus.start(n.entry.id, n.entry.title, n.minutes)
                        nav.go(Route.Focus)
                    }, icon = "play", kind = BtnKind.Primary)
                    Btn("Done", { org.done(n.entry) }, icon = "check")
                    Btn("Not now", { nav.skipped.remove(n.entry.id); nav.skipped.add(n.entry.id) })
                }
            }
        }
    }
}

/** Today's plan (proposal 4): its few lines with sizes, and time planned against time left. */
@Composable
internal fun PlanBlock(g: AppGraph, nav: Nav, plan: List<Entry>, stepsLeft: Map<Long, Int>, bounds: DayBounds, onPlan: () -> Unit) {
    val c = Lfm.c
    val org = remember { Organize(g, nav) }
    WashCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val done = plan.count { it.done }
                Txt(if (plan.isEmpty()) "Today's plan" else "Today's plan · $done of ${plan.size} done", LfmType.label, c.text2, Modifier.weight(1f))
                Box(Modifier.clip(LfmShape.round).clickable(role = Role.Button, onClick = onPlan).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Txt(if (plan.isEmpty()) "Plan my day" else "Plan again", LfmType.button, c.text, decoration = TextDecoration.Underline)
                }
            }
            if (plan.isEmpty()) {
                Txt("Inkling picks up to three things that fit the ${leftLabel(bounds.minutesLeft)} left today.", LfmType.small, c.text2, Modifier.padding(top = 2.dp))
                return@Column
            }
            plan.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable { nav.go(Route.Editor(e.id, EntryKind.Todo)) }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        CheckBox(e.done) { on -> if (on) org.done(e, bounds) else g.scope.launch { g.db.entries().update(e.copy(done = false, doneAt = null)) } }
                    }
                    Txt(e.title, LfmType.body, if (e.done) c.text3 else c.text, Modifier.weight(1f).padding(start = 6.dp), maxLines = 2,
                        decoration = if (e.done) TextDecoration.LineThrough else null)
                    val left = stepsLeft[e.id] ?: 0
                    Txt(listOfNotNull(if (left > 0 && !e.done) "$left steps" else null, Sizes.label(Sizes.of(e))).joinToString(" · "), LfmType.meta, c.text2)
                }
            }
            // Visible time: what the plan still needs against what is left of the day.
            val need = plan.filter { !it.done }.sumOf { Sizes.of(it) }
            val frac = if (bounds.minutesLeft <= 0) 1f else (need.toFloat() / bounds.minutesLeft).coerceIn(0f, 1f)
            val shown by androidx.compose.animation.core.animateFloatAsState(frac, LfmMotion.spec(LfmMotion.LONG), label = "planBar")
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(8.dp).clip(LfmShape.round).background(c.bg)) {
                Box(Modifier.fillMaxWidth(shown.coerceAtLeast(if (need > 0) 0.03f else 0f)).height(8.dp).clip(LfmShape.round).background(c.highlight))
            }
            Txt(if (need == 0) "All done. The rest of the day is yours." else
                "about ${leftLabel(need)} still planned · ${leftLabel(bounds.minutesLeft)} left today", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Where was I (proposal 7): after 20 minutes away, what was being worked on and what came in since, with Resume and
 * a recap the model writes.
 */
@Composable
internal fun WhereWasI(g: AppGraph, nav: Nav, away: Long, lines: List<Entry>, onClose: () -> Unit) {
    val c = Lfm.c
    val run by g.focus.run.collectAsState()
    var last by remember { mutableStateOf<Entry?>(null) }
    var since by remember { mutableStateOf(0) }
    LaunchedEffect(away) {
        last = g.db.entries().lastEdited()?.takeIf { it.updatedAt < g.focus.leftAt + 1000 }
        since = g.db.entries().countSince(g.focus.leftAt)
    }
    val job = remember { OrgJob(g) }
    var recap by remember { mutableStateOf<String?>(null) }
    val focusTitle = run?.title ?: g.focus.lastTitle
    WashCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Where was I · ${awayLabel(away)} ago", LfmType.label, c.text2, Modifier.weight(1f))
                IconButtonPlain("x", onClose, tint = c.text2, desc = "Hide")
            }
            val r = run
            val head = when {
                r != null -> "You were on: ${r.title}"
                last != null -> "You were writing: ${last!!.title}"
                focusTitle != null -> "Last focus: $focusTitle"
                else -> "Welcome back"
            }
            Txt(head, LfmType.rowTitle, c.text, maxLines = 2)
            val detail = listOfNotNull(
                r?.let { val m = (it.totalMs - it.leftMs(System.currentTimeMillis())) / 60_000; "Focus, $m of ${it.totalMs / 60_000} min" + if (it.paused) ", paused" else "" },
                if (since > 0) (if (since == 1) "1 line captured since" else "$since lines captured since") else null,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Txt(detail, LfmType.small, c.text2)
            val said = recap ?: job.text.takeIf { it.isNotBlank() }?.let { Recap.short(it) }
            if (said != null) Txt(said, LfmType.voiceBody, c.text, Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
            else if (job.running) Txt("Thinking back…", LfmType.small, c.text2, Modifier.padding(top = 6.dp))
            LaunchedEffect(job.finished) { if (job.finished && job.text.isBlank()) recap = Recap.rules(focusTitle, lines) }
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r != null) Btn("Resume", { onClose(); nav.go(Route.Focus) }, icon = "play", kind = BtnKind.Primary)
                else last?.let { e -> Btn("Resume", { onClose(); nav.go(if (e.isDump()) Route.Dump(e.id) else Route.Editor(e.id, e.kind)) }, icon = "edit", kind = BtnKind.Primary) }
                if (said == null && !job.running) Btn("Recap", {
                    if (g.engine.hasText()) job.start(Recap.ask(focusTitle, lines), Recap.MAX_TOKENS) else recap = Recap.rules(focusTitle, lines)
                }, icon = "sparkle")
            }
        }
    }
}

private fun awayLabel(ms: Long): String {
    val m = ms / 60_000
    return when {
        m < 60 -> "$m min"
        m < 24 * 60 -> "${m / 60} h"
        else -> "${m / (24 * 60)} d"
    }
}

/** A dump note, written in the brain dump (proposal 3). */
internal fun Entry.isDump() = kind == EntryKind.Note && tags.split(' ').contains("dump")

/** Smaller steps (proposal 2): the model's steps for [task] as a proposal, each with its size and an ×. */
@Composable
internal fun StepsSheet(g: AppGraph, nav: Nav, task: Entry, onClose: () -> Unit) {
    val c = Lfm.c
    val job = remember(task.id) { OrgJob(g) }
    val model = g.engine.hasText()
    val steps = remember(task.id) { mutableStateListOf<Organizer.Sized>() }
    var byRules by remember(task.id) { mutableStateOf(!model) }
    LaunchedEffect(task.id) {
        if (model) job.start(Steps.ask(task.title, task.body.takeIf { it != task.title }.orEmpty()), Steps.MAX_TOKENS)
        else steps.addAll(Steps.starter(task.title))
    }
    LaunchedEffect(job.finished) {
        if (!job.finished) return@LaunchedEffect
        val parsed = Steps.parse(job.text)
        steps.clear()
        if (parsed.size >= 2) steps.addAll(parsed) else { byRules = true; steps.addAll(Steps.starter(task.title)) }
    }
    androidx.compose.runtime.DisposableEffect(job) { onDispose { job.stop() } }
    FlatSheet({ job.stop(); onClose() }) {
        Txt("Smaller steps", LfmType.header)
        Txt(task.title, LfmType.small, c.text2, Modifier.padding(top = 2.dp))
        Txt(if (byRules) "A simple start, made on this phone. Nothing changes until you add it." else "On this phone. Nothing changes until you add it.",
            LfmType.meta, c.text2, Modifier.padding(top = 2.dp, bottom = 10.dp))
        if (job.running) {
            // The answer as it streams, so the wait is visible.
            val partial = Organizer.items(job.text, 6)
            if (partial.isEmpty()) Txt("Breaking it down…", LfmType.body, c.text2)
            partial.forEach { s -> StepRow(s, null) }
            Row(Modifier.padding(top = 12.dp)) { Btn("Stop", { job.stop() }, icon = "stop") }
            return@FlatSheet
        }
        steps.toList().forEachIndexed { i, s -> StepRow(s) { steps.removeAt(i) } }
        if (steps.isNotEmpty()) Txt("about ${leftLabel(steps.sumOf { it.minutes })} in all", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (steps.isNotEmpty()) Btn(if (steps.size == 1) "Add 1 step" else "Add ${steps.size} steps", {
                Organize(g, nav).addSteps(task, steps.toList()); onClose()
            }, icon = "plus", kind = BtnKind.Primary)
            if (model) Btn("Try again", { byRules = false; job.start(Steps.ask(task.title, task.body), Steps.MAX_TOKENS) }, icon = "sparkle")
            Btn("Close", onClose)
        }
    }
}

@Composable
private fun StepRow(s: Organizer.Sized, onRemove: (() -> Unit)?) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(20.dp).clip(LfmShape.mark).background(c.highlight))
        Txt(s.text, LfmType.body, c.text, Modifier.weight(1f).padding(start = 10.dp), maxLines = 3)
        Txt(Sizes.label(s.minutes), LfmType.meta, c.text2, Modifier.padding(horizontal = 6.dp))
        if (onRemove != null) IconButtonPlain("x", onRemove, desc = "Leave out ${s.text}")
    }
}

/** A calm plan (proposal 4): the model (or the rules) picks up to three; × drops one; Use this plan sets it. */
@Composable
internal fun PlanSheet(g: AppGraph, nav: Nav, open: List<Entry>, onClose: () -> Unit) {
    val c = Lfm.c
    val bounds = remember { DayBounds() }
    val list = remember(open) {
        val stepMinutes = open.filter { it.parentId != null }.groupBy { it.parentId }.mapValues { (_, s) -> s.sumOf { Sizes.of(it) } }
        DayPlan.candidates(open, bounds.end).map { it to (stepMinutes[it.id]?.takeIf { m -> m > 0 } ?: Sizes.of(it)) }
    }
    val model = g.engine.hasText()
    val job = remember { OrgJob(g) }
    val picks = remember { mutableStateListOf<DayPlan.Pick>() }
    var byRules by remember { mutableStateOf(!model) }
    fun draft() {
        picks.clear()
        if (list.isEmpty()) return
        if (model) { byRules = false; job.start(DayPlan.ask(list, bounds.minutesLeft), DayPlan.MAX_TOKENS) }
        else picks.addAll(DayPlan.rules(list, bounds.minutesLeft))
    }
    LaunchedEffect(Unit) { draft() }
    LaunchedEffect(job.finished) {
        if (!job.finished) return@LaunchedEffect
        val parsed = DayPlan.parse(job.text, list)
        picks.clear()
        if (parsed.isNotEmpty()) picks.addAll(parsed) else { byRules = true; picks.addAll(DayPlan.rules(list, bounds.minutesLeft)) }
    }
    androidx.compose.runtime.DisposableEffect(job) { onDispose { job.stop() } }
    FlatSheet({ job.stop(); onClose() }) {
        Txt("A calm plan for today", LfmType.header)
        Txt(when {
            list.isEmpty() -> "Nothing open to plan yet. Write a to-do on the page first."
            byRules -> "Up to three that fit, picked on this phone by simple rules."
            else -> "Up to three that fit, picked on this phone."
        }, LfmType.meta, c.text2, Modifier.padding(top = 2.dp, bottom = 10.dp))
        if (job.running) {
            Txt("Looking at ${list.size} open to-dos…", LfmType.body, c.text2)
            Row(Modifier.padding(top = 12.dp)) { Btn("Stop", { job.stop() }, icon = "stop") }
            return@FlatSheet
        }
        picks.toList().forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).clip(LfmShape.round).background(c.wash), contentAlignment = Alignment.Center) {
                    Txt("${i + 1}", LfmType.label, c.text)
                }
                Txt(p.entry.title, LfmType.body, c.text, Modifier.weight(1f).padding(start = 10.dp), maxLines = 2)
                Txt(Sizes.label(p.minutes), LfmType.meta, c.text2, Modifier.padding(horizontal = 6.dp))
                IconButtonPlain("x", { picks.removeAt(i) }, desc = "Leave out ${p.entry.title}")
            }
        }
        if (picks.isNotEmpty()) {
            val need = picks.sumOf { it.minutes }
            Txt("about ${leftLabel(need)} · ${leftLabel(bounds.minutesLeft)} left today", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
        }
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (picks.isNotEmpty()) Btn("Use this plan", { Organize(g, nav).usePlan(picks.toList(), bounds); onClose() }, icon = "check", kind = BtnKind.Primary)
            if (list.isNotEmpty()) Btn("Try again", { draft() }, icon = "sparkle")
            Btn("Close", onClose)
        }
    }
}

/** "Not today" for a to-do: tomorrow, next week or someday, never a red date. */
@Composable
internal fun NotTodayMenu(open: Boolean, onDismiss: () -> Unit, onPick: (LocalDate?, String) -> Unit) {
    Menu(open, onDismiss) {
        val today = LocalDate.now()
        MenuItem("Tomorrow", { onDismiss(); onPick(today.plusDays(1), "tomorrow") })
        MenuItem("Next week", { onDismiss(); onPick(today.plusWeeks(1), "next week") })
        MenuItem("Someday", { onDismiss(); onPick(null, "Someday") })
    }
}

/** A small line under the editor's to-do: its size, picked in one tap. */
@Composable
internal fun SizeChips(minutes: Int, guess: Int, onPick: (Int) -> Unit) {
    val c = Lfm.c
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Sizes.choices.forEach { m ->
            val on = minutes == m
            val shape = LfmShape.control
            Box(Modifier.heightIn(min = 36.dp).clip(shape).background(if (on) c.text else androidx.compose.ui.graphics.Color.Transparent)
                .border(1.dp, if (on) c.text else c.control, shape).clickable(role = Role.RadioButton) { onPick(if (on) 0 else m) }
                .padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Txt(Sizes.label(m), LfmType.button, if (on) c.bg else c.text2)
            }
        }
    }
    if (minutes == 0) Txt("Not set: about ${Sizes.label(guess)} is a guess", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
}

/** The steps of a to-do in its editor, each ticked or left out; Smaller steps to add more. */
@Composable
internal fun StepsList(g: AppGraph, nav: Nav, task: Entry, onSteps: () -> Unit) {
    val c = Lfm.c
    val steps by remember(task.id) { g.db.entries().steps(task.id) }.collectAsState(emptyList())
    val org = remember { Organize(g, nav) }
    Column {
        steps.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CheckBox(s.done) { on -> if (on) org.done(s) else g.scope.launch { g.db.entries().update(s.copy(done = false, doneAt = null)) } }
                Txt(s.title, LfmType.body, if (s.done) c.text3 else c.text, Modifier.weight(1f), maxLines = 3, decoration = if (s.done) TextDecoration.LineThrough else null)
                Txt(Sizes.label(Sizes.of(s)), LfmType.meta, c.text2, Modifier.padding(horizontal = 6.dp))
                IconButtonPlain("x", { g.scope.launch { g.db.entries().delete(s.id); nav.show("Step removed") { g.db.entries().insert(s) } } }, desc = "Remove ${s.title}")
            }
        }
        if (steps.isNotEmpty()) Divider(Modifier.padding(vertical = 4.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (steps.isEmpty()) "Smaller steps" else "More steps", onSteps, icon = "steps")
            if (!task.done) Btn("Focus", {
                val first = steps.firstOrNull { !it.done } ?: task
                g.focus.start(first.id, first.title, Sizes.of(first)); nav.go(Route.Focus)
            }, icon = "timer")
        }
    }
}
