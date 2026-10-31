package com.lfmpixel.lfm.llm

import android.os.Build
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One model on one backend, as the benchmark measured it. [run] is null if it would not load or run ([note] says why). */
data class BackendResult(val slot: Slot, val backend: Backend, val run: BenchRun?, val ok: Boolean, val answers: List<String>,
                         val note: String? = null, val restartMs: Double = 0.0) {
    val speed get() = BackendSpeed(run?.genTps ?: 0.0, run?.promptTps ?: 0.0, ok && run != null, restartMs / 1000)
}

/** What the benchmark measured, and the same as plain text for Copy results. */
data class BenchReport(
    val model: String,
    val main: BenchRun,
    val chatTps: Double,
    val chatTokens: Int,
    val sweep: List<BenchRun>,
    val threadSetting: Int,
    val info: Map<String, String>,
    val infoLines: List<String>,
    val loadMs: Double,
    val at: LocalDateTime,
    /** Every model and backend tried, side by side. */
    val backends: List<BackendResult> = emptyList(),
    /** Where each model runs now (Auto's pick, or the owner's). */
    val chosen: Map<Slot, Backend> = emptyMap(),
    val gpu: String = "",
) {
    /** llama.cpp's CPU run of the chat model: the one the thread sweep is about. */
    private val cpuRun get() = backends.firstOrNull { it.slot == Slot.Text && it.backend == Backend.Cpu }?.run ?: main.takeIf { it.threads > 0 }

    /** The sweep's fastest thread count, if it beats the one llama.cpp's CPU run used by 5% or more. */
    val better: BenchRun? get() {
        val cpu = cpuRun ?: return null
        val best = sweep.maxByOrNull { it.genTps } ?: return null
        val current = sweep.firstOrNull { it.threads == cpu.threads }?.genTps ?: cpu.genTps
        return best.takeIf { it.threads != cpu.threads && it.genTps >= current * 1.05 }
    }

    fun text(): String = buildString {
        fun f(v: Double, d: Int = 1) = "%.${d}f".format(Locale.ENGLISH, v)
        appendLine("Inkling benchmark · ${at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH))}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("GPU (Vulkan): ${gpu.ifEmpty { "none found" }}")
        appendLine("Model: $model")
        appendLine("Backends (write tok/s · read tok/s · first token s · check):")
        backends.forEach { b ->
            val r = b.run
            appendLine("  ${b.slot.label} · ${b.backend.short}: " + if (r == null) "failed (${b.note.orEmpty()})" else
                "${f(r.genTps)} (${r.genTokens} tokens) · ${f(r.promptTps)} · ${f(r.firstTokenMs / 1000, 2)} · ${if (b.ok) "right" else "WRONG"}" +
                    " (${b.answers.joinToString(" | ") { it.take(24).replace('\n', ' ') }})")
        }
        appendLine("In use: " + chosen.entries.joinToString(" · ") { "${it.key.label} ${it.value.short}" })
        appendLine("Prompt: ${f(main.promptTps)} tok/s (${main.promptTokens} tokens)")
        appendLine("Generation: ${f(main.genTps)} tok/s (${main.genTokens} tokens)")
        appendLine("Time to first token: ${f(main.firstTokenMs / 1000, 2)} s (${main.promptTokens}-token prompt)")
        appendLine("Through the chat: ${f(chatTps)} tok/s ($chatTokens tokens)")
        appendLine("Sampler: ${f(if (main.genTokens > 0) main.samplerMs / main.genTokens else 0.0, 2)} ms a token")
        if (main.threads > 0) appendLine("Threads: ${main.threads} generating, ${main.batchThreads} reading the prompt (setting: ${if (threadSetting == 0) "Auto" else threadSetting})")
        if (sweep.isNotEmpty()) appendLine("Thread sweep, CPU (gen tok/s): " + sweep.joinToString(" · ") { "${it.threads}: ${f(it.genTps)}" })
        if (loadMs > 0) appendLine("Model load: ${f(loadMs / 1000, 1)} s")
        infoLines.forEach { appendLine(it) }
    }.trimEnd()
}

/**
 * Settings → Models → Benchmark: measures each model on this phone on every backend it can run on (llama.cpp on the
 * CPU and through Vulkan, LiteRT-LM on the GPU and the CPU), checks each gets three easy answers right, and keeps the
 * numbers so Auto can choose (see [Backends.pick]). Then it writes through the chat on the backend now in use.
 * Runs in the app's scope, so it carries on if the screen is left.
 */
class Benchmark(private val engine: ModelManager, private val store: ModelStore, private val settings: Settings, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Running(val step: String) : State
        data class Done(val report: BenchReport) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = State.Running("Starting…")
        job = scope.launch {
            _state.value = try { State.Done(run()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                State.Failed(e.message ?: "The benchmark could not run")
            }
        }
    }

    /** One model on one backend: speed, the check questions and, on llama.cpp's CPU, the thread sweep. */
    private suspend fun measure(slot: Slot, backend: Backend, nPrompt: Int, nGen: Int, sweep: MutableList<BenchRun>?): BackendResult {
        val name = "${slot.label} on ${backend.short}"
        _state.value = State.Running("Loading $name…")
        val s = try { engine.benchSession(slot, backend, fromBenchmark = true) } catch (e: Exception) {
            return BackendResult(slot, backend, null, false, emptyList(), e.message ?: "did not load")
        }
        try {
            // LiteRT-LM starts a new engine for each fresh prompt, so it gets no separate warm-up.
            if (!backend.liteRt) { _state.value = State.Running("Warming up $name…"); s.bench(32, 8) }
            _state.value = State.Running("$name: reading $nPrompt tokens, writing $nGen…")
            val run = s.bench(nPrompt, nGen)
            _state.value = State.Running("$name: checking its answers…")
            val answers = s.checkAnswers()
            if (sweep != null && s is LlamaSession) {
                val counts = (2..LlamaNative.fastCores().coerceAtMost(8)).toList()
                counts.forEach { t ->
                    _state.value = State.Running("Trying $t threads…")
                    sweep += s.bench(64, SWEEP_TOKENS, t, 0)
                }
            }
            return BackendResult(slot, backend, run, Backends.passes(answers), answers, restartMs = (s as? LiteRtSession)?.restartMs ?: 0.0)
        } catch (e: Exception) {
            return BackendResult(slot, backend, null, false, emptyList(), e.message ?: "did not run")
        } finally {
            s.close()
        }
    }

    private suspend fun run(): BenchReport {
        val model = engine.chatModel() ?: throw LlmException("No text model yet. Import LFM3-1.7B first.")
        val gpu = engine.gpuName()
        val results = mutableListOf<BackendResult>()
        val sweep = mutableListOf<BenchRun>()
        // Chat, Talk and pictures wait: they would close the model being measured, or load a second copy beside it,
        // and their work would slow the numbers Auto goes by.
        engine.benchmarking = true
        try {
            engine.unloadAll()
            measureAll(results, sweep)
        } finally { engine.benchmarking = false }
        return report(model, gpu, results, sweep)
    }

    private suspend fun measureAll(results: MutableList<BackendResult>, sweep: MutableList<BenchRun>) {
        for (slot in Slot.entries) {
            if (engine.file(slot) == null) continue
            val (nPrompt, nGen) = if (slot == Slot.Text) PROMPT_TOKENS to GEN_TOKENS else OTHER_PROMPT_TOKENS to OTHER_GEN_TOKENS
            val tried = engine.available(slot).sortedBy { it.ordinal }.map { b ->
                measure(slot, b, nPrompt, nGen, sweep.takeIf { slot == Slot.Text && b == Backend.Cpu })
            }
            results += tried
            // Kept per file: llama.cpp's backends under the GGUF, LiteRT-LM's under the .litertlm.
            tried.groupBy { if (it.backend.liteRt) store.liteRt()?.file?.name else engine.file(slot)?.file?.name }.forEach { (file, rs) ->
                if (file != null) settings.setBackendSpeeds(file, settings.now().backendSpeeds[file].orEmpty() + rs.associate { it.backend to it.speed })
            }
        }
    }

    private suspend fun report(model: InstalledModel, gpu: String, results: List<BackendResult>, sweep: List<BenchRun>): BenchReport {
        val chosen = Slot.entries.filter { engine.file(it) != null }.associateWith { engine.backendFor(it) }
        _state.value = State.Running("Loading ${model.known.fileName} on ${chosen[Slot.Text]?.short}…")
        val t0 = System.nanoTime()
        var session = engine.textSession()
        val loadMs = (System.nanoTime() - t0) / 1e6
        // An idle unload or a settings change can free the session mid-run: then load it again and carry on.
        suspend fun <T> live(block: suspend (TextSession) -> T): T {
            if (session.isClosed) session = engine.textSession()
            return try { block(session) } catch (e: LlmException) {
                if (!session.isClosed) throw e
                session = engine.textSession(); block(session)
            }
        }
        _state.value = State.Running("Writing through the chat…")
        val prompt = PromptFormat.build("Answer at length.", emptyList(),
            listOf(PromptMessage(PromptMessage.Role.User, "Tell me the story of a lighthouse keeper and a storm.")), false)
        val chat = live { s -> s.generate(prompt, CHAT_TOKENS).collect { }; s.stats() }
        val main = results.firstOrNull { it.slot == Slot.Text && it.backend == session.backend }?.run
            ?: results.firstOrNull { it.slot == Slot.Text && it.run != null }?.run
            ?: throw LlmException("The benchmark could not run on any backend")
        val lines = live { it.runInfo() }.lines().filter { it.isNotBlank() }
        val info = lines.mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return BenchReport(model.known.fileName, main, chat.decodeTps, chat.decodeTokens, sweep, settings.now().threads,
            info, lines.map { l -> l.replaceFirstChar { it.uppercase() } }, loadMs, LocalDateTime.now(), results, chosen, gpu)
    }

    companion object {
        const val PROMPT_TOKENS = 512
        const val GEN_TOKENS = 128
        /** Omni and VL: shorter, they only check the backend works and how fast it writes. */
        const val OTHER_PROMPT_TOKENS = 256
        const val OTHER_GEN_TOKENS = 64
        const val CHAT_TOKENS = 96
        const val SWEEP_TOKENS = 48
    }
}
