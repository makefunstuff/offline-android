package com.lfmpixel.lfm.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** The native side; see app/src/main/cpp/lfm_jni.cpp. */
object LlamaNative {
    fun interface TokenListener {
        /** A finished UTF-8 piece of output. Return false to stop. */
        fun onToken(bytes: ByteArray): Boolean
    }

    /** Omni's speech out: 24 kHz mono float PCM as it is made, and for Talk the answer's text. Return false to stop. */
    interface SpeechListener {
        fun onAudio(pcm: FloatArray): Boolean
        fun onText(bytes: ByteArray): Boolean
    }

    const val PROJ_AUDIO = 1
    const val PROJ_VISION = 2

    @Volatile private var ready = false

    fun ensureLoaded(nativeLibDir: String) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            // Vulkan on the Pixel 10's PowerVR GPU gives wrong answers with these features on (llama.cpp issue #28502);
            // read when the Vulkan backend starts, below.
            for (f in listOf("COOPMAT", "COOPMAT2", "COOPMAT2_DECODE_VECTOR", "INTEGER_DOT_PRODUCT"))
                android.system.Os.setenv("GGML_VK_DISABLE_$f", "1", true)
            System.loadLibrary("lfm")
            backendInit(nativeLibDir)
            setHeatCap(com.lfmpixel.lfm.device.Heat.threadCap(heat, fastCores()))
            ready = true
        }
    }

    /** How hot the phone is; kept here so the thread cap holds from the first load. */
    @Volatile private var heat = com.lfmpixel.lfm.device.Heat.Normal

    fun heat(h: com.lfmpixel.lfm.device.Heat) = synchronized(this) {
        heat = h
        if (ready) setHeatCap(com.lfmpixel.lfm.device.Heat.threadCap(h, fastCores()))
    }

    external fun backendInit(nativeLibDir: String)
    external fun systemInfo(): String
    /** Cores that are not little (Tensor G5: 6 of 8); threads go only there. */
    external fun fastCores(): Int
    /** At most [cap] threads in every session while the phone is hot (0: no cap); applies from the next decode. */
    external fun setHeatCap(cap: Int)
    /** [gpu]: every layer on the first GPU (Vulkan); fails (0) if there is none. */
    external fun load(path: String, nCtx: Int, nThreads: Int, nThreadsBatch: Int, gpu: Boolean): Long
    /** The GPU llama.cpp found, "Vulkan0: <name> (<n> MiB free)", or "" for none (an emulator, a missing driver). */
    external fun gpuName(): String
    /** Whether the vendor's OpenCL loads here: LiteRT-LM's GPU needs it. */
    external fun hasOpenCl(): Boolean
    /** Only [path]'s tokenizer, for [countTokens]; 0 if it did not load. Free it with [free]. */
    external fun loadVocab(path: String): Long
    /** What the projector takes in: [PROJ_AUDIO], [PROJ_VISION] bits, or -1 if it did not load. */
    external fun loadProjector(handle: Long, path: String): Int
    external fun free(handle: Long)
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temp: Float, topP: Float, seed: Int, listener: TokenListener): Int
    external fun transcribe(handle: Long, pcm: FloatArray, prompt: ByteArray, maxTokens: Int, listener: TokenListener): Int
    external fun see(handle: Long, images: Array<ByteArray>, sizes: IntArray, prompt: ByteArray, maxTokens: Int, temp: Float, listener: TokenListener): Int
    external fun canSpeak(handle: Long): Boolean
    external fun speak(handle: Long, prompt: ByteArray, maxFrames: Int, listener: SpeechListener): Int
    external fun talkReply(handle: Long, history: ByteArray, prefix: ByteArray, maxSteps: Int, temp: Float, spoken: Boolean, listener: SpeechListener): Int
    /** Stops any picture being read in (between pictures). */
    external fun cancelPictures()
    /** Stops any speech being made (between audio frames). */
    external fun cancelSpeech()
    external fun lastStats(handle: Long): DoubleArray
    /** See [LlamaSession.bench]; empty on failure. */
    external fun bench(handle: Long, nPrompt: Int, nGen: Int, nThreads: Int, nThreadsBatch: Int): DoubleArray
    /** How the session runs, one "key: value" a line. */
    external fun runInfo(handle: Long): String
}

