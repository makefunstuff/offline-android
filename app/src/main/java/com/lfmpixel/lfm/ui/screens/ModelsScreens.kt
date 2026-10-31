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
import com.lfmpixel.lfm.llm.InstalledModel
import com.lfmpixel.lfm.llm.Slot
import com.lfmpixel.lfm.ui.components.Segmented
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
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
    var deleting by remember { mutableStateOf<InstalledModel?>(null) }
    deleting?.let { m -> DeleteModelSheet(g, m) { deleting = null } }
    val installed by g.models.installed.collectAsState()
    val downloads by g.downloads.items.collectAsState()
    val used = g.models.usedBytes()
    val free = g.models.freeBytes()
    Column(Modifier.fillMaxSize()) {
        Header("Models", onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                Bar(if (used + free > 0) used.toFloat() / (used + free) else 0f)
                Txt("Storage: ${formatBytes(used)} of models · ${formatBytes(free)} free", LfmType.label.copy(fontWeight = null), c.text2)
            }
            ModelControl(g)
            SectionLabel("Get models")
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
                        IconButtonPlain("trash", { deleting = m }, desc = "Delete ${m.known.fileName}")
                    }
                    Divider()
                }
            }
            Btn("Import a file", onImport, Modifier.padding(top = 14.dp).fillMaxWidth(), icon = "folder")
            Txt("Downloads come from Hugging Face with no login, through Inkling Net, and each file is checked against its pinned SHA-256 before it is used. " +
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
    var deleting by remember { mutableStateOf<InstalledModel?>(null) }
    deleting?.let { m -> DeleteModelSheet(g, m) { deleting = null } }
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
                val inMemory = have != null && status.of(slotOf(m.role)) == LoadState.Ready &&
                    (m.role != ModelRole.Text || status.textFile == have.file.name)
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
                            IconButtonPlain("trash", { deleting = have }, desc = "Delete ${m.fileName}")
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
                NetClient.State.NotInstalled -> "Inkling Net is not installed"
                NetClient.State.Off -> "Inkling Net is turned off"
                else -> "Inkling Net is signed with another key"
            }, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
        }
        Txt(when (st) {
            NetClient.State.NotInstalled -> "Downloads come through Inkling Net, a small helper app. It is the only part with network access; Inkling itself has none. Without it, import model files instead."
            NetClient.State.Off -> "You turned it off in Privacy, so nothing is downloaded. Import works without it."
            else -> "Inkling only talks to the Inkling Net built with it. Uninstall the Inkling Net you have, then come back here to install this one."
        }, LfmType.small, c.text2, Modifier.padding(top = 6.dp))
        val bundled = remember { g.netInstaller.bundled() }
        if (st == NetClient.State.NotInstalled && bundled) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn(if (install == NetInstaller.Status.Working) "Installing…" else "Install Inkling Net",
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
                ModelDownloads.State.Running -> "Downloading through Inkling Net"
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

fun slotOf(role: ModelRole) = when (role) {
    ModelRole.Text -> Slot.Text
    ModelRole.Omni, ModelRole.OmniProjector -> Slot.Omni
    ModelRole.Vision, ModelRole.VisionProjector -> Slot.Vision
}

private val idleChoices = listOf(0 to "Never", 1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min")

/**
 * What is in memory and what it takes, Load and Unload for each model, when they unload by themselves, and which
 * model chat uses (ux-spec section 5, model control). On the Models screen and in the chat header's sheet.
 */
@Composable
fun ModelControl(g: AppGraph) {
    val c = Lfm.c
    val status by g.engine.status.collectAsState()
    val installed by g.models.installed.collectAsState()
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val memory by produceState(emptyMap<Slot, Long>(), status, installed, prefs) { value = Slot.entries.associateWith { g.engine.memoryBytes(it) } }
    val slots = Slot.entries.filter { g.engine.installed(it) }
    var failed by remember { mutableStateOf<Pair<Slot, String>?>(null) }
    // Unloading waits for an answer being written with that model; say so instead of looking stuck.
    var unloading by remember { mutableStateOf(emptySet<Slot>()) }
    val working by g.assistant.working.collectAsState()
    androidx.compose.runtime.LaunchedEffect(status) { unloading = unloading.filter { status.of(it) == LoadState.Ready }.toSet() }
    SectionLabel("In memory")
    if (slots.isEmpty()) Txt("No models on this phone yet.", LfmType.small, c.text2)
    Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp))) {
        slots.forEachIndexed { i, slot ->
            if (i > 0) Divider()
            val st = status.of(slot)
            val name = if (slot == Slot.Text) installed.firstOrNull { it.file.name == status.textFile }?.let { "${it.known.family} ${it.known.quant}" }
                ?: (g.models.text(prefs?.chatModel ?: "")?.let { "${it.known.family} ${it.known.quant}" } ?: slot.label) else slot.label
            val mem = memory[slot]?.let { "about ${formatBytes(it)}" } ?: ""
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val dot = Modifier.size(8.dp)
                when (st) {
                    LoadState.Ready -> Box(dot.background(c.text, RoundedCornerShape(2.dp)))
                    else -> Box(dot.border(1.dp, if (st == LoadState.Loading) c.text else c.text3, RoundedCornerShape(2.dp)))
                }
                Column(Modifier.weight(1f)) {
                    Txt(name, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt(listOf(slot.purpose, when (st) {
                        LoadState.Ready -> "loaded · $mem"
                        LoadState.Loading -> "loading…"
                        LoadState.Failed -> "didn't load"
                        LoadState.None -> "not loaded · $mem when loaded"
                    }).joinToString(" · "), LfmType.label.copy(fontWeight = null), c.text2)
                    failed?.takeIf { it.first == slot }?.let { Txt(it.second, LfmType.meta, c.text, maxLines = 3) }
                    if (slot in unloading && working) Txt("Unloads when the current answer ends", LfmType.meta, c.text)
                }
                when {
                    slot in unloading -> Btn("Unloading…", {}, small = true, enabled = false)
                    st == LoadState.Ready -> Btn("Unload", { unloading = unloading + slot; g.scope.launch { g.engine.unload(slot) } }, small = true)
                    st == LoadState.Loading -> Btn("Loading…", {}, small = true, enabled = false)
                    else -> Btn("Load", {
                        failed = null
                        g.scope.launch { runCatching { g.engine.load(slot) }.onFailure { failed = slot to (it.message ?: "Could not load") } }
                    }, small = true)
                }
            }
        }
    }
    val am = ctx.getSystemService(android.app.ActivityManager::class.java)
    val mi = remember(status) { android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) } }
    if (mi.totalMem > 0) Txt("Phone memory: ${formatBytes(mi.availMem)} free of ${formatBytes(mi.totalMem)}", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))

    val texts = installed.filter { it.known.role == ModelRole.Text && it.verified }
    if (texts.size > 1) {
        SectionLabel("Chat model")
        val current = g.models.text(prefs?.chatModel ?: "")
        Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp))) {
            texts.forEachIndexed { i, m ->
                if (i > 0) Divider()
                val on = m.file == current?.file
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                    scope.launch {
                        g.settings.setChatModel(m.file.name)
                        // Swap now if a chat model is loaded, so the next answer doesn't wait.
                        if (status.text == LoadState.Ready) g.scope.launch { runCatching { g.engine.load(Slot.Text) } }
                    }
                }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(18.dp).border(if (on) 6.dp else 1.dp, if (on) c.text else c.control, RoundedCornerShape(50)))
                    Column(Modifier.weight(1f)) {
                        Txt("${m.known.family} ${m.known.quant}", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        Txt(m.known.sizeLabel + if (m.known.quant.startsWith("Q4")) " · smaller, faster" else " · more accurate", LfmType.label.copy(fontWeight = null), c.text2)
                    }
                }
            }
        }
    }
    SectionLabel("Unload when idle")
    val minutes = prefs?.unloadAfterMinutes ?: 5
    Segmented(idleChoices.map { it.second }, idleChoices.firstOrNull { it.first == minutes }?.second ?: "$minutes min", { l ->
        scope.launch { g.settings.setUnloadMinutes(idleChoices.first { it.second == l }.first); g.engine.idleTimeChanged() }
    })
    Txt(if (minutes == 0) "Models stay in memory until you unload them. Uses more battery and memory." else
        "Frees memory after $minutes ${if (minutes == 1) "minute" else "minutes"} without use; the next question loads it again in a few seconds.",
        LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
}

