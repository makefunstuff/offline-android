package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import com.lfmpixel.lfm.ui.components.FlatSheet
import com.lfmpixel.lfm.ui.theme.LfmShape
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", minLines: Int = 1) {
    val c = Lfm.c
    val shape = LfmShape.control
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
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, c.control, LfmShape.control).padding(horizontal = 10.dp, vertical = 8.dp),
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
                    Box(Modifier.width(6.dp).height(h.dp).background(c.text, LfmShape.mark))
                }
            }
            Column(Modifier.weight(1f)) {
                Marked(if (state == VoiceState.Listening) "Listening" else "Writing it down", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                Txt(if (state == VoiceState.Listening) "Tap stop when you're done" else "Omni, on this phone", LfmType.meta, c.text2)
            }
            if (state == VoiceState.Listening) Box(Modifier.size(52.dp).background(c.text, LfmShape.card).clickable(onClick = onStop)
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
