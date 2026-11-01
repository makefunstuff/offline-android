package com.lfmpixel.lfm

import com.lfmpixel.lfm.edge.EdgeModels
import com.lfmpixel.lfm.edge.Gateway
import com.lfmpixel.lfm.edge.GatewayReply
import com.lfmpixel.lfm.edge.QwenState
import com.lfmpixel.lfm.net.EdgeRules
import com.lfmpixel.lfm.web.EDGE_REFUSED
import com.lfmpixel.lfm.web.EDGE_UNREACHABLE
import com.lfmpixel.lfm.web.NetException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The homelab gateway (HOME-8) as Wren uses it, against a fake gateway on virtual time: the warm call once per Talk,
 * the minute-long swap, a refused swap during a game, the port drop near the end of a swap, and a wrong or missing key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GatewayTest {
    /** The gateway: Edge mode until warmed, voice mode (Qwen listed) [swapMs] later; ports drop for the swap's last 8 s. */
    private class FakeGateway(val clock: () -> Long) {
        val key = "inkling-key"
        var sentKey = key
        var swapMs = 60_000L
        var game = false
        var warmAt: Long? = null
        val calls = mutableListOf<String>()
        var dropped = 0
        val warms get() = calls.count { it == "POST ${EdgeRules.WARM}" }
        val polls get() = calls.count { it == "GET /v1/models" }

        private fun dropping(t: Long) = warmAt?.let { t in it + swapMs - 8_000 until it + swapMs } == true

        /** As the network module answers: the reply, or a [NetException] as NetClient throws it. */
        fun raw(method: String, path: String): GatewayReply {
            calls += "$method $path"
            val t = clock()
            if (dropping(t)) dropped++
            if (dropping(t)) throw NetException("Failed to connect to /192.168.1.138:18110", EDGE_UNREACHABLE)
            if (sentKey != key) throw NetException("HTTP 401: Invalid API Key", EDGE_REFUSED)
            return when (path) {
                EdgeRules.WARM -> if (game) throw NetException("HTTP 409: a game is running on GPU 0", EDGE_REFUSED)
                    else { if (warmAt == null) warmAt = t; GatewayReply(202, """{"mode":"edge","target":"voice"}""") }
                "/v1/models" -> {
                    val up = warmAt?.let { t >= it + swapMs } == true
                    val ids = listOf(EdgeModels.TEXT, EdgeModels.VISION, EdgeModels.OMNI) + if (up) listOf(EdgeModels.ANSWER) else emptyList()
                    GatewayReply(200, """{"data":[${ids.joinToString(",") { """{"id":"$it"}""" }}]}""")
                }
                else -> throw NetException("HTTP 404", EDGE_REFUSED)
            }
        }
    }

    private fun TestScope.setUp(): Pair<FakeGateway, Gateway> {
        val now = { testScheduler.currentTime }
        val fake = FakeGateway(now)
        // As Edge.gatewaySend: through Edge.request's retry, the error's HTTP status, 0 when not reached.
        val send: suspend (String, String) -> GatewayReply = { m, p ->
            try {
                Gateway.throughSwap(now, 15_000, { e -> Gateway.isDrop(e) && (p != EdgeRules.WARM || e.code == EDGE_UNREACHABLE) }) { fake.raw(m, p) }
            } catch (e: NetException) {
                GatewayReply(if (e.code == EDGE_UNREACHABLE) 0 else Gateway.httpCode(e) ?: 0, e.message.orEmpty())
            }
        }
        return fake to Gateway(backgroundScope, send, { EdgeModels.ANSWER }, now)
    }

    @Test fun warmCallOncePerTalkSession() = runTest {
        val (fake, gw) = setUp()
        gw.talkOpened()
        gw.talkOpened()  // paused and resumed: the same Talk
        runCurrent()
        assertEquals(1, fake.warms)
        assertTrue(gw.state.value is QwenState.Waking)
        advanceTimeBy(70_000); runCurrent()
        assertEquals(QwenState.Ready, gw.state.value)
        // Qwen is up: chat asks for nothing more.
        gw.wantQwen(); runCurrent()
        assertEquals(1, fake.warms)
        // A new Talk session, with the server back in Edge mode: asked again.
        gw.talkClosed()
        gw.listed(listOf(EdgeModels.TEXT))
        assertEquals(QwenState.Idle, gw.state.value)
        gw.talkOpened(); runCurrent()
        assertEquals(2, fake.warms)
    }

    @Test fun waitsOutTheSwapWithBackoff() = runTest {
        val (fake, gw) = setUp()
        gw.talkOpened(); runCurrent()
        advanceTimeBy(50_000); runCurrent()
        assertTrue("still waking before a minute", gw.state.value is QwenState.Waking)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(QwenState.Ready, gw.state.value)
        // A growing pause: a handful of model lists in 80 s, not one a second.
        assertTrue("polls: ${fake.polls}", fake.polls in 3..14)
        val after = fake.calls.size
        advanceTimeBy(600_000); runCurrent()
        assertEquals("nothing more once Qwen is up", after, fake.calls.size)
    }

    @Test fun refusedSwapDuringAGame() = runTest {
        val (fake, gw) = setUp()
        fake.game = true
        gw.talkOpened(); runCurrent()
        val st = gw.state.value
        assertTrue("$st", st is QwenState.Busy && st.reason == "a game is running on GPU 0")
        advanceTimeBy(600_000); runCurrent()
        assertEquals("one warm call, no polling, no retry loop", listOf("POST ${EdgeRules.WARM}"), fake.calls)
        // Once a Talk session, also while it is busy.
        gw.talkOpened(); runCurrent()
        assertEquals(1, fake.warms)
    }

    @Test fun portDropMidSwapIsRiddenOut() = runTest {
        val (fake, gw) = setUp()
        fake.swapMs = 30_000
        gw.talkOpened(); runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(QwenState.Ready, gw.state.value)
        assertTrue("a poll met the dropped port", fake.dropped > 0)
    }

    @Test fun throughSwapRetriesFastDropsOnly() = runTest {
        val now = { testScheduler.currentTime }
        // Refused for 8 s, then answers: tried again with backoff, and gets the answer.
        var tries = 0
        val got = Gateway.throughSwap(now, 15_000, Gateway::isDrop) {
            tries++
            if (now() < 8_000) throw NetException("Connection refused", EDGE_UNREACHABLE)
            "ok"
        }
        assertEquals("ok", got)
        assertTrue("tries: $tries", tries in 3..6)
        // A timeout (the server is away) is not tried again: the phone answers sooner.
        tries = 0
        val slow = runCatching { Gateway.throughSwap(now, 15_000, Gateway::isDrop) { tries++; delay(4_000); throw NetException("Connection refused", EDGE_UNREACHABLE) } }
        assertTrue(slow.isFailure); assertEquals(1, tries)
        // Nor Wren's own Offline, nor an error the server meant (a bad request), nor anything past the budget.
        tries = 0
        runCatching { Gateway.throughSwap(now, 15_000, Gateway::isDrop) { tries++; throw NetException("Wren is offline", EDGE_UNREACHABLE) } }
        assertEquals(1, tries)
        tries = 0
        runCatching { Gateway.throughSwap(now, 15_000, Gateway::isDrop) { tries++; throw NetException("HTTP 400: bad", EDGE_REFUSED) } }
        assertEquals(1, tries)
        tries = 0
        val t0 = now()
        val never = runCatching { Gateway.throughSwap(now, 15_000, Gateway::isDrop) { tries++; throw NetException("HTTP 503: swapping", EDGE_REFUSED) } }
        assertTrue(never.isFailure)
        assertTrue("gave up within the budget", now() - t0 <= 15_000)
        assertTrue("tries: $tries", tries in 3..7)
        // Budget 0 (the server was not answering anyway): one try.
        tries = 0
        runCatching { Gateway.throughSwap(now, 0, Gateway::isDrop) { tries++; throw NetException("Connection refused", EDGE_UNREACHABLE) } }
        assertEquals(1, tries)
    }

    @Test fun wrongOrMissingKeySaysSo() = runTest {
        for (bad in listOf("not-the-key", "")) {
            val (fake, gw) = setUp()
            fake.sentKey = bad
            gw.talkOpened(); runCurrent()
            assertEquals(QwenState.KeyWrong, gw.state.value)
            advanceTimeBy(600_000); runCurrent()
            assertEquals("no retry loop on a wrong key", 1, fake.warms)
            assertTrue(fake.polls <= 1)
            // Still wrong: chat doesn't ask either.
            gw.wantQwen(); runCurrent()
            assertEquals(1, fake.warms)
            // The owner saves the right key: Talk, still open, asks again.
            fake.sentKey = fake.key
            gw.reset(); runCurrent()
            assertEquals(2, fake.warms)
            advanceTimeBy(80_000); runCurrent()
            assertEquals(QwenState.Ready, gw.state.value)
        }
    }

    @Test fun chatAsksAtMostEveryFewMinutes() = runTest {
        val (fake, gw) = setUp()
        fake.game = true
        gw.wantQwen(); runCurrent()
        gw.wantQwen(); runCurrent()
        assertEquals(1, fake.warms)
        advanceTimeBy(Gateway.ASK_EVERY_MS); runCurrent()
        gw.wantQwen(); runCurrent()
        assertEquals(2, fake.warms)
    }

    @Test fun notAGatewayIsNotAskedAgain() = runTest {
        val now = { testScheduler.currentTime }
        var calls = 0
        val gw = Gateway(backgroundScope, { _, _ -> calls++; GatewayReply(404, "HTTP 404: File Not Found") }, { EdgeModels.ANSWER }, now)
        gw.talkOpened(); runCurrent()
        assertEquals(QwenState.Idle, gw.state.value)
        advanceTimeBy(Gateway.ASK_EVERY_MS * 2); runCurrent()
        gw.wantQwen(); runCurrent()
        assertEquals(1, calls)
    }

    @Test fun oldVoicePortsMoveToTheGateway() {
        assertEquals("http://192.168.1.138:18110", Gateway.gatewayFor("http://192.168.1.138:18101"))
        assertEquals("http://100.124.77.61:18450", Gateway.gatewayFor("http://100.124.77.61:18448"))
        assertEquals("http://100.124.77.61:18450", Gateway.gatewayFor("100.124.77.61:18448/"))
        assertNull("already the gateway", Gateway.gatewayFor("http://100.124.77.61:18450"))
        assertNull(Gateway.gatewayFor("http://192.168.1.138:18110"))
        assertNull(Gateway.gatewayFor(""))
        assertNull(Gateway.gatewayFor("http://8.8.8.8:18101"))
    }

    @Test fun repliesRead() {
        assertTrue(Gateway.refused("""{"ok":false,"reason":"game"}"""))
        assertTrue(Gateway.refused("""{"status":"refused"}"""))
        assertTrue(!Gateway.refused("""{"mode":"voice"}"""))
        assertTrue(!Gateway.refused("not json"))
        assertEquals("game on GPU 0", Gateway.reason("""{"reason":"game on GPU 0"}"""))
        assertEquals("busy", Gateway.reason("""{"error":{"message":"busy"}}"""))
        assertEquals(503, Gateway.httpCode(NetException("HTTP 503: x")))
        assertEquals(listOf("a", "b"), Gateway.ids("""{"data":[{"id":"a"},{"id":"b"}]}"""))
    }

    @Test fun aReplyFromTheOldAddressChangesNothing() = runTest {
        val now = { testScheduler.currentTime }
        var old = true
        var warms = 0
        // The old direct port answers 404, slowly; the owner switches to the gateway meanwhile.
        val gw = Gateway(backgroundScope, { _, p ->
            if (p == EdgeRules.WARM) warms++
            if (old) { delay(3_000); GatewayReply(404, "HTTP 404") } else GatewayReply(202, "{}")
        }, { EdgeModels.ANSWER }, now)
        gw.talkOpened(); runCurrent()
        advanceTimeBy(1_000)
        old = false
        gw.reset(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("the new gateway was asked", 2, warms)
        assertTrue("${gw.state.value}", gw.state.value is QwenState.Waking)
    }

    @Test fun talkOpenedBeforeTheServerIsSetAsksOnceItIs() = runTest {
        val (fake, _) = setUp()
        var set = false
        val gw = Gateway(backgroundScope, { m, p -> fake.raw(m, p) }, { EdgeModels.ANSWER }, { testScheduler.currentTime }, { set })
        gw.talkOpened(); runCurrent()
        assertEquals("nothing sent before the server is set", 0, fake.warms)
        set = true
        gw.reset(); runCurrent()
        assertEquals(1, fake.warms)
    }

    @Test fun aFixedKeyWakesAnOpenTalk() = runTest {
        val (fake, gw) = setUp()
        fake.sentKey = "wrong"
        gw.talkOpened(); runCurrent()
        assertEquals(QwenState.KeyWrong, gw.state.value)
        fake.sentKey = fake.key
        gw.keyOk(); runCurrent()
        assertEquals(2, fake.warms)
    }

    @Test fun forbiddenWarmIsBusyNotAWrongKey() = runTest {
        val gw = Gateway(backgroundScope, { _, _ -> GatewayReply(403, "HTTP 403: warm not allowed for this key") }, { EdgeModels.ANSWER }, { testScheduler.currentTime })
        gw.talkOpened(); runCurrent()
        assertTrue("${gw.state.value}", gw.state.value is QwenState.Busy)
    }

    @Test fun onlyRealDropsAreTriedAgain() {
        assertTrue(Gateway.isDrop(NetException("Failed to connect to /192.168.1.138:18110", EDGE_UNREACHABLE)))
        assertTrue(Gateway.isDrop(NetException("unexpected end of stream on http://100.124.77.61:18450/...", EDGE_UNREACHABLE)))
        assertTrue(Gateway.isDrop(NetException("HTTP 502: Bad Gateway", EDGE_REFUSED)))
        assertTrue(!Gateway.isDrop(NetException("That isn't the server saved in Settings", EDGE_UNREACHABLE)))
        assertTrue(!Gateway.isDrop(NetException("Unable to resolve host \"gaming-pc\"", EDGE_UNREACHABLE)))
        assertTrue(!Gateway.isDrop(NetException("Wren is offline", EDGE_UNREACHABLE)))
        assertTrue(!Gateway.isDrop(NetException("timeout", EDGE_UNREACHABLE)))
        assertTrue(!Gateway.isDrop(NetException("HTTP 401: Invalid API Key", EDGE_REFUSED)))
    }
}
