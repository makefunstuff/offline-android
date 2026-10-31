package com.lfmpixel.lfm.edge

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.lfmpixel.lfm.llm.Backend
import com.lfmpixel.lfm.llm.BenchRun
import com.lfmpixel.lfm.llm.GenStats
import com.lfmpixel.lfm.llm.LiteRtSession
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.RgbImage
import com.lfmpixel.lfm.llm.TextSession
import com.lfmpixel.lfm.web.EDGE_REFUSED
import com.lfmpixel.lfm.web.NetException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * A model on the owner's server, as a [TextSession]: the same raw LFM3 prompts the phone builds (PromptFormat, tools
 * and all) go to the server's /v1/completions and stream back, so tool calls are parsed exactly as on the phone.
 * Pictures go to /v1/chat/completions with [EdgeModels.VISION].
 *
 * If the server can't be reached before anything was written, [local] answers instead (the phone's own model), and
 * [Edge] turns Offline until the server is back. [usedLocal] then says so, for the "on this phone" label.
 */
class EdgeSession(
    private val edge: Edge,
    private val model: String,
    private val local: (suspend () -> TextSession)?,
) : TextSession {
    override val path = "edge:$model"
    override val backend = Backend.Cpu
    override val contextTokens = if (model == EdgeModels.TEXT) EdgeModels.LONG_CONTEXT else 131_072
    override val isClosed get() = false
    override val where: String get() = if (usedLocal) "on this phone (server not reached)" else "on your server"

    @Volatile private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)
    /** The last call was answered by the phone's own model, because the server could not be reached. */
    @Volatile var usedLocal = false
        private set
    private var localStats: (() -> GenStats)? = null

    /** Estimated, erring high: no round trip per count. Past 32K the long model takes the prompt. */
    override suspend fun countTokens(text: String): Int = LiteRtSession.estimateTokens(text)

    /** Which server model reads a prompt this long: the 32K-a-slot model while it fits, else the 262K one. */
    private fun modelFor(prompt: String, maxTokens: Int) =
        if (model == EdgeModels.TEXT && LiteRtSession.estimateTokens(prompt) + maxTokens > EdgeModels.TEXT_CONTEXT - 256) EdgeModels.TEXT_LONG else model

    override fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float): Flow<String> = callbackFlow {
        val json = JSONObject().put("model", modelFor(prompt, maxTokens)).put("prompt", prompt).put("max_tokens", maxTokens)
            .put("temperature", temp.toDouble()).put("top_p", topP.toDouble()).put("seed", 42).put("stream", true)
            .put("cache_prompt", true).put("stop", JSONArray().put(PromptFormat.IM_END))
            .put("stream_options", JSONObject().put("include_usage", true))
        var wrote = false
        val r = runStream(json.toString(), "/v1/completions", "Your server: chat") { ev ->
            val text = ev.optJSONArray("choices")?.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isNotEmpty()) { wrote = true; trySend(text).isSuccess } else true
        }
        if (r != null && !wrote && local != null && edge.fallsBack(r)) {
            usedLocal = true
            val s = local.invoke()
            s.generate(prompt, maxTokens, temp, topP).collect { send(it) }
            lastStats = s.stats()
        } else {
            usedLocal = false
            r?.let { throw LlmException("Your server: ${it.message}") }
        }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED)

    /**
     * Streams one request, keeping the stats; null when it finished, else what went wrong. Timing: from the request
     * to the first piece of text (network included), and the server's own token counts and times.
     */
    private suspend fun runStream(json: String, path: String, label: String, onEvent: (JSONObject) -> Boolean): Exception? {
        val s = edge.server() ?: return NetException("No server is set up")
        val t0 = SystemClock.elapsedRealtimeNanos()
        var first = 0L
        var timings: JSONObject? = null
        var usage: JSONObject? = null
        val err = try {
            edge.sse(s, path, json, label) { ev ->
                ev.optJSONObject("timings")?.let { timings = it }
                ev.optJSONObject("usage")?.let { usage = it }
                val hasText = ev.optJSONArray("choices")?.optJSONObject(0)?.let {
                    it.optString("text").isNotEmpty() || it.optJSONObject("delta")?.optString("content").orEmpty().isNotEmpty()
                } == true
                if (hasText && first == 0L) first = SystemClock.elapsedRealtimeNanos()
                onEvent(ev)
            }
            null
        } catch (e: NetException) { e }
        val end = SystemClock.elapsedRealtimeNanos()
        val firstMs = if (first > 0) (first - t0) / 1e6 else 0.0
        val tm = timings
        val n = tm?.optInt("predicted_n") ?: usage?.optInt("completion_tokens") ?: 0
        lastStats = GenStats(
            prefillMs = tm?.optDouble("prompt_ms") ?: firstMs, prefillTokens = tm?.optInt("prompt_n") ?: usage?.optInt("prompt_tokens") ?: 0,
            // Decode speed as the phone sees it: the tokens after the first, over the time they took to arrive.
            decodeMs = if (first > 0 && n > 1) (end - first) / 1e6 * n / (n - 1) else tm?.optDouble("predicted_ms") ?: 0.0,
            decodeTokens = n, reusedTokens = tm?.optInt("cache_n") ?: 0, firstTokenMs = firstMs)
        return err
    }

    /**
     * Pictures in, text out, on the server's VL model. [prompt] is the phone's raw prompt with one `<__media__>` per
     * picture (SeeRules.prompt); it is turned into chat messages with the pictures as JPEG in their places.
     */
    fun see(images: List<RgbImage>, prompt: String, maxTokens: Int, temp: Float): Flow<String> = callbackFlow {
        val json = JSONObject().put("model", EdgeModels.VISION).put("messages", chatMessages(prompt, images.map(::jpegDataUrl)))
            .put("max_tokens", maxTokens).put("temperature", temp.toDouble()).put("stream", true).put("reasoning_effort", "none")
        val r = runStream(json.toString(), "/v1/chat/completions", "Your server: pictures") { ev ->
            val text = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
            if (text.isNotEmpty()) trySend(text).isSuccess else true
        }
        r?.let { throw LlmException("Your server: ${it.message}") }
        close()
        awaitClose { }
    }.buffer(Channel.UNLIMITED)

    override fun stats(): GenStats = lastStats

    /** The server's speed for the benchmark: a long prompt read in, then [nGen] tokens written greedily. */
    override suspend fun bench(nPrompt: Int, nGen: Int): BenchRun {
        val ask = "Retell this passage as a long story, at least 300 words:\n"
        val passage = LiteRtSession.BENCH_SENTENCE.repeat((nPrompt * LiteRtSession.BENCH_CHARS_PER_TOKEN / LiteRtSession.BENCH_SENTENCE.length).toInt() + 1)
            .take((nPrompt * LiteRtSession.BENCH_CHARS_PER_TOKEN).toInt())
        // A number in front, so the server can't answer from its cache of the last run.
        val text = PromptFormat.build("", emptyList(), listOf(com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.User,
            "Run ${System.nanoTime()}. " + ask + passage)), thinking = false)
        val pieces = generate(text, nGen, temp = 0f).toList()
        if (pieces.isEmpty() || usedLocal) throw LlmException("The server did not answer")
        val st = lastStats
        return BenchRun(st.prefillMs, st.prefillTokens, st.firstTokenMs, st.decodeMs, st.decodeTokens, 0.0, 0, 0)
    }

    override suspend fun runInfo(): String = "runs on: your server (${edge.server()?.url ?: "not set"})\nmodel: $model\n" +
        "context: $contextTokens tokens\n"

    override suspend fun close() {}

    companion object {
        /** A raw ChatML prompt (system, user, assistant prefix) as chat messages, pictures where the markers were. */
        fun chatMessages(prompt: String, images: List<String>): JSONArray {
            val out = JSONArray()
            var next = 0
            for (turn in prompt.split(PromptFormat.IM_START).drop(1)) {
                val role = turn.substringBefore('\n')
                val body = turn.substringAfter('\n').substringBefore(PromptFormat.IM_END)
                    .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
                if (role == "assistant" && body.isBlank()) continue
                if ("<__media__>" !in body) { out.put(JSONObject().put("role", role).put("content", body.trim())); continue }
                val parts = JSONArray()
                val pieces = body.split("<__media__>")
                pieces.forEachIndexed { i, text ->
                    if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text.trim()))
                    if (i < pieces.lastIndex && next < images.size)
                        parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", images[next++])))
                }
                out.put(JSONObject().put("role", role).put("content", parts))
            }
            return out
        }

        fun jpegDataUrl(img: RgbImage): String {
            val px = IntArray(img.width * img.height) { i ->
                val o = i * 3
                (0xff shl 24) or ((img.rgb[o].toInt() and 0xff) shl 16) or ((img.rgb[o + 1].toInt() and 0xff) shl 8) or (img.rgb[o + 2].toInt() and 0xff)
            }
            val bmp = Bitmap.createBitmap(px, img.width, img.height, Bitmap.Config.ARGB_8888)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
