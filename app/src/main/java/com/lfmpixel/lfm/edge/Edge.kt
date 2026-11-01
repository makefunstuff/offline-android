package com.lfmpixel.lfm.edge

import android.os.SystemClock
import android.util.Log
import com.lfmpixel.lfm.net.EdgeRules
import com.lfmpixel.lfm.settings.Prefs
import com.lfmpixel.lfm.settings.Settings
import com.lfmpixel.lfm.web.EDGE_REFUSED
import com.lfmpixel.lfm.web.NetClient
import com.lfmpixel.lfm.web.NetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where the server stands, for the chip in Chat and the row in Settings. */
sealed interface EdgeState {
    /** Edge mode is off, or no server is set: everything runs on the phone. */
    data object Off : EdgeState
    data object Checking : EdgeState
    /** Reached; [ms] is how long the last check took. */
    data class Online(val ms: Long) : EdgeState
    /** Not reached: the phone's own models answer until it is back. [reason] is what failed. */
    data class Offline(val reason: String, val since: Long) : EdgeState
}

/** The models the server serves for Wren: the LFM3 family only (homelab roles/local_ai, edge mode). */
object EdgeModels {
    const val TEXT = "lfm3-1.7b"
    /** The same model with the full 262K window, in two slots; used only when a prompt won't fit in [TEXT]'s 32K. */
    const val TEXT_LONG = "lfm3-1.7b-long"
    const val VISION = "lfm3-vl-2b"
    /** Speech in (/v1/audio/transcriptions) and out (/tts). */
    const val OMNI = "lfm3-omni-2b"
    const val TEXT_CONTEXT = 32_768
    const val LONG_CONTEXT = 262_144
    /**
     * The answer model, when the server serves it (homelab HOME-1): Qwen3.8-27B writes chat and Talk answers
     * and their tool calls, with native tool calling. The LFM3 models keep speech, pictures and the small jobs.
     */
    const val ANSWER = "qwen3.8-27b"
    /** As the server runs it (HOME-1: --ctx-size 32768). */
    const val ANSWER_CONTEXT = 32_768

    /** How a model is named under an answer and in the timings. */
    fun label(id: String): String = when {
        id.startsWith("qwen3.8-27b") -> "Qwen3.8-27B"
        id.startsWith(TEXT) -> "LFM3-1.7B"
        id == VISION -> "LFM3-VL-2B"
        id == OMNI -> "LFM3-Omni-2B"
        else -> id
    }
}

/**
 * Speech in and out on the server. Today Omni's /v1/audio/transcriptions and /tts ([Edge]); homelab's shared voice and
 * video service (HOME-1: realtime duplex, a speech formatter) can take its place behind this, with nothing else changed.
 */
interface ServerVoice {
    /** Speech to text: [pcm16k] is 16 kHz mono float. */
    suspend fun transcribe(pcm16k: FloatArray, language: String): String

    /** Text to speech, streamed: [onAudio] gets 24 kHz mono float PCM as it is made and returns false to stop. False if stopped. */
    suspend fun speak(text: String, voice: String, onAudio: (FloatArray) -> Boolean): Boolean
}

/**
 * Edge mode: the owner's own server (beefy-machine's `local-ai-mode edge`) runs the LFM3 models; the phone keeps the
 * screens, the notes and every tool. Every request goes through the network module (:net) and only
 * to the address set in Settings, which must be on the home network or tailnet ([EdgeRules]).
 *
 * When the server can't be reached the state turns [EdgeState.Offline] and the phone's own models answer; it is
 * checked again with a growing pause (15 s up to 2 min) and used again as soon as it answers.
 */
class Edge(private val net: NetClient, private val settings: Settings, private val scope: CoroutineScope) : ServerVoice {
    private val _state = MutableStateFlow<EdgeState>(EdgeState.Off)
    val state: StateFlow<EdgeState> = _state
    private var retry: Job? = null
    /** A server is set and Edge mode is on (whether or not it answers now). */
    val configured = MutableStateFlow(false)

