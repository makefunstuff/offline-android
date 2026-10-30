package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.assistant.ConfirmRequest
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.tools.TimeArgs
import com.lfmpixel.lfm.ui.EntrySheet
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.Segmented
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.voice.VoiceState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A flat bottom sheet: 12 px top corners, a grab bar, a scrim, no shadow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlatSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Lfm.c
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        containerColor = c.bg,
        contentColor = c.text,
        tonalElevation = 0.dp,
        scrimColor = c.scrim.copy(alpha = 0.45f),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 14.dp).width(36.dp).height(4.dp).background(c.text3, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 18.dp).navigationBarsPadding(),
            content = content)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", minLines: Int = 1) {
    val c = Lfm.c
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.padding(top = 10.dp)) {
        Label(label)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 44.dp).background(c.fill, shape).border(1.dp, c.control, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) Txt(placeholder, LfmType.body, c.text2)
            BasicTextField(value, onChange, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text),
                minLines = minLines, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}

/** Confirmation sheet: the exact fields, Edit, Cancel and a button named for the action. */
@Composable
fun ConfirmSheet(req: ConfirmRequest) {
    val c = Lfm.c
    var editing by remember(req) { mutableStateOf(false) }
    val edits = remember(req) { mutableStateMapOf<String, String>().apply { putAll(req.args.filterKeys { !it.startsWith("_") }) } }
    FlatSheet(onDismiss = { req.cancel() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            LIcon(req.preview.icon)
            Txt(req.preview.title, LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        }
        if (req.afterWebText) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, c.control, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LIcon("warn", size = 16.dp)
                Column { Marked("Suggested after reading web pages.", LfmType.small); Txt("Check it before you confirm.", LfmType.small) }
            }
        }
        if (!editing) {
            req.preview.fields.filter { it.value.isNotBlank() }.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Txt(f.label, LfmType.body, c.text2)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (f.highlight) Marked(f.value, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        else Txt(f.value, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight, textAlign = TextAlign.End))
                    }
                }
                Divider()
            }
        } else {
            req.preview.fields.filter { it.editable && edits.containsKey(it.key) }.forEach { f ->
                Field(f.label, edits[f.key] ?: "", { edits[f.key] = it })
            }
            req.preview.fields.filter { !it.editable }.forEach { f -> Txt("${f.label}: ${f.value}", LfmType.small, c.text2, Modifier.padding(top = 10.dp)) }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editing && req.preview.fields.any { it.editable }) Btn("Edit", { editing = true }, Modifier.weight(1f))
            Btn("Cancel", { req.cancel() }, Modifier.weight(1f))
            Btn(req.preview.confirmLabel, { req.confirm(if (editing) req.args + edits else req.args) }, Modifier.weight(1f), BtnKind.Primary)
        }
        val fine = if (req.afterWebText) "Actions suggested by web text always ask, even ones set to run directly." else req.preview.note
        if (fine.isNotEmpty()) Txt(fine, LfmType.meta, c.text2, Modifier.padding(top = 12.dp))
    }
}

