package com.lfmpixel.lfm.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.llm.LoadState
import com.lfmpixel.lfm.llm.ModelRole
import com.lfmpixel.lfm.llm.formatBytes
import com.lfmpixel.lfm.settings.SearchMode
import com.lfmpixel.lfm.settings.ToolPolicy
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.ListRow
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.Segmented
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Switch
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.wipe
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun Group(content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().border(1.dp, Lfm.c.line, RoundedCornerShape(8.dp))) { content() }
}

@Composable
private fun GroupDivider() = Divider()

/** "On · answering · 12 ms", "Off · everything on this phone" */
fun serverLabel(on: Boolean, url: String, state: com.lfmpixel.lfm.edge.EdgeState): String = when {
    !on || url.isBlank() -> "Off · everything runs on this phone"
    state is com.lfmpixel.lfm.edge.EdgeState.Online -> "On · answering · ${url.substringAfter("://")}"
    state is com.lfmpixel.lfm.edge.EdgeState.Offline -> "On · not reached, this phone answers"
    else -> "On · checking…"
}

@Composable
private fun Chevron() = LIcon("right", tint = Lfm.c.text3)

@Composable
fun SettingsScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val installed by g.models.installed.collectAsState()
    val status by g.engine.status.collectAsState()
    val scope = rememberCoroutineScope()
    val p = prefs ?: return
    val netLabel by produceState("", p.netEnabled) { value = webSearchLabel(g) }
    val asking = g.tools.all.count { !it.readOnly && (p.toolPolicies[it.name] ?: it.defaultPolicy) == ToolPolicy.Ask }
    val direct = g.tools.all.count { !it.readOnly } - asking
    Column(Modifier.fillMaxSize()) {
        Header("Settings", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Group {
                val text = installed.firstOrNull { it.known.role == ModelRole.Text && it.verified }
                val omni = installed.firstOrNull { it.known.role == ModelRole.Omni && it.verified }
                val inMem = listOfNotNull(if (status.text == LoadState.Ready) "1.7B" else null, if (status.omni == LoadState.Ready) "Omni" else null,
                    if (status.vision == LoadState.Ready) "VL" else null)
                val edgeState by g.edge.state.collectAsState()
                ListRow("Your server", serverLabel(p.edgeOn, p.edgeUrl, edgeState), "server", { nav.go(Route.Server) }) { Chevron() }
                GroupDivider()
                ListRow("Models", when {
                    text == null && omni == null -> "None yet · import a file"
                    else -> listOfNotNull(omni?.known?.family, text?.known?.family).joinToString(", ") +
                        (if (inMem.isNotEmpty()) " · ${inMem.joinToString(", ")} loaded" else " · none loaded") + " · " + formatBytes(installed.sumOf { it.known.sizeBytes })
                }, "chip", { nav.go(Route.Models) }) { Chevron() }
                GroupDivider()
                ListRow("Web search", listOf(netLabel, p.searchMode.label.lowercase()).filter { it.isNotEmpty() }.joinToString(" · "), "globe",
                    { nav.go(Route.WebSearch) }) { Chevron() }
                GroupDivider()
                ListRow("Tools", "$direct run directly · $asking always ask", "sliders", { nav.go(Route.Tools) }) { Chevron() }
                GroupDivider()
                ListRow("Action history", "Everything the assistant did, with undo", "history", { nav.go(Route.History) }) { Chevron() }
            }
            Group {
                val facts by g.memory.facts.collectAsState()
                androidx.compose.runtime.LaunchedEffect(Unit) { g.memory.load() }
                ListRow("What Inkling knows", if (!p.learning) "Learning is off" else if (facts.isEmpty()) "Learns from your notes, on this phone"
                    else "${facts.size} things learned · see, edit, forget", "sparkle", { nav.go(Route.Knows) }) { Chevron() }
                GroupDivider()
                ListRow("Privacy", (if (p.netEnabled) "" else "Offline · ") + "Network log, what is kept, delete", "shield", { nav.go(Route.Privacy) }) { Chevron() }
            }
            Group {
                ListRow("Voice and Talk", if (g.engine.hasVoice()) "${p.speechVoice.label} · ${p.speechLanguage.label} · assistant opens ${p.assistOpens.label.lowercase()}"
                    else "Import LFM3-Omni-2B and its projector", "speaker", { nav.go(Route.Voice) }) { Chevron() }
                GroupDivider()
                ListRow("Battery & heat", "${if (p.threads == 0) "Auto" else p.threads} threads · " + if (p.unloadAfterMinutes == 0) "models stay loaded" else "unload after ${p.unloadAfterMinutes} min idle",
                    "flash", { nav.go(Route.Models) }) { Chevron() }
            }
            SectionLabel("Thinking")
            Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Think before answering", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("Better answers and tool calls, a little slower. You can open what it thought above each answer.",
                        LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.thinking, "Think before answering") { scope.launch { g.settings.setThinking(it) } }
            }
            Txt("The default for new chats. Think, by the message box, turns it on or off for one chat. Voice and Talk " +
                "never think first, so they answer quickly.", LfmType.meta, c.text2, Modifier.padding(top = 8.dp))
            Label("Thinking budget", Modifier.padding(top = 16.dp, bottom = 6.dp))
            val budgets = com.lfmpixel.lfm.settings.THINK_BUDGETS.let { if (p.thinkBudget !in it) (it + p.thinkBudget).sorted() else it }
            Segmented(budgets.map { it.toString() }, p.thinkBudget.toString(), { l -> scope.launch { g.settings.setThinkBudget(l.toInt()) } })
            Txt("Tokens it may think for before it must answer. More is slower on a long question, but it gets further. LiteRT-LM, with its smaller memory, stops at 1024.",
                LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            SectionLabel("Web search")
            Segmented(SearchMode.entries.map { it.label }, p.searchMode.label, { l -> scope.launch { g.settings.setSearchMode(SearchMode.entries.first { it.label == l }) } })
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Warn about personal words", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("Names, places, numbers, e-mails", LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.warnPersonalWords, "Warn about personal words") { scope.launch { g.settings.setWarnPersonal(it) } }
            }
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Show speed", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("Tokens a second and time to the first token, under each answer", LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.showSpeed, "Show speed") { scope.launch { g.settings.setShowSpeed(it) } }
            }
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Soft sounds", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("A quiet note when Talk listens and when something is done or undone. Never on silent", LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.softSounds, "Soft sounds") { scope.launch { g.settings.setSoftSounds(it) } }
            }
            Label("Thread count", Modifier.padding(top = 20.dp, bottom = 6.dp))
            // A count the benchmark picked that is not one of these shows too.
            val counts = listOf(2, 4, 5, 6).let { if (p.threads > 0 && p.threads !in it) (it + p.threads).sorted() else it }
            Segmented(listOf("Auto") + counts.map { it.toString() }, if (p.threads == 0) "Auto" else p.threads.toString(),
                { scope.launch { g.settings.setThreads(it.toIntOrNull() ?: 0); g.engine.unloadAll() } })
            Txt("Auto keeps off the little cores and leaves one core for the screen. Settings → Models → Benchmark tries each count. " +
                "Takes effect the next time a model loads.", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToolsScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = rememberCoroutineScope()
    val p = prefs ?: return
    Column(Modifier.fillMaxSize()) {
        Header("Tools", onBack = { nav.back() })
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item { Txt("For each action, pick whether the assistant asks first or just does it (with undo). Reading tools never change anything.",
                LfmType.small, c.text2, Modifier.padding(vertical = 12.dp)) }
            items(g.tools.all.filter { !it.readOnly }, key = { it.name }) { t ->
                val policy = p.toolPolicies[t.name] ?: t.defaultPolicy
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(t.label, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        Txt(t.name, LfmType.meta, c.text2)
                    }
                    Box(Modifier.width(150.dp)) {
                        Segmented(listOf("Ask", "Do"), if (policy == ToolPolicy.Ask) "Ask" else "Do",
                            { scope.launch { g.settings.setToolPolicy(t.name, if (it == "Ask") ToolPolicy.Ask else ToolPolicy.Do) } })
                    }
                }
                Divider()
            }
            item {
                SectionLabel("Read only")
                Txt(g.tools.all.filter { it.readOnly }.joinToString(", ") { it.label }, LfmType.small, c.text2)
                Txt("Alarms and timers start as Ask: Android has no way to delete an alarm another app set, so they can't be undone from here.",
                    LfmType.meta, c.text2, Modifier.padding(top = 12.dp, bottom = 24.dp))
            }
        }
    }
}

