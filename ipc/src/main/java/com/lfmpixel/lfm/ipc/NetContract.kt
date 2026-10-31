package com.lfmpixel.lfm.ipc

import android.os.Bundle

/** Names both apps agree on. LFM Net's service is guarded by [PERMISSION], a signature permission. */
object NetContract {
    const val NET_PACKAGE = "com.lfmpixel.lfm.net"
    const val LFM_PACKAGE = "com.lfmpixel.lfm"
    const val ACTION_BIND = "com.lfmpixel.lfm.net.BIND"
    const val PERMISSION = "com.lfmpixel.lfm.permission.LFM_NET"
    const val SETTINGS_ACTIVITY = "com.lfmpixel.lfm.net.SettingsActivity"

    const val OK = "ok"
    const val ERROR = "error"
    const val CODE = "code"                  // machine-readable error: NO_WIFI
    const val NO_WIFI = "no-wifi"
    const val PROVIDER = "provider"          // "your SearXNG", "Brave", "Mojeek", "" when none
    const val WIKIPEDIA = "wikipedia"
    const val READY = "ready"                // at least one provider is set up
    const val PROXY = "proxy"                // requests go through a SOCKS proxy
    const val HITS = "hits"
    const val TEXT = "text"
    const val BYTES = "bytes"
    const val LOG = "log"
    const val STATUS = "status"              // Edge mode: the server's HTTP status, 0 if it was never reached
}

/** One search result. [source] is "web" or "wikipedia". */
data class SearchHit(val title: String, val url: String, val snippet: String, val source: String) {
    fun toBundle() = Bundle().apply {
        putString("title", title); putString("url", url); putString("snippet", snippet); putString("source", source)
    }

    companion object {
        fun from(b: Bundle) = SearchHit(b.getString("title").orEmpty(), b.getString("url").orEmpty(),
            b.getString("snippet").orEmpty(), b.getString("source").orEmpty())
    }
}

/** One request LFM Net made: when, to which host, the full address, what it sent beyond the address, bytes received. */
data class NetRequest(val at: Long, val host: String, val url: String, val sent: String, val bytes: Long, val ok: Boolean) {
    fun toBundle() = Bundle().apply {
        putLong("at", at); putString("host", host); putString("url", url); putString("sent", sent); putLong("bytes", bytes); putBoolean("ok", ok)
    }

    companion object {
        fun from(b: Bundle) = NetRequest(b.getLong("at"), b.getString("host").orEmpty(), b.getString("url").orEmpty(),
            b.getString("sent").orEmpty(), b.getLong("bytes"), b.getBoolean("ok"))
    }
}

/** Reads the request log out of any reply. */
@Suppress("DEPRECATION")
fun Bundle.requests(): List<NetRequest> = getParcelableArrayList<Bundle>(NetContract.LOG).orEmpty().map { NetRequest.from(it) }

@Suppress("DEPRECATION")
fun Bundle.hits(): List<SearchHit> = getParcelableArrayList<Bundle>(NetContract.HITS).orEmpty().map { SearchHit.from(it) }
