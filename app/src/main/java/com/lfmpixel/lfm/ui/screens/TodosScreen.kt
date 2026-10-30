package com.lfmpixel.lfm.ui.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.EntrySheet
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
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val endOfToday = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val all = todos ?: emptyList()
    val open = all.filter { !it.done && todoTab(it, endOfToday) == tab }
    val done = all.filter { it.done && todoTab(it, endOfToday) == tab && (it.doneAt ?: 0) > System.currentTimeMillis() - 7 * 86_400_000L }
    // The most urgent: overdue or earliest due today.
    val urgent = if (tab == TodoTab.Today) open.filter { it.dueAt != null }.minByOrNull { it.dueAt!! } else null

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt("To-dos", LfmType.title2, modifier = Modifier.weight(1f))
                IconButtonPlain("plus", { nav.entrySheet = EntrySheet(kind = EntryKind.Todo) }, desc = "New to-do")
            }
            Tabs(TodoTab.entries.map { it.label }, tab.label, { t -> tab = TodoTab.entries.first { it.label == t } }, Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                if (open.isEmpty()) item {
                    Empty(when (tab) { TodoTab.Today -> "Nothing due today"; TodoTab.Upcoming -> "Nothing coming up"; TodoTab.Someday -> "Nothing for someday" },
                        "Say “buy oat milk” or “renew passport by Friday”.")
                }
                items(open, key = { it.id }) { e ->
                    TodoRow(e, e == urgent, endOfToday, onCheck = { scope.launch { g.db.entries().update(e.copy(done = true, doneAt = System.currentTimeMillis())) } }) {
                        nav.entrySheet = EntrySheet(e.id, e.kind)
                    }
                }
                if (done.isNotEmpty()) {
                    item { SectionLabel("Done") }
                    items(done, key = { it.id }) { e ->
                        TodoRow(e, false, endOfToday, onCheck = { scope.launch { g.db.entries().update(e.copy(done = false, doneAt = null)) } }) {
                            nav.entrySheet = EntrySheet(e.id, e.kind)
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
        MicButton(onMic, Modifier.align(Alignment.BottomEnd).padding(16.dp), size = 56.dp, listening = listening)
    }
}

@Composable
private fun TodoRow(e: Entry, urgent: Boolean, endOfToday: Long, onCheck: () -> Unit, onClick: () -> Unit) {
    val c = Lfm.c
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(top = 0.dp)) { CheckBox(e.done) { onCheck() } }
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

private fun detail(e: Entry, endOfToday: Long): String {
    val zone = ZoneId.systemDefault()
    val parts = mutableListOf<String>()
    e.dueAt?.let { due ->
        val d = Instant.ofEpochMilli(due).atZone(zone)
        val startOfToday = endOfToday - 86_400_000L
        parts += when {
            due < startOfToday -> "Overdue · " + d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
            due < endOfToday -> "Due today" + if (e.dueHasTime) " " + d.format(DateTimeFormatter.ofPattern("HH:mm")) else ""
            else -> "Due " + d.format(DateTimeFormatter.ofPattern(if (e.dueHasTime) "EEE d MMM HH:mm" else "EEE d MMM", Locale.ENGLISH))
        }
    }
    if (e.list.isNotEmpty()) parts += e.list
    if (e.source == "voice") parts += "from voice, " + Instant.ofEpochMilli(e.createdAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    else if (e.source == "chat") parts += "from chat"
    return parts.joinToString(" · ").ifEmpty { "No date" }
}