/** Thread counts for one session: [gen] while writing (bound by memory), [batch] while reading a prompt in. */
data class Threads(val gen: Int, val batch: Int) {
    companion object {
        /**
         * [setting] 0 is Auto: one thread fewer than the cores that are not little while writing, so the screen and
         * the system keep a core and no thread waits on another, and all of them while reading the prompt in.
         */
        fun pick(setting: Int, fastCores: Int): Threads {
            val fast = fastCores.coerceAtLeast(1)
            // A picked count is for writing; prompts still read on every fast core, which is what makes the first token quick.
            if (setting > 0) return Threads(setting, maxOf(setting, fast))
            return Threads((fast - 1).coerceIn(2, 6).coerceAtMost(fast), fast)
        }
    }
}

/** One benchmark run, from [LlamaSession.bench]. */
data class BenchRun(val promptMs: Double, val promptTokens: Int, val firstTokenMs: Double, val genMs: Double, val genTokens: Int,
                    val samplerMs: Double, val threads: Int, val batchThreads: Int) {
    val promptTps get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val genTps get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

data class GenStats(val prefillMs: Double, val prefillTokens: Int, val decodeMs: Double, val decodeTokens: Int, val reusedTokens: Int,
                    val firstTokenMs: Double = 0.0) {
    val decodeTps get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0
    val prefillTps get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
}

/** A picture as llama.cpp takes it: packed RGB, 3 bytes a pixel, rows top to bottom. */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray)

class LlmException(message: String) : Exception(message)

