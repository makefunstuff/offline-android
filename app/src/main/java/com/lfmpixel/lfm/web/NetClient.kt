package com.lfmpixel.lfm.web

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.NetLogEntry
import com.lfmpixel.lfm.net.NetConfig
import com.lfmpixel.lfm.net.NetInfo
import com.lfmpixel.lfm.net.NetRequest
import com.lfmpixel.lfm.net.Network
import com.lfmpixel.lfm.net.SearchHit
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * [code] is [NO_WIFI] when a Wi-Fi-only download found no Wi-Fi; for Edge mode, [EDGE_UNREACHABLE] when
 * the server was never reached, [EDGE_REFUSED] when it answered with an error.
 */
class NetException(message: String, val code: String? = null) : Exception(message)

const val NO_WIFI = "no-wifi"
const val EDGE_UNREACHABLE = "edge-unreachable"
const val EDGE_REFUSED = "edge-refused"

private const val NO_REPLY = " · asked, no reply yet"

/**
 * Wren's side of the network module (:net, the only code allowed to open a connection). Every request goes into
 * the network log, and nothing goes at all while Offline is on (Settings → Privacy): the module itself checks that
 * before each request and between the pieces of one under way.
 */
class NetClient(context: Context, private val settings: Settings, private val db: LfmDatabase, private val scope: CoroutineScope) : SearchNet {
    enum class State { Offline, On }

    /** Offline, as last read from Settings; the network module asks this before every request. */
    @Volatile private var offline = false

    private val network = Network(context) { offline }

    /** The last known [state], for screens. */
    val current = MutableStateFlow<State?>(null)

    init {
        scope.launch {
            settings.prefs.collect { p ->
                val was = offline
                offline = !p.netEnabled
                current.value = if (offline) State.Offline else State.On
                if (offline && !was) network.stopAll()
            }
        }
    }

    override suspend fun state(): State {
        offline = !settings.now().netEnabled
        return (if (offline) State.Offline else State.On).also { current.value = it }
    }

    fun refresh() { scope.launch { state() } }

    /** The provider settings (Settings → Web search). Reading them makes no request. */
    fun config(): NetConfig = network.settings.load()
    fun save(c: NetConfig) = network.settings.save(c)

    override suspend fun info(): NetInfo = network.info()

    data class Results(val provider: String, val hits: List<SearchHit>)

    override suspend fun search(query: String): Results {
        val r = exchange(started("search provider", "", "“$query”", query), query) { network.search(query).let { it to it.log } }
        r.error?.let { throw NetException(it) }
        return Results(r.provider, r.hits)
    }

    /** One result page's HTML (slimmed), or an error. [label] names it in the network log. */
    override suspend fun fetch(url: String, label: String): String {
        val r = exchange(started(url.substringAfter("://").substringBefore('/'), url, label, ""), "") { network.fetch(url).let { it to it.log } }
        r.error?.let { throw NetException(it) }
        return r.text
    }

    /**
     * Streams a pinned model file from [offset]. [read] runs on its own thread with the bytes as they come;
     * closing that stream early stops the download.
     */
    suspend fun download(fileName: String, offset: Long, wifiOnly: Boolean, label: String, read: suspend (InputStream) -> Unit): Long {
        var readError: Throwable? = null
        val r = exchange(started("huggingface.co", "", label, fileName), fileName) {
            val (source, sink) = ParcelFileDescriptor.createPipe()
            val reply = reading(source, read, { readError = it }) {
                ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out -> network.download(fileName, offset, wifiOnly, out) }
            }
            reply to reply.log
        }
        // A failure on our side (a full disk) comes after the log is written.
        readError?.let { throw it }
        r.error?.let { throw NetException(it, if (r.noWifi) NO_WIFI else null) }
        return r.bytes
    }

    /**
     * Edge mode: one request to the owner's server. [onData] gets the reply body as it comes; returning false (or
     * this coroutine being cancelled) stops it. Returns the HTTP status; throws [NetException] ([EDGE_UNREACHABLE]
     * or [EDGE_REFUSED]) when the server could not be reached or said no.
     */
    suspend fun edge(base: String, key: String, method: String, path: String, contentType: String?, body: ByteArray?,
                     label: String, onData: (ByteArray) -> Boolean): Int {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        val host = base.substringAfter("://").substringBefore('/')
        val id = java.util.UUID.randomUUID().toString()
        val r = exchange(started(host, "", label, ""), "") {
            // Stop reaches the request at once, even before the server has sent anything.
            @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
            val onStop = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) network.edgeCancel(id)
            }
            try {
                network.edge(id, base, key, method, path, contentType, body) { bytes -> job?.isActive != false && onData(bytes) }
            } finally { onStop?.dispose() }.let { it to it.log }
        }
        // Stopped is ours: the reader had all it wanted.
        r.error?.takeIf { it != "Stopped" }?.let { throw NetException(it, if (r.status == 0) EDGE_UNREACHABLE else EDGE_REFUSED) }
        return r.status
    }

    /** Which server Settings saved: the only one Edge requests may go to. */
    fun edgePin(base: String) = network.edgePin(base)

    /**
     * One network call, logged whatever happens: a placeholder goes in the log before it is asked, and the module's
     * own entries replace it when the reply comes, even if the owner tapped Stop meanwhile (the request went out anyway).
     */
    private suspend fun <T> exchange(pending: NetLogEntry, sent: String, call: suspend () -> Pair<T, List<NetRequest>>): T {
        if (state() == State.Offline) throw NetException("Wren is offline. Turn Offline off in Settings → Privacy.")
        return withContext(Dispatchers.IO + NonCancellable) {
            // Nothing goes out that the network log can't show: a placeholder that can't be written (the disk is full)
            // stops the request, as a NetException the callers expect. The reply's own entries, once it went out, are
            // kept if they can be; their failure is only logged.
            val id = try { db.netLog().insert(pending) } catch (e: Exception) {
                Log.w("lfm-net", "network log", e)
                throw NetException("Nothing was sent: the network log could not be written (is the phone full?)")
            }
            // A call that threw may still have gone out: its placeholder stays in the log.
            val (reply, log) = try { call() } catch (e: NetException) { throw e } catch (e: Exception) {
                throw NetException(e.message ?: "The request failed")
            }
            runCatching {
                db.netLog().insertAll(log.map { r ->
                    NetLogEntry(at = r.at, host = r.host, url = r.url, label = pending.label.removeSuffix(NO_REPLY), sent = r.sent.ifEmpty { sent }, bytes = r.bytes, ok = r.ok)
                })
                db.netLog().delete(id)
            }.onFailure { Log.w("lfm-net", "network log", it) }
            reply
        }
    }

    private suspend fun <T> reading(source: ParcelFileDescriptor, read: suspend (InputStream) -> Unit,
                                    onReadError: (Throwable) -> Unit, body: suspend () -> T): T =
        kotlinx.coroutines.coroutineScope {
            val reader = launch(Dispatchers.IO) {
                runCatching { ParcelFileDescriptor.AutoCloseInputStream(source).use { read(it) } }.exceptionOrNull()?.let(onReadError)
            }
            val result = runCatching { body() }
            reader.join()
            result.getOrThrow()
        }

    /**
     * A request is in the log from the moment it is asked for, so one whose reply never comes (Wren closed
     * mid-download) is still listed. The module's own entries replace it once the reply arrives.
     */
    private fun started(host: String, url: String, label: String, sent: String) =
        NetLogEntry(at = System.currentTimeMillis(), host = host, url = url, label = label + NO_REPLY, sent = sent, bytes = 0, ok = true)
}
