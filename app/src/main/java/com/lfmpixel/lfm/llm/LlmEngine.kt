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

    const val PROJ_AUDIO = 1
    const val PROJ_VISION = 2

    @Volatile private var ready = false

    fun ensureLoaded(nativeLibDir: String) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            System.loadLibrary("lfm")
            backendInit(nativeLibDir)
            ready = true
        }
    }

    external fun backendInit(nativeLibDir: String)
    external fun systemInfo(): String
    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    /** What the projector takes in: [PROJ_AUDIO], [PROJ_VISION] bits, or -1 if it did not load. */
    external fun loadProjector(handle: Long, path: String): Int
    external fun free(handle: Long)
    external fun countTokens(handle: Long, text: ByteArray): Int
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, temp: Float, topP: Float, seed: Int, listener: TokenListener): Int
    external fun transcribe(handle: Long, pcm: FloatArray, prompt: ByteArray, maxTokens: Int, listener: TokenListener): Int
    external fun see(handle: Long, images: Array<ByteArray>, sizes: IntArray, prompt: ByteArray, maxTokens: Int, temp: Float, listener: TokenListener): Int
    /** Stops any picture being read in (between pictures). */
    external fun cancelPictures()
    external fun lastStats(handle: Long): DoubleArray
}

data class GenStats(val prefillMs: Double, val prefillTokens: Int, val decodeMs: Double, val decodeTokens: Int, val reusedTokens: Int) {
    val decodeTps get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0
    val prefillTps get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
}

/** A picture as llama.cpp takes it: packed RGB, 3 bytes a pixel, rows top to bottom. */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray)

class LlmException(message: String) : Exception(message)

/** A loaded model in llama.cpp. All calls run on one thread, one at a time. */
class LlamaSession private constructor(private val handle: Long, val path: String, private val dispatcher: CoroutineDispatcher) {
    private val mutex = Mutex()
    private var closed = false

    companion object {
        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "llama").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

        suspend fun open(nativeLibDir: String, path: String, nCtx: Int, nThreads: Int): LlamaSession = withContext(thread) {
            LlamaNative.ensureLoaded(nativeLibDir)
            val h = LlamaNative.load(path, nCtx, nThreads)
            if (h == 0L) throw LlmException("Could not load ${path.substringAfterLast('/')}")
            LlamaSession(h, path, thread)
        }
    }

    /** The session was freed (idle unload, a new import, a settings change): get a new one from ModelManager. */
    val isClosed get() = closed
    private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)

    // Every native call holds the mutex and checks [closed], so a freed handle never reaches native code.
    private suspend fun <T> native(block: () -> T): T = mutex.withLock {
        withContext(dispatcher) {
            if (closed) throw LlmException("The model was unloaded; try again")
            block()
        }
    }

    suspend fun loadProjector(path: String): Int = native { LlamaNative.loadProjector(handle, path) }

    suspend fun countTokens(text: String): Int = native { LlamaNative.countTokens(handle, text.toByteArray()) }

    /** Streams the model's output for a full prompt. Cancelling the flow stops generation. */
    fun generate(prompt: String, maxTokens: Int, temp: Float = 0.3f, topP: Float = 0.9f): Flow<String> = callbackFlow {
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

    private fun readStats() = LlamaNative.lastStats(handle).let { GenStats(it[0], it[1].toInt(), it[2], it[3].toInt(), it[4].toInt()) }

    /** Numbers from the last generate call, kept here so they stay readable after the session is freed. */
    fun stats(): GenStats = lastStats

    suspend fun close() = mutex.withLock {
        withContext(dispatcher) {
            if (!closed) {
                closed = true
                LlamaNative.free(handle)
            }
        }
    }
}
