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
    /**
     * Asked for: the server is swapping to voice mode (about a minute); LFM3 or the phone answers meanwhile. [accepted]
     * once the gateway took the warm call (a refusal, a 404 or a 401 comes back at once instead).
     */
    data class Waking(val since: Long, val accepted: Boolean = false) : QwenState
    /** Listed in /v1/models: it answers. */
    data object Ready : QwenState
    /** The gateway said no (a game is on the GPU): LFM3 answers. */
    data class Busy(val reason: String) : QwenState
    /** The server said 401: the key in Settings → Your server is wrong. */
    data object KeyWrong : QwenState
}

/**
 * What one request to the gateway came back with: [status] 0 when its port dropped (a swap), [Gateway.NOT_SENT] when it
 * never went out; [body] the reply or the error.
 */
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
        // A key refused earlier is tried again: a new Talk is the owner's next try.
        if (_state.value == QwenState.KeyWrong) _state.value = QwenState.Idle
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

    /**
     * Settings' Check now: a refused key is tried again while Talk is open (it asks for Qwen with it). Only then, a new key or a new Talk,
     * never on another request's success: the gateway may check the key on the warm call alone.
     */
    fun recheck() {
        // With Talk closed nothing is sent that would check it (health needs no key): it stays said until a real try.
        if (_state.value != QwenState.KeyWrong || !talkAsked) return
        _state.value = QwenState.Idle
        wake()
    }

    /**
     * One request as [Edge.request] sends it ([attempt], which calls its argument as bytes arrive). While the server is
     * [online] and a swap is likely ([swapping]), a dropped port is ridden out ([throughSwap]), but never once a byte
     * came. A 401 from the [current] server marks the key wrong. [failed] hears every failure but the warm call's,
     * which may wait out the whole swap and must not turn the server Offline.
     */
    suspend fun guard(path: String, online: Boolean, current: suspend () -> Boolean, failed: (NetException) -> Unit,
                      attempt: suspend (onBytes: () -> Unit) -> Int): Int {
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val budget = if (online && swapping) SWAP_RETRY_MS else 0L
        return try {
            throughSwap(now, budget, { e -> !delivered.get() && isDrop(e) && (path != EdgeRules.WARM || e.code == EDGE_UNREACHABLE) }) {
                attempt { delivered.set(true) }
            }
        } catch (e: NetException) {
            // A reply for an address or key that was just changed says nothing about the new one.
            if (httpCode(e) == 401 && current()) keyWrong()
            if (path != EdgeRules.WARM) failed(e)
            throw e
        }
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
            // Read under the lock, so a reset() in between can't have this old reply land on the new server.
            val wait = synchronized(this@Gateway) {
                if (gen != mine) return@launch
                when {
                    // Accepted, or its port dropped mid-swap: the list says when Qwen is up.
                    (r.status in 200..299 && !refused(r.body)) || r.status == 0 -> {
                        (_state.value as? QwenState.Waking)?.let { _state.value = it.copy(accepted = true) }
                        true
                    }
                    else -> {
                        poll.cancel()
                        when {
                            r.status == 401 -> keyWrong()
                            r.status == 404 -> {
                                // Not the gateway (the old direct port): LFM3 answers, as before.
                                noGateway = true
                                Log.i(Edge.TAG, "gateway: no warm call on this server")
                                idle()
                            }
                            // Not sent (Wren is Offline, the network is away): asked again on the next chance.
                            r.status == NOT_SENT -> { askedAt = -1L; idle() }
                            // A 2xx that says no, or 403, 409, 423, 503…: refused, a game is on.
                            else -> busy(reason(r.body))
                        }
                        false
                    }
                }
            }
            if (wait) poll.join()
        }
    }

    private fun idle() { if (_state.value is QwenState.Waking) _state.value = QwenState.Idle }

    /** [send], with a failure as status 0; a cancelled request stays cancelled. */
    private suspend fun ask(method: String, path: String): GatewayReply = try {
        send(method, path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GatewayReply(NOT_SENT, e.message.orEmpty())
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
            if (r.status == NOT_SENT) { synchronized(this) { if (gen == mine) { askedAt = -1L; idle() } }; return }
            if (r.status !in 200..299) continue
            listed(ids(r.body))
            if (_state.value == QwenState.Ready) return
        }
        Log.i(Edge.TAG, "gateway: ${answerId()} not served after ${POLL_FOR_MS / 1000} s; LFM3 answers")
        if (gen == mine && _state.value is QwenState.Waking) _state.value = QwenState.Idle
    }

    /** Waking, or up (the server swaps back to Edge after Talk): a dropped port is likely a mode swap. */
    val swapping get() = _state.value.let { it is QwenState.Waking || it == QwenState.Ready }

    companion object {
        /** How long Qwen is waited for: the swap takes about a minute. */
        const val POLL_FOR_MS = 4 * 60_000L
        /** How long a request is tried again through a mode swap's port drop (about 8 s) before the phone answers. */
        const val SWAP_RETRY_MS = 15_000L
        /** [GatewayReply.status] of a request that never went out (Wren is Offline, not the saved server, no network). */
        const val NOT_SENT = -1
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

        /** A 2xx reply that still says no: {"refused": true}, {"ok": false}, a status of refused or busy, a state of blocked. */
        fun refused(body: String): Boolean = runCatching {
            val j = JSONObject(body)
            j.optBoolean("refused", false) || (j.has("ok") && !j.optBoolean("ok", true)) ||
                j.optString("status").lowercase() in setOf("refused", "busy") || j.optString("state").lowercase() == "blocked"
        }.getOrDefault(false)

        /** Why, from the reply ("reason", "message", "error"), else a plain guess. */
        fun reason(body: String): String = runCatching {
            // The network module's error text: "HTTP 409: {…the server's reply…}".
            val j = JSONObject(body.replace(Regex("""^HTTP \d{3}:?\s*"""), ""))
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

        /**
         * A failed request as [Gateway] reads it: the HTTP status; 0 for a dropped port or a warm call that waited out
         * the swap past the read timeout; [NOT_SENT] when it never went out (Wren is Offline, another address, no network).
         */
        fun replyOf(e: NetException) = GatewayReply(httpCode(e) ?: if (isDrop(e) || isTimeout(e)) 0 else NOT_SENT, e.message.orEmpty())

        /** The reply took too long: sent, but not answered in time. */
        fun isTimeout(e: NetException) = e.message.orEmpty().lowercase().let { "timeout" in it || "timed out" in it }

        private val DROPS = listOf("failed to connect", "connection refused", "econnrefused", "connection reset", "unexpected end of stream", "closed")
    }
}
