package com.lfmpixel.lfm.llm

import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LoadState { None, Loading, Ready, Failed }

data class EngineStatus(val text: LoadState = LoadState.None, val omni: LoadState = LoadState.None, val vision: LoadState = LoadState.None,
                        val error: String? = null)

/** Keeps the text model (and Omni, for voice) in memory while in use, and lets them go after a few idle minutes. */
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
    fun hasVoice() = store.omni() != null && store.omniProjector() != null
    fun visionPath() = store.vision()?.file?.path
    fun hasVision() = store.vision() != null && store.visionProjector() != null

    suspend fun textSession(): LlamaSession = lock.withLock {
        touch()
        text?.takeIf { it.path == store.text()?.file?.path }?.let { return it }
        text?.close()
        text = null
        val m = store.text() ?: throw LlmException("No text model yet. Import LFM3-1.7B in Settings → Models.")
        _status.value = _status.value.copy(text = LoadState.Loading, error = null)
        val p = settings.now()
        try {
            LlamaSession.open(nativeLibDir, m.file.path, p.contextTokens, p.threads).also {
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
            val s = LlamaSession.open(nativeLibDir, m.file.path, 8192, settings.now().threads)
            val caps = s.loadProjector(proj.file.path)
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
            val s = LlamaSession.open(nativeLibDir, m.file.path, VISION_CONTEXT, settings.now().threads)
            if (s.loadProjector(proj.file.path).let { it < 0 || it and LlamaNative.PROJ_VISION == 0 }) {
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
    fun keepAlive() = touch()

    /** Whether the loaded Omni projector takes pictures; false only once a loaded one was found without. */
    @Volatile var omniSees = true
        private set

    private fun touch() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(settings.now().unloadAfterMinutes * 60_000L)
            unloadAll()
        }
    }

    suspend fun unloadAll() = lock.withLock {
        text?.close(); text = null
        omni?.close(); omni = null
        vision?.close(); vision = null
        _status.value = EngineStatus()
    }

    companion object {
        /** Enough for a 20-page document's text plus the answer. */
        const val VISION_CONTEXT = 16384
    }

    /** Stop, while a picture is being read in: takes effect before the next picture or page. */
    fun cancelPictures() { if (text != null || omni != null || vision != null) runCatching { LlamaNative.cancelPictures() } }

    fun systemInfo(): String = runCatching { LlamaNative.ensureLoaded(nativeLibDir); LlamaNative.systemInfo() }.getOrDefault("")
}
