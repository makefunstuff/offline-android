package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import com.lfmpixel.lfm.ui.components.FlatSheet
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.assistant.Margin
import com.lfmpixel.lfm.assistant.MarginAsk
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.InputLine
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.ListRow
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** One Ink-in-the-margin answer being written beside a note (notebook-spec proposal 3). */
class MarginRun(private val g: AppGraph) {
    var ask by mutableStateOf<MarginAsk?>(null)
        private set
    var text by mutableStateOf("")
        private set
    var running by mutableStateOf(false)
        private set
    var failed by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun start(note: String, what: MarginAsk, question: String = "") {
        job?.cancel()
        ask = what; text = ""; failed = null; running = true
        job = g.scope.launch {
            try {
                Margin.write(g.engine, note, what, question).collect { text = it }
                if (text.isBlank()) failed = "No answer came. Try again, or ask it another way."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e.message ?: "It stopped."
            } finally {
                running = false
            }
        }
    }

    fun stop() { job?.cancel(); job = null; running = false }

    fun dismiss() { stop(); ask = null; text = ""; failed = null }

    /** The pulled-out to-dos go on today's page, due today; Undo takes them all back. */
    fun addTodos(nav: Nav, list: List<String>) {
        g.scope.launch {
            val zone = ZoneId.systemDefault()
            val due = LocalDate.now().atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
            val ids = list.map { g.db.entries().insert(Entry(kind = EntryKind.Todo, title = it.replaceFirstChar { c -> c.uppercase() }, dueAt = due, source = "margin")) }
            nav.show(if (ids.size == 1) "1 to-do on today's page" else "${ids.size} to-dos on today's page") { ids.forEach { g.db.entries().delete(it) } }
        }
        dismiss()
    }
}

/** The Ink sheet: what to do with the note, or a question about it. */
@Composable
fun MarginSheet(g: AppGraph, nav: Nav, onPick: (MarginAsk, String) -> Unit, onClose: () -> Unit) {
    val c = Lfm.c
    FlatSheet(onClose) {
        Txt("Ink in the margin", LfmType.header)
        Txt("Runs on this phone. Your text changes only if you add what it writes.", LfmType.small, c.text2, Modifier.padding(top = 2.dp, bottom = 10.dp))
        if (!g.engine.hasText()) {
            Txt("This needs a chat model on the phone.", LfmType.body, modifier = Modifier.padding(bottom = 10.dp))
            Btn("Get a model", { onClose(); nav.go(Route.Models) }, kind = BtnKind.Primary)
            return@FlatSheet
        }
        listOf(MarginAsk.Sum, MarginAsk.Todos, MarginAsk.Questions, MarginAsk.Continue).forEach { a ->
            Divider()
            ListRow(a.label, icon = a.icon, onClick = { onPick(a, "") })
        }
        Divider()
        var q by rememberSaveable { mutableStateOf("") }
        InputLine(q, { q = it }, "Ask about this note…", "chat", Modifier.padding(top = 12.dp), imeAction = ImeAction.Send,
            onDone = { if (q.isNotBlank()) onPick(MarginAsk.Ask, q) }) {
            if (q.isNotBlank()) IconButtonPlain("send", { onPick(MarginAsk.Ask, q) }, tint = c.text, desc = "Ask")
        }
    }
}

/** The answer in the margin: a yellow edge, Inkling's words, then Add or Dismiss. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MarginBlock(run: MarginRun, nav: Nav, onAddToNote: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val ask = run.ask ?: return
    val shape = LfmShape.margin
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(c.fill)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(c.highlight))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
            Label("Inkling · ${ask.heading.lowercase()} · on this phone")
            val shown = when {
                run.failed != null && run.text.isBlank() -> run.failed!!
                run.text.isBlank() && run.running -> "Writing…"
                else -> run.text
            }
            Txt(shown, LfmType.body, if (run.text.isBlank()) c.text2 else c.text,
                Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (run.running) Btn("Stop", { run.stop() }, small = true, icon = "stop")
                else {
                    val todos = if (ask == MarginAsk.Todos) Margin.todos(run.text) else emptyList()
                    if (todos.isNotEmpty()) Btn(if (todos.size == 1) "Add 1 to-do" else "Add ${todos.size} to-dos", { run.addTodos(nav, todos) },
                        kind = BtnKind.Primary, small = true, icon = "todo")
                    if (run.text.isNotBlank()) Btn("Add to note", { onAddToNote(Margin.forNote(ask, run.text)); run.dismiss() },
                        kind = if (todos.isEmpty()) BtnKind.Primary else BtnKind.Secondary, small = true, icon = "plus")
                    Btn("Dismiss", { run.dismiss() }, small = true)
                }
            }
        }
    }
}
