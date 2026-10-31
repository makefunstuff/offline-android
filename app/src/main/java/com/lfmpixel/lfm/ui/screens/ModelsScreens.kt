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
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
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
import com.lfmpixel.lfm.web.ModelDownloads
import com.lfmpixel.lfm.web.NetClient
import com.lfmpixel.lfm.web.NetInstaller
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(g: AppGraph, nav: Nav, onImport: () -> Unit) {
    val c = Lfm.c
    val installed by g.models.installed.collectAsState()
    val downloads by g.downloads.items.collectAsState()
    val used = g.models.usedBytes()
    val free = g.models.freeBytes()
    Column(Modifier.fillMaxSize()) {
        Header("Models", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                Bar(if (used + free > 0) used.toFloat() / (used + free) else 0f)
                Txt("${formatBytes(used)} used · ${formatBytes(free)} free", LfmType.label.copy(fontWeight = null), c.text2)
            }
            ModelList(g, highlight = true)
            // Files imported by hand that the list above doesn't offer (other quantisations).
            val offered = ModelCatalog.groups.flatMap { it.variants + listOfNotNull(it.projector) }.toSet()
            val others = installed.filter { it.known !in offered }
            if (others.isNotEmpty()) {
                SectionLabel("Also on this phone")
                others.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt("${m.known.family} ${m.known.quant}", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                            Txt("${m.known.sizeLabel} · ${if (m.verified) "verified" else "not verified"} · ${m.known.role.label}", LfmType.label.copy(fontWeight = null), c.text2)
                        }
                        IconButtonPlain("trash", { g.models.delete(m) }, desc = "Delete ${m.known.fileName}")
                    }
                    Divider()
                }
            }
            Btn("Import a file", onImport, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = "folder")
            Txt("Downloads come from Hugging Face with no login, through LFM Net, and each file is checked against its pinned SHA-256 before it is used. " +
                "Import works fully offline: copy the GGUF to the phone (adb push or USB), then pick it.", LfmType.meta, c.text2, Modifier.padding(top = 12.dp))
            if (downloads.isEmpty()) g.engine.systemInfo().takeIf { it.isNotEmpty() }?.let {
                SectionLabel("llama.cpp on this phone")
                Txt(it.trim(), LfmType.meta, c.text2)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Every model LFM uses, with size, what it is for, and Download / progress / Delete (spec screen 13). Also on
 * first run. [highlight] puts the highlighter on the download in progress; first run has no yellow.
 */
@Composable
fun ModelList(g: AppGraph, highlight: Boolean) {
    val c = Lfm.c
    val installed by g.models.installed.collectAsState()
    val downloads by g.downloads.items.collectAsState()
    val status by g.engine.status.collectAsState()
    val netState by g.net.current.collectAsState()
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { g.net.refresh() }
    val canDownload = netState == NetClient.State.On
    val running = downloads.values.firstOrNull { it.state == ModelDownloads.State.Running || it.state == ModelDownloads.State.Checking }
    netState?.takeIf { !canDownload }?.let { NetMissingCard(g, it) }
    if (canDownload) Row(Modifier.padding(bottom = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt("Download on Wi-Fi only", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(if (prefs?.downloadWifiOnly != false) "Waits for Wi-Fi; never uses mobile data" else "Also over mobile data", LfmType.label.copy(fontWeight = null), c.text2)
        }
        com.lfmpixel.lfm.ui.components.Switch(prefs?.downloadWifiOnly != false) { scope.launch { g.settings.setDownloadWifiOnly(it) } }
    }
    ModelCatalog.groups.forEach { grp ->
        Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Txt(grp.family, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(grp.purpose, LfmType.label.copy(fontWeight = null), c.text2)
            (grp.variants + listOfNotNull(grp.projector)).forEach { m ->
                val have = installed.firstOrNull { it.known == m }
                val d = downloads[m.fileName]?.takeIf { have == null && it.state != ModelDownloads.State.Done }
                val inMemory = have != null && ((m.role == ModelRole.Text && status.text == LoadState.Ready && g.models.text()?.file == have.file) ||
                    (m.role in setOf(ModelRole.Omni, ModelRole.OmniProjector) && status.omni == LoadState.Ready))
                Divider(Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(if (m == grp.projector) "Projector ${m.quant}" else m.quant + if (m.quant.startsWith("Q4")) " · smaller, faster" else " · more accurate",
                            LfmType.small.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Txt(m.sizeLabel + if (m == grp.projector) " · needed with either" else "", LfmType.label.copy(fontWeight = null), c.text2)
                            if (have != null) {
                                Txt("·", LfmType.label, c.text2)
                                if (have.verified) { LIcon("check", size = 14.dp, tint = c.text2); Txt("verified", LfmType.label.copy(fontWeight = null), c.text2) }
                                else Txt("not verified", LfmType.label.copy(fontWeight = null), c.text2)
                            }
                        }
                    }
                    when {
                        have != null -> {
                            if (inMemory) Pill("In memory")
                            IconButtonPlain("trash", { g.models.delete(have) }, desc = "Delete ${m.fileName}")
                        }
                        d == null && canDownload -> Btn("Download", { g.downloads.startWithProjector(m) }, small = true, icon = "download")
                        d == null -> Txt("Not on this phone", LfmType.meta, c.text2)
                    }
                }
                if (d != null) DownloadProgress(d, highlight && d == running, onPause = { g.downloads.pause(m) },
                    onResume = { g.downloads.resume(m) }, onCancel = { g.downloads.cancel(m) })
            }
        }
    }
}

/** Said plainly: downloads need the helper app; offer to install it from the copy inside LFM. */
@Composable
private fun NetMissingCard(g: AppGraph, st: NetClient.State) {
    val c = Lfm.c
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val install by g.netInstaller.status.collectAsState()
    Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().border(1.dp, c.text, RoundedCornerShape(8.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIcon("download", size = 18.dp)
            Txt(when (st) {
                NetClient.State.NotInstalled -> "LFM Net is not installed"
                NetClient.State.Off -> "LFM Net is turned off"
                else -> "LFM Net is signed with another key"
            }, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
        }
        Txt(when (st) {
            NetClient.State.NotInstalled -> "Downloads come through LFM Net, a small helper app. It is the only part with network access; LFM itself has none. Without it, import model files instead."
            NetClient.State.Off -> "You turned it off in Privacy, so nothing is downloaded. Import works without it."
            else -> "LFM only talks to the LFM Net built with it. Uninstall the LFM Net you have, then come back here to install this one."
        }, LfmType.small, c.text2, Modifier.padding(top = 6.dp))
        val bundled = remember { g.netInstaller.bundled() }
        if (st == NetClient.State.NotInstalled && bundled) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (install == NetInstaller.Status.Working) "Installing…" else "Install LFM Net",
                { scope.launch { g.netInstaller.install() } }, small = true, kind = BtnKind.Primary, icon = "download",
                enabled = install != NetInstaller.Status.Working)
            (install as? NetInstaller.Status.Failed)?.let { Txt(it.why, LfmType.meta, c.text2, Modifier.weight(1f)) }
        }
        if (st == NetClient.State.Off) Btn("Turn on", { scope.launch { g.settings.setNetEnabled(true); g.net.refresh() } }, Modifier.padding(top = 10.dp), small = true)
    }
}

