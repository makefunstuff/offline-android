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
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.lfmpixel.lfm.ui.theme.LfmMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.lfmpixel.lfm.llm.LoadState
import com.lfmpixel.lfm.ui.components.Btn

@Composable
fun ChatScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    val messages by g.assistant.messages.collectAsState(emptyList())
    val streaming by g.assistant.streaming.collectAsState()
    val working by g.assistant.working.collectAsState()
    val queued by g.assistant.queue.collectAsState()
    val status by g.engine.status.collectAsState()
    val query by g.web.pending.collectAsState()
    val actions by remember { g.db.actions().recent() }.collectAsState(emptyList())
    val records = actions.associateBy { it.id }
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Long?>(null) }
    var modelSheet by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduced = LfmMotion.reduced()
    // New lines appear at once (a streamed answer is already on screen); moves and removals animate.
    val itemFade = if (reduced) null else tween<Float>(LfmMotion.MEDIUM, easing = LfmMotion.standard)
    val itemMove = if (reduced) null else tween<androidx.compose.ui.unit.IntOffset>(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate)
    val visible = messages.filter { !(it.role == Role.Assistant && it.text.isBlank()) }
    val count = visible.size + (if (streaming != null) 1 else 0) + (if (query != null) 1 else 0) + queued.size
    // Follow the end while the owner is at the end; once they scroll up, offer a way back instead of pulling them down.
    val atEnd by remember { derivedStateOf { !list.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    // While the owner drags, follow only if they are still at the end; reaching the end again resumes it.
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress to atEnd }.collect { (dragging, end) -> if (dragging || end) follow = end } }
    // The "unloads in N min" line counts down.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(20_000); tick++ } }
    LaunchedEffect(count, streaming?.text?.length) { if (follow && count > 0) list.scrollToItem(count - 1, Int.MAX_VALUE / 2) }
    val send: () -> Unit = {
        g.assistant.send(draft); draft = ""; follow = true
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButtonPlain("back", { nav.back() }, desc = "Back")
                Column(Modifier.weight(1f).clickable(onClickLabel = "Models") { modelSheet = true }.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
                    .semantics(mergeDescendants = true) {}) {
                    Txt("Inkling", LfmType.header)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val dot = Modifier.size(7.dp)
                        when (status.text) {
                            LoadState.Ready -> Box(dot.background(c.text, RoundedCornerShape(2.dp)))
                            LoadState.Loading -> Box(dot.border(1.dp, c.text, RoundedCornerShape(2.dp)))
                            else -> Box(dot.border(1.dp, c.text3, RoundedCornerShape(2.dp)))
                        }
                        Txt(modelState(g) + (status.unloadAt?.takeIf { status.text == LoadState.Ready && tick >= 0 }?.let { " · ${untilLabel(it)}" } ?: ""),
                            LfmType.meta, c.text2, maxLines = 1)
                    }
                }
                IconButtonPlain("plus", {
                    g.assistant.newChat()
                    if (queued.isNotEmpty()) nav.show("New chat. ${queued.size} queued ${if (queued.size == 1) "message moves" else "messages move"} with you.")
                }, desc = "New chat")
            }
            com.lfmpixel.lfm.ui.components.Divider()
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty() && streaming == null && queued.isEmpty()) item {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("Ask, capture or do.", LfmType.answer)
                        listOf("Remind me to call the plumber at six", "Add oat milk to my list", "What did I note about the boiler?").forEach { ex ->
                            Btn(ex, { draft = ex }, small = true)
                        }
                        Txt(if (g.engine.hasText()) "Runs on this phone." else "No model yet: get LFM3-1.7B in Settings → Models.",
                            LfmType.meta, c.text2)
                    }
                }
                items(visible, key = { it.id }) { m ->
                    Box(Modifier.animateItem(fadeInSpec = null, placementSpec = itemMove, fadeOutSpec = itemFade)) { when (m.role) {
                        Role.User -> UserBubble(m)
                        Role.Assistant -> AssistantText(m.text, m.meta, citations = m.searchId != null)
                        Role.Tool -> if (m.searchId != null) SearchCard(m) { nav.go(Route.Search(it)) }
                            else ToolCard(m, records[m.actionId], onUndo = { id -> scope.launch { g.assistant.undo(id) } })
                        Role.Notice -> Txt(m.text, LfmType.meta, c.text2)
                    } }
                }
                streaming?.let { s ->
                    item(key = "streaming") {
                        if (s.text.isEmpty()) Txt(s.note.ifEmpty {
                            when {
                                status.text == LoadState.Loading -> "Loading ${modelState(g).substringBefore(" ·")}…"
                                s.thinking -> "Thinking…"
                                else -> "Writing…"
                            }
                        }, LfmType.small, c.text2)
                        else AssistantText(s.text, "")
                    }
                }
                query?.let { q -> item(key = "query") { QueryCard(q) } }
                items(queued, key = { "q${it.id}" }) { q ->
                    Box(Modifier.animateItem(fadeInSpec = itemFade, placementSpec = itemMove, fadeOutSpec = itemFade)) {
                        QueuedBubble(q, queued.indexOf(q) + 1, queued.size) { editing = q.id }
                    }
                }
            }
            if (!atEnd && !follow) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                Btn("Latest", { follow = true; scope.launch { if (count > 0) list.animateScrollToItem(count - 1, Int.MAX_VALUE / 2) } }, small = true, icon = "down",
                    kind = com.lfmpixel.lfm.ui.components.BtnKind.Primary)
            }
        }
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)) {
            Composer(draft, { draft = it }, if (working) "Message… it will wait its turn" else "Message…", onSend = send,
                onMic = onMic, listening = listening, onCamera = { nav.go(Route.Camera) },
                onStop = if (working) ({ g.assistant.stop() }) else null)
        }
    }
    editing?.let { id ->
        queued.firstOrNull { it.id == id }?.let { q -> QueuedSheet(g, q, queued.indexOf(q), queued.size) { editing = null } }
            ?: LaunchedEffect(id) { editing = null }
    }
    if (modelSheet) ModelSheet(g, onModels = { modelSheet = false; nav.go(Route.Models) }) { modelSheet = false }
}

