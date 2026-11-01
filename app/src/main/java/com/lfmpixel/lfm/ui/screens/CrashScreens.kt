package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.crash.CrashLog
import com.lfmpixel.lfm.crash.Crashes
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.LfmShape
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val crashTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

fun crashWhen(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(crashTime)

/** Settings' line for the crash log: "None · kept on this phone", "2 kept · last Sat 1 Nov, 14:03". */
fun crashesLabel(reports: List<CrashLog.Report>): String =
    if (reports.isEmpty()) "None · kept on this phone, never sent" else "${reports.size} kept on this phone · last ${crashWhen(reports.first().at)}"

/** Settings → Crash reports: the last ten, each to read, copy or share by hand. Nothing is sent by itself. */
@Composable
fun CrashesScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val ctx = LocalContext.current
    var changed by remember { mutableIntStateOf(0) }
    val reports by produceState(emptyList<CrashLog.Report>(), changed) {
        value = withContext(Dispatchers.IO) { Crashes.log?.reports().orEmpty() }
    }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Seen here: the note on the next launch is not needed any more.
    androidx.compose.runtime.LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Crashes.markSeen() } }
    Column(Modifier.fillMaxSize()) {
        Header("Crash reports", onBack = { nav.back() })
        LazyColumn(Modifier.padding(horizontal = 16.dp)) {
            item {
                Txt("When Inkling closes unexpectedly, what went wrong is written down here, on this phone only. Nothing is sent: " +
                    "copy or share one yourself if you want help with it. A report can quote words from a note or a chat: read it " +
                    "before you share it. The last ten are kept.", LfmType.small, c.text2, Modifier.padding(vertical = 12.dp))
            }
            if (reports.isEmpty()) item { Empty("No crashes", "Inkling has not closed unexpectedly since this was added.") }
            items(reports, key = { it.file.name }) { r ->
                val expanded = open == r.file.name
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = if (expanded) null else r.file.name }.padding(vertical = 10.dp)) {
                    Txt(crashWhen(r.at) + if (r.fatal) " · Inkling closed" else " · caught, kept running", LfmType.meta, c.text2)
                    Txt(r.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = if (expanded) Int.MAX_VALUE else 2,
                        modifier = Modifier.padding(top = 2.dp))
                    if (expanded) {
                        SelectionContainer { Txt(r.text, LfmType.meta, c.text, Modifier.padding(top = 8.dp)) }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Btn("Copy", {
                                ctx.getSystemService(android.content.ClipboardManager::class.java)
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Inkling crash report", r.text))
                                nav.show("Copied")
                            }, Modifier.weight(1f), icon = "copy", small = true)
                            Btn("Share…", { g.launcher.share("Inkling crash report", r.text) }, Modifier.weight(1f), small = true)
                            Btn("Delete", { scope.launch { withContext(Dispatchers.IO) { Crashes.log?.delete(r) }; open = null; changed++ } }, Modifier.weight(1f), icon = "trash", small = true)
                        }
                    }
                }
                Divider()
            }
            if (reports.isNotEmpty()) item {
                Btn("Delete all", { scope.launch { withContext(Dispatchers.IO) { Crashes.log?.clear() }; changed++ } }, Modifier.padding(vertical = 16.dp), icon = "trash")
            }
        }
    }
}

/** On the launch after a crash: "Inkling closed unexpectedly last time · View", on paper, until seen or closed. */
@Composable
fun CrashNote(onView: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Lfm.c
    Box(modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        // Paper with the control-grey edge (as the menu), so it stands apart from the wash cards it may sit over.
        Box(Modifier.fillMaxWidth().background(c.bg, LfmShape.card).border(1.dp, c.control, LfmShape.card)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("Inkling closed unexpectedly last time", LfmType.small, c.text, Modifier.weight(1f))
                Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onView).padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center) { Txt("View", LfmType.button, c.text, decoration = TextDecoration.Underline) }
                IconButtonPlain("x", onClose, desc = "Close")
            }
        }
    }
}
