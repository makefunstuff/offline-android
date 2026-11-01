package com.lfmpixel.lfm.llm

import android.graphics.Bitmap
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
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine as LtEngine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig

/**
 * LFM3-VL-2B in LiteRT-LM (LFM3-VL-2B_int4.litertlm): photos, video frames and document pages in, text out, on the
 * GPU or the CPU, with its vision encoder on the same backend.
 *
 * Pictures go through LiteRT-LM's Conversation, whose chat template puts each one between `<|vision_start|>` and
 * `<|vision_end|>`; the prompts the app builds (SeeRules) are read back into a system and a user message for it. As
 * with [LiteRtSession], each question gets a fresh engine once the last one was used (LiteRT-LM issue #3165: LFM3's
 * short convolutions carry state from one conversation into the next).
 */
class LiteRtVision private constructor(override val path: String, override val backend: Backend, private val cacheDir: String,
                                       private val threads: Int, private var engine: LtEngine?, private val counter: TokenCounter?) : TextSession {
    private val mutex = Mutex()
    private var closed = false
    private var engineUsed = false
    private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)
    private var initMs = 0.0

    override val contextTokens = MAX_TOKENS
    override val isClosed get() = closed

    companion object {
        /** The file's cache: 2,048 tokens. A picture takes 256 of them. */
        const val MAX_TOKENS = 2048
        /** Pictures in one question: each is 256 tokens, and the question and answer need room too. */
        const val MAX_IMAGES = 5
        /** The file's fixed input; LiteRT-LM would stretch any other size to it with a cheaper filter (its card). */
        const val SIDE = 512

        private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "litert-vl").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

        private fun newEngine(path: String, backend: Backend, cacheDir: String, threads: Int): LtEngine {
            fun lt() = if (backend == Backend.LiteRtGpu) LtBackend.GPU() else LtBackend.CPU(threads.takeIf { it > 0 })
            val e = LtEngine(EngineConfig(modelPath = path, backend = lt(), visionBackend = lt(), maxNumTokens = MAX_TOKENS,
                maxNumImages = MAX_IMAGES, cacheDir = cacheDir))
            try { e.initialize() } catch (t: Throwable) {
                runCatching { e.close() }
                throw LlmException("LiteRT-LM could not load ${path.substringAfterLast('/')} on the ${backend.short}: ${t.message}")
            }
            return e
        }

        suspend fun open(path: String, backend: Backend, cacheDir: String, counter: TokenCounter?, threads: Int = 0): LiteRtVision = withContext(thread) {
            val t0 = System.nanoTime()
            LiteRtVision(path, backend, cacheDir, threads, newEngine(path, backend, cacheDir, threads), counter).also {
                it.initMs = (System.nanoTime() - t0) / 1e6
            }
        }

        /** [prompt] (LFM3's ChatML, as SeeRules and PromptFormat write it) as its system text and its last user text. */
        fun split(prompt: String): Pair<String, String> {
            val system = Regex("<\\|im_start\\|>system\\n(.*?)<\\|im_end\\|>", RegexOption.DOT_MATCHES_ALL).find(prompt)?.groupValues?.get(1).orEmpty()
            val user = Regex("<\\|im_start\\|>user\\n(.*?)<\\|im_end\\|>", RegexOption.DOT_MATCHES_ALL).findAll(prompt).lastOrNull()
                ?.groupValues?.get(1).orEmpty().replace("<__media__>", "").trim()
            return system.trim() to user
        }

        /** At most [max] of [n] pictures, spread evenly from the first to the last: a clip's frames still cover all of it. */
        fun pickFrames(n: Int, max: Int): List<Int> = if (n <= max) List(n) { it }
            else List(max) { i -> Math.round(i * (n - 1).toDouble() / (max - 1)).toInt() }.distinct()

        /** A picture as the file takes it: [SIDE]×[SIDE], filtered, as PNG. */
        fun png(img: RgbImage): ByteArray {
            val px = IntArray(img.width * img.height) { i ->
                val o = i * 3
                (0xFF shl 24) or ((img.rgb[o].toInt() and 0xFF) shl 16) or ((img.rgb[o + 1].toInt() and 0xFF) shl 8) or (img.rgb[o + 2].toInt() and 0xFF)
            }
            val src = Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
            val sized = Bitmap.createScaledBitmap(src, SIDE, SIDE, true)
            val out = java.io.ByteArrayOutputStream()
            sized.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (sized !== src) sized.recycle()
            src.recycle()
            return out.toByteArray()
        }
    }

    override suspend fun countTokens(text: String): Int = counter?.count(text)?.takeIf { it >= 0 } ?: LiteRtSession.estimateTokens(text)

    private fun freshEngine(): LtEngine {
        val e = engine
        if (e != null && !engineUsed) return e.also { engineUsed = true }
        runCatching { e?.close() }
        engine = null
        return newEngine(path, backend, cacheDir, threads).also { engine = it; engineUsed = true }
    }

    /** Pictures (in order) and [prompt]'s question; the answer streams. Cancelling the flow stops it. */
    fun see(images: List<RgbImage>, prompt: String, maxTokens: Int, temp: Float = 0.1f): Flow<String> {
        val keep = pickFrames(images.size, MAX_IMAGES)
        val (system, user) = split(prompt)
        // A clip cut to fewer frames says how many the model now has.
        val question = if (keep.size < images.size) user.replace(Regex("These are \\d+ frames"), "These are ${keep.size} frames") else user
        return ask(system, keep.map { images[it] }, question, maxTokens, temp)
    }

    override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> {
        val (system, user) = split(prompt)
        return ask(system, emptyList(), user, maxTokens, temp)
    }

    private fun ask(system: String, images: List<RgbImage>, question: String, maxTokens: Int, temp: Float): Flow<String> = callbackFlow {
        mutex.withLock {
            if (closed) throw LlmException("The model was unloaded; try again")
            val parts = images.map { Content.ImageBytes(png(it)) } + Content.Text(question)
            val e = freshEngine()
            val sampler = if (temp <= 0f) SamplerConfig(1, 1.0, 0.0, 42) else SamplerConfig(50, 0.9, temp.toDouble(), 42)
            val conv: Conversation = e.createConversation(ConversationConfig(
                systemInstruction = if (system.isNotEmpty()) Contents.of(system) else null,
                samplerConfig = sampler, maxOutputToken = maxTokens, thinkingConfig = ThinkingConfig(false)))
            val started = System.nanoTime()
            var firstAt = 0L
            var n = 0
            val done = CompletableDeferred<Throwable?>()
            try {
                conv.sendMessageAsync(Contents.of(parts), object : MessageCallback {
                    override fun onMessage(message: Message) {
                        if (done.isCompleted) return
                        val piece = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        if (piece.isEmpty()) return
                        if (firstAt == 0L) firstAt = System.nanoTime()
                        n++
                        if (!trySend(piece).isSuccess || n >= maxTokens) conv.cancelProcess()
                    }
                    override fun onDone() { done.complete(null) }
                    override fun onError(throwable: Throwable) { done.complete(throwable) }
                })
                val err = withContext(kotlinx.coroutines.NonCancellable) { done.await() }
                val end = System.nanoTime()
                val firstMs = if (firstAt > 0) (firstAt - started) / 1e6 else 0.0
                lastStats = GenStats(firstMs, images.size * 256 + LiteRtSession.estimateTokens(question), if (firstAt > 0) (end - firstAt) / 1e6 else 0.0, n, 0, firstMs)
                if (err != null && n < maxTokens && !isClosedForSend && err !is java.util.concurrent.CancellationException)
                    throw LlmException("Could not read the picture: ${err.message}")
            } finally {
                runCatching { conv.close() }
            }
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED).flowOn(thread)

    override fun stats() = lastStats

    override suspend fun bench(nPrompt: Int, nGen: Int): BenchRun {
        val passage = LiteRtSession.BENCH_SENTENCE.repeat((nPrompt * LiteRtSession.BENCH_CHARS_PER_TOKEN / LiteRtSession.BENCH_SENTENCE.length).toInt() + 1)
            .take((nPrompt * LiteRtSession.BENCH_CHARS_PER_TOKEN).toInt())
        val pieces = ask("", emptyList(), "Retell this passage as a long story, at least 300 words:\n$passage", nGen, 0f).toList()
        if (pieces.isEmpty()) throw LlmException("The benchmark could not run")
        val st = lastStats
        return BenchRun(st.prefillMs, nPrompt, st.firstTokenMs, st.decodeMs, st.decodeTokens, 0.0, 0, 0)
    }

    override suspend fun runInfo(): String = "runs on: ${backend.label}\nruntime: LiteRT-LM\nmodel: ${path.substringAfterLast('/')}\n" +
        "context: $MAX_TOKENS tokens\nload: %.1f s\n".format(java.util.Locale.ENGLISH, initMs / 1000)

    override suspend fun close() = mutex.withLock {
        withContext(thread) {
            if (!closed) {
                closed = true
                runCatching { engine?.close() }
                engine = null
            }
        }
    }
}
