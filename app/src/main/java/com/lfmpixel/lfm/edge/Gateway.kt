package com.lfmpixel.lfm.edge

import android.util.Log
import com.lfmpixel.lfm.net.EdgeRules
import com.lfmpixel.lfm.web.EDGE_UNREACHABLE
import com.lfmpixel.lfm.web.NetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the answer model (Qwen) stands on the owner's gateway, for Talk's "Waking Qwen…" line. */
sealed interface QwenState {
    /** Not asked for, or not served (a server without the gateway): LFM3 answers. */
    data object Idle : QwenState
    /** Asked for: the server is swapping to voice mode (about a minute); LFM3 or the phone answers meanwhile. */
    data class Waking(val since: Long) : QwenState
    /** Listed in /v1/models: it answers. */
    data object Ready : QwenState
    /** The gateway said no (a game is on the GPU): LFM3 answers. */
    data class Busy(val reason: String) : QwenState
    /** The server said 401: the key in Settings → Your server is wrong. */
    data object KeyWrong : QwenState
}

/** What one request to the gateway came back with: [status] 0 when it was not reached; [body] the reply or the error. */
data class GatewayReply(val status: Int, val body: String)

/**
 * The owner's gateway (homelab HOME-8): the server idles in Edge mode, with LFM3 only, and swaps to voice mode, with
 * Qwen, when asked ([EdgeRules.WARM]). Talk asks once a session as it opens; chat and Ask ask when they would like Qwen
 * and it isn't served (at most every [ASK_EVERY_MS]). Then /v1/models is asked again with a growing pause until Qwen is
 * listed, for at most [POLL_FOR_MS]. No request here is its own: each goes through [send], to [Edge.request] and the
 * network module.
 */
