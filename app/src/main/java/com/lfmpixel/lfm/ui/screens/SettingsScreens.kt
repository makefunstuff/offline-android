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
                ListRow("Privacy", "Network log, Inkling Net, what is kept, delete", "shield", { nav.go(Route.Privacy) }) { Chevron() }
            }
            Group {
                ListRow("Voice", if (g.engine.hasVoice()) "LFM3-Omni-2B · English" else "Import LFM3-Omni-2B and its projector", "speaker", { nav.go(Route.Models) }) { Chevron() }
                GroupDivider()
                ListRow("Battery & heat", "${p.threads} threads · " + if (p.unloadAfterMinutes == 0) "models stay loaded" else "unload after ${p.unloadAfterMinutes} min idle",
                    "flash", { nav.go(Route.Models) }) { Chevron() }
            }
            SectionLabel("Thinking")
            Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Think before answering", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("Better answers and tool calls, a little slower", LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.thinking) { scope.launch { g.settings.setThinking(it) } }
            }
            SectionLabel("Web search")
            Segmented(SearchMode.entries.map { it.label }, p.searchMode.label, { l -> scope.launch { g.settings.setSearchMode(SearchMode.entries.first { it.label == l }) } })
            Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Warn about personal words", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt("Names, places, numbers, e-mails", LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.warnPersonalWords) { scope.launch { g.settings.setWarnPersonal(it) } }
            }
            Label("Thread count", Modifier.padding(top = 20.dp, bottom = 6.dp))
            Segmented(listOf("2", "4", "6"), p.threads.toString(), { scope.launch { g.settings.setThreads(it.toInt()); g.engine.unloadAll() } })
            Txt("Takes effect the next time a model loads.", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
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
fun PrivacyScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val ctx = LocalContext.current
    val hasInternet = ctx.packageManager.checkPermission(Manifest.permission.INTERNET, ctx.packageName) == PackageManager.PERMISSION_GRANTED
    val prefs by g.settings.prefs.collectAsState(null)
    val netState by g.net.current.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { g.net.refresh() }
    val installed = netState != null && netState != com.lfmpixel.lfm.web.NetClient.State.NotInstalled
    val start = remember { java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val log by remember(start) { g.db.netLog().since(start) }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    val time = DateTimeFormatter.ofPattern("HH:mm")
    Column(Modifier.fillMaxSize()) {
        Header("Privacy", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Txt("Your data stays on this phone.", LfmType.answer.copy(fontSize = LfmType.answer.fontSize * 1.16f, fontWeight = LfmType.rowTitle.fontWeight),
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp))
            Guarantee("check", if (hasInternet) "Network permission found" else null, "Android blocks it: this app has no internet permission",
                markedTitle = if (!hasInternet) "Assistant has no network access" else null)
            Guarantee("check", "No account, no telemetry", "No analytics, crash reports or Play Services in the app")
            Guarantee("check", "Not backed up", "Excluded from cloud backup and phone-to-phone transfer")
            Guarantee("cal", "Events go to “On this phone”", "A local calendar that never syncs to any account")
            if (installed) {
                val on = prefs?.netEnabled ?: true
                Guarantee("globe", "Inkling Net handles search", if (on) "Search words, result addresses, model downloads" else "Off: nothing is searched or downloaded") {
                    Switch(on) {
                        // Off means off: downloads in progress pause too (they resume when it is back on).
                        if (!it) g.downloads.pauseAll()
                        scope.launch { g.settings.setNetEnabled(it); g.net.refresh(); if (it) g.downloads.resumeInterrupted() }
                    }
                }
            } else {
                Guarantee("globe", "Inkling Net is not installed", "Without the helper app, Inkling is fully offline: no search, no downloads")
            }
            SectionLabel("Network log · today")
            if (log.isEmpty()) Txt(if (installed) "No requests today." else "No requests. This app cannot make any.", LfmType.small, c.text2)
            log.forEach { e ->
                Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(Instant.ofEpochMilli(e.at).atZone(ZoneId.systemDefault()).format(time), LfmType.meta, c.text2)
                    Column(Modifier.weight(1f)) {
                        Txt(e.host, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 1)
                        Txt(e.label + if (e.ok) "" else " · failed", LfmType.meta, c.text2, maxLines = 1)
                    }
                    Txt(formatBytes(e.bytes), LfmType.meta, c.text2)
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Export…", {
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
            SectionLabel("Kept on this phone")
            Txt("Chats, notes, ideas, journal, to-dos, reminders, searches, the network log and the action history, in the app's private storage. Models in the app's files.",
                LfmType.small, c.text2)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Clear chats", { scope.launch { g.assistant.newChat(); g.db.messages().deleteAll(); g.db.searches().deleteAll(); g.prep.deleteAll() } }, Modifier.weight(1f), icon = "trash")
            }
            Spacer(Modifier.height(24.dp))
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

/** "Your SearXNG + Wikipedia", or why there is no search. Asks LFM Net about itself; no request goes out. */
suspend fun webSearchLabel(g: AppGraph): String = when (g.net.state()) {
    com.lfmpixel.lfm.web.NetClient.State.On -> runCatching { g.net.info() }.fold({ if (it.ready) it.provider.replaceFirstChar { c -> c.uppercase() } else "No provider set up" }, { "Inkling Net not answering" })
    com.lfmpixel.lfm.web.NetClient.State.Off -> "Inkling Net off"
    com.lfmpixel.lfm.web.NetClient.State.NotInstalled -> "Needs the Inkling Net helper"
    com.lfmpixel.lfm.web.NetClient.State.WrongSigner -> "Inkling Net signed with another key"
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
            Txt(if (g.net.installed()) "Everything it thinks, hears and keeps stays here. No account. You decide if it may search the web."
                else "Everything it thinks, hears and keeps stays here. No account. It cannot reach the internet.", LfmType.body, c.text2)
            SectionLabel("Step 1 of 2 · Models")
            val installed by g.models.installed.collectAsState()
            val downloads by g.downloads.items.collectAsState()
            val netState by g.net.current.collectAsState()
            val missing = com.lfmpixel.lfm.llm.ModelCatalog.essentials.filter { m -> installed.none { it.known == m } && downloads[m.fileName] == null }
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
