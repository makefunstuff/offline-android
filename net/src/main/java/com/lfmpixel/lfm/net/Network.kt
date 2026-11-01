package com.lfmpixel.lfm.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Is a provider set up, which one, Wikipedia on or off, through a proxy or not. */
data class NetInfo(val ready: Boolean, val provider: String, val wikipedia: Boolean, val proxy: Boolean)

/**
 * The whole network side of Inkling, and the only code in it allowed to open a connection (docs/spec.md section 3;
 * :app:checkDebugNetworkConfinement fails the build otherwise). Four calls: [search], [fetch] (only an address from
 * the latest results), [download] (only a pinned model from Hugging Face) and [edge] (only the owner's server on a
 * private or tailnet address). Each returns the requests it made, for the network log. While [offline] says so,
 * every request is refused before it opens a socket, and one under way stops at its next piece.
 */
class Network(context: Context, private val offline: () -> Boolean) {
    private val app = context.applicationContext
    val settings = NetSettings(app)

    // One engine, so the latest results outlive a screen.
    private val engine = NetEngine({ settings.load() }, edgePinned = { settings.pinnedEdge() }, blocked = offline)

    /** Edge requests in flight, by Inkling's id, so Stop and Offline reach one still waiting for the server. */
    private val calls = ConcurrentHashMap<String, okhttp3.Call>()

    fun info(): NetInfo {
        val c = settings.load()
        return NetInfo(c.ready, c.label, c.wikipedia, NetEngine.proxyOf(c.proxy) != null)
    }

    fun search(query: String): NetEngine.SearchReply = engine.search(query)

    fun fetch(url: String): NetEngine.FetchReply = engine.fetch(url)

    /**
     * Streams a pinned model file into [out] from [offset]. With [wifiOnly], only over an unmetered network (Wi-Fi):
     * otherwise, or when Wi-Fi goes away mid-download, the reply has [NetEngine.DownloadReply.noWifi].
     */
    fun download(fileName: String, offset: Long, wifiOnly: Boolean, out: OutputStream): NetEngine.DownloadReply {
        val via = if (wifiOnly) unmetered() ?: return NetEngine.DownloadReply(0, emptyList(), "Waiting for Wi-Fi", noWifi = true) else null
        val r = engine.download(fileName, offset, out, via)
        // Wi-Fi went away mid-download: say so, so Inkling waits for it instead of giving up.
        return if (wifiOnly && r.error != null && r.error != "Stopped" && unmetered() == null) r.copy(noWifi = true) else r
    }

    /** Edge mode: one request to the owner's server; the reply body goes to [out] as it comes, false stops it. */
    fun edge(id: String, baseUrl: String, key: String, method: String, path: String, contentType: String?, body: ByteArray?,
             out: (ByteArray) -> Boolean): NetEngine.EdgeReply = try {
        engine.edge(baseUrl, key, method, path, contentType, body?.inputStream(), onCall = { calls[id] = it }, out = out)
    } finally { calls.remove(id) }

    /** Stops the Edge request [id] at once, even while the server has not answered yet. */
    fun edgeCancel(id: String) { calls.remove(id)?.cancel() }

    /** Offline was switched on: stop every Edge request still waiting (downloads stop at their next piece). */
    fun stopAll() { calls.keys.toList().forEach(::edgeCancel) }

    /** The owner's server, as saved in Settings: the only one Edge requests go to (Settings' Test aside). */
    fun edgePin(baseUrl: String) = settings.pinEdge(baseUrl)

    /** An unmetered network (Wi-Fi, Ethernet) with internet, bound so the download can't fall back to mobile data. */
    @Suppress("DEPRECATION")
    private fun unmetered(): NetEngine.Via? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        val n = nets.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } == true
        } ?: return null
        return NetEngine.Via(n.socketFactory) { host -> n.getAllByName(host).toList() }
    }
}