/** A loaded model in llama.cpp. All calls run on one thread, one at a time. */
class LlamaSession private constructor(private val handle: Long, override val path: String, private val dispatcher: CoroutineDispatcher,
                                       val threads: Threads, override val backend: Backend, override val contextTokens: Int) : TextSession {
    private val mutex = Mutex()
    private var closed = false

    companion object {
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

        /** [threadSetting]: the Settings value, 0 for Auto (see [Threads.pick]). */
        suspend fun open(nativeLibDir: String, path: String, nCtx: Int, threadSetting: Int, backend: Backend = Backend.Cpu): LlamaSession = withContext(thread) {
            LlamaNative.ensureLoaded(nativeLibDir)
            val t = Threads.pick(threadSetting, LlamaNative.fastCores())
            require(!backend.liteRt)
            val h = LlamaNative.load(path, nCtx, t.gen, t.batch, backend == Backend.Vulkan)
            if (h == 0L) throw LlmException("Could not load ${path.substringAfterLast('/')}" + if (backend == Backend.Vulkan) " on the GPU" else "")
            LlamaSession(h, path, thread, t, backend, nCtx)
        }

        suspend fun hasOpenCl(nativeLibDir: String): Boolean = withContext(thread) {
            LlamaNative.ensureLoaded(nativeLibDir)
            LlamaNative.hasOpenCl()
        }

        /** The GPU llama.cpp can use here, or "" for none. */
        suspend fun gpuName(nativeLibDir: String): String = withContext(thread) {
            LlamaNative.ensureLoaded(nativeLibDir)
            LlamaNative.gpuName()
        }
    }

    /** The session was freed (idle unload, a new import, a settings change): get a new one from ModelManager. */
    override val isClosed get() = closed
    private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)

    // Every native call holds the mutex and checks [closed], so a freed handle never reaches native code.
    private suspend fun <T> native(block: () -> T): T = mutex.withLock {
        withContext(dispatcher) {
            if (closed) throw LlmException("The model was unloaded; try again")
            block()
        }
    }

    suspend fun loadProjector(path: String): Int = native { LlamaNative.loadProjector(handle, path) }

    override suspend fun countTokens(text: String): Int = native { LlamaNative.countTokens(handle, text.toByteArray()) }

    /** Streams the model's output for a full prompt. Cancelling the flow stops generation. */
    override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> = callbackFlow {
        mutex.withLock {
            if (closed) throw LlmException("The model was unloaded; try again")
            val n = LlamaNative.generate(handle, prompt.toByteArray(), maxTokens, temp, topP, 42) { bytes ->
                // The buffer is unlimited, so a send only fails once the collector has gone: then stop.
                trySend(String(bytes, Charsets.UTF_8)).isSuccess
            }
            lastStats = readStats()
            if (n == -2) throw LlmException("The conversation is too long for the context")
            if (n < 0) throw LlmException("Generation failed ($n)")
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    suspend fun transcribe(pcm16k: FloatArray, systemPrompt: String, maxTokens: Int = 512): String = native {
        val out = java.io.ByteArrayOutputStream()
        val prompt = PromptFormat.system(systemPrompt, emptyList()) +
            "${PromptFormat.IM_START}user\n<__media__>${PromptFormat.IM_END}\n${PromptFormat.IM_START}assistant\n<think></think>"
        val n = LlamaNative.transcribe(handle, pcm16k, prompt.toByteArray(), maxTokens) { out.write(it); true }
        if (n < 0) throw LlmException("Transcription failed ($n)")
        out.toString(Charsets.UTF_8).replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").replace("<|im_end|>", "").trim()
    }

    /**
     * Pictures in, text streamed out. [prompt] is the full chat prompt with one `<__media__>` marker per picture.
     * The context is cleared first: pictures are not reused across calls.
     */
    fun see(images: List<RgbImage>, prompt: String, maxTokens: Int, temp: Float = 0.1f): Flow<String> = callbackFlow {
        mutex.withLock {
            if (closed) throw LlmException("The model was unloaded; try again")
            val sizes = IntArray(images.size * 2) { i -> if (i % 2 == 0) images[i / 2].width else images[i / 2].height }
            val n = LlamaNative.see(handle, images.map { it.rgb }.toTypedArray(), sizes, prompt.toByteArray(), maxTokens, temp) { bytes ->
                trySend(String(bytes, Charsets.UTF_8)).isSuccess
            }
            lastStats = readStats()
            if (n == -6) throw kotlinx.coroutines.CancellationException("Stopped")
            if (n == -2) throw LlmException("Too much to look at in one go: try fewer pages or a shorter clip")
            if (n < 0) throw LlmException("Could not read the picture ($n)")
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(dispatcher)

    suspend fun canSpeak(): Boolean = native { LlamaNative.canSpeak(handle) }

    /**
     * Speaks [prompt] (Omni's TTS template, see SpeechFormat), sending audio to [onAudio] as it is made, on the
     * model's thread. Returns false if stopped. [onAudio] returning false stops it too.
     */
    suspend fun speak(prompt: String, maxFrames: Int, onAudio: (FloatArray) -> Boolean): Boolean = native {
        val n = LlamaNative.speak(handle, prompt.toByteArray(), maxFrames, object : LlamaNative.SpeechListener {
            override fun onAudio(pcm: FloatArray) = onAudio(pcm)
            override fun onText(bytes: ByteArray) = true
        })
        if (n == -1) throw LlmException("This Omni projector file has no voice. Get mmproj-LFM3-Omni-2B again.")
        if (n < 0 && n != -6) throw LlmException("Could not speak ($n)")
        n >= 0
    }

    /** One spoken answer for Talk: its text pieces and its audio, interleaved, as Omni makes them. */
    suspend fun talkReply(history: String, prefix: String, maxSteps: Int, temp: Float, spoken: Boolean,
                          onText: (String) -> Boolean, onAudio: (FloatArray) -> Boolean): Boolean = native {
        val n = LlamaNative.talkReply(handle, history.toByteArray(), prefix.toByteArray(), maxSteps, temp, spoken, object : LlamaNative.SpeechListener {
            override fun onAudio(pcm: FloatArray) = onAudio(pcm)
            override fun onText(bytes: ByteArray) = onText(String(bytes, Charsets.UTF_8))
        })
        if (n == -1) throw LlmException("This Omni projector file has no voice. Get mmproj-LFM3-Omni-2B again.")
        if (n == -2) throw LlmException("The conversation is too long; start Talk again")
        if (n < 0 && n != -6) throw LlmException("Could not answer ($n)")
        n >= 0
    }


    private fun readStats() = LlamaNative.lastStats(handle).let { GenStats(it[0], it[1].toInt(), it[2], it[3].toInt(), it[4].toInt(), it[5]) }

    /**
     * Reads in [nPrompt] tokens, then writes [nGen] with the chat's sampler and no callbacks. Thread counts of 0 keep
     * the session's own. The context is left empty, so the next chat reads its prompt in again.
     */
    override suspend fun bench(nPrompt: Int, nGen: Int): BenchRun = bench(nPrompt, nGen, 0, 0)

    suspend fun bench(nPrompt: Int, nGen: Int, threads: Int, batchThreads: Int): BenchRun = native {
        val v = LlamaNative.bench(handle, nPrompt, nGen, threads, batchThreads)
        if (v.size < 8) throw LlmException("The benchmark could not run")
        BenchRun(v[0], v[1].toInt(), v[2], v[3], v[4].toInt(), v[5], v[6].toInt(), v[7].toInt())
    }

    override suspend fun runInfo(): String = native { LlamaNative.runInfo(handle) }

    /** Numbers from the last generate call, kept here so they stay readable after the session is freed. */
    override fun stats(): GenStats = lastStats

    override suspend fun close() = mutex.withLock {
        withContext(dispatcher) {
            if (!closed) {
                closed = true
                LlamaNative.free(handle)
            }
        }
    }
}
