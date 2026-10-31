package com.lfmpixel.lfm.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import com.google.ai.edge.litertlm.Backend as LtBackend
import com.google.ai.edge.litertlm.Engine as LtEngine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Session as LtSession
import com.google.ai.edge.litertlm.SessionConfig

/**
 * The chat model, loaded on one backend: llama.cpp ([LlamaSession]) or LiteRT-LM ([LiteRtSession]). Chat, tools and
 * documents use only this; pictures and speech stay with llama.cpp's Omni and VL sessions.
 */
interface TextSession {
    /** The model file it runs. */
    val path: String
    val backend: Backend
    /** Tokens of prompt and answer it holds at most. */
    val contextTokens: Int
    /** Freed (idle unload, a new import, a settings change): get a new one from ModelManager. */
    val isClosed: Boolean

    suspend fun countTokens(text: String): Int

    /** Streams the model's output for a full prompt. Cancelling the flow stops generation. */
    fun generate(prompt: String, maxTokens: Int, temp: Float = 0.3f, topP: Float = 0.9f): Flow<String>

    /** Numbers from the last generate call. */
    fun stats(): GenStats

    /** Reads in [nPrompt] tokens, then writes [nGen]; see [LlamaSession.bench]. */
    suspend fun bench(nPrompt: Int, nGen: Int): BenchRun

    /** How the session runs, one "key: value" a line. */
    suspend fun runInfo(): String

    suspend fun close()
}

/** Pictures in, text out: llama.cpp's [LlamaSession.see]; with no pictures, any session just writes. */
fun TextSession.look(images: List<RgbImage>, prompt: String, maxTokens: Int, temp: Float = 0.1f): Flow<String> = when {
    this is LlamaSession -> see(images, prompt, maxTokens, temp)
    images.isEmpty() -> generate(prompt, maxTokens, temp)
    else -> throw LlmException("Pictures need LFM3-Omni-2B or LFM3-VL-2B")
}

/** Greedy answers to [Backends.checks] with thinking off, asked as one conversation so each prompt extends the last. */
suspend fun TextSession.checkAnswers(): List<String> {
    val history = mutableListOf<PromptMessage>()
    return Backends.checks.map { (q, _) ->
        history += PromptMessage(PromptMessage.Role.User, q)
        val prompt = PromptFormat.build("", emptyList(), history, thinking = false)
        val answer = generate(prompt, 12, temp = 0f).toList().joinToString("").substringBefore(PromptFormat.IM_END).trim()
        history += PromptMessage(PromptMessage.Role.Assistant, answer)
        answer
    }
}

/**
 * Counts tokens with a GGUF's tokenizer alone, loaded without weights: LFM3's, the same one the .litertlm file
 * carries (its card: the source tokenizer.json byte for byte), so LiteRT-LM chats count exactly.
 */
class TokenCounter private constructor(private var handle: Long) {
    companion object {
        /** Null if [ggufPath] would not load. */
        fun open(nativeLibDir: String, ggufPath: String): TokenCounter? = runCatching {
            LlamaNative.ensureLoaded(nativeLibDir)
            LlamaNative.loadVocab(ggufPath).takeIf { it != 0L }?.let { TokenCounter(it) }
        }.getOrNull()
    }

    /** Tokens in [text], or -1 once closed. */
    @Synchronized fun count(text: String): Int = if (handle == 0L) -1 else LlamaNative.countTokens(handle, text.toByteArray())

    @Synchronized fun close() {
        if (handle != 0L) LlamaNative.free(handle)
        handle = 0L
    }
}

/**
 * LFM3-1.7B in LiteRT-LM (the .litertlm file), on the GPU through OpenCL or on the CPU through XNNPACK.
 *
 * It takes raw prompts, built by [PromptFormat] as for llama.cpp. One LiteRT-LM session holds the conversation so
 * far: a prompt that starts with everything the session has read and written only has its new part read in. Any
 * other prompt gets a new engine, not just a new session: LFM3's short convolutions keep a running state that
 * LiteRT-LM carries from one session of an engine into the next (LiteRT-LM issue #3165), which corrupts answers.
 */
