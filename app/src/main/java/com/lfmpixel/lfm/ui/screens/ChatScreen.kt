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
import androidx.compose.runtime.snapshotFlow
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
import com.lfmpixel.lfm.assistant.withoutSpeed
import com.lfmpixel.lfm.data.ActionRecord
import com.lfmpixel.lfm.data.ActionStatus
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.Role
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.layout.onSizeChanged
import com.lfmpixel.lfm.llm.LoadState
import com.lfmpixel.lfm.ui.components.Btn
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.liveRegion
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ChatScreen(g: AppGraph, nav: Nav, onMic: () -> Unit, listening: Boolean) {
    val c = Lfm.c
    // Null only for the moment before the thread is first read: then neither the thread nor the empty chat shows.
    val loadedMessages by g.assistant.messages.collectAsState()
    val messages = loadedMessages ?: emptyList()
    // Only the streaming line reads the streamed text, so a new token redraws that line, not the whole chat.
    val streamingState = g.assistant.streaming.collectAsState()
    val showSpeed = g.settings.prefs.collectAsState(null).value?.showSpeed ?: true
    val working by g.assistant.working.collectAsState()
    val queued by g.assistant.queue.collectAsState()
    val status by g.engine.status.collectAsState()
    val query by g.web.pending.collectAsState()
    val reading by g.readAloud.state.collectAsState()
    reading.error?.let { e -> LaunchedEffect(e) { nav.show(e); g.readAloud.clearError() } }
    // Reading stops when the chat is left.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { g.readAloud.stop() } }
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
    // Assistant rows show when they have words, or a thought (the one before a tool call).
    val visible = remember(messages) {
        messages.filter { !(it.role == Role.Assistant && it.text.isBlank() && com.lfmpixel.lfm.llm.ModelOutput.thoughtOf(it.raw).isEmpty()) }
    }
    // Thoughts opened by hand stay open, per answer, also after leaving the chat. The one being written is LIVE_THOUGHT
    // until its answer is saved, then it carries over to that answer.
    val thoughtsOpen = rememberSaveable(saver = openSaver) { androidx.compose.runtime.mutableStateMapOf<Long, Boolean>() }
    // A whole answer (Streaming.done, then with its savedId) reaches this list from the database a moment after the
    // stream ends. Until its row is here, the streamed one stays on screen as it was, so it never blinks out and back
    // in, nor shows twice; its thought's open or folded state goes with it. A stopped or failed answer was never
    // whole: it goes at once.
    var finishing by remember { mutableStateOf<Finishing?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { streamingState.value }.collect { s ->
            when {
                s?.done == true -> finishing = Finishing(s, finishing?.takeIf { it.stream.copy(savedId = null) == s.copy(savedId = null) }?.open
                    ?: (thoughtsOpen.remove(LIVE_THOUGHT) == true))
                s == null -> thoughtsOpen.remove(LIVE_THOUGHT)
            }
        }
    }
    val saved = finishing?.stream?.savedId?.let { id -> visible.firstOrNull { it.id == id } }
    LaunchedEffect(saved?.id, finishing) {
        val f = finishing ?: return@LaunchedEffect
        if (saved != null) { if (f.open) thoughtsOpen[saved.id] = true; finishing = null }
        else { kotlinx.coroutines.delay(1500); finishing = null }
    }
    val live = streamingState.value
    val stream = when {
        live != null && !(live.done && saved != null) -> live
        live == null && saved == null -> finishing?.stream
        else -> null
    }
    // The list runs bottom up (reverseLayout), as a messenger's does: the newest line is item 0, sitting on the composer.
    // Growing answers, a keyboard coming up and new lines then keep the bottom in place by themselves; nothing scrolls
    // per token.
    val bottomKey: Any? = queued.lastOrNull()?.let { "q${it.id}" } ?: query?.let { "query" }
        ?: (if (stream != null) "streaming" else null) ?: visible.lastOrNull()?.id
    // Follow the end while the owner is at the end; once they scroll up, offer a way back instead of pulling them down.
    val atEnd by remember { derivedStateOf { !list.canScrollBackward } }
    // Kept while away (a search card, Settings), so coming back doesn't pull the owner down to the end.
    var follow by rememberSaveable { mutableStateOf(true) }
    // "Latest" scrolling down is not the owner scrolling away.
    var jumping by remember { mutableStateOf(false) }
    // While the owner drags, follow only if they are still at the end; reaching the end again resumes it.
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress to atEnd }.collect { (dragging, end) -> if (!jumping && (dragging || end)) follow = end } }
    // The "unloads in N min" line counts down.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(20_000); tick++ } }
    // The answer streams in at an even pace: what arrived in a burst is revealed over the next ~120 ms, a few
    // characters a frame, instead of jumping in chunks. Frames run only while there is something to catch up on.
    val shown = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(reduced) {
        snapshotFlow { streamingState.value?.text?.length ?: -1 }.collectLatest { target ->
            if (target < 0) { shown.intValue = 0; return@collectLatest }
            if (reduced || shown.intValue > target) { shown.intValue = target; return@collectLatest }
            // At most 30 steps a second: each step lays the answer out again, so not every frame at 120 Hz.
            var last = 0L
            while (shown.intValue < target) {
                androidx.compose.runtime.withFrameNanos { t ->
                    val ms = if (last == 0L) 33f else (t - last) / 1e6f
                    if (last != 0L && ms < 30f) return@withFrameNanos
                    last = t
                    val backlog = target - shown.intValue
                    shown.intValue += kotlin.math.ceil(backlog * (ms / 120f).coerceAtMost(1f)).toInt().coerceIn(1, backlog)
                }
            }
        }
    }
    // A new line at the bottom (a sent message, the answer starting, a card): stay on it while following.
    LaunchedEffect(bottomKey) { if (follow) list.scrollToItem(0) }
    val send: () -> Unit = {
        g.assistant.send(draft); draft = ""; follow = true
        scope.launch { list.scrollToItem(0) }
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
                IconButtonPlain("wave", { nav.go(Route.Talk) }, desc = "Talk")
                IconButtonPlain("plus", {
                    g.assistant.newChat()
                    if (queued.isNotEmpty()) nav.show("New chat. ${queued.size} queued ${if (queued.size == 1) "message moves" else "messages move"} with you.")
                }, desc = "New chat")
            }
            com.lfmpixel.lfm.ui.components.Divider()
        }
        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)) {
                // Bottom up: queued messages, the search being asked, the answer being written, then the thread.
                items(queued.asReversed(), key = { "q${it.id}" }) { q ->
                    Box(Modifier.animateItem(fadeInSpec = itemFade, placementSpec = itemMove, fadeOutSpec = itemFade)) {
                        QueuedBubble(q, queued.indexOf(q) + 1, queued.size) { editing = q.id }
                    }
                }
                query?.let { q -> item(key = "query") { QueryCard(q) } }
                if (stream != null) {
                    item(key = "streaming") {
                        // Bottom up, a growing answer grows upward. While the owner reads further up (not following),
                        // the list moves by as much, so the line they read stays put.
                        var height by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
                        val s = stream
                        val loading = status.text == LoadState.Loading && s.text.isEmpty() && s.thought.isEmpty()
                        val thinks = !loading && s.note.isEmpty() && (s.thinking || s.thought.isNotEmpty())
                        val text = if (s.done) s.text else s.text.take(shown.intValue).let { t -> if (t.lastOrNull()?.isHighSurrogate() == true) t.dropLast(1) else t }
                        val f = finishing
                        val open = if (s.done && f != null && f.stream.copy(savedId = null) == s.copy(savedId = null)) f.open else thoughtsOpen[LIVE_THOUGHT] == true
                        Column(Modifier.onSizeChanged { size ->
                            val grew = if (height < 0) 0 else size.height - height
                            height = size.height
                            val at = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "streaming" }?.index
                            if (grew != 0 && !follow && at != null && list.firstVisibleItemIndex <= at &&
                                (list.firstVisibleItemIndex < at || list.firstVisibleItemScrollOffset > 0)) list.dispatchRawDelta(grew.toFloat())
                        }) {
                            if (thinks) ThoughtBlock(s.thought, s.thoughtMs, live = !s.done && s.thinking, since = s.thinkingSince, open = open) {
                                if (s.done && f != null) finishing = f.copy(open = !f.open) else thoughtsOpen[LIVE_THOUGHT] = !open
                            }
                            if (text.isNotEmpty()) AssistantText(text, "", writing = !s.done)
                            // Thought done, nothing to read yet (a tool call being written, say): still visibly at work.
                            else if (!thinks || !s.thinking) Typing(s.note.ifEmpty { if (loading) "Loading ${modelState(g).substringBefore(" ·")}…" else "Writing…" })
                        }
                    }
                }
                items(visible.asReversed(), key = { it.id }) { m ->
                    // Your messages, cards and notices fade in; a finished answer takes the streamed one's place as it is.
                    Box(Modifier.animateItem(fadeInSpec = if (m.role == Role.Assistant) null else itemFade, placementSpec = itemMove, fadeOutSpec = itemFade)) { when (m.role) {
                        Role.User -> UserBubble(m)
                        Role.Assistant -> {
                            val thought = remember(m.raw) { com.lfmpixel.lfm.llm.ModelOutput.thoughtOf(m.raw) }
                            Column {
                                val open = thoughtsOpen[m.id] == true || (m.id == saved?.id && finishing?.open == true)
                                if (thought.isNotEmpty()) ThoughtBlock(thought, m.thoughtMs, live = false, since = 0, open = open) {
                                    if (open) { thoughtsOpen.remove(m.id); if (m.id == saved?.id) finishing = finishing?.copy(open = false) } else thoughtsOpen[m.id] = true
                                }
                                if (m.text.isNotBlank()) AssistantText(m.text, if (showSpeed) m.meta else withoutSpeed(m.meta), citations = m.searchId != null,
                                    read = if (g.readAloud.available) ReadState(reading.id == m.id, reading.id == m.id && reading.preparing) { g.readAloud.toggle(m.id, m.text) } else null)
                            }
                        }
                        Role.Tool -> if (m.searchId != null) SearchCard(m) { nav.go(Route.Search(it)) }
                            else ToolCard(m, records[m.actionId], onUndo = { id -> scope.launch { g.assistant.undo(id) } })
                        Role.Notice -> Txt(m.text, LfmType.meta, c.text2)
                    } }
                }
                if (loadedMessages != null && visible.isEmpty() && stream == null && queued.isEmpty()) item(key = "empty") {
                    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Txt("Ask, capture or do.", LfmType.answer)
                        listOf("Remind me to call the plumber at six", "Add oat milk to my list", "What did I note about the boiler?").forEach { ex ->
                            Btn(ex, { draft = ex }, small = true)
                        }
                        Txt(if (g.engine.hasText()) "Runs on this phone." else "No model yet: get LFM3-1.7B in Settings → Models.",
                            LfmType.meta, c.text2)
                    }
                }
            }
            if (!atEnd && !follow) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                Btn("Latest", { follow = true; scope.launch { jumping = true; try { list.animateScrollToItem(0) } finally { jumping = false } } }, small = true, icon = "down",
                    kind = com.lfmpixel.lfm.ui.components.BtnKind.Primary)
            }
        }
        // Android says the phone is warm: answers are slower on purpose, and say so.
        val heat by g.heat.heat.collectAsState()
        androidx.compose.animation.AnimatedVisibility(heat != com.lfmpixel.lfm.device.Heat.Normal, enter = LfmMotion.appear(), exit = LfmMotion.leave()) {
            HeatLine(heat, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
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

/** "Phone is warm · answers a little slower", with the reason in Android's words for TalkBack. */
@Composable
fun HeatLine(heat: com.lfmpixel.lfm.device.Heat, modifier: Modifier = Modifier) {
    val c = Lfm.c
    Row(modifier.semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LIcon("warn", size = 16.dp, tint = c.text2)
        Txt(heat.label, LfmType.meta, c.text2)
    }
}

/**
 * The typing indicator: three small ink squares that brighten in turn, calmly (1.2 s a cycle), beside what it is
 * doing. Read out once by TalkBack; still when animations are off.
 */
@Composable
private fun Typing(label: String) {
    val c = Lfm.c
    Row(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TypingDots()
        Txt(label, LfmType.small, c.text2)
    }
}

/** The three ink squares of the working indicator, brightening in turn. */
@Composable
private fun TypingDots() {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    // Read only while drawing (graphicsLayer), so the dots move without recomposing the row; still with animations off.
    val phase: androidx.compose.runtime.State<Float> = if (reduced) remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) } else
        androidx.compose.animation.core.rememberInfiniteTransition(label = "typing").animateFloat(0f, 3f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
            label = "phase")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            // Each square is brightest when the phase passes it, and rests at a third.
            Box(Modifier.size(6.dp).graphicsLayer {
                val d = if (reduced) 1f else (1f - kotlin.math.abs(((phase.value - i + 3f) % 3f) - 0.5f) / 1.5f).coerceIn(0f, 1f)
                alpha = 0.3f + 0.7f * d
            }.background(c.text, RoundedCornerShape(1.5.dp)))
        }
    }
}

