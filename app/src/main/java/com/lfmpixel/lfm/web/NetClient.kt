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

    suspend fun search(query: String): Results = call { s ->
        val b = s.search(query)
        log(b, query) { "“$query”" }
        b.failure()?.let { throw NetException(it) }
        Results(b.getString(NetContract.PROVIDER).orEmpty(), b.hits())
    }

    /** One result page's HTML (slimmed by LFM Net), or an error. [label] names it in the network log. */
    suspend fun fetch(url: String, label: String): String = call { s ->
        val b = s.fetch(url)
        log(b, "") { label }
        b.failure()?.let { throw NetException(it) }
        b.getString(NetContract.TEXT).orEmpty()
    }

    /**
     * Streams a pinned model file from [offset]. [read] runs on its own thread with the bytes as they come;
     * closing that stream early stops the download.
     */
    suspend fun download(fileName: String, offset: Long, wifiOnly: Boolean, label: String, read: suspend (InputStream) -> Unit): Long = call { s ->
        val (source, sink) = ParcelFileDescriptor.createPipe()
        coroutineScopeReading(source, read) {
            val b = try { s.download(fileName, offset, wifiOnly, sink) } finally { sink.close() }
            log(b, fileName) { label }
            b.failure()?.let { throw NetException(it, b.getString(NetContract.CODE)) }
            b.getLong(NetContract.BYTES)
        }
    }

    private suspend fun <T> coroutineScopeReading(source: ParcelFileDescriptor, read: suspend (InputStream) -> Unit, body: suspend () -> T): T =
        kotlinx.coroutines.coroutineScope {
            val reader = launch(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(source).use { read(it) } }
            val result = runCatching { body() }
            reader.join()
            result.getOrThrow()
        }

    /** Opens LFM Net's own settings screen (provider, key, proxy): those never come into LFM. */
    fun settingsIntent(): Intent = Intent().setComponent(ComponentName(NetContract.NET_PACKAGE, NetContract.SETTINGS_ACTIVITY))

    private fun Bundle.failure(): String? = if (getBoolean(NetContract.OK)) null else (getString(NetContract.ERROR) ?: "LFM Net failed")

    private suspend fun log(b: Bundle, sent: String, label: () -> String) {
        val entries = b.requests().map { r -> NetLogEntry(at = r.at, host = r.host, url = r.url, label = label(), sent = r.sent.ifEmpty { sent }, bytes = r.bytes, ok = r.ok) }
        if (entries.isNotEmpty()) db.netLog().insertAll(entries)
    }

    private suspend fun <T> call(block: suspend (ILfmNet) -> T): T {
        when (state()) {
            State.NotInstalled -> throw NetException("LFM Net is not installed")
            State.WrongSigner -> throw NetException("LFM Net is signed with another key, so LFM won't use it")
            State.Off -> throw NetException("LFM Net is turned off in Privacy")
            State.On -> Unit
        }
        val s = lock.withLock { calls++; idle?.cancel(); service ?: bind() }
        return try {
            withContext(Dispatchers.IO) { block(s) }
        } catch (e: android.os.RemoteException) {
            // Only a dead helper is let go; a failed transaction (a reply too big) leaves other calls running.
            if (!s.asBinder().isBinderAlive) lock.withLock { drop() }
            throw NetException("LFM Net stopped: ${e.message ?: "try again"}")
        } catch (e: SecurityException) {
            throw NetException("LFM Net refused: ${e.message}")
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
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(NetException("LFM Net refused the connection")) }
        }
        val intent = Intent(NetContract.ACTION_BIND).setPackage(NetContract.NET_PACKAGE)
        val ok = try { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (e: SecurityException) {
            throw NetException("LFM Net refused: ${e.message}")
        }
        if (!ok) { runCatching { context.unbindService(conn) }; throw NetException("Could not reach LFM Net") }
        connection = conn
        return try { withTimeout(10_000) { ready.await() } } catch (e: Exception) { drop(); throw NetException("LFM Net did not answer") }
    }

    private fun drop() {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }
}
