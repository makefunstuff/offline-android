package com.lfmpixel.lfm.net

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * The network calls behind [Network]. Each returns what it sent, so Wren can keep the network log.
 * No cookies, no cache, no referrer; the user agent of a current Chrome on Android (spec section 4).
 */
class NetEngine(
    private val config: () -> NetConfig,
    private val wikipediaBase: (String) -> String = { lang -> "https://$lang.wikipedia.org" },
    private val downloadUrl: (PinnedFiles.File) -> String = { it.url },
    private val downloadAllowed: (HttpUrl) -> Boolean = { it.isHttps && isHuggingFace(it.host) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Edge mode: which server addresses may be opened (tests also allow loopback). */
    private val edgeAddressAllowed: (InetAddress) -> Boolean = EdgeRules::isPrivate,
    /** Edge mode: the server saved in Wren's Settings, the only one used for more than a test. */
    private val edgePinned: () -> String? = { null },
    /** Offline: true refuses every request before it connects, and stops one under way at its next piece. */
    private val blocked: () -> Boolean = { false },
) {
    data class SearchReply(val provider: String, val hits: List<SearchHit>, val log: List<NetRequest>, val error: String?)
    data class FetchReply(val text: String, val log: List<NetRequest>, val error: String?)
    data class DownloadReply(val bytes: Long, val log: List<NetRequest>, val error: String?, val noWifi: Boolean = false)

    /** Addresses from the latest search results: the only ones [fetch] will open. */
    @Volatile var latest: Set<String> = emptySet()
        private set

    /** Records every host a request touches, redirects included. */
    private class Hops : Interceptor {
        val hosts = mutableListOf<HttpUrl>()
        override fun intercept(chain: Interceptor.Chain): Response {
            synchronized(hosts) { hosts += chain.request().url }
            return chain.proceed(chain.request())
        }
    }

    private fun client(cfg: NetConfig, hops: Hops, allowed: ((HttpUrl) -> Boolean)? = null, readTimeoutS: Long = 20, via: Via? = null): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(readTimeoutS, TimeUnit.SECONDS)
            .callTimeout(if (readTimeoutS > 60) 0 else 45, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .addInterceptor(offlineGate)
            .addNetworkInterceptor(hops)
        if (via != null) b.socketFactory(via.sockets).dns(via.dns)
        if (allowed != null) b.addNetworkInterceptor { chain ->
            val url = chain.request().url
            if (!allowed(url)) throw IOException("Refused to go to ${url.host}")
            chain.proceed(chain.request())
        }
        proxyOf(cfg.proxy)?.let { b.proxy(it) }
        return b.build()
    }

    /** Every request, redirects included, first asks whether Wren is offline. */
    private val offlineGate = Interceptor { chain ->
        if (blocked()) throw IOException(OFFLINE)
        chain.proceed(chain.request())
    }

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("User-Agent", USER_AGENT)
        .header("Accept-Language", "en")

    fun search(query: String): SearchReply {
        val cfg = config()
        val q = query.replace(Regex("\\s+"), " ").trim().take(300)
        if (q.isEmpty()) return SearchReply(cfg.label, emptyList(), emptyList(), "Empty search")
        if (!cfg.ready) return SearchReply("", emptyList(), emptyList(), "No search provider is set up in Settings → Web search")
        val log = mutableListOf<NetRequest>()
        val web = mutableListOf<SearchHit>()
        val wiki = mutableListOf<SearchHit>()
        val errors = mutableListOf<String>()
        if (cfg.webReady) {
            val url = when (cfg.provider) {
                WebProvider.SearXNG -> Providers.searxng(cfg.searxngUrl, q)
                WebProvider.Brave -> Providers.brave(q)
                WebProvider.Mojeek -> Providers.mojeek(q, cfg.mojeekKey)
                WebProvider.None -> null
            }
            if (url == null) errors += "The SearXNG address doesn't look right" else {
                val r = request(url).header("Accept", "application/json")
                if (cfg.provider == WebProvider.SearXNG && cfg.searxngUser.isNotEmpty()) r.header("Authorization", Credentials.basic(cfg.searxngUser, cfg.searxngPassword))
                if (cfg.provider == WebProvider.Brave) r.header("X-Subscription-Token", cfg.braveKey)
                runCatching {
                    val body = get(cfg, r.build(), q, log, 2_000_000)
                    when (cfg.provider) {
                        WebProvider.SearXNG -> Providers.parseSearxng(body)
                        WebProvider.Brave -> Providers.parseBrave(body)
                        else -> Providers.parseMojeek(body)
                    }
                }.onSuccess { web += it }.onFailure { errors += "${cfg.provider.label}: ${it.message ?: "failed"}" }
            }
        }
        if (cfg.wikipedia) {
            val base = wikipediaBase(cfg.wikiLang.ifBlank { "en" })
            // Keep articles whose title shares a word with the query; the rest are usually far off.
            runCatching { Providers.parseWikipedia(get(cfg, request(Providers.wikipedia(base, q)).build(), q, log, 1_000_000), base).filter { Providers.related(it.title, q) } }
                .onSuccess { wiki += it }
                .onFailure { errors += "Wikipedia: ${it.message ?: "failed"}" }
        }
        // Wikipedia matches words, not meaning: one article next to web results, more only on its own.
        val hits = (web.distinctBy { it.url }.take(if (wiki.isEmpty()) 6 else 5) + wiki.take(if (web.isEmpty()) 5 else 1))
        latest = hits.map { it.url }.toSet()
        val error = if (hits.isEmpty() && errors.isNotEmpty()) errors.joinToString("; ") else null
        return SearchReply(cfg.label, hits, log, error)
    }

    fun fetch(url: String): FetchReply {
        if (url !in latest) return FetchReply("", emptyList(), "Only addresses from the latest search results can be opened")
        val u = url.toHttpUrlOrNull() ?: return FetchReply("", emptyList(), "Not a web address")
        val log = mutableListOf<NetRequest>()
        return try {
            val body = get(config(), request(u).header("Accept", "text/html,application/xhtml+xml;q=0.9,text/plain;q=0.8").build(), "", log, 3_000_000,
                types = listOf("text/html", "application/xhtml+xml", "text/plain"))
            FetchReply(Html.slim(body), log, null)
        } catch (e: Exception) {
            FetchReply("", log, e.message ?: "Could not open the page")
        }
    }

    /** GETs [r], reading at most [max] bytes; logs each host it touched. */
    private fun get(cfg: NetConfig, r: Request, sent: String, log: MutableList<NetRequest>, max: Int, types: List<String>? = null): String {
        val hops = Hops()
        val at = clock()
        var bytes = 0L
        var ok = false
        try {
            client(cfg, hops).newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body
                val type = body.contentType()
                if (types != null && type != null && types.none { "${type.type}/${type.subtype}".equals(it, true) }) throw IOException("Not a web page ($type)")
                val src = body.source()
                src.request(max.toLong())
                val buf = src.buffer
                val n = minOf(buf.size, max.toLong())
                bytes = n
                ok = true
                return buf.readString(n, type?.charset() ?: Charsets.UTF_8)
            }
        } finally {
            logHops(hops, at, sent, bytes, ok, log, r.url)
        }
    }

    private fun logHops(hops: Hops, at: Long, sent: String, bytes: Long, ok: Boolean, log: MutableList<NetRequest>, first: HttpUrl) {
        // A request that failed before it reached the server (no answer, DNS, TLS) was still attempted: log it.
        val urls = synchronized(hops.hosts) { hops.hosts.toList() }.ifEmpty { listOf(first) }
        urls.forEachIndexed { i, u ->
            val last = i == urls.lastIndex
            log += NetRequest(at, u.host, Providers.redact(u), sent, if (last) bytes else 0, if (last) ok else true)
        }
    }

    /**
     * Streams a pinned file into [out] from [offset]. Only names on the pinned list, only from Hugging Face over https;
     * Wren checks the SHA-256 once it has the whole file.
     */
    /** A particular network to use (Wi-Fi only): its sockets and its DNS. */
    class Via(val sockets: javax.net.SocketFactory, val dns: okhttp3.Dns)

    fun download(name: String, offset: Long, out: OutputStream, via: Via? = null): DownloadReply {
        val file = PinnedFiles.byName(name) ?: return DownloadReply(0, emptyList(), "$name is not a pinned model file")
        if (offset < 0 || offset >= file.size) return DownloadReply(0, emptyList(), "Bad offset")
        val url = downloadUrl(file).toHttpUrlOrNull() ?: return DownloadReply(0, emptyList(), "Bad address")
        val cfg = config()
        val hops = Hops()
        val log = mutableListOf<NetRequest>()
        val at = clock()
        var bytes = 0L
        var error: String? = null
        try {
            val r = request(url).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client(cfg, hops, downloadAllowed, readTimeoutS = 120, via = via).newCall(r).execute().use { resp ->
                when {
                    !resp.isSuccessful -> throw IOException("HTTP ${resp.code}")
                    offset > 0 && resp.code != 206 -> throw IOException("The server can't resume this download")
                }
                val len = resp.body.contentLength()
                if (len >= 0 && len != file.size - offset) throw IOException("Unexpected size: $len bytes, expected ${file.size - offset}")
                val src = resp.body.source()
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (bytes + n > file.size - offset) throw IOException("More data than the pinned size")
                    if (blocked()) throw IOException(OFFLINE)
                    try { out.write(buf, 0, n) } catch (e: IOException) { throw StoppedException() }
                    bytes += n
                }
                out.flush()
            }
        } catch (e: StoppedException) {
            error = "Stopped"
        } catch (e: Exception) {
            error = e.message ?: "Download failed"
        }
        logHops(hops, at, name, bytes, error == null, log, url)
        return DownloadReply(bytes, log, error)
    }

    private class StoppedException : IOException("Stopped")

    data class EdgeReply(val status: Int, val bytes: Long, val log: List<NetRequest>, val error: String?)

    /**
     * Edge mode: one request to the owner's server. Only [EdgeRules.PATHS], only a private or tailnet address (a
     * name must resolve to such addresses only), no redirects, no proxy, no cookies. The reply body goes to [out]
     * as it comes; [out] returning false stops it. A reply that is not 2xx is read (up to 4 KB) into the error.
     */
    fun edge(base: String, key: String, method: String, path: String, contentType: String?, body: InputStream?,
             onCall: (okhttp3.Call) -> Unit = {}, out: (ByteArray) -> Boolean): EdgeReply {
        val sentBytes = body?.use { it.readNBytes(MAX_EDGE_BODY + 1) }
        val b = base.trim().trimEnd('/').toHttpUrlOrNull() ?: return EdgeReply(0, 0, emptyList(), "The server address doesn't look right")
        // Only the saved server, but for Settings' Test, which may try a new address (health and the model list only).
        val pinned = edgePinned()?.trim()?.trimEnd('/')
        if (path !in TEST_PATHS && pinned != base.trim().trimEnd('/')) return EdgeReply(0, 0, emptyList(), "That isn't the server saved in Settings")
        val only = path.substringBefore('?')
        val query = path.substringAfter('?', "").takeIf { '?' in path }
        // A path with queries (the warm call) is sent only with one of them, and only as a POST.
        val wantsQuery = only in EdgeRules.QUERIES
        if (only !in EdgeRules.PATHS || (query != null && query !in EdgeRules.QUERIES[only].orEmpty()) ||
            (wantsQuery && (query == null || method != "POST")))
            return EdgeReply(0, 0, emptyList(), "Wren doesn't open $path on the server")
        if (method != "GET" && method != "POST") return EdgeReply(0, 0, emptyList(), "Bad method")
        EdgeRules.literal(b.host)?.let { if (!edgeAddressAllowed(it)) return EdgeReply(0, 0, emptyList(), "${b.host} is not on your home network or tailnet") }
        EdgeRules.refuse(b.host)?.takeIf { EdgeRules.literal(b.host) == null }?.let { return EdgeReply(0, 0, emptyList(), it) }
        val url = b.newBuilder().addPathSegments(only.removePrefix("/")).encodedQuery(query).build()
        if (sentBytes != null && sentBytes.size > MAX_EDGE_BODY) return EdgeReply(0, 0, emptyList(), "Too much to send in one request")
        val hops = Hops()
        val at = clock()
        var bytes = 0L
        var status = 0
        var error: String? = null
        try {
            val r = Request.Builder().url(url).header("Authorization", "Bearer $key").header("Accept-Encoding", "identity")
                // llama-server closes each connection after its reply (it says so): never reuse one.
                .header("Connection", "close")
            if (method == "POST") r.post((sentBytes ?: ByteArray(0)).toRequestBody(contentType?.toMediaTypeOrNull())) else r.get()
            edgeClient().newBuilder().addNetworkInterceptor(hops).build().newCall(r.build()).also(onCall).execute().use { resp ->
                status = resp.code
                val src = resp.body.source()
                if (!resp.isSuccessful) {
                    src.request(4096)
                    val text = src.buffer.readUtf8(minOf(src.buffer.size, 4096))
                    // llama-server's {"error":{"message":…}}, else the start of what it said.
                    val msg = Regex(""""message"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1) ?: text.take(200)
                    throw IOException("HTTP ${resp.code}${if (msg.isBlank()) "" else ": $msg"}")
                }
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    bytes += n
                    if (blocked()) throw IOException(OFFLINE)
                    if (!out(buf.copyOf(n))) throw StoppedException()
                }
            }
        } catch (e: StoppedException) {
            error = "Stopped"
        } catch (e: Exception) {
            error = e.message ?: "The server did not answer"
        }
        val sent = "${path.removePrefix("/")}${if (sentBytes != null) " · ${sentBytes.size} bytes sent" else ""}"
        val log = mutableListOf<NetRequest>()
        logHops(hops, at, sent, bytes, error == null || error == "Stopped", log, url)
        return EdgeReply(status, bytes, log, error)
    }

    /** No proxy (the server is on the owner's own network), no redirects, and a DNS that only answers private addresses. */
    private fun edgeClient(): OkHttpClient = edgeBase ?: OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .addInterceptor(offlineGate)
        // No Nagle: a POST's body goes out right behind its headers, not after the server's delayed ACK (that wait
        // was half a second a request on the emulator).
        .socketFactory(NoDelaySockets)
        .followRedirects(false).followSslRedirects(false)
        // A connection the server already closed is retried once on a fresh one, before anything was sent.
        .retryOnConnectionFailure(true)
        .connectTimeout(4, TimeUnit.SECONDS)
        // A long document or answer streams for minutes; between pieces the server is never silent this long.
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val all = okhttp3.Dns.SYSTEM.lookup(hostname)
                val bad = all.filterNot(edgeAddressAllowed)
                if (bad.isNotEmpty() || all.isEmpty())
                    throw IOException("$hostname is not on your home network or tailnet (${bad.firstOrNull()?.hostAddress ?: "no address"})")
                return all
            }
        })
        .build().also { edgeBase = it }

    @Volatile private var edgeBase: OkHttpClient? = null

    private object NoDelaySockets : javax.net.SocketFactory() {
        private val d = javax.net.SocketFactory.getDefault()
        private fun java.net.Socket.nd() = apply { tcpNoDelay = true }
        override fun createSocket() = d.createSocket().nd()
        override fun createSocket(host: String, port: Int) = d.createSocket(host, port).nd()
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = d.createSocket(host, port, local, localPort).nd()
        override fun createSocket(host: InetAddress, port: Int) = d.createSocket(host, port).nd()
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) = d.createSocket(host, port, local, localPort).nd()
    }

    companion object {
        /** The error every request gets while Wren is offline. */
        const val OFFLINE = "Wren is offline"
        /** The most one Edge request may send: a long recording or a few pictures. */
        const val MAX_EDGE_BODY = 32 * 1024 * 1024
        /** What Settings' Test asks of an address not saved yet. */
        val TEST_PATHS = setOf("/health", "/v1/models")

        /** A current Chrome on Android, reduced the way Chrome itself sends it: looks like any phone's. */
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"

        /** huggingface.co and its download CDN (hf.co hosts). */
        fun isHuggingFace(host: String) = host == "huggingface.co" || host.endsWith(".huggingface.co") || host == "hf.co" || host.endsWith(".hf.co")

        fun proxyOf(s: String): Proxy? {
            val t = s.trim().removePrefix("socks5://").removePrefix("socks://")
            if (t.isEmpty()) return null
            val host = t.substringBeforeLast(':')
            // Out of range ("host:90500", typed a digit too many) is not a proxy: createUnresolved would throw, and this
            // is read while Settings draws.
            val port = t.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            if (host.isBlank()) return null
            // Unresolved: the proxy looks the name up, so DNS goes through it too (Orbot, Tor).
            return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
        }
    }
}
