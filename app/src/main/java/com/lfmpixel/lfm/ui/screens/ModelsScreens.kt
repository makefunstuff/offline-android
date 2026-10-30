package com.lfmpixel.lfm.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.llm.CheckResult
import com.lfmpixel.lfm.llm.LoadState
import com.lfmpixel.lfm.llm.ModelCatalog
import com.lfmpixel.lfm.llm.ModelRole
import com.lfmpixel.lfm.llm.ModelStore
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.formatBytes
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.Pill
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(g: AppGraph, nav: Nav, onImport: () -> Unit) {
    val c = Lfm.c
    val installed by g.models.installed.collectAsState()
    val status by g.engine.status.collectAsState()
    val used = g.models.usedBytes()
    val free = g.models.freeBytes()
    Column(Modifier.fillMaxSize()) {
        Header("Models", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                Bar(if (used + free > 0) used.toFloat() / (used + free) else 0f)
                Txt("${formatBytes(used)} used · ${formatBytes(free)} free", LfmType.label.copy(fontWeight = null), c.text2)
            }
            installed.forEach { m ->
                val inMemory = (m.known.role == ModelRole.Text && status.text == LoadState.Ready && g.models.text()?.file == m.file) ||
                    (m.known.role in setOf(ModelRole.Omni, ModelRole.OmniProjector) && status.omni == LoadState.Ready)
                Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(m.known.family, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.weight(1f))
                        if (inMemory) Pill("In memory")
                        IconButtonPlain("trash", { g.models.delete(m) }, desc = "Delete ${m.known.fileName}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt("${m.known.quant} · ${m.known.sizeLabel} · ", LfmType.label.copy(fontWeight = null), c.text2)
                        if (m.verified) { LIcon("check", size = 16.dp, tint = c.text2); Txt("verified", LfmType.label.copy(fontWeight = null), c.text2) }
                        else Txt("not verified", LfmType.label.copy(fontWeight = null), c.text2)
                    }
                    Txt(m.known.role.label, LfmType.label.copy(fontWeight = null), c.text2)
                }
            }
            val missing = listOf(ModelRole.Text, ModelRole.Omni, ModelRole.OmniProjector).filter { r -> installed.none { it.known.role == r } }
            if (missing.isNotEmpty()) {
                SectionLabel("Not installed")
                missing.forEach { r ->
                    val rec = ModelCatalog.all.first { it.role == r }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Txt(rec.fileName, LfmType.small.copy(fontWeight = LfmType.rowTitle.fontWeight))
                            Txt("${rec.sizeLabel} · ${r.label} · huggingface.co/${rec.repo}", LfmType.meta, c.text2)
                        }
                    }
                    Divider()
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Import a file", onImport, Modifier.weight(1f), BtnKind.Primary, icon = "folder")
                Btn("Download", {}, Modifier.weight(1f), icon = "download", enabled = false)
            }
            Txt("Import works fully offline: copy the GGUF to the phone (adb push or USB), then pick it. Download comes with the LFM Net helper.",
                LfmType.meta, c.text2, Modifier.padding(top = 12.dp))
            g.engine.systemInfo().takeIf { it.isNotEmpty() }?.let {
                SectionLabel("llama.cpp on this phone")
                Txt(it.trim(), LfmType.meta, c.text2)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    val c = Lfm.c
    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(4.dp).background(c.line, RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.01f, 1f)).height(4.dp).background(c.text, RoundedCornerShape(2.dp)))
    }
}

/** One import: copy, known model, SHA-256, test prompt. Lives in the app graph so it survives leaving the screen. */
class ImportRun(val uris: List<Uri>) {
    class FileState(val picked: ModelStore.Picked) {
        var copied by mutableStateOf(0L)
        var result by mutableStateOf<CheckResult?>(null)
        var error by mutableStateOf<String?>(null)
        var test by mutableStateOf<String?>(null)
        var originalDeleted by mutableStateOf(false)
    }
    val files = mutableStateListOf<FileState>()
    var done by mutableStateOf(false)

