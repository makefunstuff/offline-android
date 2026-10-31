package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.ActionRecord
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Composer
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.IconButtonPlain
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    val messages by g.assistant.messages.collectAsState(emptyList())
    val streaming by g.assistant.streaming.collectAsState()
    val busy by g.assistant.busy.collectAsState()
    val query by g.web.pending.collectAsState()
    val actions by remember { g.db.actions().recent() }.collectAsState(emptyList())
    val records = actions.associateBy { it.id }
    var draft by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val visible = messages.filter { !(it.role == Role.Assistant && it.text.isBlank()) }
    LaunchedEffect(visible.size, streaming?.text?.length, query) {
        val n = visible.size + (if (streaming != null) 1 else 0) + (if (query != null) 1 else 0)
        if (n > 0) list.scrollToItem(n - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Header("Assistant", onBack = { nav.back() }, leftIcon = "back") {
            IconButtonPlain("plus", { g.assistant.newChat() }, desc = "New chat")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty() && streaming == null) item {
                Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt("Ask, capture or do.", LfmType.answer)
                    Txt("“Remind me to call the plumber at six.” “Add oat milk to my list.” “What did I note about the boiler?”",
                        LfmType.small, c.text2)
                    Txt(if (g.engine.hasText()) "Runs on this phone with LFM3-1.7B." else "No model yet: import LFM3-1.7B in Settings → Models.",
                        LfmType.meta, c.text2)
                }
            }
            items(visible, key = { it.id }) { m ->
                when (m.role) {
                    Role.User -> UserBubble(m)
                    Role.Assistant -> AssistantText(m.text, m.meta, citations = m.searchId != null)
                    Role.Tool -> if (m.searchId != null) SearchCard(m) { nav.go(Route.Search(it)) }
                        else ToolCard(m, records[m.actionId], onUndo = { id -> scope.launch { g.assistant.undo(id) } })
                    Role.Notice -> Txt(m.text, LfmType.meta, c.text2)
                }
            }
            streaming?.let { s ->
                item(key = "streaming") {
                    if (s.text.isEmpty()) Txt(if (s.thinking) "Thinking…" else "…", LfmType.small, c.text2)
                    else AssistantText(s.text, "")
                }
            }
            query?.let { q -> item(key = "query") { QueryCard(q) } }
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
            Composer(draft, { draft = it }, if (busy) "Writing…  tap the mic to speak next" else "Message…", onSend = {
                if (!busy) { g.assistant.send(draft); draft = "" }
            }, onMic = onMic, listening = listening)
        }
        if (busy) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
            Txt("Stop", LfmType.button, c.text, Modifier.clickable { g.assistant.stop() }.padding(8.dp), decoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    val c = Lfm.c
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
        Column(Modifier.widthIn(max = 300.dp).background(c.fill, shape).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (m.voiceSeconds > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp)) {
                LIcon("wave", size = 16.dp, tint = c.text2)
                Txt("0:%02d".format(m.voiceSeconds), LfmType.meta, c.text2)
            }
            Txt(m.text, LfmType.body)
        }
    }
}

@Composable
private fun AssistantText(text: String, meta: String, citations: Boolean = false) {
    val c = Lfm.c
    Column {
        AnswerText(text, if (text.length > 220) LfmType.bodyLarge else LfmType.answer, markKeyFact = false, citations = citations)
        if (meta.isNotEmpty()) Txt(meta, LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
    }
}

@Composable
fun ToolCard(m: ChatMessage, record: ActionRecord?, onUndo: (Long) -> Unit) {
    val c = Lfm.c
    val status = record?.status
    val shape = RoundedCornerShape(8.dp)
    val failed = status == ActionStatus.Failed || status == ActionStatus.Cancelled
    Row(Modifier.fillMaxWidth().border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).border(1.dp, c.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            LIcon(when { failed -> "x"; status == ActionStatus.Undone -> "undo"; m.cardLabel.startsWith("Added") -> "plus"; else -> "check" }, tint = c.text)
        }
        Column(Modifier.weight(1f)) {
            Label(if (status == ActionStatus.Undone) "Undone" else m.cardLabel)
            Txt(m.cardTitle, LfmType.bodyLarge.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 2,
                decoration = if (status == ActionStatus.Undone) TextDecoration.LineThrough else null)
            if (m.cardDetail.isNotEmpty()) Txt(m.cardDetail, LfmType.label.copy(fontWeight = null), c.text2, maxLines = 2)
        }
        val id = m.actionId
        if (id != null && status == ActionStatus.Done && record.undo.isNotEmpty()) {
            Txt("Undo", LfmType.button.copy(fontWeight = LfmType.rowTitle.fontWeight), c.text,
                Modifier.clickable { onUndo(id) }.padding(6.dp), decoration = TextDecoration.Underline)
        }
    }
}
