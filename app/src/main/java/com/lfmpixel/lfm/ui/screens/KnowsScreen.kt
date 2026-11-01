package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.border
import com.lfmpixel.lfm.ui.components.FlatSheet
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.memory.Fact
import com.lfmpixel.lfm.memory.FactKind
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Switch
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Inkling knows (notebook-spec section 6): every fact it learned, where it came from, each one editable and
 * deletable; the corrections it keeps; the patterns it noticed (shown here only, never pushed); the learning switch
 * and Forget everything learned.
 */
@Composable
fun KnowsScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val allFacts by g.memory.facts.collectAsState()
    // While the journal is locked, what was learned from it stays hidden here too.
    val journalLocked = g.lock.locks(com.lfmpixel.lfm.settings.LockMode.Journal)
    val facts = if (journalLocked) allFacts.filter { !it.journal } else allFacts
    val corrections by g.memory.corrections.collectAsState()
    val patterns by g.memory.patterns.collectAsState()
    val lastRun by g.memory.lastRun.collectAsState()
    val working by g.memory.working.collectAsState()
    val ui = rememberCoroutineScope()
    // A change the keystore or a full disk refuses is said, not a crash (a failure here would close the app).
    val scope = remember(ui) { kotlinx.coroutines.CoroutineScope(ui.coroutineContext + kotlinx.coroutines.SupervisorJob(ui.coroutineContext[kotlinx.coroutines.Job]) + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.w("lfm-memory", "change not kept", e); nav.show("Couldn't save that change. Try again") }) }
    var editing by remember { mutableStateOf<Fact?>(null) }
    var adding by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { g.memory.load() }
    val p = prefs ?: return
    val day = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    fun date(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(day)
    Column(Modifier.fillMaxSize()) {
        Header("What Inkling knows", onBack = { nav.back() })
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item {
                Group {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("Learn from my notes", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                            Txt(if (p.learning) "On · from notes, chats, Talk and your corrections. Kept on this phone, sealed."
                                else "Off · nothing is learned, and nothing learned is used. Answers still read your notes.", LfmType.label.copy(fontWeight = null), c.text2)
                        }
                        Switch(p.learning, "Learn from my notes") { on -> scope.launch { g.settings.setLearning(on) } }
                    }
                }
                Txt("Answers use these when they help. Only what one answer needs goes with it to your server, as your notes do; " +
                    "health, money and the locked journal stay out of the standing profile.", LfmType.meta, c.text2, Modifier.padding(top = 8.dp))
            }
            val byKind = facts.groupBy { it.kind }
            if (journalLocked && allFacts.size > facts.size) item {
                Txt("${allFacts.size - facts.size} learned from your journal are hidden while it is locked.", LfmType.meta, c.text2, Modifier.padding(top = 8.dp))
            }
            if (facts.isEmpty()) item { Empty("Nothing yet", "As you write (\"my sister Ana…\", \"I prefer mornings\"), what lasts shows here, with where it came from.") }
            for (kind in FactKind.entries) {
                val list = byKind[kind] ?: continue
                item(key = "h" + kind.name) { SectionLabel(kind.label) }
                items(list.sortedByDescending { it.lastSeen }, key = { "f" + it.id }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { editing = f }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(f.text, LfmType.body)
                            Txt(listOfNotNull(f.source.label, date(f.source.at), if (f.mentions > 1) "seen ${f.mentions} times" else null,
                                if (f.edited) "your words" else null).joinToString(" · "), LfmType.meta, c.text2, maxLines = 2)
                        }
                        IconButtonPlain("x", { scope.launch { g.memory.delete(f.id) } }, desc = "Forget this")
                    }
                    Divider()
                }
            }
            item { Btn("Add something Inkling should know", { adding = true }, Modifier.padding(top = 12.dp).fillMaxWidth(), icon = "plus") }
            if (corrections.isNotEmpty()) {
                item { SectionLabel("Corrections it keeps") }
                items(corrections.reversed(), key = { "c" + it.id }) { k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("“${k.text}”", LfmType.body, maxLines = 2)
                            Txt(when (k.kind) {
                                com.lfmpixel.lfm.memory.CorrectionKind.Kind -> "Goes to ${k.to}" + (if (k.from.isNotEmpty()) ", not ${k.from}" else "")
                                com.lfmpixel.lfm.memory.CorrectionKind.List -> "On the ${k.to} list" + (if (k.from.isNotEmpty()) ", not ${k.from}" else "")
                            } + " · " + date(k.at), LfmType.meta, c.text2)
                        }
                        IconButtonPlain("x", { scope.launch { g.memory.deleteCorrection(k.id) } }, desc = "Forget this correction")
                    }
                    Divider()
                }
            }
            if (patterns.isNotEmpty()) {
                item { SectionLabel("Patterns it noticed") }
                items(patterns, key = { "p$it" }) { line -> Txt(line, LfmType.body, modifier = Modifier.padding(vertical = 6.dp)) }
                item { Txt("Just for you to see; Inkling won't nag about them.", LfmType.meta, c.text2) }
            }
            item {
                SectionLabel("Tidying up")
                Txt(lastRun.ifEmpty { "Each night while the phone charges, Inkling merges duplicates, lets stale facts go and looks for patterns." },
                    LfmType.meta, c.text2)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn(if (working) "Learning…" else "Learn now", {
                        if (!working) g.scope.launch { runCatching { g.memory.nightly { if (g.engine.canChat()) g.engine.textSession() else null } } }
                    }, Modifier.weight(1f), icon = "sparkle")
                    Btn("Forget everything", { forgetting = true }, Modifier.weight(1f), icon = "trash")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    editing?.let { f ->
        FactSheet("Edit what Inkling knows", f.text, "Save", onDelete = { scope.launch { g.memory.delete(f.id) }; editing = null },
            onClose = { editing = null }) { text -> scope.launch { g.memory.edit(f.id, text) }; editing = null }
    }
    if (adding) FactSheet("Add something Inkling should know", "", "Add", onDelete = null, onClose = { adding = false }) { text ->
        scope.launch { g.memory.add(FactKind.Other, text) }; adding = false
    }
    if (forgetting) FlatSheet(onDismiss = { forgetting = false }) {
        Txt("Forget everything learned?", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.semantics { heading() })
        Txt("Every fact, correction and pattern, and the key that sealed them. Your notes stay as they are. It can't be undone.",
            LfmType.small, c.text2, Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", { forgetting = false }, Modifier.weight(1f))
            Btn("Forget", { scope.launch { g.memory.forget(); nav.show("Inkling forgot everything it learned") }; forgetting = false },
                Modifier.weight(1f), BtnKind.Primary, icon = "trash")
        }
    }
}

@Composable
private fun FactSheet(title: String, start: String, action: String, onDelete: (() -> Unit)?, onClose: () -> Unit, onSave: (String) -> Unit) {
    val c = Lfm.c
    var text by remember { mutableStateOf(start) }
    FlatSheet(onDismiss = onClose) {
        Txt(title, LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.semantics { heading() })
        val shape = LfmShape.card
        BasicTextField(text, { text = it }, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().heightIn(min = 48.dp).border(1.dp, c.control, shape).padding(12.dp))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onDelete != null) Btn("Forget it", onDelete, Modifier.weight(1f), icon = "trash")
            Btn(action, { if (text.isNotBlank()) onSave(text) }, Modifier.weight(1f), BtnKind.Primary)
        }
    }
}
