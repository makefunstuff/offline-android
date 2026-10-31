package com.lfmpixel.lfm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.llm.ToolCall
import com.lfmpixel.lfm.tools.PermissionGate
import com.lfmpixel.lfm.ui.Nav
import com.lfmpixel.lfm.ui.Route
import com.lfmpixel.lfm.ui.VoiceTarget
import com.lfmpixel.lfm.ui.components.Divider
import com.lfmpixel.lfm.ui.components.LIcon
import com.lfmpixel.lfm.ui.components.Txt
import com.lfmpixel.lfm.ui.screens.CameraQuestion
import com.lfmpixel.lfm.ui.screens.CameraScreen
import com.lfmpixel.lfm.ui.screens.ChatScreen
import com.lfmpixel.lfm.ui.screens.Pickers
import com.lfmpixel.lfm.ui.theme.LfmMotion
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import com.lfmpixel.lfm.ui.screens.ConfirmSheet
import com.lfmpixel.lfm.ui.screens.EntryEditor
import com.lfmpixel.lfm.ui.screens.FirstRunScreen
import com.lfmpixel.lfm.ui.screens.HistoryScreen
import com.lfmpixel.lfm.ui.screens.ImportRun
import com.lfmpixel.lfm.ui.screens.ImportScreen
import com.lfmpixel.lfm.ui.screens.LibraryScreen
import com.lfmpixel.lfm.ui.screens.ModelsScreen
import com.lfmpixel.lfm.ui.screens.PrivacyScreen
import com.lfmpixel.lfm.ui.screens.SearchScreen
import com.lfmpixel.lfm.ui.screens.SettingsScreen
import com.lfmpixel.lfm.ui.screens.WebSearchScreen
import com.lfmpixel.lfm.ui.screens.TodayScreen
import com.lfmpixel.lfm.ui.screens.TodosScreen
import com.lfmpixel.lfm.ui.screens.ToolsScreen
import com.lfmpixel.lfm.ui.screens.VoiceInfoSheet
import com.lfmpixel.lfm.ui.screens.VoicePanel
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmTheme
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.voice.VoiceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph get() = (application as LfmApp).graph
    private var permissionAnswer: CompletableDeferred<Boolean>? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionAnswer?.complete(result.values.all { it })
    }
    private var onPicked: ((List<android.net.Uri>) -> Unit)? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onPicked?.invoke(uris) }
    private var onPickedOne: ((android.net.Uri) -> Unit)? = null
    private val mediaPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onPickedOne?.invoke(it) } }
    private val documentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onPickedOne?.invoke(it) } }
    private val pickers = Pickers(
        media = { cb -> onPickedOne = cb; mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        document = { cb -> onPickedOne = cb; documentPicker.launch(arrayOf("application/pdf", "image/*")) },
    )

    private val gate = object : PermissionGate {
        override suspend fun ensure(permissions: List<String>): Boolean {
            if (permissions.all { ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED }) return true
            val answer = CompletableDeferred<Boolean>()
            permissionAnswer = answer
            permissionLauncher.launch(permissions.toTypedArray())
            return answer.await()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graph.assistant.permissionGate = gate
        graph.web.permissionGate = gate
        graph.launcher.activity = this
        setContent {
            LfmTheme {
                Root(graph, gate, pickers, pickFiles = { cb -> onPicked = cb; picker.launch(arrayOf("*/*")) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // LFM Net may have been installed, removed or set up meanwhile; downloads cut off by a restart carry on.
        graph.net.refresh()
        graph.downloads.resumeInterrupted()
        // Back from Android's install prompt without an answer: let Install be tapped again.
        if (graph.netInstaller.status.value == com.lfmpixel.lfm.web.NetInstaller.Status.Working) graph.netInstaller.status.value = com.lfmpixel.lfm.web.NetInstaller.Status.Idle
    }

    override fun onDestroy() {
        if (graph.launcher.activity === this) graph.launcher.activity = null
        super.onDestroy()
    }
}

@Composable
private fun Root(g: AppGraph, gate: PermissionGate, pickers: Pickers, pickFiles: ((List<android.net.Uri>) -> Unit) -> Unit) {
    val c = Lfm.c
    val prefs by g.settings.prefs.collectAsState(null)
    val p = prefs ?: return Box(Modifier.fillMaxSize().background(c.bg))
    val nav = remember { Nav(if (p.firstRunDone) Route.Today else Route.FirstRun) }
    val scope = rememberCoroutineScope()
    val confirm by g.assistant.pendingConfirm.collectAsState()
    val voiceState by g.voice.state.collectAsState()
    val listening = voiceState == VoiceState.Listening
    val imports = remember { mutableMapOf<List<android.net.Uri>, ImportRun>() }
    val cameraQuestion = remember { CameraQuestion() }
    val reduced = LfmMotion.reduced()

    val startImport: () -> Unit = {
        pickFiles { uris ->
            val run = ImportRun(uris).also { it.start(g) }
            imports[uris] = run
            nav.go(Route.Import(uris))
        }
    }

    fun voice(target: VoiceTarget) {
        if (voiceState == VoiceState.Listening) { g.voice.stop(); return }
        if (voiceState != VoiceState.Idle) return
        if (!g.voice.available) {
            nav.voiceInfo = "Voice needs LFM3-Omni-2B and its projector file. Import both in Settings → Models. Until then, type instead."
            return
        }
        scope.launch {
            if (!gate.ensure(listOf(Manifest.permission.RECORD_AUDIO))) return@launch
            try {
                val (text, seconds) = g.voice.listen()
                if (text.isBlank()) return@launch
                when (target) {
                    VoiceTarget.Question -> cameraQuestion.text = text
                    VoiceTarget.Assistant -> { g.assistant.send(text, seconds, "voice"); nav.go(Route.Chat) }
                    is VoiceTarget.Library -> {
                        val tool = when (target.kind) { EntryKind.Note -> "add_note"; EntryKind.Idea -> "add_idea"; EntryKind.Journal -> "add_journal"; EntryKind.Todo -> "add_todo" }
                        g.assistant.runDirect(ToolCall(tool, mapOf("text" to text)), "voice")
                    }
                }
            } catch (e: Exception) {
                nav.voiceInfo = e.message ?: "Voice failed"
            }
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize().background(if (nav.current == Route.Camera) Color.Black else c.bg).statusBarsPadding().navigationBarsPadding()) {
            // The camera opens and closes along the Z axis; other screens change at once for now (the motion pass is LP-6).
            AnimatedContent(nav.current, Modifier.weight(1f), contentKey = { it == Route.Camera }, transitionSpec = {
                if (targetState == Route.Camera || initialState == Route.Camera) LfmMotion.sharedAxisZ(targetState == Route.Camera, reduced)
                else EnterTransition.None togetherWith ExitTransition.None
            }, label = "screen") { r ->
                when (r) {
                    Route.Today -> TodayScreen(g, nav, { voice(VoiceTarget.Assistant) }, listening)
                    Route.Chat -> ChatScreen(g, nav, { voice(VoiceTarget.Assistant) }, listening)
                    Route.Camera -> CameraScreen(g, nav, gate, pickers, cameraQuestion, { voice(VoiceTarget.Question) }, listening)
                    is Route.Library -> LibraryScreen(g, nav, r.tab, { voice(VoiceTarget.Library(it)) }, listening)
                    Route.Todos -> TodosScreen(g, nav, { voice(VoiceTarget.Library(EntryKind.Todo)) }, listening)
                    Route.Settings -> SettingsScreen(g, nav)
                    Route.Models -> ModelsScreen(g, nav, startImport)
                    is Route.Import -> imports[r.uris]?.let { ImportScreen(g, nav, it) } ?: LaunchedEffect(r) { nav.back() }
                    Route.Tools -> ToolsScreen(g, nav)
                    Route.History -> HistoryScreen(g, nav)
                    Route.Privacy -> PrivacyScreen(g, nav)
                    Route.FirstRun -> FirstRunScreen(g, nav) { nav.go(Route.Models); startImport() }
                    is Route.Search -> SearchScreen(g, nav, r.id)
                    Route.WebSearch -> WebSearchScreen(g, nav)
                }
            }
            VoicePanel(g) { g.voice.stop() }
            if (nav.current == Route.Today || nav.current is Route.Library || nav.current == Route.Todos) BottomBar(nav)
        }
    }
    confirm?.let { ConfirmSheet(it) }
    nav.entrySheet?.let { s -> EntryEditor(g, s) { nav.entrySheet = null } }
    nav.voiceInfo?.let { m -> VoiceInfoSheet(m, onModels = { nav.voiceInfo = null; nav.go(Route.Models) }, onClose = { nav.voiceInfo = null }) }
}

@Composable
private fun BottomBar(nav: Nav) {
    val c = Lfm.c
    Column {
        Divider()
        Row(Modifier.fillMaxWidth().height(70.dp)) {
            listOf(Triple("sun", "Today", Route.Today), Triple("chat", "Chat", Route.Chat), Triple("book", "Library", Route.Library()),
                Triple("todo", "To-dos", Route.Todos)).forEach { (icon, label, route) ->
                val on = when (route) { is Route.Library -> nav.current is Route.Library; else -> nav.current == route }
                Column(Modifier.weight(1f).height(70.dp).clickable(role = Role.Tab) {
                    if (route is Route.Library && nav.current is Route.Library) return@clickable
                    nav.go(route)
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val shape = RoundedCornerShape(6.dp)
                    Box(Modifier.size(56.dp, 30.dp).background(if (on) c.fill else Color.Transparent, shape)
                        .border(1.dp, if (on) c.line else Color.Transparent, shape), contentAlignment = Alignment.Center) {
                        LIcon(icon, tint = if (on) c.text else c.text2)
                    }
                    Txt(label, LfmType.meta.copy(fontWeight = if (on) LfmType.rowTitle.fontWeight else null), if (on) c.text else c.text2,
                        Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
