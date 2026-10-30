package com.lfmpixel.lfm.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher

/** The native side; see app/src/main/cpp/lfm_jni.cpp. */
object LlamaNative {
    fun interface TokenListener {
        /** A finished UTF-8 piece of output. Return false to stop. */
        fun onToken(bytes: ByteArray): Boolean
    }

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
    external fun loadProjector(handle: Long, path: String): Boolean
    external fun free(handle: Long)
    external fun countTokens(handle: Long, text: String): Int
    external fun generate(handle: Long, prompt: String, maxTokens: Int, temp: Float, topP: Float, seed: Int, listener: TokenListener): Int
    external fun transcribe(handle: Long, pcm: FloatArray, prompt: String, maxTokens: Int, listener: TokenListener): Int
    external fun lastStats(handle: Long): DoubleArray
}

data class GenStats(val prefillMs: Double, val prefillTokens: Int, val decodeMs: Double, val decodeTokens: Int, val reusedTokens: Int) {
    val decodeTps get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0
    val prefillTps get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
}

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

    suspend fun loadProjector(path: String): Boolean = mutex.withLock {
        withContext(dispatcher) { LlamaNative.loadProjector(handle, path) }
    }

    suspend fun countTokens(text: String): Int = mutex.withLock {
        withContext(dispatcher) { LlamaNative.countTokens(handle, text) }
    }

    /** Streams the model's output for a full prompt. Cancelling the flow stops generation. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun generate(prompt: String, maxTokens: Int, temp: Float = 0.3f, topP: Float = 0.9f): Flow<String> = callbackFlow {
        val stop = AtomicBoolean(false)
        mutex.withLock {
            check(!closed) { "session closed" }
            val n = LlamaNative.generate(handle, prompt, maxTokens, temp, topP, 42) { bytes ->
                // A cancelled collector closes the channel, so the send fails and generation stops.
                trySend(String(bytes, Charsets.UTF_8)).isSuccess && !stop.get()
            }
            if (n == -2) throw LlmException("The conversation is too long for the context")
            if (n < 0) throw LlmException("Generation failed ($n)")
        }
        close()
        awaitClose { stop.set(true) }
    }.flowOn(dispatcher)

    suspend fun transcribe(pcm16k: FloatArray, systemPrompt: String, maxTokens: Int = 512): String = mutex.withLock {
        withContext(dispatcher) {
            val out = StringBuilder()
            val prompt = PromptFormat.system(systemPrompt, emptyList()) +
                "${PromptFormat.IM_START}user\n<__media__>${PromptFormat.IM_END}\n${PromptFormat.IM_START}assistant\n"
            val n = LlamaNative.transcribe(handle, pcm16k, prompt, maxTokens) { out.append(String(it, Charsets.UTF_8)); true }
            if (n < 0) throw LlmException("Transcription failed ($n)")
            out.toString().replace("<|im_end|>", "").trim()
        }
    }

    fun stats(): GenStats = LlamaNative.lastStats(handle).let { GenStats(it[0], it[1].toInt(), it[2], it[3].toInt(), it[4].toInt()) }

    suspend fun close() = mutex.withLock {
        withContext(dispatcher) {
            if (!closed) {
                closed = true
                LlamaNative.free(handle)
            }
        }
    }
}