/** A whole answer on its way to the database, and whether its thought is open. */
private data class Finishing(val stream: com.lfmpixel.lfm.assistant.Streaming, val open: Boolean)

/** The key of the thought being written, in the chat's map of opened thoughts. */
private const val LIVE_THOUGHT = -1L

/** Keeps only the opened thoughts (folded is the default). */
private val openSaver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<Long, Boolean>, Long>(
    save = { m -> m.filterValues { it }.keys.toList() },
    restore = { ids -> androidx.compose.runtime.mutableStateMapOf<Long, Boolean>().apply { ids.forEach { put(it, true) } } },
)

/**
 * What the model thought, folded to one quiet line above its answer: "Thought for 3 s". A tap opens it (its height
 * grows smoothly) and folds it again. While it thinks: the working squares and "Thinking · 4 s", and a tap lets you
 * peek at the newest of it as it is written.
 */
@Composable
private fun ThoughtBlock(thought: String, ms: Long, live: Boolean, since: Long, open: Boolean, onToggle: () -> Unit) {
    val c = Lfm.c
    val turn by animateFloatAsState(if (open) 90f else 0f, LfmMotion.spec(LfmMotion.MEDIUM), label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(RoundedCornerShape(6.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (open) "Hide thinking" else "Show thinking", onClick = onToggle)
            .padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) { if (live) liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (live) TypingDots() else LIcon("bulb", size = 15.dp, tint = c.text2)
            if (live) ThinkingFor(since) else Txt(thoughtLabel(ms), LfmType.small, c.text2, maxLines = 1)
            LIcon("right", Modifier.graphicsLayer { rotationZ = turn }, size = 14.dp, tint = c.text3)
        }
        androidx.compose.animation.AnimatedVisibility(open && thought.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(LfmMotion.spec(LfmMotion.MEDIUM)),
            exit = androidx.compose.animation.shrinkVertically(LfmMotion.spec(LfmMotion.SHORT, LfmMotion.emphasizedAccelerate), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(LfmMotion.spec(LfmMotion.SHORT))) {
            // A hairline down the left, as a quote: thinking, not the answer.
            val line = c.line
            Txt(thought, LfmType.small, c.text2,
                Modifier.padding(start = 9.dp, top = 2.dp, bottom = 8.dp).drawBehind {
                    drawLine(line, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, size.height), 1.dp.toPx())
                }.padding(start = 12.dp))
        }
    }
}

