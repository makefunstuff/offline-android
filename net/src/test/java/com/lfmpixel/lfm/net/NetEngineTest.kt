package com.lfmpixel.lfm.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** The network module's calls against a local server: what is sent, what is refused, what is logged. */
class NetEngineTest {
    private val server = MockWebServer()
    private var cfg = NetConfig()

    @Before fun start() { server.start() }
    @After fun stop() { server.close() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun engine(file: PinnedFiles.File? = null) = NetEngine({ cfg }, wikipediaBase = { base() },
        downloadUrl = { base() + "/resolve/" + it.name }, downloadAllowed = { it.host == server.hostName })

    private val searx = """{"query":"x","results":[
        {"url":"https://www.checkatrade.com/prices","title":"Plumbing prices in Leeds, 2026 guide","content":"Most charge &pound;60&ndash;&pound;95 <b>call-out</b>"},
        {"url":"https://www.which.co.uk/plumber","title":"How much does a plumber cost?","content":"Typical fees"},
        {"url":"not a url","title":"broken","content":""}]}"""

    @Test fun searxngAndWikipediaAreAskedAndLogged() {
        cfg = NetConfig(provider = WebProvider.SearXNG, searxngUrl = base(), searxngUser = "me", searxngPassword = "pw", wikipedia = true)
        server.enqueue(MockResponse.Builder().body(searx).build())
        server.enqueue(MockResponse.Builder().body("""{"query":{"search":[{"title":"Plumbing","snippet":"<span class=\"searchmatch\">Plumbing</span> is"}]}}""").build())
        val r = engine().search("plumber  call-out fee Leeds 2026")
        assertNull(r.error)
        assertEquals("your SearXNG + Wikipedia", r.provider)
        assertEquals(listOf("web", "web", "wikipedia"), r.hits.map { it.source })
        assertEquals("Most charge £60–£95 call-out", r.hits[0].snippet)
        assertEquals("Plumbing is", r.hits[2].snippet)
        assertEquals(base() + "/wiki/Plumbing", r.hits[2].url)

        val first = server.takeRequest()
        assertEquals("plumber call-out fee Leeds 2026", first.url.queryParameter("q"))
        assertEquals("json", first.url.queryParameter("format"))
        assertTrue(first.headers["Authorization"]!!.startsWith("Basic "))
        assertEquals(NetEngine.USER_AGENT, first.headers["User-Agent"])
        assertNull("no cookies", first.headers["Cookie"])
        assertNull("no referrer", first.headers["Referer"])
        val wiki = server.takeRequest()
        assertEquals("plumber call-out fee Leeds 2026", wiki.url.queryParameter("srsearch"))
        assertNull("Wikipedia gets no password", wiki.headers["Authorization"])

        assertEquals(2, r.log.size)
        assertTrue(r.log.all { it.sent == "plumber call-out fee Leeds 2026" && it.ok && it.bytes > 0 })
    }

    @Test fun fetchOpensOnlyLatestResults() {
        cfg = NetConfig(provider = WebProvider.SearXNG, searxngUrl = base(), wikipedia = false)
        val page = base() + "/page"
        server.enqueue(MockResponse.Builder().body("""{"results":[{"url":"$page","title":"A page","content":""}]}""").build())
        val e = engine()
        e.search("anything")
        // A made-up address (say, one a web page told the model to open, with notes in it) is refused before any request.
        val refused = e.fetch(base() + "/page?secret=journal+text")
        assertNotNull(refused.error)
        assertTrue(refused.log.isEmpty())
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8")
            .body("<html><head><script>var x=1</script><style>p{}</style></head><body><article><p onclick=\"x()\">Hello <b>there</b></p></article></body></html>").build())
        val ok = e.fetch(page)
        assertNull(ok.error)
        assertFalse(ok.text.contains("var x"))
        assertFalse(ok.text.contains("onclick"))
        assertTrue(ok.text.contains("<article><p>Hello <b>there</b></p></article>"))
        assertEquals(server.hostName, ok.log.single().host)
    }

    @Test fun fetchRefusesWhatIsNotAPage() {
        cfg = NetConfig(provider = WebProvider.SearXNG, searxngUrl = base(), wikipedia = false)
        val pdf = base() + "/file.zip"
        server.enqueue(MockResponse.Builder().body("""{"results":[{"url":"$pdf","title":"A file","content":""}]}""").build())
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/zip").body("PK").build())
        val e = engine()
        e.search("x")
        assertNotNull(e.fetch(pdf).error)
    }

    @Test fun noProviderMeansNoRequest() {
        cfg = NetConfig(provider = WebProvider.None, wikipedia = false)
        assertNotNull(engine().search("x").error)
        assertEquals(0, server.requestCount)
    }

    @Test fun mojeekKeyNeverReachesTheLog() {
        val url = Providers.mojeek("cats", "SECRET123")
        assertEquals("SECRET123", url.queryParameter("api_key"))
        assertFalse(Providers.redact(url).contains("SECRET123"))
    }

