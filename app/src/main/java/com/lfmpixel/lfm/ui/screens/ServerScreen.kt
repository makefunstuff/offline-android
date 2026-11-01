package com.lfmpixel.lfm.ui.screens

import androidx.compose.foundation.background
import com.lfmpixel.lfm.ui.theme.LfmShape
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.edge.Edge
import com.lfmpixel.lfm.edge.EdgeKey
import com.lfmpixel.lfm.edge.EdgeState
import com.lfmpixel.lfm.edge.Gateway
import com.lfmpixel.lfm.edge.QwenState
import com.lfmpixel.lfm.web.NetException
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Switch
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import kotlinx.coroutines.launch

/**
 * Settings → Your server (Edge mode): the address of the owner's own server on the home network or tailnet, its
 * key (sealed with the phone's keystore), Test, and whether it answers now.
 */
@Composable
fun ServerScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val state by g.edge.state.collectAsState()
    val qwen by g.edge.gateway.state.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val p = prefs ?: return
    LaunchedEffect(Unit) { if (!loaded) { url = p.edgeUrl; loaded = true } }
    val keySaved = p.edgeKeySealed.isNotEmpty()
    val problem = Edge.whyNot(url)
    // An empty key field keeps the saved key.
    fun keyNow() = key.ifEmpty { EdgeKey.open(p.edgeKeySealed) }

    Column(Modifier.fillMaxSize()) {
        Header("Your server", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Txt("Run chat, pictures, documents and voice on your own computer instead of the phone: much faster, with " +
                "long chats kept whole. Notes, chats and tools stay on the phone; each request goes " +
                "only to this address, and is listed in Privacy → Network log. If the server can't be reached, the phone's " +
                "own models answer until it is back.", LfmType.small, c.text2, Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 14.dp).fillMaxWidth().background(c.wash, LfmShape.card).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f)) {
                    Txt("Use my server", LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
                    Txt(serverLabel(p.edgeOn, p.edgeUrl, state), LfmType.label.copy(fontWeight = null), c.text2)
                }
                Switch(p.edgeOn, "Use my server") { on -> scope.launch { g.settings.setEdgeOn(on) } }
            }

            SectionLabel("Server")
            // The old direct voice port never wakes Qwen: one tap moves it to the gateway, with the same key.
            Gateway.gatewayFor(p.edgeUrl)?.let { to ->
                Column(Modifier.padding(top = 10.dp).fillMaxWidth().background(c.wash, LfmShape.card).padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Txt("This is the server's old voice port. Through the gateway, Talk can wake Qwen and requests ride out a mode switch.",
                        LfmType.small, c.text2)
                    Btn("Switch to the gateway", {
                        scope.launch {
                            g.settings.setEdgeServer(to, p.edgeKeySealed)
                            url = to
                            result = "Saved · ${to.substringAfter("://")}. If Test says the key is wrong, paste Wren's gateway key."
                        }
                    }, Modifier.padding(top = 8.dp), kind = BtnKind.Primary, small = true)
                }
            }
            ServerField("Address", url, { url = it; result = null }, "http://100.124.77.61:18450", KeyboardType.Uri)
            Txt("Your server's gateway: http://192.168.1.138:18110 at home, http://100.124.77.61:18450 on the tailnet. It " +
                "wakes Qwen when Talk opens (about a minute); LFM3 answers meanwhile.", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
            problem?.let { Txt(it, LfmType.meta, c.text, Modifier.padding(top = 4.dp)) }
            if (problem == null && url.isNotBlank() && !Edge.isTailnet(url))
                Txt("Not a tailnet address: requests are not encrypted, and away from home another network could answer at it. " +
                    "Use the server's 100.x tailnet address unless it is https.", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
            ServerField("API key", key, { key = it; result = null }, if (keySaved) "Saved · type to replace" else "Wren's gateway key",
                KeyboardType.Password, secret = true)
            Txt("Wren's own gateway key, from ~/.config/local-ai/gateway/inkling.key on gaming-pc. It is kept encrypted with " +
                "this phone's keystore.", LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Btn("Save", {
                    val u = Edge.normalize(url) ?: return@Btn
                    scope.launch {
                        val sealed = if (key.isEmpty()) p.edgeKeySealed else runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EdgeKey.seal(key) }
                        }.getOrElse { result = "Not saved: the phone's keystore said no (${it.message})"; return@launch }
                        g.settings.setEdgeServer(u, sealed)
                        if (!p.edgeOn) g.settings.setEdgeOn(true)
                        url = u; key = ""
                        result = "Saved"
                    }
                }, kind = BtnKind.Primary, enabled = url.isNotBlank() && problem == null)
                Btn(if (testing) "Testing…" else "Test", {
                    testing = true
                    result = null
                    scope.launch {
                        result = try { g.edge.test(url, keyNow()) } catch (e: Exception) {
                            if (e is NetException && Gateway.httpCode(e) == 401) KEY_WRONG else "Not reached: ${e.message}"
                        }
                        testing = false
                    }
                }, icon = "server", enabled = !testing && url.isNotBlank() && problem == null)
            }
            result?.let { Txt(it, LfmType.small, if (it.startsWith("Not")) c.text else c.text, Modifier.padding(top = 10.dp)) }

            SectionLabel("Now")
            Txt(when (val s = state) {
                EdgeState.Off -> "Off: everything runs on this phone."
                EdgeState.Checking -> "Checking the server…"
                is EdgeState.Online -> "Answering (${s.ms} ms). Chat, pictures and voice go to it."
                is EdgeState.Offline -> "Not reached: ${s.reason}. This phone answers; it tries the server again every little while."
            }, LfmType.small, c.text2)
            when (val q = qwen) {
                QwenState.KeyWrong -> KEY_WRONG
                is QwenState.Waking -> "Waking Qwen: the server is switching to voice mode."
                QwenState.Ready -> "Qwen is up: it writes chat and Talk answers."
                is QwenState.Busy -> "Qwen can't wake now: ${q.reason}. LFM3 answers."
                QwenState.Idle -> null
            }?.takeIf { p.edgeOn }?.let { Txt(it, LfmType.small, c.text2, Modifier.padding(top = 6.dp)) }
            Btn("Check now", { scope.launch { runCatching { g.edge.check() } } }, Modifier.padding(top = 8.dp, bottom = 24.dp), small = true,
                enabled = p.edgeOn && p.edgeUrl.isNotBlank())
        }
    }
}

@Composable
internal fun ServerField(label: String, value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType, secret: Boolean = false) {
    val c = Lfm.c
    val shape = LfmShape.control
    Column(Modifier.padding(top = 10.dp)) {
        Label(label)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 44.dp).background(c.wash, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (value.isEmpty()) Txt(placeholder, LfmType.body, c.text2)
            BasicTextField(value, onChange, textStyle = LfmType.body.copy(color = c.text), cursorBrush = SolidColor(c.text), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
}

private const val KEY_WRONG = "The server says the key is wrong. Paste Wren's gateway key, from " +
    "~/.config/local-ai/gateway/inkling.key on gaming-pc, and Save."