class LiteRtSession private constructor(override val path: String, override val backend: Backend, private val cacheDir: String,
                                        private var engine: LtEngine?, private val counter: TokenCounter?) : TextSession {
    private val mutex = Mutex()
    private var closed = false
    private var session: LtSession? = null
    private var sampler: SamplerConfig? = null
    /** The text the session has read and written, in order. */
    private val consumed = StringBuilder()
    /** Its state was cut short (a stop mid-answer): the next prompt starts over. */
    private var dirty = false
    private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)
    private var initMs = 0.0
    private var engines = 1
    /** How long the last engine took to start: a new conversation pays it again. */
    var restartMs = 0.0
        private set
    /** A session was made on [engine]: a new conversation needs a new engine. */
    private var engineUsed = false

    override val contextTokens = MAX_TOKENS
    override val isClosed get() = closed

    companion object {
        /** The cache the file was built with (LFM3-1.7B_mixed_int4_ekv4096). */
        const val MAX_TOKENS = 4096

        /** The sampler for [temp] and [topP], built the same way everywhere so equal settings give equal configs. */
        private fun sampler(temp: Float, topP: Float) =
            if (temp <= 0f) SamplerConfig(1, 1.0, 0.0, 42) else SamplerConfig(50, topP.toDouble(), temp.toDouble(), 42)

        /** The chat's sampler (TextSession.generate's defaults); the probe's session uses it so the first chat carries on. */
        private val CHAT_SAMPLER = sampler(0.3f, 0.9f)

        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "litert").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

        private fun newEngine(path: String, backend: Backend, cacheDir: String): LtEngine {
            val lt = if (backend == Backend.LiteRtGpu) LtBackend.GPU() else LtBackend.CPU()
            val e = LtEngine(EngineConfig(modelPath = path, backend = lt, maxNumTokens = MAX_TOKENS, cacheDir = cacheDir))
            try { e.initialize() } catch (t: Throwable) {
                runCatching { e.close() }
                throw LlmException("LiteRT-LM could not load ${path.substringAfterLast('/')} on the ${if (backend == Backend.LiteRtGpu) "GPU" else "CPU"}: ${t.message}")
            }
            return e
        }

        /**
         * [cacheDir] keeps the GPU's compiled programs, so the second load is quicker. [counter] counts prompt tokens
         * (the session closes it); without one they are estimated.
         */
        suspend fun open(path: String, backend: Backend, cacheDir: String, counter: TokenCounter?): LiteRtSession = withContext(thread) {
            require(backend.liteRt)
            val t0 = System.nanoTime()
            val e = try { newEngine(path, backend, cacheDir) } catch (t: Throwable) { counter?.close(); throw t }
            LiteRtSession(path, backend, cacheDir, e, counter).also {
                it.restartMs = (System.nanoTime() - t0) / 1e6
                it.probe()
                it.initMs = (System.nanoTime() - t0) / 1e6
            }
        }

        /** llama.cpp's benchmark text (lfm_jni.cpp), and how many characters of it LFM3's tokenizer makes one token. */
        const val BENCH_SENTENCE = "The quick brown fox jumps over the lazy dog while the old clock on the wall ticks the afternoon away. "
        const val BENCH_CHARS_PER_TOKEN = 4.4

        /**
         * About how many tokens [text] is, erring high, when no GGUF tokenizer is at hand: LiteRT-LM has no tokenizer call. LFM3's makes about one token
         * of 4.4 bytes of English; here 3, and digits a token each, as in dates, times and tool results they may split.
         */
        fun estimateTokens(text: String): Int {
            val digits = text.count { it.isDigit() }
            return digits + (text.toByteArray().size - digits) / 3 + 1
        }
    }

    override suspend fun countTokens(text: String): Int = counter?.count(text)?.takeIf { it >= 0 } ?: estimateTokens(text)

    /** Lets the session and engine go; neither close may throw past here, so an unload always completes. */
    private fun closeEngine() {
        runCatching { session?.close() }
        session = null
        runCatching { engine?.close() }
        engine = null
    }

    /**
     * Reads in the token every chat prompt starts with, so a GPU that can't run the model (no OpenCL) fails here, at
     * load, where ModelManager falls back to llama.cpp, and not in the middle of an answer.
     */
    private fun probe() {
        try {
            val s = sessionFor(PromptFormat.IM_START, CHAT_SAMPLER).first
            s.runPrefill(listOf(InputData.Text(PromptFormat.IM_START)))
            consumed.append(PromptFormat.IM_START)
        } catch (t: Throwable) {
            closeEngine()
            counter?.close()
            throw LlmException("LiteRT-LM could not run ${path.substringAfterLast('/')} on the ${if (backend == Backend.LiteRtGpu) "GPU" else "CPU"}: ${t.message}")
        }
    }

    /** A session ready to read [prompt]: the current one if the prompt extends what it holds, else a fresh engine's. */
    private fun sessionFor(prompt: String, config: SamplerConfig): Pair<LtSession, String> {
        val current = session
        if (current != null && !dirty && config == sampler && prompt.startsWith(consumed)) return current to prompt.substring(consumed.length)
        consumed.setLength(0)
        if (engineUsed || engine == null) {
            // A used engine would carry the old conversation's state into the new one.
            closeEngine()
            val t0 = System.nanoTime()
            // If this throws, there is no engine (not a closed one): the next prompt tries again.
            engine = newEngine(path, backend, cacheDir)
            restartMs = (System.nanoTime() - t0) / 1e6
            engines++
        } else {
            runCatching { current?.close() }
            session = null
        }
        dirty = false
        engineUsed = true
        val s = engine!!.createSession(SessionConfig(config))
        session = s
        sampler = config
        return s to prompt
    }

    override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> = callbackFlow {
        mutex.withLock {
            if (closed) throw LlmException("The model was unloaded; try again")
            val config = sampler(temp, topP)
            val (s, input) = sessionFor(prompt, config)
            val started = System.nanoTime()
            var firstAt = 0L
            var n = 0
            val done = CompletableDeferred<Throwable?>()
            val out = StringBuilder()
            if (input.isNotEmpty()) s.generateContentStream(listOf(InputData.Text(input)), object : ResponseCallback {
                override fun onNext(response: String) {
                    if (done.isCompleted) return
                    if (firstAt == 0L) firstAt = System.nanoTime()
                    n++
                    out.append(response)
                    // The buffer is unlimited, so a send only fails once the collector has gone: then stop.
                    if (!trySend(response).isSuccess) { dirty = true; s.cancelProcess() } else if (n >= maxTokens) s.cancelProcess()
                }
                override fun onDone() { done.complete(null) }
                override fun onError(throwable: Throwable) { done.complete(throwable) }
            }) else done.complete(null)
            // Not cancellable: the native call must end before the session is used again.
            val err = withContext(kotlinx.coroutines.NonCancellable) { done.await() }
            val end = System.nanoTime()
            consumed.append(input).append(out)
            // Stopped at maxTokens: LiteRT-LM leaves a cancelled session that fails on the next prompt ("Prefill task
            // finished in cancelled state", seen on the emulator), so the next prompt starts over.
            if (n >= maxTokens) dirty = true
            val firstMs = if (firstAt > 0) (firstAt - started) / 1e6 else 0.0
            // Like llama.cpp, decodeTokens counts every token written (the Assistant compares it with its budget). The
            // first token's time is in firstMs with the prompt, so the time for all n is scaled from the n - 1 after it.
            val afterFirstMs = if (firstAt > 0) (end - firstAt) / 1e6 else 0.0
            lastStats = GenStats(firstMs, estimateTokens(input), if (n > 1) afterFirstMs * n / (n - 1) else afterFirstMs, n, 0, firstMs)
            if (err != null && n < maxTokens && !isClosedForSend) { dirty = true; throw LlmException("Generation failed: ${err.message}") }
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(thread)

    override fun stats() = lastStats

    /**
     * The benchmark: a plain-text prompt of about [nPrompt] tokens on a fresh engine, then [nGen] tokens written
     * greedily. Token counts of the prompt are estimated (see [estimateTokens]).
     */
    override suspend fun bench(nPrompt: Int, nGen: Int): BenchRun {
        // A chat turn asking for a long answer: as raw text the model soon ends its reply, leaving too few tokens to time.
        val ask = "Retell this passage as a long story, at least 300 words:\n"
        val passage = BENCH_SENTENCE.repeat((nPrompt * BENCH_CHARS_PER_TOKEN / BENCH_SENTENCE.length).toInt() + 1)
            .take((nPrompt * BENCH_CHARS_PER_TOKEN).toInt() - ask.length - 40)
        val text = PromptFormat.build("", emptyList(), listOf(PromptMessage(PromptMessage.Role.User, ask + passage)), thinking = false)
        // Fresh state: never extends a chat.
        mutex.withLock { withContext(thread) { dirty = true } }
        val pieces = generate(text, nGen, temp = 0f).toList()
        val st = lastStats
        mutex.withLock { withContext(thread) { dirty = true } }
        return BenchRun(st.prefillMs, nPrompt, st.firstTokenMs, st.decodeMs, st.decodeTokens, 0.0, 0, 0).also {
            if (pieces.isEmpty()) throw LlmException("The benchmark could not run")
        }
    }

    override suspend fun runInfo(): String = "runs on: ${backend.label}\nruntime: LiteRT-LM\nmodel: ${path.substringAfterLast('/')}\n" +
        "context: $MAX_TOKENS tokens\nload: %.1f s (engines started: $engines)\n".format(java.util.Locale.ENGLISH, initMs / 1000)

    override suspend fun close() = mutex.withLock {
        withContext(thread) {
            if (!closed) {
                closed = true
                closeEngine()
                counter?.close()
            }
        }
    }
}