/** Add or edit a note, idea, journal line or to-do; one tap moves it to another kind. */
@Composable
fun EntryEditor(g: AppGraph, sheet: EntrySheet, onClose: () -> Unit) {
    val c = Lfm.c
    val scope = rememberCoroutineScope()
    var loaded by remember(sheet) { mutableStateOf(sheet.id == null) }
    var entry by remember(sheet) { mutableStateOf(Entry(kind = sheet.kind, title = "", body = sheet.text, source = "typed")) }
    var text by remember(sheet) { mutableStateOf(sheet.text) }
    var due by remember(sheet) { mutableStateOf("") }
    var list by remember(sheet) { mutableStateOf("") }
    var tags by remember(sheet) { mutableStateOf("") }
    var error by remember(sheet) { mutableStateOf("") }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(sheet) {
        sheet.id?.let { id ->
            g.db.entries().get(id)?.let { e ->
                entry = e
                text = when {
                    // Notes from the assistant keep the whole text in the body, and its first line as the title.
                    e.kind == EntryKind.Note && e.body.startsWith(e.title.removeSuffix("…")) -> e.body
                    e.kind == EntryKind.Note -> listOf(e.title, e.body).filter { it.isNotBlank() }.joinToString("\n")
                    else -> e.body.ifBlank { e.title }
                }
                due = e.dueAt?.let { TimeArgs.iso(com.lfmpixel.lfm.tools.When(Instant.ofEpochMilli(it).atZone(zone).toLocalDate(),
                    if (e.dueHasTime) Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) else null)) } ?: ""
                list = e.list
                tags = if (e.kind == EntryKind.Idea) e.topic else e.tags
            }
            loaded = true
        }
    }
    FlatSheet(onDismiss = onClose) {
        if (!loaded) return@FlatSheet
        Txt(if (sheet.id == null) "New ${entry.kind.label.lowercase()}" else entry.kind.label, LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        Label("Kind", Modifier.padding(top = 14.dp, bottom = 6.dp))
        Segmented(EntryKind.entries.map { it.label }, entry.kind.label, { l -> entry = entry.copy(kind = EntryKind.entries.first { it.label == l }) })
        Field(if (entry.kind == EntryKind.Journal) "Entry" else "Text", text, { text = it }, minLines = if (entry.kind == EntryKind.Todo) 1 else 4)
        when (entry.kind) {
            EntryKind.Todo -> {
                Field("Due", due, { due = it }, placeholder = "e.g. tomorrow 17:00, friday, 2026-11-06")
                Field("List", list, { list = it }, placeholder = "Shopping, Work, Home")
            }
            EntryKind.Idea -> Field("Topic", tags, { tags = it }, placeholder = "Side projects")
            EntryKind.Note -> Field("Tags", tags, { tags = it }, placeholder = "work home")
            EntryKind.Journal -> {
                Label("Mood", Modifier.padding(top = 10.dp, bottom = 6.dp))
                Segmented(listOf("None", "Good", "Low"), listOf("None", "Good", "Low")[entry.mood], { m -> entry = entry.copy(mood = listOf("None", "Good", "Low").indexOf(m)) })
            }
        }
        if (error.isNotEmpty()) Txt(error, LfmType.small, c.text, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sheet.id != null) Btn("Delete", { scope.launch { g.db.entries().delete(sheet.id); onClose() } }, Modifier.weight(1f), icon = "trash")
            Btn("Cancel", onClose, Modifier.weight(1f))
            Btn("Save", {
                val body = text.trim()
                if (body.isEmpty()) { error = "Write something first."; return@Btn }
                val w = if (entry.kind == EntryKind.Todo && due.isNotBlank()) TimeArgs.parse(due, LocalDateTime.now()) else null
                if (entry.kind == EntryKind.Todo && due.isNotBlank() && w == null) { error = "I couldn't read that date."; return@Btn }
                val first = body.lineSequence().first().trim()
                val e = entry.copy(
                    title = first.take(120),
                    body = if (entry.kind == EntryKind.Note) body.lineSequence().drop(1).joinToString("\n").trim() else if (body.length > 120 || body.contains('\n')) body else "",
                    dueAt = w?.epochMillis(zone) ?: if (entry.kind == EntryKind.Todo) null else entry.dueAt,
                    dueHasTime = w?.time != null,
                    list = if (entry.kind == EntryKind.Todo) list.trim() else entry.list,
                    tags = if (entry.kind == EntryKind.Note) tags.trim().lowercase() else entry.tags,
                    topic = if (entry.kind == EntryKind.Idea) tags.trim() else entry.topic,
                    updatedAt = System.currentTimeMillis(),
                )
                scope.launch { if (sheet.id == null) g.db.entries().insert(e) else g.db.entries().update(e); onClose() }
            }, Modifier.weight(1f), BtnKind.Primary)
        }
    }
}

/** Listening / transcribing: what it is doing now gets the highlighter. */
@Composable
fun VoicePanel(g: AppGraph, onStop: () -> Unit) {
    val c = Lfm.c
    val state by g.voice.state.collectAsState()
    val level by g.voice.level.collectAsState()
    if (state == VoiceState.Idle) return
    Column(Modifier.fillMaxWidth().background(c.bg).padding(16.dp)) {
        Divider()
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val base = listOf(0.4f, 0.8f, 1f, 0.6f, 0.9f, 0.5f, 0.3f)
                base.forEach { b ->
                    val h = if (state == VoiceState.Listening) (6f + 34f * b * (0.25f + (level * 4f).coerceAtMost(0.75f))) else 6f
                    Box(Modifier.width(6.dp).height(h.dp).background(c.text, RoundedCornerShape(2.dp)))
                }
            }
            Column(Modifier.weight(1f)) {
                Marked(if (state == VoiceState.Listening) "Listening" else "Writing it down", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                Txt(if (state == VoiceState.Listening) "Tap stop when you're done" else "Omni, on this phone", LfmType.meta, c.text2)
            }
            if (state == VoiceState.Listening) Box(Modifier.size(52.dp).background(c.text, RoundedCornerShape(8.dp)).clickable(onClick = onStop)
                .semantics { contentDescription = "Stop" }, contentAlignment = Alignment.Center) {
                LIcon("stop", tint = c.bg)
            }
        }
    }
}

/** Shown when voice is tapped but Omni isn't installed: the seam, said plainly. */
@Composable
fun VoiceInfoSheet(message: String, onModels: () -> Unit, onClose: () -> Unit) {
    val c = Lfm.c
    FlatSheet(onDismiss = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LIcon("mic"); Txt("Voice", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        }
        Txt(message, LfmType.body, c.text, Modifier.padding(top = 10.dp))
        Txt("Speech is turned into text by LFM3-Omni-2B on this phone; no audio leaves it.", LfmType.small, c.text2, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Close", onClose, Modifier.weight(1f))
            Btn("Models", onModels, Modifier.weight(1f), BtnKind.Primary)
        }
        Spacer(Modifier.height(4.dp))
    }
}
