package com.lfmpixel.lfm.net

import android.app.Service
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.lfmpixel.lfm.ipc.ILfmNet
import com.lfmpixel.lfm.ipc.NetContract
import com.lfmpixel.lfm.ipc.NetRequest

/**
 * The bound service LFM talks to. Android lets only apps holding the signature permission bind (LFM, signed with
 * the same key); each call checks the caller is LFM itself, too.
 */
class NetService : Service() {
    private val settings by lazy { NetSettings(this) }

    private val binder = object : ILfmNet.Stub() {
        override fun info(): Bundle {
            checkCaller()
            val c = settings.load()
            return Bundle().apply {
                putBoolean(NetContract.READY, c.ready)
                putString(NetContract.PROVIDER, c.label)
                putBoolean(NetContract.WIKIPEDIA, c.wikipedia)
                putBoolean(NetContract.PROXY, NetEngine.proxyOf(c.proxy) != null)
            }
        }

        override fun search(query: String?): Bundle {
            checkCaller()
            val r = engine.search(query.orEmpty())
            return reply(r.error, r.log).apply {
                putString(NetContract.PROVIDER, r.provider)
                putParcelableArrayList(NetContract.HITS, ArrayList(r.hits.map { it.toBundle() }))
            }
        }

        override fun fetch(url: String?): Bundle {
            checkCaller()
            val r = engine.fetch(url.orEmpty())
            return reply(r.error, r.log).apply { putString(NetContract.TEXT, r.text) }
        }

        override fun download(fileName: String?, offset: Long, wifiOnly: Boolean, sink: ParcelFileDescriptor?): Bundle {
            checkCaller()
            if (sink == null) return reply("No sink", emptyList())
            val via = if (wifiOnly) unmetered() ?: return ParcelFileDescriptor.AutoCloseOutputStream(sink).use {
                reply("Waiting for Wi-Fi", emptyList()).apply { putString(NetContract.CODE, NetContract.NO_WIFI) }
            } else null
            val r = ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out -> engine.download(fileName.orEmpty(), offset, out, via) }
            return reply(r.error, r.log).apply {
                putLong(NetContract.BYTES, r.bytes)
                // Wi-Fi went away mid-download: say so, so LFM waits for it instead of giving up.
                if (wifiOnly && r.error != null && r.error != "Stopped" && unmetered() == null) putString(NetContract.CODE, NetContract.NO_WIFI)
            }
        }
    }

    private fun reply(error: String?, log: List<NetRequest>) = Bundle().apply {
        putBoolean(NetContract.OK, error == null)
        error?.let { putString(NetContract.ERROR, it) }
        putParcelableArrayList(NetContract.LOG, ArrayList(log.map { it.toBundle() }))
    }

    /** An unmetered network (Wi-Fi, Ethernet) with internet, bound so the download can't fall back to mobile data. */
    @Suppress("DEPRECATION")
    private fun unmetered(): NetEngine.Via? {
        val cm = getSystemService(ConnectivityManager::class.java)
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        val n = nets.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } == true
        } ?: return null
        return NetEngine.Via(n.socketFactory) { host -> n.getAllByName(host).toList() }
    }

    private fun checkCaller() {
        val pkgs = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (NetContract.LFM_PACKAGE !in pkgs) throw SecurityException("Inkling Net only answers Inkling")
    }

    override fun onBind(intent: Intent): IBinder = binder

    companion object {
        // One engine per process, so the latest results outlive a rebind.
        private var shared: NetEngine? = null
    }

    private val engine: NetEngine
        get() = synchronized(NetService) { shared ?: NetEngine({ settings.load() }).also { shared = it } }
}