class Gateway(
    private val scope: CoroutineScope,
    private val send: suspend (method: String, path: String) -> GatewayReply,
    private val answerId: () -> String,
    private val now: () -> Long,
    /** A server is set and Edge mode is on: only then is it asked. */
    private val configured: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow<QwenState>(QwenState.Idle)
    val state: StateFlow<QwenState> = _state

    @Volatile private var talkAsked = false
    @Volatile private var askedAt = -1L
    /** The server has no warm call (404): it is not the gateway, so it is never asked again until the address changes. */
    @Volatile private var noGateway = false
    private var waking: Job? = null
    /** Bumped by [reset]: a reply to an earlier address or key, still on its way, changes nothing. */
    @Volatile private var gen = 0

    /** Talk opened: asks for Qwen, once until [talkClosed] (or as soon as the server is set, if it isn't yet). */
    fun talkOpened() {
        if (talkAsked) return
        talkAsked = true
        wake()
    }

    fun talkClosed() { talkAsked = false }

    /** Chat or Ask is about to answer without Qwen: asks for it, at most every [ASK_EVERY_MS]. */
    fun wantQwen() {
        if (askedAt >= 0 && now() - askedAt < ASK_EVERY_MS) return
        wake()
    }

    /** The server's model list, from any request: Qwen listed is Ready; gone again (back in Edge mode) is Idle. */
    fun listed(ids: Collection<String>) {
        val st = _state.value
        if (answerId() in ids) { if (st != QwenState.Ready) Log.i(Edge.TAG, "gateway: ${answerId()} is served"); _state.value = QwenState.Ready }
        else if (st == QwenState.Ready) _state.value = QwenState.Idle
    }

    /** The server answered 401: the key is wrong. Said in Talk and Settings; each request then falls back as before. */
    fun keyWrong() {
        if (_state.value != QwenState.KeyWrong) Log.w(Edge.TAG, "gateway: the key was refused (401)")
        synchronized(this) { waking?.cancel(); waking = null }
        _state.value = QwenState.KeyWrong
    }

    /** A request with the key went through: it is right after all, and an open Talk asks for Qwen again. */
    fun keyOk() {
        if (_state.value != QwenState.KeyWrong) return
        _state.value = QwenState.Idle
        if (talkAsked) wake()
    }

    /** A new address or key: forget what this server said; Talk, if open, asks again. */
    fun reset() {
        synchronized(this) { gen++; waking?.cancel(); waking = null }
        noGateway = false
        askedAt = -1L
        _state.value = QwenState.Idle
        if (talkAsked) wake()
    }

    private fun wake() = synchronized(this) {
        val st = _state.value
        if (!configured() || noGateway || waking?.isActive == true || st == QwenState.Ready || st == QwenState.KeyWrong) return@synchronized
        askedAt = now()
        _state.value = QwenState.Waking(askedAt)
        Log.i(Edge.TAG, "gateway: asking for voice mode")
        val mine = gen
        waking = scope.launch {
            // The warm call may answer at once or only when the swap is done: the list is asked meanwhile either way.
            val poll = launch { poll(mine) }
            val r = ask("POST", EdgeRules.WARM)
            if (gen != mine) return@launch
            when {
                r.status == 401 -> { poll.cancel(); keyWrong() }
                r.status == 404 -> {
                    // Not the gateway (the old direct port): LFM3 answers, as before.
                    poll.cancel(); noGateway = true
                    Log.i(Edge.TAG, "gateway: no warm call on this server")
                    if (_state.value is QwenState.Waking) _state.value = QwenState.Idle
                }
                r.status in 200..299 && refused(r.body) -> { poll.cancel(); busy(reason(r.body)) }
                // Accepted, or not reached (the swap drops ports for a few seconds): the list says when Qwen is up.
                r.status in 200..299 || r.status == 0 -> poll.join()
                // 403, 409, 423, 503…: refused, a game is on.
                else -> { poll.cancel(); busy(reason(r.body)) }
            }
        }
    }

    /** [send], with a failure as status 0; a cancelled request stays cancelled. */
    private suspend fun ask(method: String, path: String): GatewayReply = try {
        send(method, path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GatewayReply(0, e.message.orEmpty())
    }

    private fun busy(reason: String) {
        Log.i(Edge.TAG, "gateway: voice mode refused: $reason")
        if (_state.value !is QwenState.Ready) _state.value = QwenState.Busy(reason)
    }

    /** /v1/models with a growing pause (2 s up to 15 s) until Qwen is listed; gives up after [POLL_FOR_MS]. */
    private suspend fun poll(mine: Int) {
        val start = now()
        var wait = 2_000L
        while (now() - start < POLL_FOR_MS) {
            delay(wait)
            wait = (wait * 3 / 2).coerceAtMost(15_000L)
            if (gen != mine || _state.value == QwenState.Ready) return
            val r = ask("GET", "/v1/models")
            if (gen != mine) return
            if (r.status == 401) { keyWrong(); return }
            if (r.status !in 200..299) continue
            listed(ids(r.body))
            if (_state.value == QwenState.Ready) return
        }
        Log.i(Edge.TAG, "gateway: ${answerId()} not served after ${POLL_FOR_MS / 1000} s; LFM3 answers")
        if (gen == mine && _state.value is QwenState.Waking) _state.value = QwenState.Idle
    }

    /** Asked for or up lately: a request that meets a dropped port is likely meeting a mode swap. */
    val swapping get() = _state.value.let { it is QwenState.Waking || it == QwenState.Ready } ||
        (askedAt >= 0 && now() - askedAt < SWAP_WINDOW_MS)

    companion object {
        /** How long Qwen is waited for: the swap takes about a minute. */
        const val POLL_FOR_MS = 4 * 60_000L
        /** After asking, how long a dropped port is taken for the swap. */
        const val SWAP_WINDOW_MS = 5 * 60_000L
        /** Chat and Ask ask for Qwen at most this often (the server goes back to Edge 10 minutes after Talk ends). */
        const val ASK_EVERY_MS = 5 * 60_000L

        /** The gateway for an old direct voice port (:18101 at home, :18448 on the tailnet), or null. The host stays. */
        fun gatewayFor(url: String): String? {
            val u = Edge.normalize(url) ?: return null
            val rest = u.substringAfter("://")
            val port = rest.substringAfterLast(':', "").takeUnless { rest.endsWith("]") } ?: return null
            val to = PORTS[port] ?: return null
            return u.removeSuffix(":$port") + ":$to"
        }

        private val PORTS = mapOf("18101" to "18110", "18448" to "18450")

        /** The model ids in a /v1/models reply. */
        fun ids(body: String): List<String> = runCatching {
            val data = JSONObject(body).getJSONArray("data")
            (0 until data.length()).map { data.getJSONObject(it).getString("id") }
        }.getOrDefault(emptyList())

        /** A 2xx reply that still says no: {"refused": true}, {"ok": false} or a status of refused or busy. */
        fun refused(body: String): Boolean = runCatching {
            val j = JSONObject(body)
            j.optBoolean("refused", false) || (j.has("ok") && !j.optBoolean("ok", true)) ||
                j.optString("status").lowercase() in setOf("refused", "busy")
        }.getOrDefault(false)

        /** Why, from the reply ("reason", "message", "error"), else a plain guess. */
        fun reason(body: String): String = runCatching {
            val j = JSONObject(body)
            listOf("reason", "message", "detail").firstNotNullOfOrNull { k -> j.optString(k).takeIf { it.isNotBlank() } }
                ?: j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: j.optString("error").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.replace(Regex("""^HTTP \d{3}:?\s*"""), "").take(120).ifBlank { "the server is busy" }

        /** The HTTP status in a [NetException]'s message ("HTTP 503: …"), or null. */
        fun httpCode(e: Exception): Int? = Regex("""HTTP (\d{3})""").find(e.message.orEmpty())?.groupValues?.get(1)?.toInt()

        /**
         * Runs [attempt] again through the brief port drop of a mode swap (about 8 s): a failure that came fast (a
         * refused connection, the gateway's 502–504) and that [transient] allows is tried again after 0.5, 1, 2, 4 s…,
         * for at most [budgetMs] in all. A slow failure (a timeout: the server is away) is not, nor anything once
         * [budgetMs] is spent: then the caller falls back as before.
         */
        suspend fun <T> throughSwap(now: () -> Long, budgetMs: Long, transient: (NetException) -> Boolean, attempt: suspend () -> T): T {
            val start = now()
            var wait = 500L
            while (true) {
                val t0 = now()
                try {
                    return attempt()
                } catch (e: NetException) {
                    val fast = now() - t0 < QUICK_MS
                    if (!fast || !transient(e) || now() - start + wait > budgetMs) throw e
                    Log.i(Edge.TAG, "server: ${e.message}; trying again in $wait ms")
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(4_000L)
                }
            }
        }

        /** How long a failure may take to count as a dropped port rather than a server that is away. */
        const val QUICK_MS = 2_500L

        /**
         * A dropped port or the gateway's own "not now": a connection refused, reset or closed, or 502, 503, 504. Not
         * Wren's Offline, a name that didn't resolve, or the network module's own refusals (another address, a path).
         */
        fun isDrop(e: NetException): Boolean {
            val m = e.message.orEmpty()
            if (httpCode(e) in setOf(502, 503, 504)) return true
            return e.code == EDGE_UNREACHABLE && com.lfmpixel.lfm.net.NetEngine.OFFLINE !in m &&
                DROPS.any { it in m.lowercase() }
        }

        private val DROPS = listOf("failed to connect", "connection refused", "econnrefused", "connection reset", "unexpected end of stream", "closed")
    }
}
