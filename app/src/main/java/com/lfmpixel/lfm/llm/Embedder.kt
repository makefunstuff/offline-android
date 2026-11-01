package com.lfmpixel.lfm.llm

import android.os.SystemClock
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/** Turns text into vectors whose dot product says how close two texts are in meaning (docs/embeddings.md). */
interface Embedder : Closeable {
    /** One unit-length vector of [EmbedPrompt.DIM] floats per text, in order. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/**
 * EmbeddingGemma 2's task prompts (google/embeddinggemma-2's card, "SearchQuery" and "Document"): a note is embedded
 * as a document, what the owner searches for as a query. The same strings are in app/src/test/python/embed_eval.py.
 */
object EmbedPrompt {
    /** Matryoshka output size: 256 keeps 99% of 768's quality (its card; our test set: the same recall) at a third of the bytes. */
    const val DIM = 256
    /** Tokens per text; a longer note is cut. Title and the start of the body carry most of a note's meaning. */
    const val MAX_TOKENS = 256

    fun query(q: String) = "task: search result | query: ${q.trim()}"

    fun document(title: String, body: String): String {
        val t = title.trim().ifEmpty { "none" }
        // About 4 characters a token: no need to send the engine more than it reads.
        return "title: $t | text: ${body.trim().take(MAX_TOKENS * 4)}"
    }
}

/**
 * EmbeddingGemma 2 text 270m (litert-community/embeddinggemma-2-text-270m-litert-lm) on LiteRT-LM's EmbeddingEngine,
 * on the CPU: about 15 ms a note on a desktop CPU, 27 ms on a Galaxy S26 (its card), about 330 MB in memory. The GPU
 * is left to chat. One call at a time.
 */
class GemmaEmbedder private constructor(private val engine: EmbeddingEngine) : Embedder {
    private val lock = Mutex()
    private var closed = false
    private val options = EmbeddingOptions(normalize = true, insertSpecialTokens = null, outputSize = EmbedPrompt.DIM)

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.Default) {
        lock.withLock {
            if (closed) throw java.io.IOException("The search model was unloaded")
            texts.map { engine.computeEmbedding(listOf(InputData.Text(it)), options).embedding }
        }
    }

    override fun close() {
        // Waits for a call under way: the engine must not go while it runs.
        kotlinx.coroutines.runBlocking { lock.withLock { if (!closed) { closed = true; engine.close() } } }
    }

    companion object {
        /** Loads [path]; [cacheDir] keeps LiteRT-LM's compiled caches, so the second load is quicker. */
        suspend fun open(path: String, cacheDir: String, threads: Int): GemmaEmbedder = withContext(Dispatchers.IO) {
            val t0 = SystemClock.elapsedRealtime()
            val cpu = com.google.ai.edge.litertlm.Backend.CPU(threads.takeIf { it > 0 })
            val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = path, backend = cpu, cacheDir = cacheDir,
                maxInputLength = EmbedPrompt.MAX_TOKENS))
            e.initialize()
            android.util.Log.i("lfm-embed", "EmbeddingGemma 2 loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            GemmaEmbedder(e)
        }
    }
}
