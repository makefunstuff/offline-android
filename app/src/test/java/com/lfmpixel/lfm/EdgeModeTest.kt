package com.lfmpixel.lfm

import com.lfmpixel.lfm.edge.Edge
import com.lfmpixel.lfm.edge.EdgeSession
import com.lfmpixel.lfm.edge.PcmFloats
import com.lfmpixel.lfm.edge.SseLines
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.voice.Jitter
import com.lfmpixel.lfm.voice.SpeechChunker
import com.lfmpixel.lfm.voice.simulatePlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** Edge mode's pure parts: the address, the streams from the server, speaking while writing, the jitter buffer. */
@RunWith(RobolectricTestRunner::class)
class EdgeModeTest {
    @Test fun addresses() {
        assertEquals("http://100.124.77.61:18448", Edge.normalize("100.124.77.61:18448"))
        assertEquals("http://100.124.77.61:18448", Edge.normalize(" http://100.124.77.61:18448/v1/ "))
        assertEquals("http://10.0.2.2:18101", Edge.normalize("http://10.0.2.2:18101"))
        assertEquals("https://beefy.tail1234.ts.net", Edge.normalize("https://beefy.tail1234.ts.net"))
        assertNull("public", Edge.normalize("http://8.8.8.8:18101"))
        assertNull("other scheme", Edge.normalize("ftp://10.0.0.1"))
        assertNull("a path", Edge.normalize("http://10.0.0.1/x/y"))
        assertNull("a user", Edge.normalize("http://me@10.0.0.1"))
        assertNull("bad port", Edge.normalize("http://10.0.0.1:99999"))
        assertTrue(Edge.whyNot("http://8.8.8.8")!!.contains("home network"))
        assertNull(Edge.whyNot(""))
        assertTrue(Edge.isTailnet("100.124.77.61:18448"))
        assertTrue(Edge.isTailnet("https://192.168.1.138:18101"))
        assertTrue(Edge.isTailnet("http://beefy.tail1234.ts.net:18448"))
        assertTrue(!Edge.isTailnet("http://192.168.1.138:18101"))
    }

    @Test fun eventsAcrossPieces() {
        val sse = SseLines()
        val stream = "data: {\"a\":1}\n\ndata: {\"b\":\"x\\ny\"}\r\n\n: comment\ndata: [DONE]\n\n".toByteArray()
        val got = mutableListOf<String>()
        // Cut at every byte: events still come out whole, multi-byte letters too.
        stream.forEach { got += sse.feed(byteArrayOf(it)) }
        assertEquals(listOf("{\"a\":1}", "{\"b\":\"x\\ny\"}", "[DONE]"), got)
        val uni = SseLines()
        val bytes = "data: {\"t\":\"café ✓\"}\n".toByteArray()
        assertEquals(listOf("{\"t\":\"café ✓\"}"), uni.feed(bytes.copyOfRange(0, 17)) + uni.feed(bytes.copyOfRange(17, bytes.size)))
    }

    @Test fun pcmAcrossPieces() {
        val src = FloatArray(1000) { it / 1000f }
        val bytes = ByteBuffer.allocate(4000).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(src) }.array()
        val p = PcmFloats()
        val out = mutableListOf<Float>()
        var i = 0
        for (n in generateSequence(1) { it % 7 + 1 }) { if (i >= bytes.size) break; val e = minOf(bytes.size, i + n); out += p.feed(bytes.copyOfRange(i, e)).toList(); i = e }
        assertEquals(src.toList(), out)
    }

    @Test fun speaksSentencesAsTheyEnd() {
        val c = SpeechChunker()
        val said = mutableListOf<String>()
        // As the server streams it: a few characters at a time.
        "Sure. Black cats suit names like **Shadow**, Onyx or Salem [1]. Want more ideas?".chunked(3).forEach { said += c.feed(it) }
        said += c.flush()
        assertEquals(listOf("Sure.", "Black cats suit names like Shadow, Onyx or Salem.", "Want more ideas?"), said)
        // A long first sentence is cut at a comma, so the voice doesn't wait for its end.
        val long = SpeechChunker(first = 40)
        val first = long.feed("If you want something classic and a little spooky, Salem works")
        assertEquals(listOf("If you want something classic and a"), first.take(1).map { it.substringBefore(" little") })
        assertTrue(first.isNotEmpty())
        // Numbers with a decimal point are not a sentence end.
        assertEquals(emptyList<String>(), SpeechChunker().feed("It costs 3.5 pounds"))
    }

    @Test fun picturesBecomeChatMessages() {
        val prompt = PromptFormat.system("Look carefully.", emptyList()) +
            "${PromptFormat.IM_START}user\n<__media__><__media__>\nThese are 2 frames. What is it?${PromptFormat.IM_END}\n${PromptFormat.IM_START}assistant\n<think></think>"
        val m = EdgeSession.chatMessages(prompt, listOf("data:a", "data:b"))
        assertEquals(2, m.length())
        assertEquals("system", m.getJSONObject(0).getString("role"))
        assertEquals("Look carefully.", m.getJSONObject(0).getString("content"))
        val parts = m.getJSONObject(1).getJSONArray("content")
        assertEquals(listOf("image_url", "image_url", "text"), (0 until parts.length()).map { parts.getJSONObject(it).getString("type") })
        assertEquals("data:b", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("These are 2 frames. What is it?", parts.getJSONObject(2).getString("text"))
    }

    @Test fun wavHeader() {
        val w = Edge.wav16(FloatArray(16_000) { 0.5f }, 16_000)
        assertEquals(44 + 32_000, w.size)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16_000, b.getInt(24))
        assertEquals((0.5f * 32767).toInt().toShort(), b.getShort(44))
    }

    // The jitter buffer from LP-30: what the server's streamed voice plays through.
    private val rate = 24_000
    private fun speech(ms: Int, wordMs: Int = 320, gapMs: Int = 80) = FloatArray(rate * ms / 1000) { i ->
        val t = (i * 1000L / rate).toInt() % (wordMs + gapMs)
        if (t < wordMs) (0.1 * sin(2 * PI * 180 * i / rate)).toFloat() else 0f
    }
    private fun frames(pcm: FloatArray, rtf: Double, firstMs: Double = 300.0): List<Pair<Double, FloatArray>> {
        val f = rate * 80 / 1000
        return (0 until pcm.size / f).map { k -> firstMs + k * 80 * rtf to pcm.copyOfRange(k * f, (k + 1) * f) }
    }

    @Test fun serverSpeedVoiceStartsAtOnceAndNeverWaits() {
        // The server makes speech about six times faster than it plays; the first audio comes after 40 ms.
        val sim = simulatePlayback(Jitter(shared = Jitter.Prior()), frames(speech(8000), 0.17, firstMs = 40.0))
        assertEquals(0, sim.underruns)
        assertEquals(0, sim.pauses)
        assertTrue("starts ${sim.startMs}", sim.startMs < 120)
    }

    @Test fun slowVoiceWaitsUpFrontAndNeverRunsDryMidWord() {
        val pcm = speech(8000)
        val j = Jitter(shared = Jitter.Prior())
        simulatePlayback(j, frames(pcm, 1.4)) { 8000.0 }
        assertTrue("rtf ${j.rtf}", j.rtf > 1.2)
        val sim = simulatePlayback(j, frames(pcm, 1.4)) { 8000.0 }
        assertEquals(0, sim.underruns)
    }

    @Test fun anUnderrunRaisesTheWait() {
        val j = Jitter(shared = Jitter.Prior())
        val before = j.needMs(0.0)
        j.underrun(100.0)
        assertTrue(j.needMs(0.0) > before)
    }
}
