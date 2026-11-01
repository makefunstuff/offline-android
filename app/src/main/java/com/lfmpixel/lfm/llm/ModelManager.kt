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
import com.lfmpixel.lfm.voice.OmniVoice

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
    Omni("LFM3-Omni-2B", "Voice and Talk"),
    Vision("LFM3-VL-2B", "Photos, video and documents"),
}

/**
 * Keeps the text model (and Omni and VL when used) in memory while in use, and lets them go after the idle time the
 * owner picked (never, if they chose so). Each can also be loaded or unloaded by hand. Every model on the phone runs
 * on LiteRT (docs/model-pool.md); with Edge mode on, the owner's server answers first and the phone stands behind it.
 */
class ModelManager(
    private val store: ModelStore,
    private val settings: Settings,
    private val scope: CoroutineScope,
    /** Where LiteRT-LM keeps the GPU programs it compiled, so the next load is quicker. */
    private val liteRtCache: java.io.File,
    /** The phone's heat: CPU models loaded while it is hot take fewer threads (Heat.threadCap). */
    private val heat: kotlinx.coroutines.flow.StateFlow<com.lfmpixel.lfm.device.Heat>? = null,
) {
    private val lock = Mutex()
    private var text: TextSession? = null
    private var omni: OmniVoice? = null
    private var vision: LiteRtVision? = null
    private var idleJob: Job? = null
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status

    /** A chat model on the phone. */
    fun hasText() = store.text() != null

    /** Edge mode (set by AppGraph once the network client exists). */
    @Volatile var edge: com.lfmpixel.lfm.edge.Edge? = null

    /** Chat can run: a model on the phone, or a server set up in Edge mode. */
    fun canChat() = hasText() || edge?.configured?.value == true

    private var edgeText: com.lfmpixel.lfm.edge.EdgeSession? = null
    private var edgeAnswer: com.lfmpixel.lfm.edge.EdgeSession? = null
    private var edgeVision: com.lfmpixel.lfm.edge.EdgeSession? = null

    /**
     * The server's model for [slot] when Edge mode is on and the server answers, else null (the phone's own models).
     * The text model falls back to the phone's own for a request the server never got.
     */
    suspend fun edgeSession(slot: Slot): com.lfmpixel.lfm.edge.EdgeSession? {
        val e = edge ?: return null
        if (benchmarking || !e.usable()) return null
        return when (slot) {
            Slot.Text -> edgeText ?: com.lfmpixel.lfm.edge.EdgeSession(e, com.lfmpixel.lfm.edge.EdgeModels.TEXT,
                { if (hasText()) localTextSession() else null }).also { edgeText = it }
            else -> edgeVision ?: com.lfmpixel.lfm.edge.EdgeSession(e, com.lfmpixel.lfm.edge.EdgeModels.VISION, null).also { edgeVision = it }
        }
    }
    /** The model chat uses. */
    suspend fun chatModel(): InstalledModel? = store.text(settings.now().chatModel)
    fun installed(slot: Slot) = when (slot) { Slot.Text -> hasText(); Slot.Omni -> hasVoice(); Slot.Vision -> hasVision() }
    /** Omni's .litertlm, its voice graphs and the tokenizer are all here. */
    fun hasVoice() = store.omni() != null && store.omniSpeech() && store.tokenizer() != null
    fun visionPath() = store.vision()?.file?.path
    fun hasVision() = store.vision() != null

    @Volatile private var openCl: Boolean? = null

    /**
     * Whether LiteRT-LM can reach the GPU: it runs there through the vendor's OpenCL. An emulator has none, and there
     * a failed GPU run hangs on close, so the GPU is offered only where the library loads.
     */
    private fun hasOpenCl(): Boolean = openCl ?: runCatching { System.loadLibrary("OpenCL"); true }.getOrDefault(false).also { openCl = it }

    /** The backends models can run on here: the CPU always, the GPU with OpenCL. Omni's voice graphs run on the CPU. */
    fun available(slot: Slot): Set<Backend> = buildSet {
        add(Backend.LiteRtCpu)
        if (slot != Slot.Omni && hasOpenCl()) add(Backend.LiteRtGpu)
    }

    /** What the benchmark measured for [slot]'s model, per backend. */
    suspend fun speeds(slot: Slot): Map<Backend, BackendSpeed> = file(slot)?.let { settings.now().backendSpeeds[it.file.name] }.orEmpty()

    /** Where [slot]'s model runs now: the owner's pick, or Auto's (the GPU where there is one; see [Backends.pick]). */
    suspend fun backendFor(slot: Slot): Backend = settings.now().let { p ->
        Backends.pick(p.backend, available(slot), speeds(slot),
            if (p.thinking) BackendSpeed.FRESH_SHARE_THINKING else BackendSpeed.FRESH_SHARE)
    }

    /** Notes a backend that would not load [slot]'s model, so Auto stops choosing it. */
    private suspend fun markFailed(slot: Slot, backend: Backend) {
        val file = file(slot)?.file?.name ?: return
        settings.setBackendSpeeds(file, settings.now().backendSpeeds[file].orEmpty() + (backend to BackendSpeed(0.0, 0.0, ok = false)))
    }

    /** CPU threads for a model loaded now: the owner's setting, held down while the phone is hot. */
    private suspend fun threads(): Int {
        val h = heat?.value ?: com.lfmpixel.lfm.device.Heat.Normal
        return Threads.pick(settings.now().threads, Cores.fast, com.lfmpixel.lfm.device.Heat.threadCap(h, Cores.fast)).gen
    }

    private fun counter() = store.tokenizer()?.let { TokenCounter.open(it.file.path) }

    /** What each loaded slot was asked for (file, backend), so a settings change loads it again. */
    private val asked = mutableMapOf<Slot, Pair<String?, Backend>>()

    /** Set while the Benchmark runs: it loads every model on every backend, and nothing else may load meanwhile. */
    @Volatile var benchmarking = false

    private fun notWhileBenchmarking() {
        if (benchmarking) throw LlmException("The benchmark in Settings → Models is running; try again when it ends.")
    }

    /**
     * The model that answers the owner (chat, Talk, a note's margin): in Edge mode the server's answer model when it is
     * loaded (Qwen, with LFM3-1.7B on the server and then the phone behind it), else as [textSession].
     */
    suspend fun answerSession(): TextSession {
        val e = edge
        if (e != null && !benchmarking && e.usable()) {
            val s = edgeAnswer ?: com.lfmpixel.lfm.edge.EdgeSession(e, com.lfmpixel.lfm.edge.EdgeModels.TEXT,
                { if (hasText()) localTextSession() else null }, answers = true).also { edgeAnswer = it }
            s.refresh()
            return s
        }
        return textSession()
    }

    /** The small, fast model (LFM3-1.7B): sorting, labels, the organizer's jobs. On the server in Edge mode, else the phone. */
    suspend fun textSession(): TextSession {
        edgeSession(Slot.Text)?.let { return it }
        if (!hasText() && edge?.configured?.value == true)
            throw LlmException("Your server can't be reached, and there is no chat model on this phone. Get LFM3-1.7B in Settings → Models to use Wren offline.")
        return localTextSession()
    }

    /**
     * Opens [slot]'s model on [backend]; if the GPU won't take it, on the CPU instead, and the GPU is marked not ok
     * for it so Auto stops trying. Returns the session and the backend it loaded on.
     */
    private suspend fun <T> openOn(slot: Slot, backend: Backend, open: suspend (Backend) -> T): Pair<T, Backend> =
        withContext(NonCancellable) {
            if (backend == Backend.LiteRtGpu) {
                try { return@withContext open(Backend.LiteRtGpu) to Backend.LiteRtGpu } catch (_: LlmException) { markFailed(slot, Backend.LiteRtGpu) }
            }
            open(Backend.LiteRtCpu) to Backend.LiteRtCpu
        }

    /** The chat model on its backend. */
    suspend fun localTextSession(): TextSession = lock.withLock {
        notWhileBenchmarking()
        touch()
        val m = chatModel()
        val backend = backendFor(Slot.Text)
        text?.takeIf { !it.isClosed && asked[Slot.Text] == (m?.file?.path to backend) }?.let { return it }
        text?.close()
        text = null
        _status.value = _status.value.copy(text = LoadState.None, textFile = null, backends = _status.value.backends - Slot.Text)
        if (m == null) throw LlmException("No text model yet. Get LFM3-1.7B in Settings → Models.")
        _status.value = _status.value.copy(text = LoadState.Loading, error = null, textFile = m.file.name)
        try {
            val t = threads()
            val (s, on) = openOn(Slot.Text, backend) { b -> LiteRtSession.open(m.file.path, b, liteRtCache.path, counter(), t) }
            text = s
            // A GPU that failed is now marked not ok, so Auto asks for the CPU next time: that is what loaded.
            asked[Slot.Text] = m.file.path to (if (settings.now().backend == BackendChoice.Auto) on else backend)
            _status.value = _status.value.copy(text = LoadState.Ready, backends = _status.value.backends + (Slot.Text to on))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(text = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** Omni for voice: dictation, read-aloud and Talk. Its decoder and voice graphs run on LiteRT's CPU. */
    suspend fun omniSession(): OmniVoice = lock.withLock {
        notWhileBenchmarking()
        touch()
        val m = store.omni()
        omni?.takeIf { asked[Slot.Omni]?.first == m?.file?.path }?.let { return it }
        runCatching { omni?.close() }
        omni = null
        if (m == null) throw LlmException("Voice needs LFM3-Omni-2B. Get it in Settings → Models.")
        val tok = store.tokenizer()
        if (!store.omniSpeech() || tok == null) throw LlmException("Omni's voice files are not all here yet. Get LFM3-Omni-2B again in Settings → Models.")
        _status.value = _status.value.copy(omni = LoadState.Loading, error = null)
        try {
            val t = threads()
            val s = withContext(NonCancellable) { OmniVoice.open(m.file, store.dir, BpeTokenizer.load(tok.file), t) }
            omni = s
            asked[Slot.Omni] = m.file.path to Backend.LiteRtCpu
            _status.value = _status.value.copy(omni = LoadState.Ready, backends = _status.value.backends + (Slot.Omni to Backend.LiteRtCpu))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(omni = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** LFM3-VL-2B for photos, video and documents. It loads only when one is asked about, then unloads with the others when idle. */
    suspend fun visionSession(): LiteRtVision = lock.withLock {
        notWhileBenchmarking()
        touch()
        val m = store.vision()
        val backend = backendFor(Slot.Vision)
        vision?.takeIf { !it.isClosed && asked[Slot.Vision] == (m?.file?.path to backend) }?.let { return it }
        vision?.close()
        vision = null
        if (m == null) throw LlmException("Photos and documents need LFM3-VL-2B. Get it in Settings → Models.")
        _status.value = _status.value.copy(vision = LoadState.Loading, error = null)
        try {
            val t = threads()
            val (s, on) = openOn(Slot.Vision, backend) { b -> LiteRtVision.open(m.file.path, b, liteRtCache.path, counter(), t) }
            vision = s
            asked[Slot.Vision] = m.file.path to (if (settings.now().backend == BackendChoice.Auto) on else backend)
            _status.value = _status.value.copy(vision = LoadState.Ready, backends = _status.value.backends + (Slot.Vision to on))
            s
        } catch (e: Exception) {
            _status.value = _status.value.copy(vision = LoadState.Failed, error = e.message)
            throw e
        }
    }

    /** The model file each slot uses now, if it is on the phone. */
    suspend fun file(slot: Slot): InstalledModel? = when (slot) { Slot.Text -> chatModel(); Slot.Omni -> store.omni(); Slot.Vision -> store.vision() }

    /**
     * For the benchmark: [slot]'s model alone on [backend], outside the slots (that slot is unloaded first; while
     * [benchmarking], no slot loads). The caller closes it. Throws if [backend] won't load it. Omni is measured by
     * the voice benchmark instead.
     */
    suspend fun benchSession(slot: Slot, backend: Backend, fromBenchmark: Boolean = false): TextSession = lock.withLock {
        if (!fromBenchmark) notWhileBenchmarking()
        val m = file(slot) ?: throw LlmException("${slot.label} is not on this phone")
        when (slot) {
            Slot.Text -> { text?.close(); text = null; _status.value = _status.value.copy(text = LoadState.None, textFile = null) }
            Slot.Omni -> throw LlmException("Omni is measured by the voice benchmark")
            Slot.Vision -> { vision?.close(); vision = null; _status.value = _status.value.copy(vision = LoadState.None) }
        }
        _status.value = _status.value.copy(backends = _status.value.backends - slot)
        val t = threads()
        withContext(NonCancellable) {
            if (slot == Slot.Vision) LiteRtVision.open(m.file.path, backend, liteRtCache.path, counter(), t)
            else LiteRtSession.open(m.file.path, backend, liteRtCache.path, counter(), t)
        }
    }

    private val decideLock = Mutex()
    @Volatile private var decide: com.lfmpixel.lfm.assistant.Decide? = null

    /**
     * d1-omni-600M for the small classifiers when the server is away (Labels), or null when its files are not here.
     * Loaded on first use and kept: a capture line is sorted in well under a second. Its XNNPACK weight cache sits
     * beside LiteRT-LM's, so a second load maps it instead of holding the weights in memory again.
     */
    suspend fun decide(): com.lfmpixel.lfm.assistant.Decide? = decideLock.withLock {
        touch()
        decide ?: if (!store.decide()) null else withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.lfmpixel.lfm.assistant.Decide.open(store.dir, threads(), java.io.File(liteRtCache, "d1-omni-600M_xnnpack.cache"))
        }.also { decide = it }
    }

    @Volatile private var embedder: GemmaEmbedder? = null

    /**
     * EmbeddingGemma 2 for search by meaning, or null when its file is not here. Loaded on first use (the index or a
     * search) on the CPU, kept until the idle unload: about 330 MB.
     */
    suspend fun embedder(): Embedder? = decideLock.withLock {
        val m = store.embedder() ?: return@withLock null
        touch()
        embedder ?: run {
            // A file that failed to load is not tried again on every index pass and search for a while: each try costs
            // hundreds of MB for a moment.
            // The same file, unchanged: a new download or import of it is tried at once.
            val which = "${m.file.path}:${m.file.length()}:${m.file.lastModified()}"
            embedFailed?.let { (file, at) ->
                if (file == which && System.currentTimeMillis() - at < EMBED_RETRY_MS) throw LlmException("EmbeddingGemma did not load")
            }
            try { GemmaEmbedder.open(m.file.path, liteRtCache.path, threads()).also { embedder = it; embedFailed = null } }
            catch (e: LlmException) { embedFailed = which to System.currentTimeMillis(); throw e }
        }
    }

    @Volatile private var embedFailed: Pair<String, Long>? = null
    private val EMBED_RETRY_MS = 10 * 60_000L

    /** Long jobs (a scanned PDF read page by page) call this between steps so the idle unload waits. */
    fun keepAlive() { if (_status.value.anyLoaded) touch() }

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
        // All of it to the end, though the idle job (which runs this) cancelled itself just above: a wait for decideLock
        // or a close must not stop the unload halfway (the job's CancellationException comes once it is all done).
        withContext(kotlinx.coroutines.NonCancellable) {
            val t = text; val o = omni; val v = vision
            text = null; omni = null; vision = null
            // d1 and the embedder are loaded under decideLock: taken here too, so one loading now is not left behind.
            val (d, e) = decideLock.withLock { (decide to embedder).also { decide = null; embedder = null } }
            _status.value = EngineStatus()
            // The closes wait for a call under way and free native memory: off the main thread (the idle unload runs
            // there). Each one goes even if another's close fails.
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { t?.close() }; runCatching { o?.close() }; runCatching { v?.close() }; runCatching { d?.close() }; runCatching { e?.close() }
            }
        }
    }


    /** Loads one model by hand, so the next question doesn't wait for it. */
    suspend fun load(slot: Slot) {
        when (slot) { Slot.Text -> localTextSession(); Slot.Omni -> omniSession(); Slot.Vision -> visionSession() }
    }

    /** Lets one model go; the others stay. */
    suspend fun unload(slot: Slot) = lock.withLock {
        when (slot) {
            Slot.Text -> { text?.close(); text = null; _status.value = _status.value.copy(text = LoadState.None, textFile = null) }
            Slot.Omni -> { runCatching { omni?.close() }; omni = null; _status.value = _status.value.copy(omni = LoadState.None) }
            Slot.Vision -> { vision?.close(); vision = null; _status.value = _status.value.copy(vision = LoadState.None) }
        }
        _status.value = _status.value.copy(backends = _status.value.backends - slot)
        if (!_status.value.anyLoaded) { idleJob?.cancel(); _status.value = _status.value.copy(unloadAt = null) }
    }

    /**
     * About how much memory a model takes once loaded: its weights plus working buffers, from file sizes; shown with
     * "about". On the GPU most of it sits in GPU memory.
     */
    suspend fun memoryBytes(slot: Slot): Long = when (slot) {
        Slot.Text -> (chatModel()?.known?.sizeBytes ?: 0) + 300_000_000L
        Slot.Omni -> (store.omni()?.known?.sizeBytes ?: 0) + ModelCatalog.all.filter { it.role == ModelRole.OmniSpeech }.sumOf { it.sizeBytes } + 200_000_000L
        Slot.Vision -> (store.vision()?.known?.sizeBytes ?: 0) + 400_000_000L
    }

    /** Stop, while a picture is being read in: the stream's collector going away cancels it. */
    fun cancelPictures() {}

    /** Stop, while Omni speaks (read-aloud or Talk): takes effect before the next audio frame. */
    fun cancelSpeech() { omni?.cancel() }

    fun systemInfo(): String = "LiteRT-LM 0.19.0 · LiteRT 2.3.0 · ${Cores.fast} fast cores · GPU (OpenCL): ${if (hasOpenCl()) "yes" else "no"}"
}
