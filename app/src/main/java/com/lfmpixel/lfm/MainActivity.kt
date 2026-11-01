package com.lfmpixel.lfm

import android.Manifest
import com.lfmpixel.lfm.ui.theme.CameraColors
import com.lfmpixel.lfm.ui.theme.LfmShape
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
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
import com.lfmpixel.lfm.ui.screens.EditorScreen
import com.lfmpixel.lfm.ui.components.SnackHost
import com.lfmpixel.lfm.ui.isTab
import com.lfmpixel.lfm.ui.screens.FirstRunScreen
import com.lfmpixel.lfm.ui.screens.HistoryScreen
import com.lfmpixel.lfm.ui.screens.ImportRun
import com.lfmpixel.lfm.ui.screens.ImportScreen
import com.lfmpixel.lfm.ui.screens.LibraryScreen
import com.lfmpixel.lfm.ui.screens.LockScreen
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lfmpixel.lfm.ui.screens.ModelsScreen
import com.lfmpixel.lfm.ui.screens.PrivacyScreen
import com.lfmpixel.lfm.ui.screens.SearchScreen
import com.lfmpixel.lfm.ui.screens.SettingsScreen
import com.lfmpixel.lfm.ui.screens.WebSearchScreen
import com.lfmpixel.lfm.ui.screens.TodayScreen
import com.lfmpixel.lfm.ui.screens.TalkScreen
import com.lfmpixel.lfm.ui.screens.VoiceSettingsScreen
import com.lfmpixel.lfm.ui.screens.TodosScreen
import com.lfmpixel.lfm.ui.screens.ToolsScreen
import com.lfmpixel.lfm.ui.screens.VoiceInfoSheet
import com.lfmpixel.lfm.ui.screens.VoicePanel
import com.lfmpixel.lfm.ui.theme.Lfm
import com.lfmpixel.lfm.ui.theme.LfmTheme
import com.lfmpixel.lfm.ui.theme.LfmType
import com.lfmpixel.lfm.voice.VoiceState
import com.lfmpixel.lfm.voice.TalkPhase as VoiceTalkPhase
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
    private var onSaveAt: ((android.net.Uri) -> Unit)? = null
    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { onSaveAt?.invoke(it) } }
    private val pickers = Pickers(
        media = { cb -> onPickedOne = cb; mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
        document = { cb -> onPickedOne = cb; documentPicker.launch(arrayOf("application/pdf", "image/*")) },
        save = { name, cb -> onSaveAt = cb; saver.launch(name) },
    )

    private val gate = com.lfmpixel.lfm.tools.SerialPermissionGate(
        granted = { p -> p.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED } },
        launch = { p, answer -> permissionAnswer = answer; permissionLauncher.launch(p.toTypedArray()) },
    )

    /** What the assistant gesture, the Quick Settings tile or the widget asked for: "talk" or "capture". */
    private val opened = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private fun take(intent: android.content.Intent?) {
        val what = intent?.getStringExtra(EXTRA_OPEN) ?: if (intent?.action == android.content.Intent.ACTION_ASSIST) "assist" else null
        if (what != null) opened.value = what
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) take(intent)
        enableEdgeToEdge()
        graph.assistant.permissionGate = gate
        graph.web.permissionGate = gate
        graph.launcher.activity = this
        graph.lock.prompt = ::authenticate
        graph.assistant.onShown()
        setContent {
            LfmTheme {
                // The mic button's pulse follows the voice being recorded, wherever the mic is.
                val level = graph.voice.level.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(com.lfmpixel.lfm.ui.components.LocalMicLevel provides level) {
                    Root(graph, gate, pickers, opened, pickFiles = { cb -> onPicked = cb; picker.launch(arrayOf("*/*")) })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.launcher.onScreen = true
        graph.lock.onShown()
        graph.assistant.onShown()
    }

    override fun onPause() {
        // Still visible here, so Android lets a foreground service start: an answer or queue keeps going after we leave.
        if (graph.assistant.busy.value) com.lfmpixel.lfm.assistant.WorkService.start(this)
        super.onPause()
    }

    override fun onStop() {
        graph.launcher.onScreen = false
        graph.lock.onHidden()
        graph.focus.left(System.currentTimeMillis())
        // Plans may have changed while Inkling was open: the horizon widget catches up.
        com.lfmpixel.lfm.daylight.HorizonWidget.refresh(this)
        super.onStop()
    }

    /** The phone's own fingerprint, face or screen lock, for Inkling's lock. True once the owner got through. */
    private suspend fun authenticate(title: String): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val cancel = android.os.CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        runCatching {
            android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title)
                .setAllowedAuthenticators(com.lfmpixel.lfm.device.AppLock.AUTHENTICATORS).build()
                .authenticate(cancel, mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                })
        }.onFailure { if (cont.isActive) cont.resumeWith(Result.success(false)) }
    }

    override fun onResume() {
        super.onResume()
        // Back on screen (also after a permission dialog): the "keeps going" notice is not needed; it starts again
        // if Inkling is left while busy.
        com.lfmpixel.lfm.assistant.WorkService.back(this)
        // Downloads cut off by a restart carry on.
        graph.net.refresh()
        graph.downloads.resumeInterrupted()
        // Back from Android's install or uninstall prompt: see what is there now (the old Inkling Net, its hand-off).
        if (graph.handoff.status.value == com.lfmpixel.lfm.web.NetHandoff.Status.Working) graph.handoff.status.value = com.lfmpixel.lfm.web.NetHandoff.Status.Needed
        graph.scope.launch { graph.handoff.refresh() }
    }

    companion object {
        const val EXTRA_OPEN = "com.lfmpixel.lfm.OPEN"
        const val OPEN_TALK = "talk"
        const val OPEN_CAPTURE = "capture"
        const val OPEN_NOTE = "note"
        /** Followed by the id of the quote ShareActivity saved. */
        const val OPEN_SHARED = "shared:"
        /** The launcher shortcuts and the Focus notification (notebook-spec A.1). */
        const val OPEN_LINE = "line"
        const val OPEN_DUMP = "dump"
        const val OPEN_NOW = "now"
        const val OPEN_FOCUS = "focus"
        /** The assistant gesture: Talk or voice capture, as set in Settings → Voice. */
        const val OPEN_ASSIST = "assist"

        fun intent(context: android.content.Context, what: String) = android.content.Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN, what)
            // CLEAR_TOP with SINGLE_TOP: the running app gets onNewIntent even when its task is only brought forward.
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onDestroy() {
        // A request still open would leave its caller waiting: it counts as a no.
        permissionAnswer?.complete(false)
        if (graph.lock.prompt == ::authenticate) graph.lock.prompt = null
        if (graph.launcher.activity === this) graph.launcher.activity = null
        super.onDestroy()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Root(g: AppGraph, gate: PermissionGate, pickers: Pickers, opened: kotlinx.coroutines.flow.MutableStateFlow<String?>, pickFiles: ((List<android.net.Uri>) -> Unit) -> Unit) {
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
    val lockOpen by g.lock.open.collectAsState()
    val appLocked = p.lockMode == com.lfmpixel.lfm.settings.LockMode.App && !lockOpen
    // Locking leaves Talk: coming back through the lock should not open the mic by itself.
    LaunchedEffect(appLocked) { if (appLocked && nav.current == Route.Talk) nav.back() }
    // With a lock on, Recents shows a blank card instead of what was on screen.
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    LaunchedEffect(p.lockMode) { activity?.setRecentsScreenshotEnabled(p.lockMode == com.lfmpixel.lfm.settings.LockMode.Off) }
    // Screens under the one on top keep their saveable state (search, tab, scroll, chat draft) while it is shown.
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()

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
                    // Only while the camera is still open: a late transcript is not kept for the next visit.
                    VoiceTarget.Question -> if (nav.current == Route.Camera) cameraQuestion.text = text
                    VoiceTarget.Line -> if (nav.current == Route.Today) nav.heard = text else { g.assistant.send(text, seconds, "voice"); nav.go(Route.Chat) }
                    VoiceTarget.Assistant -> { g.assistant.send(text, seconds, "voice"); nav.go(Route.Chat) }
                    // Into the dump being written; gone from it, the words still land in a dump of their own.
                    VoiceTarget.Dump -> if (nav.current is Route.Dump) nav.heard = text else g.scope.launch { com.lfmpixel.lfm.ui.screens.saveDump(g, null, text) }
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

    // The assistant gesture, the tile and the widget land here.
    val open by opened.collectAsState()
    LaunchedEffect(open, p.firstRunDone, appLocked) {
        val what = open ?: return@LaunchedEffect
        // Locked: the gesture, tile or widget waits for the owner to unlock, then goes on.
        if (appLocked) return@LaunchedEffect
        opened.value = null
        if (!p.firstRunDone) return@LaunchedEffect
        // A quote ShareActivity already saved: show it on today's page, with Undo.
        if (what.startsWith(MainActivity.OPEN_SHARED)) {
            val id = what.removePrefix(MainActivity.OPEN_SHARED).toLongOrNull() ?: return@LaunchedEffect
            if (nav.current == Route.Talk) nav.back()
            nav.reset(Route.Today)
            nav.daysBack = 0
            nav.show("Saved to today's page") { g.db.entries().delete(id) }
            return@LaunchedEffect
        }
        if (what in setOf(MainActivity.OPEN_LINE, MainActivity.OPEN_DUMP, MainActivity.OPEN_NOW, MainActivity.OPEN_FOCUS)) {
            if (nav.current == Route.Talk) nav.back()
            nav.reset(Route.Today)
            nav.daysBack = 0
            when (what) {
                MainActivity.OPEN_LINE -> nav.inkFocus++
                MainActivity.OPEN_DUMP -> nav.go(Route.Dump())
                MainActivity.OPEN_FOCUS -> if (g.focus.run.value != null) nav.go(Route.Focus)
            }
            return@LaunchedEffect
        }
        if (what == MainActivity.OPEN_NOTE) { nav.reset(Route.Today); nav.go(Route.Editor(kind = EntryKind.Note)); return@LaunchedEffect }
        val talk = what == MainActivity.OPEN_TALK || (what == MainActivity.OPEN_ASSIST && p.assistOpens == com.lfmpixel.lfm.settings.AssistOpens.Talk)
        if (talk) {
            if (nav.current != Route.Talk) nav.go(Route.Talk)
            // Already open (paused or stopped): the gesture means "listen".
            else if (g.talk.state.value.phase.let { it == VoiceTalkPhase.Paused || it == VoiceTalkPhase.Failed }) {
                if (gate.ensure(listOf(Manifest.permission.RECORD_AUDIO))) g.talk.start()
            }
        }
        else {
            if (nav.current == Route.Talk) nav.back()
            if (nav.current != Route.Today && nav.current != Route.Chat) nav.reset(Route.Today)
            if (voiceState == VoiceState.Idle) voice(VoiceTarget.Assistant)
        }
    }

    // Predictive back (Android 16): while the back swipe is held, the screen shrinks a little toward the far edge
    // and its corners round, following the finger; letting go goes back with the usual shared-axis slide, and a
    // cancelled swipe settles back. Sheets handle their own back.
    val backProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    var backEdge by remember { androidx.compose.runtime.mutableIntStateOf(androidx.activity.BackEventCompat.EDGE_LEFT) }
    androidx.activity.compose.PredictiveBackHandler(enabled = nav.stack.size > 1 && !appLocked) { events ->
        try {
            events.collect { e ->
                backEdge = e.swipeEdge
                if (!reduced) backProgress.snapTo(e.progress)
            }
            nav.back()
            backProgress.snapTo(0f)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // The handler's own job is cancelled with the swipe: settle back in the screen's scope instead.
            scope.launch { backProgress.animateTo(0f, androidx.compose.animation.core.spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) }
            throw e
        }
    }

    // Black behind the camera, fading in and out with its transition, so leaving or opening it never flashes the
    // other screen's background over the whole window at once.
    val ground by androidx.compose.animation.animateColorAsState(if (nav.current == Route.Camera) CameraColors.ground else c.bg,
        LfmMotion.spec(LfmMotion.LONG), label = "ground")
    Box(Modifier.fillMaxSize().background(c.bg)) {
        // Behind the lock nothing is composed at all: no screen, sheet or menu can show or take a tap. The screens'
        // saveable state (drafts, tabs, scroll) is kept by the state holder, and the place in the app by the stack.
        if (!appLocked) Column(Modifier.fillMaxSize()
            .drawBehind { drawRect(ground) }.statusBarsPadding().navigationBarsPadding()) {
            // ux-spec-v1 section 7: the camera opens along the Z axis, pushed screens (editor, chat, settings) along X;
            // the four places of the bottom bar change at once.
            // Every screen sits above the keyboard: the inset is taken here once (screens that also ask for it get none
            // twice), and the bottom bar, which would be under the keyboard anyway, steps aside while it is up.
            Box(Modifier.weight(1f).imePadding()) {
            AnimatedContent(nav.current, Modifier.fillMaxSize().graphicsLayer {
                val p = backProgress.value
                if (p > 0f) {
                    // M3 back preview: down to 90 %, drifting 8 dp away from the edge the swipe came from.
                    val s = 1f - 0.1f * p
                    scaleX = s; scaleY = s
                    translationX = (if (backEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1f else -1f) * 8.dp.toPx() * p
                    shape = LfmShape.backPreview(p)
                    clip = true
                }
            }, contentKey = { when { it.isTab() -> "places"; it is Route.Editor -> "editor" + it.opened; it is Route.Dump -> "dump" + it.opened; else -> it } }, transitionSpec = {
                when {
                    targetState == Route.Camera || initialState == Route.Camera -> LfmMotion.sharedAxisZ(targetState == Route.Camera, reduced)
                    targetState == Route.Talk || initialState == Route.Talk -> LfmMotion.sharedAxisZ(targetState == Route.Talk, reduced)
                    targetState.isTab() && initialState.isTab() -> EnterTransition.None togetherWith ExitTransition.None
                    else -> LfmMotion.sharedAxisX(nav.forward, reduced)
                }
            }, label = "screen") { r ->
                saved.SaveableStateProvider(when (r) { is Route.Library -> "library"; is Route.Editor -> "editor" + r.opened; is Route.Dump -> "dump" + r.opened; else -> r.toString() }) { when (r) {
                    Route.Today -> TodayScreen(g, nav, { voice(VoiceTarget.Line) }, listening)
                    Route.Chat -> ChatScreen(g, nav, { voice(VoiceTarget.Assistant) }, listening)
                    Route.Talk -> TalkScreen(g, nav, gate)
                    Route.Voice -> VoiceSettingsScreen(g, nav)
                    Route.Server -> com.lfmpixel.lfm.ui.screens.ServerScreen(g, nav)
                    Route.Camera -> CameraScreen(g, nav, gate, pickers, cameraQuestion, { voice(VoiceTarget.Question) }, listening)
                    is Route.Library -> LibraryScreen(g, nav, r.filter, { voice(VoiceTarget.Library(it)) }, listening)
                    is Route.Editor -> EditorScreen(g, nav, r)
                    Route.Focus -> com.lfmpixel.lfm.ui.screens.FocusScreen(g, nav)
                    is Route.Dump -> com.lfmpixel.lfm.ui.screens.DumpScreen(g, nav, r, { voice(VoiceTarget.Dump) }, listening)
                    Route.Todos -> TodosScreen(g, nav, { voice(VoiceTarget.Library(EntryKind.Todo)) }, listening)
                    Route.Settings -> SettingsScreen(g, nav)
                    Route.Models -> ModelsScreen(g, nav, startImport)
                    is Route.Import -> imports[r.uris]?.let { ImportScreen(g, nav, it) } ?: LaunchedEffect(r) { nav.back() }
                    Route.Tools -> ToolsScreen(g, nav)
                    Route.History -> HistoryScreen(g, nav)
                    Route.Privacy -> PrivacyScreen(g, nav, pickers)
                    Route.Knows -> com.lfmpixel.lfm.ui.screens.KnowsScreen(g, nav)
                    Route.Crashes -> com.lfmpixel.lfm.ui.screens.CrashesScreen(g, nav)
                    Route.FirstRun -> FirstRunScreen(g, nav) { nav.go(Route.Models); startImport() }
                    is Route.Search -> SearchScreen(g, nav, r.id)
                    Route.WebSearch -> WebSearchScreen(g, nav)
                } }
            }
            // On today's page it sits above the Ink line, so it never covers the line being written.
            SnackHost(nav.snack, { nav.snack = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = if (nav.current == Route.Today) 64.dp else 0.dp))
            // The launch after a crash: a quiet note where the snack goes, until viewed or closed.
            val crashed by com.lfmpixel.lfm.crash.Crashes.unseen.collectAsState()
            if (crashed != null && nav.snack == null && p.firstRunDone && nav.current.isTab()) {
                com.lfmpixel.lfm.ui.screens.CrashNote(onView = { nav.go(Route.Crashes) },
                    onClose = { g.scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.lfmpixel.lfm.crash.Crashes.markSeen() } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (nav.current == Route.Today) 64.dp else 0.dp))
            }
            }
            VoicePanel(g) { g.voice.stop() }
            if (nav.current == Route.Today || nav.current is Route.Library || nav.current == Route.Todos) {
                androidx.compose.animation.AnimatedVisibility(!WindowInsets.isImeVisible, enter = LfmMotion.appear() + androidx.compose.animation.expandVertically(LfmMotion.spec(LfmMotion.MEDIUM, LfmMotion.emphasizedDecelerate)),
                    exit = LfmMotion.leave()) { BottomBar(nav) }
            }
        }
        if (appLocked) Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { LockScreen(g) }
        // Daylight: the sky of the hour runs under the status bar on every screen (not the camera's black), and the
        // status icons take its ink.
        if (nav.current != Route.Camera) {
            val now by com.lfmpixel.lfm.daylight.rememberNow()
            val sky = com.lfmpixel.lfm.daylight.skyAt(now)
            Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Color(sky.top)))
            // Talk and the app lock are all sky: the horizon colour runs under the navigation bar too.
            if (nav.current == Route.Talk || appLocked) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars).background(Color(sky.horizon)))
            val view = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(sky.lightInk) {
                activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !sky.lightInk }
            }
        } else {
            val view = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(Unit) {
                activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = false }
            }
        }
    }
    if (appLocked) return
    confirm?.let { ConfirmSheet(it) }
    nav.voiceInfo?.let { m -> VoiceInfoSheet(m, onModels = { nav.voiceInfo = null; nav.go(Route.Models) }, onClose = { nav.voiceInfo = null }) }
}