    data class Server(val url: String, val key: String)

    /** The owner's gateway: wakes Qwen (voice mode) for Talk, chat and Ask ([Gateway]). */
    val gateway = Gateway(scope, ::gatewaySend, { answerId }, SystemClock::elapsedRealtime, { configured.value })

    init {
        // Turning it on, or a new address, checks at once.
        // Off the main thread, and with the settings it was handed: a second read from inside the collector, on a
        // busy main thread, can wait on the store forever (seen in the Delete everything test).
        scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            settings.prefs.distinctUntilChanged { a, b -> a.edgeOn == b.edgeOn && a.edgeUrl == b.edgeUrl && a.edgeKeySealed == b.edgeKeySealed }
                .collect { p ->
                    configured.value = p.edgeOn && p.edgeUrl.isNotBlank()
                    // The network module opens only this server (Settings' Test excepted).
                    if (configured.value) runCatching { net.edgePin(p.edgeUrl) }
                    gateway.reset()
                    scope.launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { check(p) } }
                }
        }
    }

    /** The server to use, or null when Edge mode is off or not set up. */
    suspend fun server(p: Prefs? = null): Server? {
        val prefs = p ?: settings.now()
        if (!prefs.edgeOn || prefs.edgeUrl.isBlank()) return null
        return Server(prefs.edgeUrl, openKey(prefs.edgeKeySealed))
    }

    // The keystore is asked once per sealed key, not on every request.
    @Volatile private var opened: Pair<String, String>? = null
    private fun openKey(sealed: String): String = opened?.takeIf { it.first == sealed }?.second
        ?: EdgeKey.open(sealed).also { opened = sealed to it }

    /** True when requests should go to the server now. Not checked yet (just started, just set): checks first. */
    suspend fun usable(): Boolean {
        if (server() == null) return false
        if (_state.value is EdgeState.Off || _state.value is EdgeState.Checking) runCatching { check() }
        return _state.value is EdgeState.Online
    }

    /**
     * As [usable], but an Offline server is asked again first (at most every 10 s), instead of waiting for the next
     * retry (up to 2 min): Talk asks before each turn, so Qwen answers again as soon as the server is back. Waits
     * at most 1.5 s for that check (the mic waits on it); a slower one goes on in the background for the next turn.
     */
    suspend fun usableNow(): Boolean {
        if (server() == null) return false
        if (_state.value is EdgeState.Offline && SystemClock.elapsedRealtime() - checkedAt > 10_000) {
            val asked = scope.async(kotlinx.coroutines.Dispatchers.Default) { runCatching { check() } }
            kotlinx.coroutines.withTimeoutOrNull(1_500) { asked.await() }
        }
        return usable()
    }

    @Volatile private var checkedAt = 0L

    /** The answer model's id: [EdgeModels.ANSWER]; debug builds may name another to test the path (DEBUG_EDGE). */
    @Volatile var answerId: String = EdgeModels.ANSWER
    @Volatile private var served: Map<String, String> = emptyMap()
    @Volatile private var servedAt = 0L
    @Volatile private var wokeAt = 0L

    /** The server's models and their state ("loaded", "unloaded", "loading"), asked at most every [maxAgeMs]. */
    suspend fun models(maxAgeMs: Long = 30_000): Map<String, String> {
        if (SystemClock.elapsedRealtime() - servedAt < maxAgeMs) return served
        val s = server() ?: return emptyMap()
        val out = ByteArrayOutputStream()
        request(s, "GET", "/v1/models", null, null, "Your server: models") { out.write(it); true }
        remember(out.toString(Charsets.UTF_8.name()))
        gateway.listed(served.keys)
        return served
    }

    /** Keeps a /v1/models reply as [served]. */
    private fun remember(models: String) {
        val data = JSONObject(models).getJSONArray("data")
        served = (0 until data.length()).associate { i ->
            data.getJSONObject(i).let { it.getString("id") to (it.optJSONObject("status")?.optString("value") ?: "loaded") }
        }
        servedAt = SystemClock.elapsedRealtime()
    }

    /** The answer model now ready on the server, or null: then [EdgeModels.TEXT] answers. */
    suspend fun answerModel(): String? {
        val m = runCatching { models() }.getOrNull() ?: return null
        val id = answerId
        // Not served: the server is in Edge mode. The gateway is asked for voice mode; LFM3 answers meanwhile.
        val st = m[id] ?: run { gateway.wantQwen(); return null }
        if (st == "loaded") return id
        // Served but asleep (the router unloads it when idle): woken in the background, LFM3 answers meanwhile.
        if (SystemClock.elapsedRealtime() - wokeAt > 120_000) {
            wokeAt = SystemClock.elapsedRealtime()
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val s = server() ?: return@runCatching
                    val json = JSONObject().put("model", id).put("prompt", "Hi").put("max_tokens", 1).toString()
                    request(s, "POST", "/v1/completions", "application/json", json.toByteArray(), "Your server: wake the answer model") { true }
                    servedAt = 0
                }
            }
        }
        return null
    }

    /** The answer model failed a request: asked again on the next check, not on every message meanwhile. */
    fun answerFailed() { served = served - answerId; servedAt = SystemClock.elapsedRealtime() }

    /** Whether a failed request should be answered on the phone instead: the server was not reached, or broke. */
    fun fallsBack(e: Exception): Boolean = e is NetException &&
        (e.code != EDGE_REFUSED || e.message.orEmpty().let { "HTTP 5" in it || "HTTP 401" in it })

    /** Asks the server's /health now; the state follows. Returns how long it took, or throws why it failed. */
    suspend fun check(p: Prefs? = null): Long {
        val s = server(p) ?: run { retry?.cancel(); _state.value = EdgeState.Off; return 0 }
        // Offline stays shown while it is checked again (no flicker); only a first check shows Checking.
        if (_state.value is EdgeState.Off) _state.value = EdgeState.Checking
        val t0 = SystemClock.elapsedRealtime()
        checkedAt = t0
        return try {
            request(s, "GET", "/health", null, null, "Your server: check") { true }
            val ms = SystemClock.elapsedRealtime() - t0
            online(ms)
            ms
        } catch (e: Exception) {
            failed(e)
            throw e
        }
    }

    /** Settings' Test button: health, then the key against /v1/models, with the models it found. */
    suspend fun test(url: String, key: String): String {
        val u = normalize(url) ?: throw NetException("That doesn't look like an address (http://100.x.y.z:port)")
        val t0 = SystemClock.elapsedRealtime()
        val out = ByteArrayOutputStream()
        // Straight to the network: a mistyped address tested here must not mark the saved server unreachable.
        net.edge(u, key, "GET", "/v1/models", null, null, "Your server: test") { out.write(it); true }
        val ms = SystemClock.elapsedRealtime() - t0
        val ids = runCatching {
            val data = JSONObject(out.toString(Charsets.UTF_8.name())).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it).getString("id") }
        }.getOrDefault(emptyList())
        val need = listOf(EdgeModels.TEXT, EdgeModels.VISION, EdgeModels.OMNI)
        val missing = need.filter { it !in ids }
        return if (missing.isEmpty()) "Works · answered in $ms ms · ${need.joinToString(", ")}"
            else "Reached in $ms ms, but it doesn't serve ${missing.joinToString(", ")}. Is it in edge mode?"
    }

    private fun online(ms: Long) {
        retry?.cancel()
        retry = null
        if (_state.value !is EdgeState.Online) Log.i(TAG, "server online ($ms ms)")
        _state.value = EdgeState.Online(ms)
    }

    /**
     * A request failed. Unreachable (no route, refused, timed out, Offline on): Offline, and checked again
     * later. An error the server sent (a bad request, a model loading) leaves it Online: the caller falls back once.
     */
    fun failed(e: Throwable) {
        // An error the server sent (a 5xx from one model) falls that request back, but the server is still there.
        if (e is Exception && (!fallsBack(e) || (e is NetException && e.code == EDGE_REFUSED))) return
        val reason = e.message ?: "No answer"
        val was = _state.value
        if (was !is EdgeState.Offline) {
            Log.w(TAG, "server offline: $reason")
            _state.value = EdgeState.Offline(reason, System.currentTimeMillis())
        }
        if (retry?.isActive != true) retry = scope.launch {
            var wait = 15_000L
            while (true) {
                delay(wait)
                if (runCatching { check() }.isSuccess) return@launch
                wait = (wait * 2).coerceAtMost(120_000L)
            }
        }
    }

    /**
     * One request; a failure that means the server is gone turns the state Offline. While the server was answering, a
     * request that never got a reply is tried again through a mode swap's brief port drop ([Gateway.throughSwap]);
     * the warm call's own "no" (503 during a game) is an answer, not a drop, and its failure (it may wait out the whole
     * swap) never turns the server Offline. A 401 says the key is wrong.
     */
    suspend fun request(s: Server, method: String, path: String, contentType: String?, body: ByteArray?, label: String,
                        onData: (ByteArray) -> Boolean): Int =
        gateway.guard(path, _state.value is EdgeState.Online, { server() == s }, ::failed) { onBytes ->
            net.edge(s.url, s.key, method, path, contentType, body, label) { bytes -> onBytes(); onData(bytes) }
        }

    /** One request for [Gateway]: its status and reply, or status 0 and why it was not reached. */
    private suspend fun gatewaySend(method: String, path: String): GatewayReply {
        val s = server() ?: return GatewayReply(Gateway.NOT_SENT, "No server is set up")
        val out = ByteArrayOutputStream()
        return try {
            val label = if (path == EdgeRules.WARM) "Your server: wake Qwen" else "Your server: is Qwen up?"
            val status = request(s, method, path, null, if (method == "POST") ByteArray(0) else null, label) { out.write(it); true }
            val text = out.toString(Charsets.UTF_8.name())
            if (path == "/v1/models") runCatching { remember(text) }
            GatewayReply(status, text)
        } catch (e: NetException) {
            Gateway.replyOf(e)
        }
    }

    /** POSTs JSON to [path] and hands each server-sent event's data (one JSON object) to [onEvent], until [DONE]. */
    suspend fun sse(s: Server, path: String, json: String, label: String, onEvent: (JSONObject) -> Boolean) {
        val lines = SseLines()
        request(s, "POST", path, "application/json", json.toByteArray(), label) { bytes ->
            lines.feed(bytes).all { data -> data == "[DONE]" || runCatching { JSONObject(data) }.getOrNull()?.let(onEvent) ?: true }
        }
    }

    /** The server's voice: Omni today, behind [ServerVoice]. */
    val voice: ServerVoice get() = this

    /** Speech to text on the server (Omni): [pcm16k] is 16 kHz mono float. */
    override suspend fun transcribe(pcm16k: FloatArray, language: String): String {
        val s = server() ?: throw NetException("No server is set up")
        val boundary = "wren" + System.nanoTime()
        val body = ByteArrayOutputStream()
        fun part(name: String, value: String) = body.write(
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        part("model", EdgeModels.OMNI)
        part("language", language)
        part("response_format", "json")
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"turn.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
        body.write(wav16(pcm16k, 16_000))
        body.write("\r\n--$boundary--\r\n".toByteArray())
        val out = ByteArrayOutputStream()
        request(s, "POST", "/v1/audio/transcriptions", "multipart/form-data; boundary=$boundary", body.toByteArray(),
            "Your server: speech to text") { out.write(it); true }
        return runCatching { JSONObject(out.toString(Charsets.UTF_8.name())).optString("text") }.getOrDefault("").trim()
    }

    /**
     * Text to speech on the server (Omni's /tts), streamed: [onAudio] gets 24 kHz mono float PCM as it is made, and
     * returns false to stop. Returns false if stopped.
     */
    override suspend fun speak(text: String, voice: String, onAudio: (FloatArray) -> Boolean): Boolean {
        val s = server() ?: throw NetException("No server is set up")
        val json = JSONObject().put("model", EdgeModels.OMNI).put("input", text).put("voice", voice)
            .put("response_format", "pcm").put("stream", true).toString()
        val pcm = PcmFloats()
        var stopped = false
        request(s, "POST", "/tts", "application/json", json.toByteArray(), "Your server: speech") { bytes ->
            val f = pcm.feed(bytes)
            (f.isEmpty() || onAudio(f)).also { if (!it) stopped = true }
        }
        return !stopped
    }

    companion object {
        const val TAG = "lfm-edge"

        /**
         * The address as the network module takes it: http(s)://host[:port], no path ("/v1" at the end is dropped). Null if
         * it isn't one or isn't on the home network or tailnet.
         */
        fun normalize(url: String): String? {
            var u = url.trim().trimEnd('/')
            if (u.isEmpty()) return null
            if ("://" !in u) u = "http://$u"
            u = u.removeSuffix("/v1")
            val scheme = u.substringBefore("://").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = u.substringAfter("://")
            if ('/' in rest || '?' in rest || '@' in rest) return null
            val host = if (rest.startsWith("[")) rest.substringBefore("]") + "]" else rest.substringBeforeLast(':', rest)
            val port = if (rest.startsWith("[")) rest.substringAfter("]").removePrefix(":") else rest.substringAfterLast(':', "")
            if (port.isNotEmpty() && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) return null
            if (EdgeRules.refuse(host) != null) return null
            return "$scheme://$rest"
        }

        /** Encrypted on the way: a tailnet address (100.64/10, a .ts.net name, Tailscale's IPv6) or https. */
        fun isTailnet(url: String): Boolean {
            val u = normalize(url) ?: return false
            if (u.startsWith("https://")) return true
            val host = u.substringAfter("://").let { if (it.startsWith("[")) it.substringBefore("]").removePrefix("[") else it.substringBeforeLast(':', it) }
            if (host.endsWith(".ts.net")) return true
            return EdgeRules.isTailnetLiteral(host)
        }

        /** Why [url] can't be used, for the Settings field; null if it can. */
        fun whyNot(url: String): String? {
            if (url.isBlank()) return null
            if (normalize(url) != null) return null
            val host = url.trim().substringAfter("://").substringBefore('/').substringBeforeLast(':')
            return EdgeRules.refuse(host) ?: "Write it as http://address:port"
        }

        /** 16-bit mono WAV from float PCM. */
        fun wav16(pcm: FloatArray, rate: Int): ByteArray {
            val data = pcm.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (x in pcm) b.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            return b.array()
        }
    }
}