    @Test fun brokenEntitiesAndPasswordsDontBreakOrLeak() {
        assertEquals("a &#x110000; b", Html.text("a &#x110000; b"))
        assertFalse(Providers.redact("https://me:secret@searx.lan/search?q=x".toHttpUrl()).contains("secret"))
        assertFalse("Wikipedia stays off until chosen", NetConfig().ready)
    }

    @Test fun providerJsonIsRead() {
        val brave = Providers.parseBrave("""{"web":{"results":[{"title":"T <strong>x</strong>","url":"https://a.example/1","description":"D"}]}}""")
        assertEquals("T x", brave.single().title)
        val mojeek = Providers.parseMojeek("""{"response":{"results":[{"title":"M","url":"https://b.example/","desc":"d"}]}}""")
        assertEquals("https://b.example/", mojeek.single().url)
        assertEquals("https://h.example:8888/search?q=a%20b&format=json", Providers.searxng("https://h.example:8888/", "a b").toString())
        assertEquals("https://searx.lan/search?q=a&format=json", Providers.searxng("searx.lan", "a").toString())
    }

    @Test fun downloadsOnlyPinnedFilesAtTheirSize() {
        val small = PinnedFiles.all.minBy { it.size }
        assertNotNull(engine().download("../../etc/passwd", 0, ByteArrayOutputStream()).error)
        assertNotNull(engine().download("my-journal.gguf", 0, ByteArrayOutputStream()).error)
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse.Builder().body("too short").build())
        val r = engine().download(small.name, 0, ByteArrayOutputStream())
        assertTrue(r.error!!.startsWith("Unexpected size"))
        val req = server.takeRequest()
        assertEquals("/resolve/" + small.name, req.url.encodedPath)
        assertNull(req.headers["Range"])
    }

    @Test fun resumeAsksForTheRestAndPauseStops() {
        val small = PinnedFiles.all.minBy { it.size }
        server.enqueue(MockResponse.Builder().code(206).chunkedBody("x".repeat(10), 5).build())
        val closed = object : OutputStream() { override fun write(b: Int) = throw IOException("EPIPE") }
        val r = engine().download(small.name, 100, closed)
        assertEquals("bytes=100-", server.takeRequest().headers["Range"])
        assertEquals("Stopped", r.error)
    }

    @Test fun downloadRefusesOtherHosts() {
        val small = PinnedFiles.all.minBy { it.size }
        val strict = NetEngine({ cfg }, downloadUrl = { base() + "/" + it.name })
        assertTrue(strict.download(small.name, 0, ByteArrayOutputStream()).error!!.startsWith("Refused"))
        assertEquals(0, server.requestCount)
        assertTrue(NetEngine.isHuggingFace("huggingface.co"))
        assertTrue(NetEngine.isHuggingFace("cas-bridge.xethub.hf.co"))
        assertFalse(NetEngine.isHuggingFace("huggingface.co.evil.example"))
        assertFalse(NetEngine.isHuggingFace("nothf.co"))
    }

    @Test fun proxyIsResolvedByTheProxy() {
        val p = NetEngine.proxyOf("127.0.0.1:9050")!!
        assertEquals(java.net.Proxy.Type.SOCKS, p.type())
        assertNull(NetEngine.proxyOf(""))
        assertNull(NetEngine.proxyOf("nonsense"))
        // A port out of range, typed in Settings, is no proxy (it used to throw while Settings drew, every time).
        assertNull(NetEngine.proxyOf("127.0.0.1:90500"))
        assertNull(NetEngine.proxyOf("host:-1"))
        assertNull(NetEngine.proxyOf("host:0"))
        assertNull(NetEngine.proxyOf(":9050"))
        assertEquals("https://x.example/", "https://x.example/".toHttpUrl().toString())
    }

    @Test fun offlineSendsNothing() {
        cfg = NetConfig(provider = WebProvider.SearXNG, searxngUrl = base(), wikipedia = true)
        var offline = true
        val e = NetEngine({ cfg }, wikipediaBase = { base() }, downloadUrl = { base() + "/resolve/" + it.name },
            downloadAllowed = { it.host == server.hostName }, edgeAddressAllowed = { it.isLoopbackAddress }, edgePinned = { base() },
            blocked = { offline })
        val s = e.search("weather")
        assertTrue(s.hits.isEmpty())
        assertTrue(s.error!!.contains(NetEngine.OFFLINE))
        val d = e.download(PinnedFiles.all.first().name, 0, ByteArrayOutputStream())
        assertEquals(NetEngine.OFFLINE, d.error)
        val edge = e.edge(base(), "k", "GET", "/health", null, null) { true }
        assertEquals(NetEngine.OFFLINE, edge.error)
        assertEquals(0, server.requestCount)

        // Turned on mid-download: it stops at the next piece.
        offline = false
        val file = PinnedFiles.byName("d1-omni-600M_tokenizer.json")!! // 4.7 MB: many pieces
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(ByteArray(file.size.toInt()))).build())
        val out = object : OutputStream() {
            var n = 0L
            override fun write(b: Int) { n++ }
            override fun write(b: ByteArray, off: Int, len: Int) { n += len; offline = true }
        }
        val cut = e.download(file.name, 0, out)
        assertEquals(NetEngine.OFFLINE, cut.error)
        assertTrue(out.n < file.size)
    }
}
