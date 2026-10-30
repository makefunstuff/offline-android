package com.lfmpixel.lfm.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lfmpixel.lfm.AppGraph
import com.lfmpixel.lfm.data.ChatMessage
import com.lfmpixel.lfm.data.SearchSource
import com.lfmpixel.lfm.settings.SearchMode
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.components.Btn
import com.lfmpixel.lfm.ui.components.BtnKind
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.Header
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Label
import com.lfmpixel.lfm.ui.components.ListRow
import com.lfmpixel.lfm.ui.components.SectionLabel
import com.lfmpixel.lfm.ui.components.Segmented
import com.lfmpixel.lfm.ui.components.Switch
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.web.NetClient
import com.lfmpixel.lfm.web.NetException
import com.lfmpixel.lfm.web.SearchRequest
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The query card in the chat (spec screens 2 and S3): the words the model wants to send, shown before they go.
 * With a personal word in them, the card says what it is, marks it, and offers Remove name / Send anyway.
 */
@Composable
fun QueryCard(req: SearchRequest) {
    val c = Lfm.c
    var editing by remember(req) { mutableStateOf(false) }
    var text by remember(req) { mutableStateOf(req.query) }
    val shape = RoundedCornerShape(8.dp)
    val personal = req.personal
    if (personal.isEmpty() && !editing) {
        Row(Modifier.fillMaxWidth().border(1.dp, c.control, shape).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIcon("globe", size = 18.dp)
            Column(Modifier.weight(1f)) {
                Label(if (req.afterWebText) "Search again, after reading web pages" else "Search the web for")
                Txt(req.query, LfmType.small.copy(fontWeight = FontWeight.Bold))
            }
            Btn("Edit", { editing = true }, small = true)
            Btn("Send", { req.send() }, small = true, kind = BtnKind.Primary)
        }
        return
    }
    Column(Modifier.fillMaxWidth().border(1.dp, if (personal.isNotEmpty()) c.text else c.control, shape).padding(12.dp)) {
        if (personal.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIcon("warn", size = 18.dp)
            Txt("This search has ${personal.first().kind.what}", LfmType.small.copy(fontWeight = FontWeight.Bold))
        }
        Column(Modifier.padding(vertical = 10.dp).fillMaxWidth()) {
            Divider()
            Column(Modifier.padding(vertical = 8.dp)) {
                Label("Search the web for")
                if (editing) {
                    val fieldShape = RoundedCornerShape(6.dp)
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().heightIn(min = 40.dp).border(1.dp, c.control, fieldShape).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        BasicTextField(text, { text = it }, textStyle = LfmType.small.copy(color = c.text, fontWeight = FontWeight.Bold),
                            cursorBrush = SolidColor(c.text), modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search words" })
                    }
                } else {
                    Text(markWords(req.query, personal.map { it.word }), style = LfmType.small.copy(fontWeight = FontWeight.Bold), color = c.text)
                }
            }
            Divider()
        }
        if (personal.isNotEmpty() && !editing) {
            val noun = personal.first().kind.noun
            Txt("The provider would see this $noun. Search without it, or send it anyway.", LfmType.small.copy(fontSize = 13.sp), c.text2)
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (editing) {
                Btn("Cancel", { editing = false; text = req.query }, Modifier.weight(1f), small = true)
                Btn("Send", { req.send(text) }, Modifier.weight(1f), small = true, kind = BtnKind.Primary)
            } else {
                Btn("Edit", { editing = true }, Modifier.weight(0.8f), small = true)
                Btn("Send anyway", { req.send() }, Modifier.weight(1.2f), small = true)
                Btn("Remove ${personal.first().kind.noun}", { req.removePersonal() }, Modifier.weight(1.3f), small = true, kind = BtnKind.Primary)
            }
        }
    }
}

/** The personal words get the highlighter: the one thing that matters on this card. */
@Composable
private fun markWords(text: String, words: List<String>): AnnotatedString {
    val c = Lfm.c
    val spans = words.mapNotNull { w -> text.indexOf(w, ignoreCase = true).takeIf { it >= 0 }?.let { it until it + w.length } }.sortedBy { it.first }
    return buildAnnotatedString {
        var i = 0
        for (r in spans) {
            if (r.first < i) continue
            append(text.substring(i, r.first))
            withStyle(SpanStyle(background = c.highlight, color = c.onHighlight)) { append(text.substring(r)) }
            i = r.last + 1
        }
        append(text.substring(i))
    }
}