@Composable
fun HistoryScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val actions by remember { g.db.actions().recent() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
    Column(Modifier.fillMaxSize()) {
        Header("Action history", onBack = { nav.back() })
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            if (actions.isEmpty()) item { Empty("Nothing yet", "Every action the assistant takes is listed here, kept on this phone.") }
            items(actions, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Txt(a.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                        if (a.detail.isNotEmpty()) Txt(a.detail, LfmType.small, c.text2, maxLines = 2)
                        Txt(listOfNotNull(Instant.ofEpochMilli(a.at).atZone(ZoneId.systemDefault()).format(fmt), a.tool,
                            when (a.status) {
                                ActionStatus.Undone -> "undone"; ActionStatus.Cancelled -> "cancelled"; ActionStatus.Failed -> "failed"
                                ActionStatus.Opened -> "finished in another app"; ActionStatus.Done -> null
                            }, if (a.askedFirst) "asked first" else null).joinToString(" · "), LfmType.meta, c.text2)
                    }
                    if (a.status == ActionStatus.Done && a.undo.isNotEmpty()) Btn("Undo", { scope.launch { g.assistant.undo(a.id) } }, small = true, icon = "undo")
                }
                Divider()
            }
        }
    }
}

@Composable
fun PrivacyScreen(g: AppGraph, nav: Nav, pickers: Pickers) {
    val c = Lfm.c
    val ctx = LocalContext.current
    val prefs by g.settings.prefs.collectAsState(null)
    val start = remember { java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val log by remember(start) { g.db.netLog().since(start) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val time = DateTimeFormatter.ofPattern("HH:mm")
    Column(Modifier.fillMaxSize()) {
        Header("Privacy", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Txt("Your data stays on this phone.", LfmType.answer.copy(fontSize = LfmType.answer.fontSize * 1.16f, fontWeight = LfmType.rowTitle.fontWeight),
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
            val offline = prefs?.netEnabled == false
            Guarantee("globe", "Offline", if (offline) "Nothing leaves the phone: no search, downloads or server" else
                "Off: search, model downloads and your server may use the network, each with its own switch") {
                Switch(offline, "Offline") { off ->
                    // Offline means off: downloads in progress pause too (they resume when it is back on).
                    if (off) g.downloads.pauseAll()
                    scope.launch { g.settings.setNetEnabled(!off); g.net.refresh(); if (!off) g.downloads.resumeInterrupted() }
                }
            }
            Guarantee("check", "Network code kept in one place", "Only search, opening result pages, pinned model downloads and your own server. The build fails if anything else could open a connection",
                markedTitle = "Network code kept in one place")
            Guarantee("check", "No account, no telemetry", "No analytics, crash reports or Play Services in the app")
            Guarantee("check", "Not backed up", "Excluded from cloud backup and phone-to-phone transfer")
            Guarantee("cal", "Events go to “On this phone”", "A local calendar that never syncs to any account")
            SectionLabel("Each kind on its own")
            Txt("Web search: ${prefs?.searchMode?.label ?: "…"} (Settings → Web search). Model downloads: only when you tap Download" +
                (if (prefs?.downloadWifiOnly != false) ", on Wi-Fi" else "") + ". Your server: " + (if (prefs?.edgeOn == true) "on" else "off") + " (Settings → Your server).",
                LfmType.small, c.text2)
            HandoffCard(g)
            SectionLabel("Network log · today")
            if (log.isEmpty()) Txt(if (prefs?.netEnabled == false) "No requests: Inkling is offline." else "No requests today.", LfmType.small, c.text2)
            log.forEach { e ->
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(time), LfmType.meta, c.text2)
                    Column(Modifier.weight(1f)) {
                        Txt(e.host, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 1)
                        // The whole address, query and all: exactly what went out.
                        Txt(e.url, LfmType.meta, c.text, Modifier.padding(top = 2.dp))
                        Txt(e.label + if (e.ok) "" else " · failed", LfmType.meta, c.text2, maxLines = 1)
                    }
                    Txt(formatBytes(e.bytes), LfmType.meta, c.text2)
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Export log…", {
                    scope.launch {
                        val all = g.db.netLog().all()
                        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        g.launcher.share("Inkling network log", all.joinToString("\n") { e ->
                            listOf(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(fmt), e.host, e.url, e.sent, "${e.bytes} bytes",
                                if (e.ok) "ok" else "failed").joinToString("\t")
                        }.ifEmpty { "No requests." })
                    }
                }, Modifier.weight(1f))
                Btn("Delete all", { scope.launch { g.db.netLog().deleteAll() } }, Modifier.weight(1f), icon = "trash")
            }
            SectionLabel("Lock")
            val mode = prefs?.lockMode ?: com.lfmpixel.lfm.settings.LockMode.Off
            Segmented(com.lfmpixel.lfm.settings.LockMode.entries.map { it.label }, mode.label, { l ->
                val to = com.lfmpixel.lfm.settings.LockMode.entries.first { it.label == l }
                if (to == mode) return@Segmented
                scope.launch {
                    when {
                        to != com.lfmpixel.lfm.settings.LockMode.Off && !g.lock.canLock() ->
                            nav.show("Set a screen lock on the phone first: Android Settings → Security")
                        // On, off or changed: only the phone's owner may, so it asks for the phone's lock first.
                        g.lock.confirm(if (to == com.lfmpixel.lfm.settings.LockMode.Off) "Turn the lock off" else "Lock ${to.label.lowercase()}") ->
                            g.settings.setLockMode(to)
                    }
                }
            })
            Txt(when (mode) {
                com.lfmpixel.lfm.settings.LockMode.Off -> "Lock the whole app, or only the journal, with your fingerprint, face or screen lock."
                com.lfmpixel.lfm.settings.LockMode.App -> "Inkling opens with your fingerprint, face or screen lock, and locks again a minute after you leave it."
                com.lfmpixel.lfm.settings.LockMode.Journal -> "The journal opens with your fingerprint, face or screen lock; it is left out of lists, search and the assistant until then. It locks again a minute after you leave Inkling."
            }, LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            SectionLabel("Kept on this phone")
            Txt("Chats, notes, ideas, journal, to-dos, reminders, searches, the network log and the action history, in the app's private storage. Models in the app's files.",
                LfmType.small, c.text2)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Export all…", {
                    scope.launch {
                        if (mode != com.lfmpixel.lfm.settings.LockMode.Off && !g.lock.confirm("Export your data")) return@launch
                        val name = "inkling-export-" + java.time.LocalDate.now() + ".zip"
                        pickers.save(name) { uri ->
                            g.scope.launch {
                                val ok = runCatching {
                                    ctx.contentResolver.openOutputStream(uri)!!.use { com.lfmpixel.lfm.data.Export.write(g.db, it) }
                                }.isSuccess
                                nav.show(if (ok) "Exported: notes, journal, to-dos, chats and logs" else "Export failed. Try another place.")
                            }
                        }
                    }
                }, Modifier.weight(1f), icon = "down")
                Btn("Clear chats", { scope.launch { g.assistant.clearQueue(); g.assistant.newChat(); g.db.messages().deleteAll(); g.db.searches().deleteAll(); g.prep.deleteAll(); g.settings.clearChatThinking() } }, Modifier.weight(1f), icon = "trash")
            }
            var wipe by remember { mutableStateOf(false) }
            var wiping by remember { mutableStateOf(false) }
            Btn("Delete everything…", { wipe = true }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = "trash")
            if (wipe) WipeSheet(g, onClose = { wipe = false }) { models ->
                if (wiping) return@WipeSheet
                wiping = true
                g.scope.launch {
                    if (mode != com.lfmpixel.lfm.settings.LockMode.Off && !g.lock.confirm("Delete everything")) { wiping = false; return@launch }
                    wipe = false
                    g.wipe(ctx.applicationContext, models)
                    nav.reset(Route.FirstRun)
                    nav.show(if (models) "Everything was deleted, models too" else "Everything was deleted. The models are still here.")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What Delete everything takes, said plainly, with the models as a separate choice (they are big to get again). */
@Composable
private fun WipeSheet(g: AppGraph, onClose: () -> Unit, onDelete: (models: Boolean) -> Unit) {
    val c = Lfm.c
    var models by remember { mutableStateOf(false) }
    val size = formatBytes(g.models.usedBytes())
    FlatSheet(onDismiss = onClose) {
        Txt("Delete everything?", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.semantics { heading() })
        Txt("Notes, ideas, journal, to-dos, chats, reminders, searches, the network log, the action history, pictures, " +
            "Inkling's calendar and every setting. It can't be undone; export first if you want a copy.", LfmType.small, c.text2,
            Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().toggleable(models, role = androidx.compose.ui.semantics.Role.Checkbox) { models = it },
            verticalAlignment = Alignment.CenterVertically) {
            com.lfmpixel.lfm.ui.components.CheckBox(models, null)
            Column(Modifier.weight(1f)) {
                Txt("Also delete the models", LfmType.body)
                Txt("$size. You'd download or import them again.", LfmType.meta, c.text2)
            }
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", onClose, Modifier.weight(1f))
            Btn("Delete everything", { onDelete(models) }, Modifier.weight(1f), com.lfmpixel.lfm.ui.components.BtnKind.Primary, icon = "trash")
        }
    }
}

@Composable
private fun Guarantee(icon: String, title: String?, detail: String, markedTitle: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(30.dp).border(1.dp, c.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { LIcon(icon, size = 18.dp) }
        Column(Modifier.weight(1f)) {
            if (markedTitle != null) Marked(markedTitle, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            else Txt(title ?: "", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(detail, LfmType.label.copy(fontWeight = null), c.text2)
        }
        trailing()
    }
}

/** "Your SearXNG + Wikipedia", or why there is no search. Reads the settings only; no request goes out. */
suspend fun webSearchLabel(g: AppGraph): String = when (g.net.state()) {
    com.lfmpixel.lfm.web.NetClient.State.On -> g.net.info().let { if (it.ready) it.provider.replaceFirstChar { c -> c.uppercase() } else "No provider set up" }
    com.lfmpixel.lfm.web.NetClient.State.Offline -> "Offline"
}

@Composable
fun FirstRunScreen(g: AppGraph, nav: Nav, onImport: () -> Unit) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = rememberCoroutineScope()
    val onImportOnly: () -> Unit = { g.scope.launch { g.settings.setFirstRunDone() }; nav.reset(Route.Today); onImport() }
    val size = formatBytes(com.lfmpixel.lfm.llm.ModelCatalog.essentials.sumOf { it.sizeBytes })
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(28.dp).height(4.dp).background(c.text, RoundedCornerShape(2.dp)))
                Box(Modifier.width(28.dp).height(4.dp).background(c.line, RoundedCornerShape(2.dp)))
            }
            Txt("Your assistant,\non this phone", LfmType.title, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            Txt("Everything it thinks, hears and keeps stays here. No account. You decide if it may search the web.", LfmType.body, c.text2)
            SectionLabel("Step 1 of 2 · Models")
            val installed by g.models.installed.collectAsState()
            val downloads by g.downloads.items.collectAsState()
            val netState by g.net.current.collectAsState()
            val missing = com.lfmpixel.lfm.llm.ModelCatalog.essentials.filter { m -> installed.none { it.known == m } &&
                (downloads[m.fileName]?.state ?: com.lfmpixel.lfm.web.ModelDownloads.State.Failed) == com.lfmpixel.lfm.web.ModelDownloads.State.Failed }
            var choose by remember { mutableStateOf(false) }
            when {
                missing.isEmpty() -> Txt(if (downloads.values.any { it.state != com.lfmpixel.lfm.web.ModelDownloads.State.Done }) "Getting LFM3-1.7B and Omni. They carry on in the background; Models shows how far."
                    else "LFM3-1.7B and Omni are on this phone. You're set.", LfmType.body, c.text, Modifier.padding(bottom = 10.dp))
                netState == com.lfmpixel.lfm.web.NetClient.State.On -> {
                    Txt("LFM3-1.7B for chat and Omni for voice and photos: $size in all, each checked against its pinned SHA-256.", LfmType.meta, c.text2,
                        Modifier.padding(bottom = 10.dp))
                    Btn("Get the recommended models", { g.downloads.start(missing) }, Modifier.fillMaxWidth(), com.lfmpixel.lfm.ui.components.BtnKind.Primary, icon = "download")
                }
                else -> Txt("For chat get LFM3-1.7B; for voice also Omni-2B ($size for both at Q4). Each is checked against its pinned SHA-256.",
                    LfmType.meta, c.text2, Modifier.padding(bottom = 10.dp))
            }
            if (choose || netState != com.lfmpixel.lfm.web.NetClient.State.On) {
                Spacer(Modifier.height(10.dp))
                ModelList(g, highlight = false)
            } else Btn("Choose models yourself", { choose = true }, Modifier.padding(top = 8.dp).fillMaxWidth(), icon = "list")
            Spacer(Modifier.height(8.dp))
            Btn("Import files instead", onImportOnly, Modifier.fillMaxWidth(), icon = "folder")
            Txt("Already on this phone or copied over USB; no network needed.", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            SectionLabel("Step 2 of 2 · Web search (change any time)")
            Segmented(SearchMode.entries.map { it.label }, prefs?.searchMode?.label ?: "Ask first",
                { l -> scope.launch { g.settings.setSearchMode(SearchMode.entries.first { it.label == l }) } })
            Txt("Permissions (calendar, contacts, microphone) are asked only when a tool first needs one.", LfmType.meta, c.text2, Modifier.padding(top = 12.dp))
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Continue", {
                g.scope.launch { g.settings.setFirstRunDone() }
                nav.reset(Route.Today)
                // Downloads carry on in the background; Models shows them.
                if (g.downloads.items.value.isNotEmpty()) nav.go(Route.Models)
            }, Modifier.fillMaxWidth(), com.lfmpixel.lfm.ui.components.BtnKind.Primary)
            Btn("Later", { g.scope.launch { g.settings.setFirstRunDone() }; nav.reset(Route.Today) }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Option(icon: String, title: String, detail: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Lfm.c
    Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().border(1.dp, if (selected) c.text else c.control, RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LIcon(icon, tint = if (enabled) c.text else c.text3)
        Column(Modifier.weight(1f)) {
            Txt(title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), if (enabled) c.text else c.text3)
            Txt(detail, LfmType.label.copy(fontWeight = null), c.text2)
        }
        Box(Modifier.size(18.dp).border(if (selected) 6.dp else 1.dp, if (selected) c.text else c.control, RoundedCornerShape(50)))
    }
}