/** "Thinking · 4 s", counting; only this line redraws each second. */
@Composable
private fun ThinkingFor(since: Long) {
    val c = Lfm.c
    val seconds by androidx.compose.runtime.produceState(0L, since) {
        while (since > 0) {
            value = (android.os.SystemClock.elapsedRealtime() - since) / 1000
            kotlinx.coroutines.delay(1000 - (android.os.SystemClock.elapsedRealtime() - since) % 1000)
        }
    }
    // TalkBack hears "Thinking" once, not every second of the count.
    Txt(if (since > 0 && seconds > 0) "Thinking · $seconds s" else "Thinking…", LfmType.small, c.text2,
        Modifier.clearAndSetSemantics { contentDescription = "Thinking" }, maxLines = 1)
}

/** "Thought for 3 s" (under a second counts as 1), or "Thought" when its time wasn't kept. */
internal fun thoughtLabel(ms: Long) = when {
    ms <= 0 -> "Thought"
    ms < 60_000 -> "Thought for ${(ms / 1000).coerceAtLeast(1)} s"
    else -> "Thought for ${ms / 60_000} min ${(ms / 1000) % 60} s"
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
    val save = { if (text.trim() != q.text) g.assistant.editQueued(q.id, text) }
    // Closed any way (a lock, leaving the chat): the edit is kept before the message may run.
    val saveNow by androidx.compose.runtime.rememberUpdatedState(save)
    androidx.compose.runtime.DisposableEffect(q.id) { g.assistant.hold(q.id); onDispose { saveNow(); g.assistant.hold(null) } }
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

/** Read aloud for one answer: whether it is the one being read, still being prepared, and the tap. */
private class ReadState(val on: Boolean, val preparing: Boolean, val toggle: () -> Unit)

@Composable
private fun AssistantText(text: String, meta: String, citations: Boolean = false, read: ReadState? = null, writing: Boolean = false) {
    val c = Lfm.c
    Column {
        // Short answers are set large, long ones in body text. An answer being written crosses that line once: the two
        // sizes cross-fade and the height eases, instead of the text jumping to its new size.
        val sizeChange = LfmMotion.spec<Float>(LfmMotion.MEDIUM)
        val heightChange = LfmMotion.spec<androidx.compose.ui.unit.IntSize>(LfmMotion.MEDIUM)
        androidx.compose.animation.AnimatedContent(text.length > 220, transitionSpec = {
            androidx.compose.animation.fadeIn(sizeChange) togetherWith androidx.compose.animation.fadeOut(sizeChange) using
                androidx.compose.animation.SizeTransform(clip = false) { _, _ -> heightChange }
        }, label = "answer size") { long ->
            AnswerText(text, if (long) LfmType.bodyLarge else LfmType.answer, markKeyFact = false, citations = citations, writing = writing)
        }
        if (meta.isNotEmpty() || read != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (meta.isNotEmpty()) Txt(meta, LfmType.meta, c.text2, Modifier.weight(1f, fill = false))
            if (read != null) ReadAloudButton(read)
        }
    }
}

/** A quiet "Read aloud" after an answer; while it reads, "Stop reading" with the voice's bars. */
@Composable
private fun ReadAloudButton(r: ReadState) {
    val c = Lfm.c
    val label = when { r.preparing -> "Getting the voice ready…"; r.on -> "Stop reading"; else -> "Read aloud" }
    Row(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = r.toggle)
        .padding(horizontal = 6.dp, vertical = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val fadeIn = LfmMotion.spec<Float>(LfmMotion.SHORT)
        val fadeOut = LfmMotion.spec<Float>(90)
        androidx.compose.animation.AnimatedContent(r.on, transitionSpec = {
            androidx.compose.animation.fadeIn(fadeIn) togetherWith androidx.compose.animation.fadeOut(fadeOut)
        }, label = "read") { on -> LIcon(if (on) "stop" else "speaker", size = 15.dp, tint = if (on) c.text else c.text2) }
        Txt(label, LfmType.meta, if (r.on) c.text else c.text2, maxLines = 1)
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
