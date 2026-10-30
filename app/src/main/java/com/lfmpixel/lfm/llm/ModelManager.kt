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

data class EngineStatus(val text: LoadState = LoadState.None, val omni: LoadState = LoadState.None, val error: String? = null)

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
    private var idleJob: Job? = null
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    fun hasText() = store.text() != null
    fun hasVoice() = store.omni() != null && store.omniProjector() != null

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
            val s = LlamaSession.open(nativeLibDir, m.file.path, 4096, settings.now().threads)
            if (!s.loadProjector(proj.file.path)) {
                s.close()
                throw LlmException("Omni's projector loaded, but it has no audio input")
            }
            omni = s
            _status.value = _status.value.copy(omni = LoadState.Ready)
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(omni = LoadState.Failed, error = e.message)
            throw e
        }
    }

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
        _status.value = EngineStatus()
    }

    fun systemInfo(): String = runCatching { LlamaNative.ensureLoaded(nativeLibDir); LlamaNative.systemInfo() }.getOrDefault("")
}
