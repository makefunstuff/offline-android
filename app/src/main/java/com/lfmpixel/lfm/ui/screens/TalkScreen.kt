package com.lfmpixel.lfm.ui.screens

import com.lfmpixel.lfm.ui.components.SunDot
import android.Manifest
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.tools.PermissionGate
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.Marked
import com.lfmpixel.lfm.ui.components.MicButton
import com.lfmpixel.lfm.ui.components.Pill
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmMotion
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.voice.TalkPhase
import com.lfmpixel.lfm.voice.TalkTurn
import kotlinx.coroutines.launch

/**
 * Talk (spec screen 10; Daylight in docs/ux-spec.md): a hands-free conversation, as captions over the sky of the hour.
 * The presence mark (the sun) follows the real voice level, yours while it listens and its own while it speaks; what a
 * turn did with the app's tools shows under its caption with Undo and Show.
 */
@Composable
fun TalkScreen(g: AppGraph, nav: Nav, gate: PermissionGate) {
    val c = Lfm.c
    val state by g.talk.state.collectAsState()
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = rememberCoroutineScope()
    val available = g.talk.available

    // Opening the screen starts listening (after the mic permission); leaving it stops everything.
    LaunchedEffect(Unit) {
        g.readAloud.stop()
        // Opening Talk means "listen", also when an earlier visit was left paused.
        if (available && state.phase in listOf(TalkPhase.Off, TalkPhase.Paused, TalkPhase.Failed)) {
            if (gate.ensure(listOf(Manifest.permission.RECORD_AUDIO))) g.talk.start()
            else g.talk.pause()
        }
    }
    DisposableEffect(Unit) { onDispose { g.talk.close() } }
    // Out of sight, the mic closes and any answer stops: Talk is never heard or heard from in the background.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (state.phase != TalkPhase.Off && state.phase != TalkPhase.Paused) g.talk.pause()
    }

    var looking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val close = { nav.back() }
    val view = androidx.compose.ui.platform.LocalView.current
    // A soft note when it starts to listen (not on every turn), and one when a turn has done something.
    var lastPhase by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        if (state.phase == TalkPhase.Listening && lastPhase in listOf(TalkPhase.Off, TalkPhase.Loading, TalkPhase.Paused, TalkPhase.Failed))
            g.feel.cue(com.lfmpixel.lfm.daylight.Cue.Listen, view)
        lastPhase = state.phase
    }
    val doneCount = state.turns.sumOf { t -> t.actions.count { it.ok } }
    var heardDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(doneCount) }
    LaunchedEffect(doneCount) {
        if (doneCount > heardDone) g.feel.cue(com.lfmpixel.lfm.daylight.Cue.Done, view)
        heardDone = doneCount
    }
    val now by com.lfmpixel.lfm.daylight.rememberNow()
    val sky = com.lfmpixel.lfm.daylight.skyAt(now)
    com.lfmpixel.lfm.daylight.OnSky(sky) {
    val c = Lfm.c
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(sky.top), Color(sky.horizon))))) {
        // Look (Edge mode): the camera's view, described live, so Talk can answer about what it sees.
        val edgeOn by g.edge.configured.collectAsState()
        Header("Talk", onBack = { close() }, leftIcon = "x", onSky = false) {
            if (edgeOn && available) com.lfmpixel.lfm.ui.components.IconButtonPlain("camera", {
                if (looking) looking = false else scope.launch { if (gate.ensure(listOf(Manifest.permission.CAMERA))) looking = true }
            }, tint = if (looking) c.text else c.text2, desc = if (looking) "Look is on. Turn it off" else "Look: let Talk see the camera")
            prefs?.let { Pill(it.speechLanguage.label, modifier = Modifier.padding(end = 10.dp)) }
        }
        if (!available) {
            NoVoice { nav.go(Route.Models) }
            return@Column
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (looking && edgeOn) LookTile(g, prefs?.showSpeed == true, Modifier.padding(top = 12.dp, bottom = 10.dp))
            else Presence(g, state.phase, Color(sky.sun), Modifier.padding(top = 20.dp, bottom = 10.dp))
            PhaseLine(state.phase, state.spoken, state.answering)
            // Qwen on the gateway: asked for as Talk opened, up in about a minute; LFM3 (or the phone) answers meanwhile.
            val qwen by g.edge.gateway.state.collectAsState()
            val qwenLine = if (!edgeOn) null else qwenLine(qwen)
            // The last line stays while it fades out.
            val shown = androidx.compose.runtime.remember { arrayOf("") }
            if (qwenLine != null) shown[0] = qwenLine
            AnimatedVisibility(qwenLine != null, enter = fadeIn(LfmMotion.spec()) + expandVertically(LfmMotion.spec()),
                exit = fadeOut(LfmMotion.spec()) + shrinkVertically(LfmMotion.spec())) {
                Txt(shown[0], LfmType.small, c.text2, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
            AnimatedVisibility(state.error != null, enter = fadeIn(LfmMotion.spec()) + expandVertically(LfmMotion.spec()),
                exit = fadeOut(LfmMotion.spec()) + shrinkVertically(LfmMotion.spec())) {
                Txt(state.error ?: "", LfmType.small, c.text2, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
            Captions(g, nav, state.turns, Modifier.weight(1f).padding(top = 14.dp))
            // Where the last answer came from, and how soon it was heard (Settings → Show speed).
            val showSpeed = prefs?.showSpeed == true
            state.timing?.takeIf { showSpeed && (it.firstSoundMs > 0 || it.firstWordMs > 0) }?.let { t ->
                Txt((if (t.viaServer) "Your server" else "This phone") + (if (t.model.isNotEmpty()) " · ${t.model}" else "") +
                    (if (t.tools > 0) " · ${t.tools} ${if (t.tools == 1) "action" else "actions"}" else "") +
                    (if (t.firstWordMs > 0) " · first word %.1f s".format(java.util.Locale.ENGLISH, t.firstWordMs / 1000.0) else "") +
                    (if (t.firstSoundMs > 0) " · first sound %.1f s".format(java.util.Locale.ENGLISH, t.firstSoundMs / 1000.0) else ""),
                    LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            }
            AnimatedVisibility(state.warm, enter = fadeIn(LfmMotion.spec()) + expandVertically(LfmMotion.spec()),
                exit = fadeOut(LfmMotion.spec()) + shrinkVertically(LfmMotion.spec())) {
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LIcon("warn", size = 16.dp, tint = c.text)
                    Txt("Phone is warm, answers may be slower", LfmType.small)
                }
            }
        }
        Controls(
            phase = state.phase, spoken = state.spoken,
            onSpoken = { g.talk.setSpoken(!state.spoken) },
            onMain = {
                if (state.phase == TalkPhase.Paused || state.phase == TalkPhase.Failed || state.phase == TalkPhase.Off) scope.launch {
                    if (gate.ensure(listOf(Manifest.permission.RECORD_AUDIO))) g.talk.start()
                } else g.talk.stopOrResume()
            },
            onChat = {
                val turns = state.turns.filter { it.you.isNotBlank() }
                g.talk.close()
                scope.launch {
                    if (turns.isNotEmpty()) g.assistant.addTalk(turns)
                    nav.back()
                    nav.go(Route.Chat)
                }
            },
        )
    }
    }
}

/**
 * The presence mark: the sun of the hour, a disc with a soft halo, inside a dial of 60 ticks (Horizon Almanac's
 * signature). It swells with the real voice level (the mic while it listens, its own voice while it speaks) and the
 * ticks light round the dial with it; it breathes slowly while it thinks, and lies as a quiet ring when paused. With
 * animations off it holds still and the ticks show the state: all lit while it listens, half while it thinks.
 */
@Composable
private fun Presence(g: AppGraph, phase: TalkPhase, sun: Color, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    val mic by g.talk.micLevel.collectAsState()
    val out by g.talk.out.level.collectAsState()
    val raw = when (phase) {
        TalkPhase.Listening, TalkPhase.Hearing -> mic
        TalkPhase.Speaking -> out
        else -> 0f
    }.coerceIn(0f, 1f)
    val level by animateFloatAsState(if (reduced) 0f else raw, LfmMotion.level(), label = "level")
    // The breath runs only while it thinks, and never with animations off.
    val breathing = !reduced && (phase == TalkPhase.Thinking || phase == TalkPhase.Loading)
    val breath = if (breathing) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b").value
    } else 0f
    val resting = phase == TalkPhase.Paused || phase == TalkPhase.Failed || phase == TalkPhase.Off
    androidx.compose.foundation.Canvas(modifier.size(210.dp).semantics { contentDescription = "Wren"; stateDescription = phase.label }) {
        val center = this.center
        val base = 34.dp.toPx()
        // The dial: 60 ticks, every fifth longer; lit from the top, clockwise, as far as the voice (or the breath) goes.
        val lit = when {
            resting -> 0f
            reduced -> if (phase == TalkPhase.Thinking || phase == TalkPhase.Loading) 0.5f else 1f
            phase == TalkPhase.Thinking || phase == TalkPhase.Loading -> 0.25f + 0.5f * breath
            else -> 0.2f + 0.8f * level
        }
        val outer = size.minDimension / 2 - 2.dp.toPx()
        for (i in 0 until 60) {
            val a = Math.PI * 2 * i / 60 - Math.PI / 2
            val inner = outer - (if (i % 5 == 0) 9.dp.toPx() else 5.dp.toPx())
            val on = i < 60 * lit
            drawLine(c.text.copy(alpha = if (on) 0.75f else 0.2f),
                androidx.compose.ui.geometry.Offset(center.x + inner * kotlin.math.cos(a).toFloat(), center.y + inner * kotlin.math.sin(a).toFloat()),
                androidx.compose.ui.geometry.Offset(center.x + outer * kotlin.math.cos(a).toFloat(), center.y + outer * kotlin.math.sin(a).toFloat()),
                (if (i % 5 == 0) 2.dp else 1.dp).toPx())
        }
        if (resting) {
            drawCircle(c.text2, base, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            return@Canvas
        }
        val r = base * (1f + 0.35f * level + 0.08f * breath)
        val halo = r * (1.9f + 0.4f * level)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(sun.copy(alpha = 0.30f + 0.30f * level + 0.1f * breath), sun.copy(alpha = 0f)),
            center, halo), halo, center)
        drawCircle(sun, r, center)
        // Listening: a thin ring in the ink says the ear is open.
        if (phase == TalkPhase.Listening || phase == TalkPhase.Hearing)
            drawCircle(c.text.copy(alpha = 0.5f), r + 7.dp.toPx(), center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
    }
}

/** Talk's calm line about Qwen on the gateway, or null when there is nothing to say. */
internal fun qwenLine(q: com.lfmpixel.lfm.edge.QwenState): String? = when (q) {
    is com.lfmpixel.lfm.edge.QwenState.Waking -> "Waking Qwen… LFM3 answers meanwhile"
    is com.lfmpixel.lfm.edge.QwenState.Busy -> "Qwen can't wake now (${q.reason.take(80)}); LFM3 answers"
    com.lfmpixel.lfm.edge.QwenState.KeyWrong -> "Your server's key is wrong: enter Wren's gateway key in Settings → Your server"
    com.lfmpixel.lfm.edge.QwenState.Ready, com.lfmpixel.lfm.edge.QwenState.Idle -> null
}

/** What it is doing now, marked; a short hint under it. Changes fade through. */
@Composable
private fun PhaseLine(phase: TalkPhase, spoken: Boolean, answering: String) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    AnimatedContent(phase, transitionSpec = {
        if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
        else fadeIn(tween(LfmMotion.SHORT, delayMillis = 60, easing = LfmMotion.standard)) togetherWith fadeOut(tween(90, easing = LfmMotion.standard))
    }, label = "phase") { p ->
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
            val doing = p != TalkPhase.Paused && p != TalkPhase.Failed && p != TalkPhase.Off
            if (doing) Marked(p.label, LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
            else Txt(p.label, LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(when (p) {
                TalkPhase.Loading -> "Getting ready"
                TalkPhase.Listening -> "Whenever you're ready"
                TalkPhase.Hearing -> "Take your time; pause when you're done"
                // Which model is answering, and where (Qwen on the server, or Omni on the phone).
                TalkPhase.Thinking -> answering.ifEmpty { "Thinking it over" }
                TalkPhase.Speaking -> if (spoken) "Tap stop to cut in" else "Answers in text only"
                TalkPhase.Paused -> "Tap the mic to carry on"
                TalkPhase.Failed -> "Tap the mic to try again"
                TalkPhase.Off -> ""
            }, LfmType.small, c.text2, Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * The conversation as captions: the latest exchange large, in Wren's serif, earlier ones smaller and faded above it.
 * Under each answer, what it did, with Undo and Show.
 */
@Composable
private fun Captions(g: AppGraph, nav: Nav, turns: List<TalkTurn>, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val list = rememberLazyListState()
    val reduced = LfmMotion.reduced()
    val fade = if (reduced) null else tween<Float>(LfmMotion.MEDIUM, easing = LfmMotion.standard)
    val move = if (reduced) null else tween<androidx.compose.ui.unit.IntOffset>(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate)
    val last = turns.lastOrNull()
    LaunchedEffect(turns.size, last?.it?.length, last?.actions?.size) { if (turns.isNotEmpty()) list.scrollToItem(turns.size - 1, Int.MAX_VALUE / 2) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
        if (turns.isEmpty()) item(key = "empty") {
            Txt(if (com.lfmpixel.lfm.LfmApp.edgeOn) "Say it the way you'd say it to a friend. Your server hears it and answers; nothing goes anywhere else."
                else "Say it the way you'd say it to a friend. I answer out loud, here on the phone. Nothing you say leaves it.",
                LfmType.small, c.text2, Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp).fillMaxWidth(), textAlign = TextAlign.Center)
        }
        items(turns, key = { it.id }) { t ->
            val latest = t === last
            Column(Modifier.fillMaxWidth().animateItem(fadeInSpec = fade, placementSpec = move, fadeOutSpec = fade)
                .padding(vertical = 10.dp).let { if (latest) it else it.alpha(0.6f) }) {
                Txt(t.you, if (latest) LfmType.bodyLarge else LfmType.small, c.text2, Modifier.semantics { contentDescription = "You said: " + t.you })
                if (t.it.isNotBlank() || t.done) {
                    val said = t.it.ifBlank { "Stopped." } + if (t.stopped && t.it.isNotBlank()) " …" else ""
                    Txt(said, if (latest) LfmType.caption else LfmType.voice, c.text, Modifier.padding(top = 6.dp)
                        .semantics { if (latest) liveRegion = LiveRegionMode.Polite })
                }
                t.actions.forEach { a -> ActionLine(g, nav, a) }
            }
        }
    }
}

/** "✓ Reminder set · Dentist at 15:00   Undo  Show": what a turn did, and the two ways to check it. */
@Composable
private fun ActionLine(g: AppGraph, nav: Nav, a: com.lfmpixel.lfm.voice.TalkAction) {
    val c = Lfm.c
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    var undone by androidx.compose.runtime.saveable.rememberSaveable(a.actionId) { androidx.compose.runtime.mutableStateOf(false) }
    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (a.ok && !undone) SunDot(8.dp) else LIcon(if (!a.ok) "x" else "undo", size = 16.dp, tint = c.text2)
        Txt(listOf(if (undone) "Undone" else a.label, a.title).filter { it.isNotEmpty() }.joinToString(" · "), LfmType.small, c.text2,
            Modifier.weight(1f), maxLines = 2, decoration = if (undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
        val id = a.actionId
        if (a.ok && !undone && id != null && a.undo.isNotEmpty()) SmallAction("Undo") {
            scope.launch { if (g.assistant.undo(id)) { undone = true; g.feel.cue(com.lfmpixel.lfm.daylight.Cue.Undo, view) } }
        }
        if (a.ok && !undone) SmallAction("Show") { scope.launch { show(g, nav, a) } }
    }
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    val c = Lfm.c
    Box(Modifier.clip(LfmShape.control).clickable(role = Role.Button, onClick = onClick)
        .size(width = 56.dp, height = 44.dp), contentAlignment = Alignment.Center) {
        Txt(text, LfmType.action, c.action)
    }
}

/** Show: the note or to-do it made in the editor; reminders and events on today's page; anything else in Action history. */
private suspend fun show(g: AppGraph, nav: Nav, a: com.lfmpixel.lfm.voice.TalkAction) {
    val (kind, value) = a.undo.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val entry = if (kind == "entry") value.toLongOrNull()?.let { g.db.entries().get(it) } else null
    when {
        entry != null -> nav.go(Route.Editor(entry.id, entry.kind))
        kind == "reminder" || kind == "event" -> { nav.back(); nav.reset(Route.Today); nav.daysBack = 0 }
        else -> nav.go(Route.History)
    }
}

@Composable
private fun Controls(phase: TalkPhase, spoken: Boolean, onSpoken: () -> Unit, onMain: () -> Unit, onChat: () -> Unit) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        Round(if (spoken) "speaker" else "note", if (spoken) "Answers out loud. Tap for text only" else "Answers in text only. Tap for out loud",
            on = !spoken, onClick = onSpoken)
        val resting = phase == TalkPhase.Paused || phase == TalkPhase.Failed || phase == TalkPhase.Off
        val reduced = LfmMotion.reduced()
        AnimatedContent(resting, transitionSpec = {
            if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else (fadeIn(tween(LfmMotion.SHORT, easing = LfmMotion.emphasizedDecelerate)) +
                scaleIn(tween(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate), initialScale = 0.85f)) togetherWith
                fadeOut(tween(90, easing = LfmMotion.emphasizedAccelerate))
        }, label = "main") { rest ->
            if (rest) MicButton(onMain, size = 64.dp, primary = true)
            else {
                val stopLabel = when (phase) { TalkPhase.Speaking, TalkPhase.Thinking -> "Stop the answer"; else -> "Pause" }
                Box(Modifier.size(64.dp).clip(LfmShape.round).background(c.text).clickable(role = Role.Button, onClick = onMain)
                    .semantics { contentDescription = stopLabel }, contentAlignment = Alignment.Center) { LIcon("stop", tint = c.bg) }
            }
        }
        Round("chat", "Carry on in Chat", on = false, onClick = onChat)
    }
}

@Composable
private fun Round(icon: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val c = Lfm.c
    val shape = LfmShape.round
    Box(Modifier.size(48.dp).clip(shape).background(if (on) c.text.copy(alpha = 0.16f) else c.fill)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        LIcon(icon, tint = c.text2)
    }
}

@Composable
private fun NoVoice(onModels: () -> Unit) {
    val c = Lfm.c
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Talk needs LFM3-Omni-2B", LfmType.title2, modifier = Modifier.padding(top = 24.dp))
        Txt("Omni listens and answers out loud, all on this phone. Get it and its projector file (1.3 GB) in Models, and I'll be here.",
            LfmType.voiceBody, c.text2)
        Btn("Open Models", onModels, kind = BtnKind.Primary)
    }
}
