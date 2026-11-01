package com.lfmpixel.lfm.ui.screens

import android.app.StatusBarManager
import com.lfmpixel.lfm.ui.theme.LfmShape
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.R
import com.lfmpixel.lfm.assist.CaptureTileService
import com.lfmpixel.lfm.assist.CaptureWidget
import com.lfmpixel.lfm.settings.AssistOpens
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Segmented
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.voice.SpeechLanguage
import com.lfmpixel.lfm.voice.SpeechVoice
import kotlinx.coroutines.launch

/** Settings → Voice and Talk: Omni's voice and language, Talk, and the ways in (assistant gesture, tile, widget). */
@Composable
fun VoiceSettingsScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val ctx = LocalContext.current
    val prefs by g.settings.prefs.collectAsState(null)
    val reading by g.readAloud.state.collectAsState()
    val scope = rememberCoroutineScope()
    val p = prefs ?: return
    var isAssistant by remember { mutableStateOf(false) }
    // Back from Android's settings: check again.
    LifecycleResumeEffect(Unit) {
        isAssistant = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        onPauseOrDispose { }
    }
    var allLanguages by remember { mutableStateOf(false) }
    // The sample stops however the screen is left.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { g.readAloud.stop() } }

    Column(Modifier.fillMaxSize()) {
        Header("Voice and Talk", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (!g.engine.hasVoice()) {
                Txt("Voice, Talk and read-aloud need LFM3-Omni-2B and its projector file.", LfmType.small, c.text2, Modifier.padding(top = 12.dp))
                Btn("Open Models", { nav.go(Route.Models) }, Modifier.padding(top = 8.dp), icon = "chip")
            }
            SectionLabel("Voice")
            Segmented(SpeechVoice.entries.map { it.label }, p.speechVoice.label,
                { l -> scope.launch { g.settings.setSpeechVoice(SpeechVoice.entries.first { it.label == l }) } })
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(if (reading.id == SAMPLE_ID) "Stop" else "Hear it", { g.readAloud.toggle(SAMPLE_ID, sample(p.speechLanguage)) },
                    small = true, icon = if (reading.id == SAMPLE_ID) "stop" else "speaker", enabled = g.engine.hasVoice())
                Txt(if (reading.id == SAMPLE_ID && reading.preparing) "Getting the voice ready…" else "Omni speaks on this phone; nothing is sent.",
                    LfmType.meta, c.text2)
            }

            SectionLabel("Language")
            Txt("What Talk listens for and answers in, and the accent read-aloud uses.", LfmType.small, c.text2, Modifier.padding(bottom = 8.dp))
            val shown = if (allLanguages) SpeechLanguage.entries else SpeechLanguage.entries.take(4).let { if (p.speechLanguage in it) it else it + p.speechLanguage }
            Column(Modifier.fillMaxWidth().border(1.dp, c.line, LfmShape.card)) {
                shown.forEachIndexed { i, l ->
                    if (i > 0) Divider()
                    val on = l == p.speechLanguage
                    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { scope.launch { g.settings.setSpeechLanguage(l) } }
                        .semantics { selected = on }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(l.label, LfmType.body, modifier = Modifier.weight(1f))
                        if (on) LIcon("check", tint = c.text)
                    }
                }
            }
            if (!allLanguages) Btn("More languages", { allLanguages = true }, Modifier.padding(top = 8.dp), small = true, icon = "down")
            Txt("Speech outside English was not tried yet; Omni lists 19 languages.", LfmType.meta, c.text2, Modifier.padding(top = 6.dp))

            SectionLabel("Talk")
            ToggleRow("Answer out loud", "Off: Talk answers in text only, faster and silent", p.talkSpoken) { scope.launch { g.settings.setTalkSpoken(it) } }

            SectionLabel("Ways in")
            Txt("Holding the power button and the corner swipe open:", LfmType.small, c.text2, Modifier.padding(bottom = 8.dp))
            Segmented(AssistOpens.entries.map { it.label }, p.assistOpens.label,
                { l -> scope.launch { g.settings.setAssistOpens(AssistOpens.entries.first { it.label == l }) } })
            WayIn("Phone's assistant", if (isAssistant) "Inkling is the phone's assistant" else "Gemini or another app answers the power button now",
                if (isAssistant) "Change" else "Set", Modifier.padding(top = 12.dp)) {
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { nav.show("Open Android Settings → Apps → Default apps → Digital assistant app") }
            }
            WayIn("Quick Settings tile", "Capture, one tap from anywhere", "Add", Modifier.padding(top = 8.dp)) {
                ctx.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                    ComponentName(ctx, CaptureTileService::class.java), "Capture", Icon.createWithResource(ctx, R.drawable.ic_tile_capture),
                    ctx.mainExecutor) { result ->
                    nav.show(when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Capture is in Quick Settings"
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Capture is already in Quick Settings"
                        else -> "Not added. You can add it by editing Quick Settings."
                    })
                }
            }
            WayIn("Home-screen widget", "Capture, Talk and a new note", "Add", Modifier.padding(top = 8.dp)) {
                val awm = ctx.getSystemService(AppWidgetManager::class.java)
                if (awm?.isRequestPinAppWidgetSupported == true) awm.requestPinAppWidget(ComponentName(ctx, CaptureWidget::class.java), null, null)
                else nav.show("Long-press the home screen → Widgets → Inkling")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun WayIn(title: String, detail: String, action: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Lfm.c
    Row(modifier.fillMaxWidth().border(1.dp, c.line, LfmShape.card).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(detail, LfmType.label.copy(fontWeight = null), c.text2)
        }
        Btn(action, onClick, small = true)
    }
}

/** Not a chat message id: the "Hear it" sample. */
private const val SAMPLE_ID = -42L

private fun sample(l: SpeechLanguage) = when (l) {
    SpeechLanguage.German -> "Hallo, so klinge ich. Alles bleibt auf diesem Telefon."
    SpeechLanguage.French -> "Bonjour, voici ma voix. Tout reste sur ce téléphone."
    SpeechLanguage.Spanish -> "Hola, así suena mi voz. Todo se queda en este teléfono."
    else -> "Hello, this is how I sound. Everything stays on this phone."
}
