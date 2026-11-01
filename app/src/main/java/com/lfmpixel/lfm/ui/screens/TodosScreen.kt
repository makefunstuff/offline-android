package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.clickable
import com.lfmpixel.lfm.ui.components.PlaceTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Route
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import com.lfmpixel.lfm.ui.components.InputLine
import com.lfmpixel.lfm.ui.components.SwipeAction
import com.lfmpixel.lfm.ui.components.SwipeRow
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.components.CheckBox
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Tabs
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TodoTab(val label: String) { Today("Today"), Upcoming("Upcoming"), Someday("Someday") }

/** Which tab a to-do belongs to: due today or overdue, due later, or no date. */
fun todoTab(e: Entry, endOfToday: Long): TodoTab = when {
    e.dueAt == null -> TodoTab.Someday
    e.dueAt < endOfToday -> TodoTab.Today
    else -> TodoTab.Upcoming
}

@Composable
fun TodosScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    val todos by remember { g.db.entries().todos() }.collectAsState(null)
    var tab by rememberSaveable { mutableStateOf(TodoTab.Today) }
    var adding by rememberSaveable { mutableStateOf("") }
    val zone = ZoneId.systemDefault()
    val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // Steps show under their to-do (in its editor and on the Now card), not as to-dos of their own.
    val all = (todos ?: emptyList()).filter { it.parentId == null }
    val open = all.filter { !it.done && todoTab(it, endOfToday) == tab }
    val done = all.filter { it.done && todoTab(it, endOfToday) == tab && (it.doneAt ?: 0) > System.currentTimeMillis() - 7 * 86_400_000L }
    // The most urgent: overdue or earliest due today.
    val urgent = if (tab == TodoTab.Today) open.filter { it.dueAt != null }.minByOrNull { it.dueAt!! } else null
    val actions = remember { EntryActions(g, nav) }
    val focus = remember { FocusRequester() }
    val add: () -> Unit = {
        val text = adding.trim()
        if (text.isNotEmpty()) {
            // The tab says when: due today, tomorrow, or no date. The editor sets anything else.
            val due = when (tab) { TodoTab.Today -> LocalDate.now(); TodoTab.Upcoming -> LocalDate.now().plusDays(1); TodoTab.Someday -> null }
            g.scope.launch { g.db.entries().insert(Entry(kind = EntryKind.Todo, title = text.take(120), body = if (text.length > 120) text else "",
                dueAt = due?.atTime(9, 0)?.atZone(zone)?.toInstant()?.toEpochMilli())) }
            adding = ""
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PlaceTitle("Plans")
            val counts = TodoTab.entries.associateWith { t -> all.count { !it.done && todoTab(it, endOfToday) == t } }
            Tabs(TodoTab.entries.map { t -> counts[t].takeIf { it != 0 }?.let { "${t.label} $it" } ?: t.label },
                TodoTab.entries.first { it == tab }.let { t -> counts[t].takeIf { it != 0 }?.let { "${t.label} $it" } ?: t.label },
                { l -> tab = TodoTab.entries.first { l.startsWith(it.label) } }, Modifier.padding(horizontal = 16.dp))
            InputLine(adding, { adding = it }, when (tab) { TodoTab.Today -> "Add a to-do for today"; TodoTab.Upcoming -> "Add a to-do for tomorrow"; TodoTab.Someday -> "Add a to-do, no date" },
                "plus", Modifier.padding(horizontal = 16.dp), focus = focus, imeAction = ImeAction.Done, onDone = add) {
                // Say a to-do: the mic sits in the add line, so the only round button below is the talk button. While
                // it listens it stays (as Stop), even if something is typed.
                if (adding.isNotBlank()) IconButtonPlain("send", add, tint = c.text, desc = "Add")
                if (adding.isBlank() || listening) MicButton(onMic, listening = listening)
            }
            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                // Not before the to-dos are read: "Nothing due today" must not flash before the list.
                if (todos != null && open.isEmpty()) item(key = "empty") {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        Empty(when (tab) { TodoTab.Today -> "Nothing due today"; TodoTab.Upcoming -> "Nothing coming up"; TodoTab.Someday -> "Nothing for someday" },
                            "Type above and press Enter, or say “buy oat milk” or “renew passport by Friday”.")
                    }
                }
                items(open, key = { it.id }) { e ->
                    TodoRow(e, e == urgent, endOfToday, actions) { nav.go(Route.Editor(e.id, e.kind)) }
                }
                if (done.isNotEmpty()) {
                    item(key = "done") { Box(Modifier.padding(horizontal = 16.dp)) { SectionLabel("Done") } }
                    items(done, key = { it.id }) { e ->
                        TodoRow(e, false, endOfToday, actions) { nav.go(Route.Editor(e.id, e.kind)) }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LazyItemScope.TodoRow(e: Entry, urgent: Boolean, endOfToday: Long, actions: EntryActions, onClick: () -> Unit) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    Box(Modifier.animateItem(
        // Rows fade in where they land and fade out as the rest close up (ux-spec-v1 section 7).
        fadeInSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate),
        fadeOutSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.SHORT, easing = LfmMotion.emphasizedAccelerate),
        placementSpec = if (reduced) null else androidx.compose.animation.core.tween(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate))) {
        SwipeRow(SwipeAction(if (e.done) "Not done" else "Done", "check") { actions.done(e) }, SwipeAction("Delete", "trash") { actions.delete(e) }) {
            Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CheckBox(e.done) { actions.done(e) }
                    Column(Modifier.weight(1f).padding(top = 6.dp, bottom = 6.dp)) {
                        when {
                            e.done -> Txt(e.title, LfmType.body.copy(fontWeight = FontWeight.Normal), c.text3, decoration = TextDecoration.LineThrough)
                            urgent -> Marked(e.title, LfmType.body.copy(fontWeight = FontWeight.SemiBold))
                            else -> Txt(e.title, LfmType.body.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Txt(detail(e, endOfToday), LfmType.label.copy(fontWeight = null), c.text2, maxLines = 1)
                    }
                }
                com.lfmpixel.lfm.ui.components.Divider()
            }
        }
    }
}

private fun detail(e: Entry, endOfToday: Long): String {
    val zone = ZoneId.systemDefault()
    val parts = mutableListOf<String>()
    e.dueAt?.let { due ->
        val d = Instant.ofEpochMilli(due).atZone(zone)
        val startOfToday = endOfToday - 86_400_000L
        parts += when {
            // Never "overdue" (notebook-spec A.1, proposal 6): only where it came from.
            due < startOfToday -> "From " + d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
            due < endOfToday -> "Due today" + if (e.dueHasTime) " " + d.format(DateTimeFormatter.ofPattern("HH:mm")) else ""
            else -> "Due " + d.format(DateTimeFormatter.ofPattern(if (e.dueHasTime) "EEE d MMM HH:mm" else "EEE d MMM", Locale.ENGLISH))
        }
    }
    if (e.list.isNotEmpty()) parts += e.list
    if (e.minutes > 0) parts += com.lfmpixel.lfm.assistant.Sizes.label(e.minutes)
    if (e.source == "voice") parts += "from voice, " + Instant.ofEpochMilli(e.createdAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    else if (e.source == "chat") parts += "from chat"
    return parts.joinToString(" · ").ifEmpty { "No date" }
}