/**
 * The bottom bar (docs/ux-spec.md, "Bottom bar"): the places as words, no boxes, and in the middle the pastel talk
 * button, there on every place. Ask opens the chat. The place you are on is navy and bold with the sun's dot under it.
 */
@Composable
internal fun BottomBar(nav: Nav) {
    val c = Lfm.c
    Row(Modifier.fillMaxWidth().background(c.bg).height(76.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun Place(label: String, route: Route, on: Boolean, tab: Boolean = true) {
            Column(Modifier.weight(1f).height(76.dp).clickable(role = if (tab) Role.Tab else Role.Button) {
                if (route is Route.Library && nav.current is Route.Library) return@clickable
                nav.go(route)
            }.let { if (tab) it.semantics { selected = on } else it }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                // Shrinks to fit rather than cut off ("Noteb…") at large font sizes.
                val style = LfmType.label.copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null, color = if (on) c.text else c.text3)
                androidx.compose.foundation.text.BasicText(label, style = style, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = LfmType.barMin, maxFontSize = style.fontSize))
                Box(Modifier.padding(top = 5.dp).size(5.dp).background(if (on) c.highlight else Color.Transparent, LfmShape.round))
            }
        }
        Place("Today", Route.Today, nav.current == Route.Today)
        Place("Notebook", Route.Library(), nav.current is Route.Library)
        Box(Modifier.weight(1.1f), contentAlignment = Alignment.Center) {
            Box(Modifier.size(56.dp).clip(LfmShape.round).background(c.mic).clickable(role = Role.Button) { nav.go(Route.Talk) }
                .semantics { contentDescription = "Talk to Inkling" }, contentAlignment = Alignment.Center) {
                LIcon("mic", tint = c.onMic)
            }
        }
        Place("Plans", Route.Todos, nav.current == Route.Todos)
        Place("Ask", Route.Chat, false, tab = false)
    }
}
