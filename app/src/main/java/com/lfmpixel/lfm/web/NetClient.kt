package com.lfmpixel.lfm.web

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.data.NetLogEntry
import com.lfmpixel.lfm.ipc.ILfmNet
import com.lfmpixel.lfm.ipc.NetContract
import com.lfmpixel.lfm.ipc.SearchHit
import com.lfmpixel.lfm.ipc.hits
import com.lfmpixel.lfm.ipc.requests
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.InputStream

/** [code] is NetContract.NO_WIFI when a Wi-Fi-only download found no Wi-Fi. */
class NetException(message: String, val code: String? = null) : Exception(message)

/** What LFM Net says about itself: is a provider set up, and which. */
data class NetInfo(val ready: Boolean, val provider: String, val proxy: Boolean)

/**
 * LFM's side of the line to LFM Net. LFM itself has no network: everything here crosses to the helper app over
 * Binder, and every request it reports goes into the network log. Off when the owner turns LFM Net off.
 */
private const val NO_REPLY = " · asked, no reply yet"

class NetClient(private val context: Context, private val settings: Settings, private val db: LfmDatabase, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var service: ILfmNet? = null
    private var connection: ServiceConnection? = null
    private var idle: Job? = null
    private var calls = 0

    enum class State { NotInstalled, WrongSigner, Off, On }

    /** The last known [state], for screens; [state] and [refresh] keep it current (after an install, say). */
    val current = kotlinx.coroutines.flow.MutableStateFlow<State?>(null)

    /** Installed, signed with our key, and switched on in Privacy. */
    suspend fun state(): State = when {
        !installed() -> State.NotInstalled
        context.packageManager.checkSignatures(context.packageName, NetContract.NET_PACKAGE) != PackageManager.SIGNATURE_MATCH -> State.WrongSigner
        !settings.now().netEnabled -> State.Off
        else -> State.On
    }.also { current.value = it }

    fun refresh() { scope.launch { state() } }

    fun installed() = runCatching { context.packageManager.getPackageInfo(NetContract.NET_PACKAGE, 0); true }.getOrDefault(false)

    suspend fun info(): NetInfo = call { s ->
        val b = s.info()
        NetInfo(b.getBoolean(NetContract.READY), b.getString(NetContract.PROVIDER).orEmpty(), b.getBoolean(NetContract.PROXY))
    }

    data class Results(val provider: String, val hits: List<SearchHit>)

    suspend fun search(query: String): Results {
        val b = exchange(started("search provider", "", "“$query”", query), query) { it.search(query) }
        b.failure()?.let { throw NetException(it) }
        return Results(b.getString(NetContract.PROVIDER).orEmpty(), b.hits())
    }

    /** One result page's HTML (slimmed by LFM Net), or an error. [label] names it in the network log. */
    suspend fun fetch(url: String, label: String): String {
        val b = exchange(started(url.substringAfter("://").substringBefore('/'), url, label, ""), "") { it.fetch(url) }
        b.failure()?.let { throw NetException(it) }
        return b.getString(NetContract.TEXT).orEmpty()
    }

    /**
     * Streams a pinned model file from [offset]. [read] runs on its own thread with the bytes as they come;
     * closing that stream early stops the download.
     */
    suspend fun download(fileName: String, offset: Long, wifiOnly: Boolean, label: String, read: suspend (InputStream) -> Unit): Long {
        var readError: Throwable? = null
        val b = exchange(started("huggingface.co", "", label, fileName), fileName) { s ->
            val (source, sink) = ParcelFileDescriptor.createPipe()
            coroutineScopeReading(source, read, { readError = it }) { try { s.download(fileName, offset, wifiOnly, sink) } finally { sink.close() } }
        }
        // A failure on our side (a full disk) comes after the log is written.
        readError?.let { throw it }
        b.failure()?.let { throw NetException(it, b.getString(NetContract.CODE)) }
        return b.getLong(NetContract.BYTES)
    }

    /**
     * One network call, logged whatever happens: a placeholder goes in the log before it is asked, and LFM Net's own
     * entries replace it when the reply comes, even if the owner tapped Stop meanwhile (the request went out anyway).
     */
    private suspend fun exchange(pending: NetLogEntry, sent: String, rpc: suspend (ILfmNet) -> Bundle): Bundle = call { s ->
        withContext(NonCancellable) {
            val id = db.netLog().insert(pending)
            val b = rpc(s)
            db.netLog().insertAll(b.requests().map { r ->
                NetLogEntry(at = r.at, host = r.host, url = r.url, label = pending.label.removeSuffix(NO_REPLY), sent = r.sent.ifEmpty { sent }, bytes = r.bytes, ok = r.ok)
            })
            db.netLog().delete(id)
            b
        }
    }

    private suspend fun <T> coroutineScopeReading(source: ParcelFileDescriptor, read: suspend (InputStream) -> Unit,
                                                  onReadError: (Throwable) -> Unit, body: suspend () -> T): T =
        kotlinx.coroutines.coroutineScope {
            val reader = launch(Dispatchers.IO) {
                runCatching { ParcelFileDescriptor.AutoCloseInputStream(source).use { read(it) } }.exceptionOrNull()?.let(onReadError)
            }
            val result = runCatching { body() }
            reader.join()
            result.getOrThrow()
        }

    /** Opens LFM Net's own settings screen (provider, key, proxy): those never come into LFM. */
    fun settingsIntent(): Intent = Intent().setComponent(ComponentName(NetContract.NET_PACKAGE, NetContract.SETTINGS_ACTIVITY))

    private fun Bundle.failure(): String? = if (getBoolean(NetContract.OK)) null else (getString(NetContract.ERROR) ?: "Inkling Net failed")

    /**
     * A request is in the log from the moment it is asked for, so one whose reply never comes (LFM closed mid-download,
     * LFM Net stopped) is still listed. LFM Net's own entries replace it once the reply arrives.
     */
    private fun started(host: String, url: String, label: String, sent: String) =
        NetLogEntry(at = System.currentTimeMillis(), host = host, url = url, label = label + NO_REPLY, sent = sent, bytes = 0, ok = true)

    private suspend fun <T> call(block: suspend (ILfmNet) -> T): T {
        when (state()) {
            State.NotInstalled -> throw NetException("Inkling Net is not installed")
            State.WrongSigner -> throw NetException("Inkling Net is signed with another key, so Inkling won't use it")
            State.Off -> throw NetException("Inkling Net is turned off in Privacy")
            State.On -> Unit
        }
        val s = lock.withLock {
            idle?.cancel()
            service ?: bind()
        }
        lock.withLock { calls++ }
        return try {
            withContext(Dispatchers.IO) { block(s) }
        } catch (e: android.os.RemoteException) {
            // Only a dead helper is let go; a failed transaction (a reply too big) leaves other calls running.
            if (!s.asBinder().isBinderAlive) lock.withLock { drop() }
            throw NetException("Inkling Net stopped: ${e.message ?: "try again"}")
        } catch (e: SecurityException) {
            throw NetException("Inkling Net refused: ${e.message}")
        } finally {
            lock.withLock {
                calls--
                // Let the helper go a minute after the last call, so it isn't kept alive for nothing.
                if (calls == 0) idle = scope.launch { delay(60_000); lock.withLock { if (calls == 0) drop() } }
            }
        }
    }

    private suspend fun bind(): ILfmNet {
        drop() // a connection left from a helper that restarted
        val ready = CompletableDeferred<ILfmNet>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ILfmNet.Stub.asInterface(binder).let { service = it; ready.complete(it) } }
            override fun onServiceDisconnected(name: ComponentName) { service = null }
            override fun onBindingDied(name: ComponentName) { scope.launch { lock.withLock { drop() } } }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(NetException("Inkling Net refused the connection")) }
        }
        val intent = Intent(NetContract.ACTION_BIND).setPackage(NetContract.NET_PACKAGE)
        val ok = try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) {
            throw NetException("Inkling Net refused: ${e.message}")
        }
        if (!ok) { runCatching { context.unbindService(conn) }; throw NetException("Could not reach Inkling Net") }
        connection = conn
        return try { withTimeout(10_000) { ready.await() } } catch (e: Exception) { drop(); throw NetException("Inkling Net did not answer") }
    }

    private fun drop() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }
}
