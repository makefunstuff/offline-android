package com.lfmpixel.lfm.llm

import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LoadState { None, Loading, Ready, Failed }

data class EngineStatus(val text: LoadState = LoadState.None, val omni: LoadState = LoadState.None, val vision: LoadState = LoadState.None,
                        val error: String? = null, val textFile: String? = null, val unloadAt: Long? = null) {
    fun of(slot: Slot) = when (slot) { Slot.Text -> text; Slot.Omni -> omni; Slot.Vision -> vision }
    val anyLoaded get() = Slot.entries.any { of(it) == LoadState.Ready }
}

/** The three models that can be in memory at once. */
enum class Slot(val label: String, val purpose: String) {
    Text("LFM3-1.7B", "Chat and tools"),
    Omni("LFM3-Omni-2B", "Voice, photos, video"),
    Vision("LFM3-VL-2B", "Documents"),
}

/**
 * Keeps the text model (and Omni and VL when used) in memory while in use, and lets them go after the idle time the
 * owner picked (never, if they chose so). Each can also be loaded or unloaded by hand.
 */
class ModelManager(
    private val nativeLibDir: String,
    private val store: ModelStore,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var text: LlamaSession? = null
    private var omni: LlamaSession? = null
    private var vision: LlamaSession? = null
    private var idleJob: Job? = null
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    fun hasText() = store.text() != null
    /** The model chat uses: the owner's pick, or the text model added last. */
    suspend fun chatModel(): InstalledModel? = store.text(settings.now().chatModel)
    fun installed(slot: Slot) = when (slot) { Slot.Text -> hasText(); Slot.Omni -> hasVoice(); Slot.Vision -> hasVision() }
    fun hasVoice() = store.omni() != null && store.omniProjector() != null
    fun visionPath() = store.vision()?.file?.path
    fun hasVision() = store.vision() != null && store.visionProjector() != null

    suspend fun textSession(): LlamaSession = lock.withLock {
        touch()
        val m = chatModel()
        text?.takeIf { it.path == m?.file?.path }?.let { return it }
        text?.close()
        text = null
        _status.value = _status.value.copy(text = LoadState.None, textFile = null)
        if (m == null) throw LlmException("No text model yet. Get LFM3-1.7B in Settings → Models.")
        _status.value = _status.value.copy(text = LoadState.Loading, error = null, textFile = m.file.name)
        val p = settings.now()
        try {
            // A load is never cut off half way (leaving the model in memory with nothing pointing at it).
            withContext(NonCancellable) { LlamaSession.open(nativeLibDir, m.file.path, p.contextTokens, p.threads) }.also {
                text = it
                _status.value = _status.value.copy(text = LoadState.Ready)
            }
        } catch (e: Exception) {
            _status.value = _status.value.copy(text = LoadState.Failed, error = e.message)
            throw e
        }
    }

    suspend fun omniSession(): LlamaSession = lock.withLock {
        touch()
        omni?.takeIf { it.path == store.omni()?.file?.path }?.let { return it }
        omni?.close()
        omni = null
        val m = store.omni() ?: throw LlmException("Voice needs LFM3-Omni-2B. Import it in Settings → Models.")
        val proj = store.omniProjector() ?: throw LlmException("Voice needs Omni's projector file (mmproj-LFM3-Omni-2B).")
        _status.value = _status.value.copy(omni = LoadState.Loading, error = null)
        try {
            // 8K context: room for a short clip's frames as well as a voice note.
            val threads = settings.now().threads
            val (s, caps) = withContext(NonCancellable) {
                val s = LlamaSession.open(nativeLibDir, m.file.path, 8192, threads)
                s to s.loadProjector(proj.file.path)
            }
            if (caps < 0 || caps and LlamaNative.PROJ_AUDIO == 0) {
                s.close()
                throw LlmException(if (caps < 0) "Omni's projector file could not be loaded" else "Omni's projector loaded, but it has no audio input")
            }
            omniSees = caps and LlamaNative.PROJ_VISION != 0
            omni = s
            _status.value = _status.value.copy(omni = LoadState.Ready)
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(omni = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** LFM3-VL-2B for documents. It loads only when one is asked about, then unloads with the others when idle. */
    suspend fun visionSession(): LlamaSession = lock.withLock {
        touch()
        vision?.takeIf { it.path == store.vision()?.file?.path }?.let { return it }
        vision?.close()
        vision = null
        val m = store.vision() ?: throw LlmException("Documents need LFM3-VL-2B. Get it in Settings → Models.")
        val proj = store.visionProjector() ?: throw LlmException("Documents need VL's projector file (mmproj-LFM3-VL-2B).")
        _status.value = _status.value.copy(vision = LoadState.Loading, error = null)
        try {
            val threads = settings.now().threads
            val (s, caps) = withContext(NonCancellable) {
                val s = LlamaSession.open(nativeLibDir, m.file.path, VISION_CONTEXT, threads)
                s to s.loadProjector(proj.file.path)
            }
            if (caps.let { it < 0 || it and LlamaNative.PROJ_VISION == 0 }) {
                s.close()
                throw LlmException("VL's projector file could not be loaded")
            }
            vision = s
            _status.value = _status.value.copy(vision = LoadState.Ready)
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(vision = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** Long jobs (a scanned PDF read page by page) call this between steps so the idle unload waits. */
    fun keepAlive() { if (_status.value.anyLoaded) touch() }

    /** Whether the loaded Omni projector takes pictures; false only once a loaded one was found without. */
    @Volatile var omniSees = true
        private set

    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            val minutes = settings.now().unloadAfterMinutes
            if (minutes <= 0) { _status.value = _status.value.copy(unloadAt = null); return@launch }
            val ms = minutes * 60_000L
            _status.value = _status.value.copy(unloadAt = System.currentTimeMillis() + ms)
            delay(ms)
            unloadAll()
        }
    }

    /** After the idle time changed: count it again from now, if anything is loaded. */
    fun idleTimeChanged() { if (_status.value.anyLoaded) touch() }

    suspend fun unloadAll() = lock.withLock {
        idleJob?.cancel()
        text?.close(); text = null
        omni?.close(); omni = null
        vision?.close(); vision = null
        _status.value = EngineStatus()
    }

    /** Loads one model by hand, so the next question doesn't wait for it. */
    suspend fun load(slot: Slot) {
        when (slot) { Slot.Text -> textSession(); Slot.Omni -> omniSession(); Slot.Vision -> visionSession() }
    }

    /** Lets one model go; the others stay. */
    suspend fun unload(slot: Slot) = lock.withLock {
        when (slot) {
            Slot.Text -> { text?.close(); text = null; _status.value = _status.value.copy(text = LoadState.None, textFile = null) }
            Slot.Omni -> { omni?.close(); omni = null; _status.value = _status.value.copy(omni = LoadState.None) }
            Slot.Vision -> { vision?.close(); vision = null; _status.value = _status.value.copy(vision = LoadState.None) }
        }
        if (!_status.value.anyLoaded) { idleJob?.cancel(); _status.value = _status.value.copy(unloadAt = null) }
    }

    /**
     * About how much memory a model takes once loaded: its weights (and projector), plus the context and working
     * buffers. An estimate from file sizes, shown with "about".
     */
    suspend fun memoryBytes(slot: Slot): Long = when (slot) {
        Slot.Text -> (chatModel()?.known?.sizeBytes ?: 0) + settings.now().contextTokens * 8_192L + 120_000_000L
        Slot.Omni -> (store.omni()?.known?.sizeBytes ?: 0) + (store.omniProjector()?.known?.sizeBytes ?: 0) + 180_000_000L
        Slot.Vision -> (store.vision()?.known?.sizeBytes ?: 0) + (store.visionProjector()?.known?.sizeBytes ?: 0) + 260_000_000L
    }

    companion object {
        /** Enough for a 20-page document's text plus the answer. */
        const val VISION_CONTEXT = 16384
    }

    /** Stop, while a picture is being read in: takes effect before the next picture or page. */
    fun cancelPictures() { if (text != null || omni != null || vision != null) runCatching { LlamaNative.cancelPictures() } }

    /** Stop, while Omni speaks (read-aloud or Talk): takes effect before the next audio frame. */
    fun cancelSpeech() { if (omni != null) runCatching { LlamaNative.cancelSpeech() } }

    fun systemInfo(): String = runCatching { LlamaNative.ensureLoaded(nativeLibDir); LlamaNative.systemInfo() }.getOrDefault("")
}
