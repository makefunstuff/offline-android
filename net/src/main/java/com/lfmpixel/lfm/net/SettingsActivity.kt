package com.lfmpixel.lfm.net

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** LFM Net's own screen: the provider, its address or key, Wikipedia and a proxy. Keys never leave this app. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = NetSettings(this)
        setContent {
            val c = if (isSystemInDarkTheme()) Dark else Light
            var cfg by remember { mutableStateOf(store.load()) }
            val set: (NetConfig) -> Unit = { cfg = it; store.save(it) }
            Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("LFM Net", style = header, color = c.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    Text("The helper LFM uses for web search, opening result pages and model downloads. It keeps no chats or notes: only these settings.",
                        style = small, color = c.text2, modifier = Modifier.padding(top = 14.dp))
                    Label(c, "Web search provider")
                    Segmented(c, WebProvider.entries.map { it.label }, cfg.provider.label) { l -> set(cfg.copy(provider = WebProvider.entries.first { it.label == l })) }
                    when (cfg.provider) {
                        WebProvider.None -> Fine(c, "Only Wikipedia, if it is on below.")
                        WebProvider.SearXNG -> {
                            Field(c, "Address", cfg.searxngUrl, "http://10.0.0.2:8888 or https://search.example.org", KeyboardType.Uri) { set(cfg.copy(searxngUrl = it)) }
                            Field(c, "User (optional)", cfg.searxngUser, "") { set(cfg.copy(searxngUser = it)) }
                            Field(c, "Password (optional)", cfg.searxngPassword, "", secret = true) { set(cfg.copy(searxngPassword = it)) }
                            Fine(c, "Your own server, reached over your WireGuard VPN, or over https with a password. Turn on JSON output in its settings.yml (search: formats: [html, json]). No account with anyone; the search engines it asks see your server's address.")
                        }
                        WebProvider.Brave -> {
                            Field(c, "API key", cfg.braveKey, "", secret = true) { set(cfg.copy(braveKey = it)) }
                            Fine(c, "A Brave account with your card: every search with this key is tied to you, and reportedly kept up to 90 days.")
                        }
                        WebProvider.Mojeek -> {
                            Field(c, "API key", cfg.mojeekKey, "", secret = true) { set(cfg.copy(mojeekKey = it)) }
                            Fine(c, "A Mojeek account with your card: every search with this key is tied to you; 90-day retention unless you ask for none.")
                        }
                    }
                    Label(c, "Wikipedia")
                    Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also search Wikipedia", style = rowTitle, color = c.text)
                            Text("Facts only, no account", style = meta, color = c.text2)
                        }
                        Switch(c, cfg.wikipedia) { set(cfg.copy(wikipedia = it)) }
                    }
                    if (cfg.wikipedia) Field(c, "Language", cfg.wikiLang, "en") { set(cfg.copy(wikiLang = it)) }
                    Label(c, "Proxy")
                    Field(c, "SOCKS proxy (optional)", cfg.proxy, "127.0.0.1:9050 for Orbot", KeyboardType.Uri) { set(cfg.copy(proxy = it)) }
                    Fine(c, "Hides your IP address from the provider and from opened pages. It can't reach a SearXNG that is only on your VPN.")
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

private data class NetColors(val bg: Color, val text: Color, val text2: Color, val line: Color, val fill: Color, val control: Color)
private val Light = NetColors(Color(0xFFFFFFFF), Color(0xFF0B0B0C), Color(0xFF5C5C62), Color(0xFFE4E4E7), Color(0xFFF4F4F5), Color(0xFF8B8B92))
private val Dark = NetColors(Color(0xFF09090A), Color(0xFFF2F2F3), Color(0xFF9D9DA4), Color(0xFF27272A), Color(0xFF161618), Color(0xFF6A6A72))

private val header = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
private val rowTitle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
private val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
private val label = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
private val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

@Composable
private fun Label(c: NetColors, text: String) = Text(text, style = label, color = c.text2, modifier = Modifier.padding(top = 20.dp, bottom = 6.dp))

@Composable
private fun Fine(c: NetColors, text: String) = Text(text, style = meta, color = c.text2, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun Field(c: NetColors, name: String, value: String, placeholder: String, type: KeyboardType = KeyboardType.Text, secret: Boolean = false,
                  onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.padding(top = 10.dp)) {
        Text(name, style = label, color = c.text2)
        Box(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 44.dp).background(c.fill, shape).border(1.dp, c.control, shape)
            .padding(horizontal = 12.dp, vertical = 11.dp)) {
            if (value.isEmpty()) Text(placeholder, style = body, color = c.text2)
            BasicTextField(value, onChange, textStyle = body.copy(color = c.text), cursorBrush = SolidColor(c.text), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = name })
        }
    }
}

@Composable
private fun Segmented(c: NetColors, items: List<String>, selected: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.control, shape)) {
        items.forEachIndexed { i, t ->
            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(c.control))
            val on = t == selected
            Box(Modifier.weight(1f).height(36.dp).background(if (on) c.text else Color.Transparent).clickable(role = Role.RadioButton) { onSelect(t) },
                contentAlignment = Alignment.Center) {
                Text(t, style = small.copy(fontSize = 13.sp, fontWeight = if (on) FontWeight.Medium else null), color = if (on) c.bg else c.text2)
            }
        }
    }
}

@Composable
private fun Switch(c: NetColors, on: Boolean, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.width(40.dp).height(22.dp).clip(shape).background(if (on) c.text else Color.Transparent)
        .border(1.dp, if (on) c.text else c.control, shape).clickable(role = Role.Switch) { onChange(!on) }) {
        Box(Modifier.padding(start = if (on) 21.dp else 3.dp, top = 3.dp).size(14.dp).clip(RoundedCornerShape(3.dp)).background(if (on) c.bg else c.text2))
    }
}