/** Splits a server-sent event stream into each event's data, across pieces cut anywhere. */
class SseLines {
    private val pending = ByteArrayOutputStream()

    fun feed(bytes: ByteArray): List<String> {
        pending.write(bytes)
        val all = pending.toByteArray()
        val out = mutableListOf<String>()
        var start = 0
        for (i in all.indices) if (all[i] == '\n'.code.toByte()) {
            val line = String(all, start, i - start, Charsets.UTF_8).trimEnd('\r')
            if (line.startsWith("data:")) out += line.removePrefix("data:").trim()
            start = i + 1
        }
        pending.reset()
        pending.write(all, start, all.size - start)
        return out
    }
}

/** Little-endian 32-bit floats from bytes cut anywhere. */
class PcmFloats {
    private val carry = ByteArray(4)
    private var n = 0

    fun feed(bytes: ByteArray): FloatArray {
        val total = n + bytes.size
        val out = FloatArray(total / 4)
        val all = ByteArray(total)
        System.arraycopy(carry, 0, all, 0, n)
        System.arraycopy(bytes, 0, all, n, bytes.size)
        ByteBuffer.wrap(all, 0, out.size * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        n = total - out.size * 4
        System.arraycopy(all, out.size * 4, carry, 0, n)
        return out
    }
}