/** A download under its model: the percentage gets the highlighter, then speed and time left (spec screen 13). */
@Composable
private fun DownloadProgress(d: ModelDownloads.Item, highlighted: Boolean, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    val c = Lfm.c
    val m = d.model
    val pct = if (m.sizeBytes > 0) (d.done * 100 / m.sizeBytes).toInt() else 0
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(when (d.state) {
                ModelDownloads.State.Waiting -> "Waiting to start"
                ModelDownloads.State.Running -> "Downloading through LFM Net"
                ModelDownloads.State.Pausing -> "Pausing…"
                ModelDownloads.State.Cancelling -> "Cancelling…"
                ModelDownloads.State.Paused -> d.error ?: "Paused"
                ModelDownloads.State.NoWifi -> "Waiting for Wi-Fi"
                ModelDownloads.State.Checking -> "Checking SHA-256…"
                ModelDownloads.State.Failed -> "Stopped: ${d.error ?: "failed"}"
                ModelDownloads.State.Done -> if (d.loaded) "Verified and loaded" else "Verified"
            }, LfmType.label.copy(fontWeight = null), c.text2, Modifier.weight(1f), maxLines = 2)
            if (highlighted) Marked("$pct%", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight)) else Txt("$pct%", LfmType.body, c.text2)
        }
        Bar(if (m.sizeBytes > 0) d.done.toFloat() / m.sizeBytes else 0f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(listOfNotNull("${formatBytes(d.done)} of ${m.sizeLabel}", d.speed.takeIf { it > 0 && d.state == ModelDownloads.State.Running }?.let { "${formatBytes(it)}/s" },
                d.secondsLeft?.let { left(it) }).joinToString(" · "), LfmType.label.copy(fontWeight = null), c.text2, Modifier.weight(1f))
            when (d.state) {
                ModelDownloads.State.Running, ModelDownloads.State.Waiting, ModelDownloads.State.NoWifi -> Btn("Pause", onPause, small = true)
                ModelDownloads.State.Paused, ModelDownloads.State.Failed -> Btn("Resume", onResume, small = true)
                else -> Unit
            }
            if (d.state !in setOf(ModelDownloads.State.Checking, ModelDownloads.State.Cancelling, ModelDownloads.State.Done)) Btn("Cancel", onCancel, small = true)
        }
    }
}

private fun left(s: Long) = when {
    s < 60 -> "under a minute left"
    s < 3600 -> "${(s + 59) / 60} min left"
    else -> "${s / 3600} h ${(s % 3600) / 60} min left"
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
