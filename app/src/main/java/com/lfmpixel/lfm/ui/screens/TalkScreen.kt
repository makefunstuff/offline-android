package com.lfmpixel.lfm.ui.screens

import android.Manifest
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Talk (spec screen 10): a hands-free conversation with Omni. A box of bars shows the voice (yours while it
 * listens, its own while it speaks), the highlighter marks what it is doing now, and the conversation runs below.
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
    Column(Modifier.fillMaxSize()) {
        // Look (Edge mode): the camera's view, described live, so Talk can answer about what it sees.
        val edgeOn by g.edge.configured.collectAsState()
        Header("Talk", onBack = { close() }, leftIcon = "x") {
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
            else VoiceBox(g, state.phase, Modifier.padding(top = 24.dp, bottom = 14.dp))
            PhaseLine(state.phase, state.spoken)
            AnimatedVisibility(state.error != null, enter = fadeIn(LfmMotion.spec()) + expandVertically(LfmMotion.spec()),
                exit = fadeOut(LfmMotion.spec()) + shrinkVertically(LfmMotion.spec())) {
                Txt(state.error ?: "", LfmType.small, c.text2, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
            }
            Transcript(state.turns, Modifier.weight(1f).padding(top = 14.dp))
            // Where the last answer came from, and how soon it was heard (Settings → Show speed).
            val showSpeed = prefs?.showSpeed == true
            state.timing?.takeIf { showSpeed && it.firstSoundMs > 0 }?.let { t ->
                Txt((if (t.viaServer) "Your server" else "This phone") + (if (t.model.isNotEmpty()) " · ${t.model}" else "") +
                    (if (t.tools > 0) " · ${t.tools} ${if (t.tools == 1) "action" else "actions"}" else "") +
                    (if (t.firstWordMs > 0) " · first word %.1f s".format(java.util.Locale.ENGLISH, t.firstWordMs / 1000.0) else "") +
                    " · first sound %.1f s".format(java.util.Locale.ENGLISH, t.firstSoundMs / 1000.0),
                    LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            }
            AnimatedVisibility(state.warm, enter = fadeIn(LfmMotion.spec()) + expandVertically(LfmMotion.spec()),
                exit = fadeOut(LfmMotion.spec()) + shrinkVertically(LfmMotion.spec())) {
                val shape = RoundedCornerShape(6.dp)
                Row(Modifier.padding(top = 8.dp).fillMaxWidth().border(1.dp, c.control, shape).padding(horizontal = 12.dp, vertical = 9.dp),
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

/** The square of bars from the mock-up. Heights follow the voice; thinking is a slow ripple; paused lies flat. */
@Composable
private fun VoiceBox(g: AppGraph, phase: TalkPhase, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val reduced = LfmMotion.reduced()
    val mic by g.talk.micLevel.collectAsState()
    val out by g.talk.out.level.collectAsState()
    val level = when (phase) {
        TalkPhase.Listening, TalkPhase.Hearing -> mic
        TalkPhase.Speaking -> out
        else -> 0f
    }
    // The ripple runs only while it thinks, and never with animations off.
    val rippling = !reduced && (phase == TalkPhase.Thinking || phase == TalkPhase.Loading)
    val t = if (rippling) {
        val ripple = rememberInfiniteTransition(label = "ripple")
        ripple.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t").value
    } else 0.5f
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.size(190.dp).clip(shape).background(c.fill).border(1.dp, c.control, shape)
        .semantics { contentDescription = "Voice"; stateDescription = phase.label }, contentAlignment = Alignment.Center) {
        // The mock-up's resting shape; the voice scales it.
        val shapeOf = listOf(22f, 46f, 70f, 40f, 58f, 28f, 18f)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            shapeOf.forEachIndexed { i, h ->
                val target = when (phase) {
                    TalkPhase.Listening, TalkPhase.Hearing, TalkPhase.Speaking -> 10f + h * (0.25f + 0.75f * level.coerceIn(0f, 1f))
                    TalkPhase.Thinking, TalkPhase.Loading -> if (reduced) h * 0.5f else 10f + h * 0.5f * (0.4f + 0.6f * wave(t, i))
                    else -> 8f
                }
                val height by animateFloatAsState(target, LfmMotion.spec(LfmMotion.TINY), label = "bar$i")
                Box(Modifier.width(8.dp).height(height.dp).clip(RoundedCornerShape(2.dp)).background(
                    if (phase == TalkPhase.Paused || phase == TalkPhase.Failed) c.text3 else c.text))
            }
        }
    }
}

private fun wave(t: Float, i: Int): Float = (kotlin.math.sin((t * 2f + i / 7f) * Math.PI).toFloat() + 1f) / 2f

/** What it is doing now, marked; a short hint under it. Changes fade through. */
@Composable
private fun PhaseLine(phase: TalkPhase, spoken: Boolean) {
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
                TalkPhase.Loading -> "Loading Omni on this phone"
                TalkPhase.Listening -> "Speak when you're ready"
                TalkPhase.Hearing -> "Pause and it answers"
                TalkPhase.Thinking -> "Working it out on this phone"
                TalkPhase.Speaking -> if (spoken) "Tap stop to cut in" else "Answers in text only"
                TalkPhase.Paused -> "Tap the mic to carry on"
                TalkPhase.Failed -> "Tap the mic to try again"
                TalkPhase.Off -> ""
            }, LfmType.small, c.text2, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Transcript(turns: List<TalkTurn>, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val list = rememberLazyListState()
    val reduced = LfmMotion.reduced()
    val fade = if (reduced) null else tween<Float>(LfmMotion.MEDIUM, easing = LfmMotion.standard)
    val move = if (reduced) null else tween<androidx.compose.ui.unit.IntOffset>(LfmMotion.MEDIUM, easing = LfmMotion.emphasizedDecelerate)
    val last = turns.lastOrNull()
    LaunchedEffect(turns.size, last?.it?.length) { if (turns.isNotEmpty()) list.scrollToItem(turns.size * 2 - 1, Int.MAX_VALUE / 2) }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(bottom = 8.dp)) {
        if (turns.isEmpty()) item(key = "empty") {
            Txt(if (com.lfmpixel.lfm.LfmApp.edgeOn) "Ask anything out loud. Your server hears it and answers with its voice; nothing goes anywhere else."
                else "Ask anything out loud. It answers with its voice, on this phone. Nothing you say leaves it.",
                LfmType.small, c.text2, Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
        }
        turns.forEach { t ->
            item(key = "y${t.id}") { Line("You", t.you, Modifier.animateItem(fadeInSpec = fade, placementSpec = move, fadeOutSpec = fade)) }
            item(key = "i${t.id}") {
                if (t.it.isNotBlank() || t.done) Line("Inkling", t.it.ifBlank { "Stopped." } + if (t.stopped && t.it.isNotBlank()) " …" else "",
                    Modifier.animateItem(fadeInSpec = fade, placementSpec = move, fadeOutSpec = fade))
            }
        }
    }
}

@Composable
private fun Line(who: String, text: String, modifier: Modifier = Modifier) {
    val c = Lfm.c
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Column(Modifier.padding(vertical = 10.dp).semantics(mergeDescendants = true) {}) {
            Label(who, Modifier.padding(bottom = 2.dp))
            Txt(text, LfmType.body)
        }
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
            if (rest) MicButton(onMain, size = 64.dp)
            else {
                val stopLabel = when (phase) { TalkPhase.Speaking, TalkPhase.Thinking -> "Stop the answer"; else -> "Pause" }
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(c.text).clickable(role = Role.Button, onClick = onMain)
                    .semantics { contentDescription = stopLabel }, contentAlignment = Alignment.Center) { LIcon("stop", tint = c.bg) }
            }
        }
        Round("chat", "Carry on in Chat", on = false, onClick = onChat)
    }
}

@Composable
private fun Round(icon: String, desc: String, on: Boolean, onClick: () -> Unit) {
    val c = Lfm.c
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(46.dp).clip(shape).background(if (on) c.fill else Color.Transparent).border(1.dp, c.control, shape)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        LIcon(icon, tint = c.text2)
    }
}

@Composable
private fun NoVoice(onModels: () -> Unit) {
    val c = Lfm.c
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("Talk needs LFM3-Omni-2B", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.padding(top = 24.dp))
        Txt("Omni listens and answers out loud, all on this phone. Get it and its projector file (1.3 GB) in Models.", LfmType.body, c.text2)
        Btn("Open Models", onModels, kind = BtnKind.Primary, icon = "chip")
    }
}
