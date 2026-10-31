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
                        val error: String? = null, val textFile: String? = null, val unloadAt: Long? = null,
                        /** Where each loaded model runs. */
                        val backends: Map<Slot, Backend> = emptyMap()) {
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
    /** Where LiteRT-LM keeps the GPU programs it compiled, so the next load is quicker. */
    private val liteRtCache: java.io.File,
) {
    private val lock = Mutex()
    private var text: TextSession? = null
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

    @Volatile private var gpu: String? = null

    /** The GPU llama.cpp found through Vulkan ("" for none), asked once: drivers don't come and go while the app runs. */
    suspend fun gpuName(): String = gpu ?: runCatching { LlamaSession.gpuName(nativeLibDir) }.getOrDefault("").also { gpu = it }

    @Volatile private var openCl: Boolean? = null

    /** Whether LiteRT-LM can reach the GPU: an emulator has no OpenCL, and there a failed GPU run hangs on close. */
    private suspend fun hasOpenCl(): Boolean = openCl ?: runCatching { LlamaSession.hasOpenCl(nativeLibDir) }.getOrDefault(false).also { openCl = it }

    /**
     * The backends [slot] can run on here: the CPU always, Vulkan with a GPU, LiteRT-LM for chat once its file is in
     * (on the GPU only with OpenCL).
     */
    suspend fun available(slot: Slot): Set<Backend> = buildSet {
        add(Backend.Cpu)
        if (gpuName().isNotEmpty()) add(Backend.Vulkan)
        if (slot == Slot.Text && store.liteRt() != null) {
            if (hasOpenCl()) add(Backend.LiteRtGpu)
            add(Backend.LiteRtCpu)
        }
    }

    /** What the benchmark measured for [slot]'s model: llama.cpp's backends on its GGUF file, LiteRT-LM's on the .litertlm. */
    suspend fun speeds(slot: Slot): Map<Backend, BackendSpeed> {
        val all = settings.now().backendSpeeds
        val gguf = file(slot)?.let { all[it.file.name] }.orEmpty().filterKeys { !it.liteRt }
        val lite = if (slot == Slot.Text) store.liteRt()?.let { all[it.file.name] }.orEmpty().filterKeys { it.liteRt } else emptyMap()
        return gguf + lite
    }

    /** Where [slot]'s model runs now: the owner's pick, or for Auto the backend the benchmark found quickest for it. */
    suspend fun backendFor(slot: Slot): Backend = Backends.pick(settings.now().backend, available(slot), speeds(slot))

    /** The file a backend measures under: the .litertlm for LiteRT-LM, else the slot's GGUF. */
    private suspend fun fileFor(slot: Slot, backend: Backend): String? =
        (if (backend.liteRt) store.liteRt() else file(slot))?.file?.name

    /** Notes a backend that would not load [slot]'s model, so Auto stops choosing it. */
    private suspend fun markFailed(slot: Slot, backend: Backend) {
        val file = fileFor(slot, backend) ?: return
        settings.setBackendSpeeds(file, settings.now().backendSpeeds[file].orEmpty() + (backend to BackendSpeed(0.0, 0.0, ok = false)))
    }

    /**
     * Opens a GGUF model on [backend] with llama.cpp; if Vulkan will not take it, on the CPU instead, and Vulkan is
     * marked not ok for it so Auto stops trying.
     */
    private suspend fun open(slot: Slot, path: String, nCtx: Int, backend: Backend): LlamaSession {
        val threads = settings.now().threads
        // A load is never cut off half way (leaving the model in memory with nothing pointing at it).
        return withContext(NonCancellable) {
            if (backend == Backend.Vulkan) {
                try { return@withContext LlamaSession.open(nativeLibDir, path, nCtx, threads, Backend.Vulkan) } catch (_: LlmException) {
                    markFailed(slot, Backend.Vulkan)
                }
            }
            LlamaSession.open(nativeLibDir, path, nCtx, threads, Backend.Cpu)
        }
    }

    /** The chat model on its backend: LiteRT-LM's file when a LiteRT backend is chosen, else the GGUF in llama.cpp. */
    suspend fun textSession(): TextSession = lock.withLock {
        touch()
        val m = chatModel()
        val backend = if (m != null) backendFor(Slot.Text) else null
        val lite = if (backend?.liteRt == true) store.liteRt() else null
        val path = (lite ?: m)?.file?.path
        text?.takeIf { it.path == path && it.backend == backend }?.let { return it }
        text?.close()
        text = null
        _status.value = _status.value.copy(text = LoadState.None, textFile = null, backends = _status.value.backends - Slot.Text)
        if (m == null || backend == null) throw LlmException("No text model yet. Get LFM3-1.7B in Settings → Models.")
        _status.value = _status.value.copy(text = LoadState.Loading, error = null, textFile = (lite ?: m).file.name)
        val p = settings.now()
        try {
            val s: TextSession = withContext(NonCancellable) {
                if (lite != null) {
                    try { return@withContext LiteRtSession.open(lite.file.path, backend, liteRtCache.path) } catch (_: LlmException) {
                        markFailed(Slot.Text, backend)
                        _status.value = _status.value.copy(textFile = m.file.name)
                    }
                }
                open(Slot.Text, m.file.path, p.contextTokens, if (backend.liteRt) Backend.Cpu else backend)
            }
            text = s
            _status.value = _status.value.copy(text = LoadState.Ready, backends = _status.value.backends + (Slot.Text to s.backend))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(text = LoadState.Failed, error = e.message)
            throw e
        }
    }

    suspend fun omniSession(): LlamaSession = lock.withLock {
        touch()
        val omniBackend = backendFor(Slot.Omni)
        omni?.takeIf { it.path == store.omni()?.file?.path && it.backend == omniBackend }?.let { return it }
        omni?.close()
        omni = null
        val m = store.omni() ?: throw LlmException("Voice needs LFM3-Omni-2B. Import it in Settings → Models.")
        val proj = store.omniProjector() ?: throw LlmException("Voice needs Omni's projector file (mmproj-LFM3-Omni-2B).")
        _status.value = _status.value.copy(omni = LoadState.Loading, error = null)
        try {
            // 8K context: room for a short clip's frames as well as a voice note.
            val (s, caps) = withContext(NonCancellable) {
                val s = open(Slot.Omni, m.file.path, 8192, omniBackend)
                s to s.loadProjector(proj.file.path)
            }
            if (caps < 0 || caps and LlamaNative.PROJ_AUDIO == 0) {
                s.close()
                throw LlmException(if (caps < 0) "Omni's projector file could not be loaded" else "Omni's projector loaded, but it has no audio input")
            }
            omniSees = caps and LlamaNative.PROJ_VISION != 0
            omni = s
            _status.value = _status.value.copy(omni = LoadState.Ready, backends = _status.value.backends + (Slot.Omni to s.backend))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(omni = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** LFM3-VL-2B for documents. It loads only when one is asked about, then unloads with the others when idle. */
    suspend fun visionSession(): LlamaSession = lock.withLock {
        touch()
        val visionBackend = backendFor(Slot.Vision)
        vision?.takeIf { it.path == store.vision()?.file?.path && it.backend == visionBackend }?.let { return it }
        vision?.close()
        vision = null
        val m = store.vision() ?: throw LlmException("Documents need LFM3-VL-2B. Get it in Settings → Models.")
        val proj = store.visionProjector() ?: throw LlmException("Documents need VL's projector file (mmproj-LFM3-VL-2B).")
        _status.value = _status.value.copy(vision = LoadState.Loading, error = null)
        try {
            val (s, caps) = withContext(NonCancellable) {
                val s = open(Slot.Vision, m.file.path, VISION_CONTEXT, visionBackend)
                s to s.loadProjector(proj.file.path)
            }
            if (caps.let { it < 0 || it and LlamaNative.PROJ_VISION == 0 }) {
                s.close()
                throw LlmException("VL's projector file could not be loaded")
            }
            vision = s
            _status.value = _status.value.copy(vision = LoadState.Ready, backends = _status.value.backends + (Slot.Vision to s.backend))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(vision = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** The model file each slot uses now, if it is on the phone. */
    suspend fun file(slot: Slot): InstalledModel? = when (slot) { Slot.Text -> chatModel(); Slot.Omni -> store.omni(); Slot.Vision -> store.vision() }

    /**
     * For the benchmark: [slot]'s model alone on [backend], without its projector and outside the slots (that slot
     * is unloaded first, so two copies are never in memory). The caller closes it. Throws if [backend] won't load it.
     */
    suspend fun benchSession(slot: Slot, backend: Backend): TextSession = lock.withLock {
        val m = (if (backend.liteRt) store.liteRt() else file(slot)) ?: throw LlmException("${slot.label} is not on this phone")
        when (slot) {
            Slot.Text -> { text?.close(); text = null; _status.value = _status.value.copy(text = LoadState.None, textFile = null) }
            Slot.Omni -> { omni?.close(); omni = null; _status.value = _status.value.copy(omni = LoadState.None) }
            Slot.Vision -> { vision?.close(); vision = null; _status.value = _status.value.copy(vision = LoadState.None) }
        }
        _status.value = _status.value.copy(backends = _status.value.backends - slot)
        withContext(NonCancellable) {
            if (backend.liteRt) LiteRtSession.open(m.file.path, backend, liteRtCache.path)
            else LlamaSession.open(nativeLibDir, m.file.path, BENCH_CONTEXT, settings.now().threads, backend)
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
        _status.value = _status.value.copy(backends = _status.value.backends - slot)
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
        /** The benchmark's 512-token prompt, its answer and room to spare. */
        const val BENCH_CONTEXT = 2048
    }

    /** Stop, while a picture is being read in: takes effect before the next picture or page. */
    fun cancelPictures() { if (text != null || omni != null || vision != null) runCatching { LlamaNative.cancelPictures() } }

    /** Stop, while Omni speaks (read-aloud or Talk): takes effect before the next audio frame. */
    fun cancelSpeech() { if (omni != null) runCatching { LlamaNative.cancelSpeech() } }

    fun systemInfo(): String = runCatching { LlamaNative.ensureLoaded(nativeLibDir); LlamaNative.systemInfo() }.getOrDefault("")
}