/** "unloads in 4 min" for the chat header. */
private fun untilLabel(at: Long): String {
    val min = ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
    return if (min < 1) "unloads in under a minute" else "unloads in $min min"
}

/** A message waiting its turn: your bubble with a dashed edge and its place in the queue. Tap to change it. */
@Composable
private fun QueuedBubble(q: com.lfmpixel.lfm.assistant.Queued, n: Int, of: Int, onClick: () -> Unit) {
    val c = Lfm.c
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
        Column(Modifier.widthIn(max = 300.dp).clip(shape).dashedBorder(c.control, 8.dp).clickable(onClickLabel = "Edit queued message", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp).semantics(mergeDescendants = true) { contentDescription = "Queued, $n of $of: ${q.text}" }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                LIcon("history", size = 14.dp, tint = c.text2)
                Txt(if (of == 1) "Queued · runs next" else "Queued · $n of $of", LfmType.meta, c.text2)
            }
            Txt(q.text, LfmType.body, c.text2)
        }
    }
}

private fun Modifier.dashedBorder(color: androidx.compose.ui.graphics.Color, radius: androidx.compose.ui.unit.Dp) = drawBehind {
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(),
        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()), style = stroke)
}

/** Edit, move or remove a queued message. */
@Composable
private fun QueuedSheet(g: AppGraph, q: com.lfmpixel.lfm.assistant.Queued, index: Int, size: Int, onClose: () -> Unit) {
    val c = Lfm.c
    var text by remember(q.id) { mutableStateOf(q.text) }
    // While the sheet is open this message (and those after it) waits, so an edit is never overtaken by its turn.
    androidx.compose.runtime.DisposableEffect(q.id) { g.assistant.hold(q.id); onDispose { g.assistant.hold(null) } }
    val save = { if (text.trim() != q.text) g.assistant.editQueued(q.id, text) }
    FlatSheet(onDismiss = { save(); onClose() }) {
        Txt("Queued message", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        Txt(if (size == 1) "Runs once the current answer ends." else "Number ${index + 1} of $size. They run in this order.", LfmType.small, c.text2,
            Modifier.padding(top = 4.dp))
        val shape = RoundedCornerShape(6.dp)
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().background(c.fill, shape).border(1.dp, c.control, shape).padding(12.dp)) {
            androidx.compose.foundation.text.BasicTextField(text, { text = it }, textStyle = LfmType.body.copy(color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.text), minLines = 2, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Message" })
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Earlier", { save(); g.assistant.moveQueued(q.id, -1) }, Modifier.weight(1f), icon = "up", enabled = index > 0)
            Btn("Later", { save(); g.assistant.moveQueued(q.id, 1) }, Modifier.weight(1f), icon = "down", enabled = index < size - 1)
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Remove", { g.assistant.removeQueued(q.id); onClose() }, Modifier.weight(1f), icon = "trash")
            Btn(if (index == 0) "Done" else "Send next", { save(); if (index > 0) g.assistant.sendNext(q.id); onClose() }, Modifier.weight(1f),
                com.lfmpixel.lfm.ui.components.BtnKind.Primary)
        }
    }
}

@Composable
private fun UserBubble(m: ChatMessage) {
    val c = Lfm.c
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val shape = RoundedCornerShape(8.dp, 8.dp, 2.dp, 8.dp)
        Column(Modifier.widthIn(max = 300.dp).background(c.fill, shape).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 10.dp)) {
            if (m.media.isNotEmpty()) MediaThumb(m)
            if (m.voiceSeconds > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 4.dp)) {
                LIcon("wave", size = 16.dp, tint = c.text2)
                Txt("0:%02d".format(m.voiceSeconds), LfmType.meta, c.text2)
            }
            Txt(m.text, LfmType.body)
        }
    }
}

/** The photo, clip or page the owner asked about: its picture fades in once read from the app's files. */
@Composable
private fun MediaThumb(m: ChatMessage) {
    val c = Lfm.c
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, m.mediaPath) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(m.mediaPath)?.asImageBitmap() }.getOrNull() }
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, LfmMotion.spec(LfmMotion.MEDIUM), label = "thumb")
    Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bitmap?.let { b ->
            val ratio = (b.width.toFloat() / b.height).coerceIn(0.6f, 1.8f)
            Image(b, m.mediaLabel, Modifier.widthIn(max = 220.dp).aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).alpha(alpha),
                contentScale = ContentScale.Crop)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LIcon(when (m.media) { "Video" -> "video"; "Document" -> "doc"; else -> "image" }, size = 16.dp, tint = c.text2)
            Txt(m.mediaLabel.ifEmpty { m.media }, LfmType.meta, c.text2, maxLines = 1)
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