/** The chat header's model sheet: the same control, and a way to the full Models screen. */
@Composable
fun ModelSheet(g: AppGraph, onModels: () -> Unit, onClose: () -> Unit) {
    FlatSheet(onDismiss = onClose) {
        Txt("Models", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        ModelControl(g)
        Btn("Downloads and all models", onModels, Modifier.padding(top = 16.dp).fillMaxWidth(), icon = "chip")
    }
}

/** A model file is big and can't be undone: say what is lost before deleting. A loaded model is unloaded first. */
@Composable
fun DeleteModelSheet(g: AppGraph, m: InstalledModel, onClose: () -> Unit) {
    val c = Lfm.c
    FlatSheet(onDismiss = onClose) {
        Txt("Delete ${m.known.family} ${m.known.quant}?", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        Txt("Frees ${m.known.sizeLabel}. To use it again you'd download or import it again.", LfmType.body, c.text2, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", onClose, Modifier.weight(1f))
            Btn("Delete", {
                g.scope.launch {
                    val slot = slotOf(m.known.role)
                    val st = g.engine.status.value
                    if (st.of(slot) != LoadState.None && (slot != Slot.Text || st.textFile == m.file.name)) g.engine.unload(slot)
                    g.models.delete(m)
                }
                onClose()
            }, Modifier.weight(1f), BtnKind.Primary, icon = "trash")
        }
    }
}