/** A search in the chat: what was sent and how much was read. Tapping it opens the Search page. */
@Composable
fun SearchCard(m: ChatMessage, onOpen: (Long) -> Unit) {
    val c = Lfm.c
    val id = m.searchId?.takeIf { it > 0 }
    Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).let { if (id != null) it.clickable { onOpen(id) } else it }
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).border(1.dp, c.line, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            LIcon(if (id != null) "globe" else "x")
        }
        Column(Modifier.weight(1f)) {
            Label(m.cardLabel)
            Txt(m.cardTitle, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 2)
            if (m.cardDetail.isNotEmpty()) Txt(m.cardDetail, LfmType.label.copy(fontWeight = null), c.text2, maxLines = 2)
        }
        if (id != null) LIcon("right", tint = c.text3)
    }
}

private val citation = Regex("""\[(\d{1,2})]""")

/**
 * An answer with its sources: **bold** shows bold (on the Search page the first bold part, the key fact, gets
 * the highlighter instead) and [1] shows as a small numbered box.
 */
@Composable
fun AnswerText(text: String, style: TextStyle, markKeyFact: Boolean, modifier: Modifier = Modifier) {
    val c = Lfm.c
    val numbers = citation.findAll(text).map { it.groupValues[1] }.toSet()
    val annotated = buildAnnotatedString {
        var marked = false
        text.split("**").let { parts -> if (parts.size % 2 == 0) listOf(text) else parts }.forEachIndexed { i, part ->
            val bold = i % 2 == 1
            val styleFor = when {
                bold && markKeyFact && !marked -> { marked = true; SpanStyle(background = c.highlight, color = c.onHighlight, fontWeight = FontWeight.SemiBold) }
                bold -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> null
            }
            styleFor?.let { pushStyle(it) }
            var last = 0
            citation.findAll(part).forEach { m ->
                append(part.substring(last, m.range.first))
                appendInlineContent("cite" + m.groupValues[1], "[${m.groupValues[1]}]")
                last = m.range.last + 1
            }
            append(part.substring(last))
            styleFor?.let { pop() }
        }
    }
    val inline = numbers.associate { n ->
        "cite$n" to InlineTextContent(Placeholder(17.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
            Box(Modifier.padding(start = 2.dp).border(1.dp, c.control, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Text(n, style = LfmType.meta.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold), color = c.text)
            }
        }
    }
    Text(annotated, style = style, color = c.text, inlineContent = inline, modifier = modifier)
}

/** Spec screen 3: the words that were sent and to whom, the answer with numbered sources, and what left the phone. */
@Composable
fun SearchScreen(g: AppGraph, nav: Nav, id: Long) {
    val c = Lfm.c
    val rec by remember(id) { g.db.searches().watch(id) }.collectAsState(null)
    var open by remember { mutableStateOf<SearchSource?>(null) }
    Column(Modifier.fillMaxSize()) {
        Header("Search", onBack = { nav.back() })
        val r = rec ?: return@Column
        val sources = SearchSource.decode(r.sources)
        val read = sources.count { it.read }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.padding(vertical = 14.dp).fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LIcon("globe", size = 18.dp)
                Column(Modifier.weight(1f)) {
                    Label("Sent to ${r.provider.ifEmpty { "the web" }} · " + Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")))
                    Txt(r.query, LfmType.body.copy(fontWeight = FontWeight.Bold))
                }
            }
            if (r.answer.isNotBlank()) {
                AnswerText(r.answer, LfmType.bodyLarge.copy(fontSize = 16.5.sp, lineHeight = 25.sp), markKeyFact = true)
                Txt(if (read > 0) "Written on this phone from $read ${if (read == 1) "page" else "pages"}" else "Written on this phone from the result snippets",
                    LfmType.meta, c.text2, Modifier.padding(top = 4.dp))
            } else if (sources.isEmpty()) {
                Txt("No results.", LfmType.body, c.text2)
            }
            if (sources.isNotEmpty()) {
                SectionLabel("Sources")
                sources.forEachIndexed { i, s ->
                    Divider()
                    Row(Modifier.fillMaxWidth().clickable { open = s }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(22.dp).border(1.dp, c.control, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Txt(s.n.toString(), LfmType.meta.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Column(Modifier.weight(1f)) {
                            Txt(s.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), maxLines = 2)
                            Txt(s.host + if (!s.read && read > 0) " · snippet only" else "", LfmType.label.copy(fontWeight = null), c.text2, maxLines = 1)
                        }
                    }
                    if (i == sources.lastIndex) Divider()
                }
            }
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LIcon("shield", size = 16.dp, tint = c.text2)
                val pages = if (read > 0) ", then the $read result ${if (read == 1) "page was" else "pages were"} opened" else ""
                Txt(if (r.personalSent.isNotEmpty()) "Sent: the search words$pages. You chose to send “${r.personalSent}”. Nothing else from your notes, contacts or chats."
                    else "Sent: the search words$pages. Nothing from your notes, contacts or chats.", LfmType.label.copy(fontWeight = null), c.text2)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    open?.let { s -> OpenPageSheet(s, onOpen = { g.launcher.openPage(s.url); open = null }, onClose = { open = null }) }
}

/** Opening a source hands its address to the browser: only from this tap, with the full address shown. */
@Composable
private fun OpenPageSheet(s: SearchSource, onOpen: () -> Unit, onClose: () -> Unit) {
    val c = Lfm.c
    FlatSheet(onDismiss = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LIcon("globe"); Txt("Open in your browser?", LfmType.answer.copy(fontWeight = LfmType.rowTitle.fontWeight))
        }
        Txt(s.title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight), modifier = Modifier.padding(top = 12.dp))
        Txt(s.url, LfmType.small, c.text2, Modifier.padding(top = 4.dp))
        if (s.snippet.isNotEmpty()) Txt(s.snippet, LfmType.small, c.text, Modifier.padding(top = 10.dp))
        Txt("Your browser opens it with its own connection, cookies and history.", LfmType.meta, c.text2, Modifier.padding(top = 12.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn("Cancel", onClose, Modifier.weight(1f))
            Btn("Open", onOpen, Modifier.weight(1f), BtnKind.Primary)
        }
    }
}

/** Settings → Web search: the mode, page reading, the personal-word check and LFM Net's provider. */
@Composable
fun WebSearchScreen(g: AppGraph, nav: Nav) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var test by remember { mutableStateOf<String?>(null) }
    val net by produceState<Pair<NetClient.State, String>?>(null, prefs?.netEnabled, refresh) {
        val st = g.net.state()
        value = st to if (st == NetClient.State.On) runCatching { g.net.info() }.fold({ if (it.ready) it.provider else "" }, { "?" }) else ""
    }
    val p = prefs ?: return
    Column(Modifier.fillMaxSize()) {
        Header("Web search", onBack = { nav.back() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionLabel("Search mode")
            Segmented(SearchMode.entries.map { it.label }, p.searchMode.label, { l -> scope.launch { g.settings.setSearchMode(SearchMode.entries.first { it.label == l }) } })
            Txt(when (p.searchMode) {
                SearchMode.Off -> "The assistant never searches. Nothing goes to LFM Net."
                SearchMode.AskFirst -> "You see the search words first, and can edit them, before they go."
                SearchMode.Automatic -> "Searches go straight away, unless a personal word is in them or web pages were read: then it asks."
            }, LfmType.meta, c.text2, Modifier.padding(top = 6.dp))
            SectionLabel("Provider")
            Group {
                val (st, provider) = net ?: (null to "")
                ListRow(when (st) {
                    NetClient.State.On -> provider.ifEmpty { "No provider set up" }.replaceFirstChar { it.uppercase() }
                    NetClient.State.Off -> "LFM Net is off"
                    NetClient.State.NotInstalled -> "LFM Net is not installed"
                    NetClient.State.WrongSigner -> "LFM Net is signed with another key"
                    null -> "…"
                }, when (st) {
                    NetClient.State.On -> "Set in LFM Net: address or key, Wikipedia, proxy"
                    NetClient.State.Off -> "Turn it on in Privacy"
                    NetClient.State.NotInstalled -> "Install the LFM Net helper app to search and download models"
                    NetClient.State.WrongSigner -> "Install the LFM Net built with this app"
                    null -> ""
                }, "globe", if (st == NetClient.State.On || st == NetClient.State.Off) ({
                    if (st == NetClient.State.On) g.launcher.openApp(g.net.settingsIntent()) else nav.go(Route.Privacy)
                }) else null) { if (st == NetClient.State.On || st == NetClient.State.Off) LIcon("right", tint = c.text3) }
            }
            if (net?.first == NetClient.State.On) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("Try a search", {
                    test = "Searching…"
                    scope.launch {
                        test = try { "Works: ${g.net.search("weather").hits.size} results. It's in the network log." } catch (e: NetException) { "Failed: ${e.message}" }
                        refresh++
                    }
                }, small = true)
                test?.let { Txt(it, LfmType.meta, c.text2, Modifier.weight(1f)) }
            }
            SectionLabel("Reading")
            ToggleRow("Read the top result pages", "Opens 3 pages and reads them on this phone. Off: snippets only, no page is opened", !p.snippetsOnly) {
                scope.launch { g.settings.setSnippetsOnly(!it) }
            }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Warn about personal words", "Names, places, numbers, e-mails", p.warnPersonalWords) { scope.launch { g.settings.setWarnPersonal(it) } }
            Txt("What leaves the phone: the search words, then the addresses of the pages it opens. Never your chats, notes, contacts or location. " +
                "The provider and the opened sites see your IP address unless LFM Net uses a proxy.", LfmType.meta, c.text2, Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(title, LfmType.body.copy(fontWeight = LfmType.rowTitle.fontWeight))
            Txt(detail, LfmType.label.copy(fontWeight = null), c.text2)
        }
        Switch(on, onChange)
    }
}
