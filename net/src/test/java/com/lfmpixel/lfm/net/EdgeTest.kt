package com.lfmpixel.lfm.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/** Edge mode in the network module: only the owner's server, only its paths, the key sent, the reply streamed and logged. */
class EdgeTest {
    private val server = MockWebServer()

    @Before fun start() { server.start() }
    @After fun stop() { server.close() }

    private fun base() = server.url("/").toString().trimEnd('/')
    // The test server is on loopback; the real rules refuse it (see privateAddresses).
    private fun engine() = NetEngine({ NetConfig(proxy = "127.0.0.1:9") }, edgeAddressAllowed = { it.isLoopbackAddress }, edgePinned = { base() })

    @Test fun privateAddresses() {
        for (ok in listOf("10.0.2.2", "192.168.1.138", "172.16.0.1", "172.31.255.1", "100.124.77.61", "100.64.0.1", "fd7a:115c:a1e0::1"))
            assertTrue(ok, EdgeRules.isPrivate(InetAddress.getByName(ok)))
        for (no in listOf("8.8.8.8", "127.0.0.1", "172.32.0.1", "100.128.0.1", "169.254.1.1", "1.1.1.1", "2001:db8::1", "::1"))
            assertFalse(no, EdgeRules.isPrivate(InetAddress.getByName(no)))
        assertNull(EdgeRules.refuse("100.124.77.61"))
        assertNull(EdgeRules.refuse("beefy-machine.tail1234.ts.net"))
        assertNotNull(EdgeRules.refuse("8.8.8.8"))
        assertNotNull(EdgeRules.refuse("bad host/x"))
        assertNotNull(EdgeRules.refuse(""))
    }

    @Test fun streamsTheReplyWithTheKeyAndLogsIt() {
        server.enqueue(MockResponse.Builder().body("data: {\"a\":1}\n\ndata: [DONE]\n\n").build())
        val got = ByteArrayOutputStream()
        val r = engine().edge(base(), "sekrit", "POST", "/v1/completions", "application/json", "{\"prompt\":\"hi\"}".byteInputStream()) { got.write(it); true }
        assertNull(r.error)
        assertEquals(200, r.status)
        assertEquals("data: {\"a\":1}\n\ndata: [DONE]\n\n", got.toString())
        val req = server.takeRequest()
        assertEquals("Bearer sekrit", req.headers["Authorization"])
        assertEquals("/v1/completions", req.url.encodedPath)
        assertEquals("{\"prompt\":\"hi\"}", req.body?.utf8())
        assertNull("no cookies", req.headers["Cookie"])
        assertEquals(1, r.log.size)
        assertTrue(r.log[0].sent.startsWith("v1/completions · 15 bytes sent"))
        assertFalse("the key is not in the log", r.log[0].toString().contains("sekrit"))
    }

    @Test fun refusesOtherPathsPublicAddressesAndRedirects() {
        val e = NetEngine({ NetConfig() })
        assertNotNull(e.edge("http://8.8.8.8:18101", "k", "GET", "/health", null, null) { true }.error)
        assertNotNull(e.edge("http://127.0.0.1:18101", "k", "GET", "/health", null, null) { true }.error)
        assertNotNull(engine().edge(base(), "k", "GET", "/slots", null, null) { true }.error)
        assertNotNull(engine().edge(base(), "k", "DELETE", "/health", null, null) { true }.error)
        assertEquals("nothing reached the server", 0, server.requestCount)
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "https://example.com/").build())
        val r = engine().edge(base(), "k", "GET", "/health", null, null) { true }
        assertNotNull("a redirect is not followed", r.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun onlyTheSavedServerBeyondATest() {
        // Another private address: Settings' Test may ask its health and models, nothing more.
        val other = NetEngine({ NetConfig() }, edgeAddressAllowed = { it.isLoopbackAddress }, edgePinned = { "http://127.0.0.1:1" })
        val r = other.edge(base(), "k", "POST", "/v1/completions", "application/json", "{}".byteInputStream()) { true }
        assertEquals("That isn't the server saved in Settings", r.error)
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse.Builder().body("{}").build())
        assertNull(other.edge(base(), "k", "GET", "/v1/models", null, null) { true }.error)
    }

    @Test fun errorsAndStop() {
        server.enqueue(MockResponse.Builder().code(401).body("{\"error\":{\"code\":401,\"message\":\"Invalid API Key\"}}").build())
        val r = engine().edge(base(), "wrong", "GET", "/v1/models", null, null) { true }
        assertEquals(401, r.status)
        assertEquals("HTTP 401: Invalid API Key", r.error)
        server.enqueue(MockResponse.Builder().body("x".repeat(100_000)).build())
        var pieces = 0
        val s = engine().edge(base(), "k", "GET", "/v1/models", null, null) { pieces++; false }
        assertEquals("Stopped", s.error)
        assertEquals(1, pieces)
    }

    @Test fun theGatewaysWarmCallOnly() {
        server.enqueue(MockResponse.Builder().code(202).body("{\"mode\":\"voice\"}").build())
        val r = engine().edge(base(), "inkling-key", "POST", EdgeRules.WARM, null, null) { true }
        assertNull(r.error)
        assertEquals(202, r.status)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/route/warm", req.url.encodedPath)
        assertEquals("mode=voice", req.url.encodedQuery)
        assertEquals("Bearer inkling-key", req.headers["Authorization"])
        // No other query, on it or on any other path.
        assertNotNull(engine().edge(base(), "k", "POST", "/route/warm?mode=edge", null, null) { true }.error)
        assertNotNull(engine().edge(base(), "k", "GET", "/v1/models?x=1", null, null) { true }.error)
        assertNotNull(engine().edge(base(), "k", "POST", "/route/swap", null, null) { true }.error)
        assertEquals(1, server.requestCount)
    }
}
