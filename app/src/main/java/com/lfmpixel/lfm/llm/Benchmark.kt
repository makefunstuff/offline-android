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
) {
    /** The sweep's fastest thread count, if it beats the one in use by 5% or more. */
    val better: BenchRun? get() {
        val best = sweep.maxByOrNull { it.genTps } ?: return null
        val current = sweep.firstOrNull { it.threads == main.threads }?.genTps ?: main.genTps
        return best.takeIf { it.threads != main.threads && it.genTps >= current * 1.05 }
    }

    fun text(): String = buildString {
        fun f(v: Double, d: Int = 1) = "%.${d}f".format(Locale.ENGLISH, v)
        appendLine("LFM benchmark · ${at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH))}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("Model: $model")
        appendLine("Prompt: ${f(main.promptTps)} tok/s (${main.promptTokens} tokens)")
        appendLine("Generation: ${f(main.genTps)} tok/s (${main.genTokens} tokens)")
        appendLine("Time to first token: ${f(main.firstTokenMs / 1000, 2)} s (${main.promptTokens}-token prompt)")
        appendLine("Through the chat: ${f(chatTps)} tok/s ($chatTokens tokens)")
        appendLine("Sampler: ${f(if (main.genTokens > 0) main.samplerMs / main.genTokens else 0.0, 2)} ms a token")
        appendLine("Threads: ${main.threads} generating, ${main.batchThreads} reading the prompt (setting: ${if (threadSetting == 0) "Auto" else threadSetting})")
        appendLine("Thread sweep (gen tok/s): " + sweep.joinToString(" · ") { "${it.threads}: ${f(it.genTps)}" })
        if (loadMs > 0) appendLine("Model load: ${f(loadMs / 1000, 1)} s")
        infoLines.forEach { appendLine(it) }
    }.trimEnd()
}

/**
 * Settings → Models → Benchmark: measures the text model on this phone the way the chat uses it.
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

    private suspend fun run(): BenchReport {
        val model = engine.chatModel() ?: throw LlmException("No text model yet. Import LFM3-1.7B first.")
        _state.value = State.Running("Loading ${model.known.fileName}…")
        val wasLoaded = engine.status.value.text == LoadState.Ready
        val t0 = System.nanoTime()
        var session = engine.textSession()
        val loadMs = if (wasLoaded) 0.0 else (System.nanoTime() - t0) / 1e6
        // An idle unload or a settings change can free the session mid-run: then load it again and carry on.
        suspend fun <T> live(block: suspend (LlamaSession) -> T): T {
            if (session.isClosed) session = engine.textSession()
            return try { block(session) } catch (e: LlmException) {
                if (!session.isClosed) throw e
                session = engine.textSession(); block(session)
            }
        }
        _state.value = State.Running("Warming up…")
        live { it.bench(32, 8) }
        _state.value = State.Running("Reading 512 tokens, writing 128…")
        val main = live { it.bench(PROMPT_TOKENS, GEN_TOKENS) }
        _state.value = State.Running("Writing through the chat…")
        var chatTokens = 0
        val prompt = PromptFormat.build("Answer at length.", emptyList(),
            listOf(PromptMessage(PromptMessage.Role.User, "Tell me the story of a lighthouse keeper and a storm.")), false)
        val chat = live { s -> chatTokens = 0; s.generate(prompt, CHAT_TOKENS).collect { chatTokens++ }; s.stats() }
        val fast = LlamaNative.fastCores()
        val counts = (2..fast.coerceAtMost(8)).toList().ifEmpty { listOf(main.threads) }
        val sweep = counts.map { t ->
            _state.value = State.Running("Trying $t threads…")
            live { it.bench(64, SWEEP_TOKENS, threads = t) }
        }
        val lines = live { it.runInfo() }.lines().filter { it.isNotBlank() }
        val info = lines.mapNotNull { l -> l.split(": ", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return BenchReport(model.known.fileName, main, chat.decodeTps, chat.decodeTokens, sweep, settings.now().threads,
            info, lines.map { l -> l.replaceFirstChar { it.uppercase() } }, loadMs, LocalDateTime.now())
    }

    companion object {
        const val PROMPT_TOKENS = 512
        const val GEN_TOKENS = 128
        const val CHAT_TOKENS = 96
        const val SWEEP_TOKENS = 48
    }
}