    fun start(g: AppGraph) {
        files.addAll(uris.map { FileState(g.models.describe(it)) })
        g.scope.launch {
            for (f in files) {
                try {
                    f.result = g.models.import(f.picked) { step -> if (step is ModelStore.Step.Copying) f.copied = step.done }
                } catch (e: Exception) {
                    f.error = e.message ?: "Copy failed"
                }
                val r = f.result
                if (r is CheckResult.Ok && r.model.role == ModelRole.Text) {
                    f.test = "Loading…"
                    try {
                        g.engine.unloadAll()
                        val t0 = System.nanoTime()
                        val s = g.engine.textSession()
                        val prompt = PromptFormat.build("", emptyList(), listOf(PromptMessage(PromptMessage.Role.User, "Say hello in three words.")), thinking = false)
                        val out = s.generate(prompt, 16, temp = 0f).toList().joinToString("").trim()
                        f.test = "“${out.take(40)}” · %.1f s".format((System.nanoTime() - t0) / 1e9)
                    } catch (e: Exception) {
                        f.test = "Failed: ${e.message}"
                    }
                }
            }
            done = true
        }
    }
}

@Composable
fun ImportScreen(g: AppGraph, nav: Nav, run: ImportRun) {
    val c = Lfm.c
    Column(Modifier.fillMaxSize()) {
        Header("Import model", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            run.files.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LIcon("folder")
                    Column(Modifier.weight(1f)) {
                        Txt(f.picked.name, LfmType.small.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 1)
                        Txt(if (f.picked.size >= 0) formatBytes(f.picked.size) else "size unknown", LfmType.label.copy(fontWeight = null), c.text2)
                    }
                }
                Divider()
            }
            SectionLabel("Checks")
            run.files.forEach { f ->
                val r = f.result
                val total = (r as? CheckResult.Ok)?.model?.sizeBytes ?: f.picked.size
                when {
                    f.error != null -> Check(false, "Could not copy ${f.picked.name}", f.error!!)
                    r == null -> {
                        Check(null, "Copying ${f.picked.name}", "${formatBytes(f.copied)} of ${formatBytes(maxOf(total, 0))} · hashing as it goes")
                        Bar(if (total > 0) f.copied.toFloat() / total else 0f)
                    }
                    r is CheckResult.Unknown -> Check(false, "Not a known model", "${r.fileName} is not in the pinned list, so it was not copied")
                    r is CheckResult.WrongSize -> Check(false, "Wrong size for ${r.model.family} ${r.model.quant}", "${formatBytes(r.size)}, expected ${r.model.sizeLabel}")
                    r is CheckResult.WrongHash -> Check(false, "SHA-256 does not match", "${short(r.sha256)} is not the pinned ${short(r.model.sha256)}. The copy was deleted.")
                    r is CheckResult.Ok -> {
                        Check(true, "Copied into the app", r.model.sizeLabel)
                        Check(true, "Known model: ${r.model.family}, ${r.model.quant}", "Matches a pinned name and size")
                        Check(true, null, "${short(r.sha256)} matches the pinned hash", marked = "SHA-256 verified")
                        f.test?.let { t -> Check(if (t.startsWith("Failed")) false else if (t == "Loading…") null else true, "Loads and answers a test prompt", t) }
                    }
                }
            }
            if (run.done && run.files.any { it.result is CheckResult.Ok && !it.originalDeleted }) {
                Row(Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, c.control, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LIcon("trash")
                    Column(Modifier.weight(1f)) {
                        Txt("Delete the originals?", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        Txt("Frees ${formatBytes(run.files.filter { it.result is CheckResult.Ok }.sumOf { it.picked.size.coerceAtLeast(0) })} where they were",
                            LfmType.label.copy(fontWeight = null), c.text2)
                    }
                    Btn("Delete", { run.files.filter { it.result is CheckResult.Ok }.forEach { it.originalDeleted = g.models.deleteOriginal(it.picked.uri) } }, small = true)
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Btn(if (run.done) "Done" else "Working…", { if (run.done) nav.back() }, Modifier.fillMaxWidth(), BtnKind.Primary, enabled = run.done)
        }
    }
}

private fun short(h: String) = if (h.length > 8) h.take(4) + "…" + h.takeLast(4) else h

@Composable
private fun Check(ok: Boolean?, title: String?, detail: String, marked: String? = null) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (ok) { true -> LIcon("check"); false -> LIcon("x"); null -> LIcon("download", tint = c.text2) }
        }
        Column(Modifier.weight(1f)) {
            if (marked != null) Marked(marked, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            else Txt(title ?: "", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(detail, LfmType.label.copy(fontWeight = null), c.text2)
        }
    }
}
