package com.lfmpixel.lfm

import com.lfmpixel.lfm.llm.Backend
import com.lfmpixel.lfm.llm.BackendChoice
import com.lfmpixel.lfm.llm.BackendSpeed
import com.lfmpixel.lfm.llm.Backends
import com.lfmpixel.lfm.llm.CheckResult
import com.lfmpixel.lfm.llm.LiteRtSession
import com.lfmpixel.lfm.llm.ModelCatalog
import com.lfmpixel.lfm.llm.ModelCheck
import com.lfmpixel.lfm.llm.ModelRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendTest {
    private val all = Backend.entries.toSet()
    private val gpu = BackendSpeed(18.0, 300.0, true)
    private val cpu = BackendSpeed(9.0, 100.0, true)

    @Test fun autoTakesTheGpuWhereThereIsOne() {
        // Unmeasured: the GPU, which is what LiteRT is for.
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.Auto, all, emptyMap()))
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.Auto, all, mapOf(Backend.LiteRtGpu to gpu, Backend.LiteRtCpu to cpu)))
        // No OpenCL (an emulator): the CPU.
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.Auto, setOf(Backend.LiteRtCpu), emptyMap()))
    }

    @Test fun autoLeavesAGpuThatFailed() {
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.Auto, all, mapOf(Backend.LiteRtGpu to BackendSpeed(40.0, 900.0, false))))
    }

    @Test fun autoKeepsTheCpuOnlyWhenClearlyQuicker() {
        // A slow GPU driver: the CPU is more than 10% quicker a turn.
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.Auto, all, mapOf(Backend.LiteRtGpu to BackendSpeed(5.0, 60.0, true), Backend.LiteRtCpu to cpu)))
        // Within 10%: the GPU, which spares the battery.
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.Auto, all, mapOf(Backend.LiteRtGpu to BackendSpeed(8.6, 96.0, true), Backend.LiteRtCpu to cpu)))
    }

    @Test fun aManualPickHoldsWhileAvailable() {
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.LiteRtCpu, all, mapOf(Backend.LiteRtGpu to gpu)))
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.LiteRtGpu, all, emptyMap()))
        // No GPU here: the CPU.
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.LiteRtGpu, setOf(Backend.LiteRtCpu), emptyMap()))
    }

    @Test fun turnTimeWeighsReadingAndWriting() {
        // 400 tokens read, 150 written.
        assertEquals(400 / 100.0 + 150 / 10.0, BackendSpeed(10.0, 100.0, true).turnSeconds, 1e-9)
        assertEquals(Double.MAX_VALUE, BackendSpeed(10.0, 100.0, false).turnSeconds, 0.0)
        assertEquals(Double.MAX_VALUE, BackendSpeed(0.0, 0.0, true).turnSeconds, 0.0)
    }

    @Test fun engineRestartsCount() {
        // Both pay LiteRT-LM's engine start; a slow GPU start makes the CPU the quicker one.
        val speeds = mapOf(Backend.LiteRtGpu to BackendSpeed(18.0, 300.0, true, restartSeconds = 30.0), Backend.LiteRtCpu to BackendSpeed(14.0, 110.0, true, 2.0))
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.Auto, all, speeds))
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.Auto, all, speeds + (Backend.LiteRtGpu to BackendSpeed(18.0, 300.0, true, 2.0))))
    }

    @Test fun checksMatchWholeNumbers() {
        assertFalse(Backends.passes(listOf("Kyoto", "142", "17")))
    }

    @Test fun checksNeedTwoOfThreeRight() {
        assertTrue(Backends.passes(listOf("Tokyo.", "42", "7")))
        assertTrue(Backends.passes(listOf("tokyo", "17 + 25 = 42", "seven")))
        assertFalse(Backends.passes(listOf("", "", "")))
        assertFalse(Backends.passes(listOf("!!!!!!", "<unk><unk>", "7")))
    }

    @Test fun speedsSurviveSettings() {
        val map = mapOf(
            "LFM3-VL-2B_int4.litertlm" to mapOf(Backend.LiteRtCpu to BackendSpeed(9.1, 120.5, true), Backend.LiteRtGpu to BackendSpeed(4.3, 13.4, false)),
            "LFM3-1.7B_mixed_int4_ekv4096.litertlm" to mapOf(Backend.LiteRtGpu to BackendSpeed(18.25, 310.0, true, 3.5)),
        )
        assertEquals(map, Backends.decode(Backends.encode(map)))
        assertEquals(emptyMap<String, Map<Backend, BackendSpeed>>(), Backends.decode(""))
        // Junk, llama.cpp's old backends and ones from a later version are skipped, not fatal.
        assertEquals(1, Backends.decode("x.gguf|Npu=1,2,1;bad;z.gguf|Cpu=3,4,1;y.litertlm|LiteRtCpu=3,4,1").size)
        // Stored before restarts were measured: three values, no restart cost.
        assertEquals(0.0, Backends.decode("y.litertlm|LiteRtCpu=3,4,1")["y.litertlm"]!![Backend.LiteRtCpu]!!.restartSeconds, 0.0)
    }

    @Test fun theLiteRtFileIsPinned() {
        val m = ModelCatalog.byName("LFM3-1.7B_mixed_int4_ekv4096.litertlm")
        assertNotNull(m)
        assertEquals(ModelRole.Text, m!!.role)
        assertEquals("litert-community/LFM3-1.7B-LiteRT", m.repo)
        assertEquals(1441629120L, m.sizeBytes)
        assertEquals("327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b", m.sha256)
        assertTrue(ModelCheck.precheck(m.fileName, m.sizeBytes) is CheckResult.Ok)
        assertTrue(ModelCheck.precheck(m.fileName, m.sizeBytes - 1) is CheckResult.WrongSize)
        // The chat model now: offered, and part of first run's essentials with its tokenizer.
        assertTrue(ModelCatalog.groups.any { m in it.variants })
        assertTrue(m in ModelCatalog.essentials && ModelCatalog.byName("LFM3_tokenizer.json") in ModelCatalog.essentials)
    }

    @Test fun liteRtTokenEstimateErrsHigh() {
        // LFM3's tokenizer makes about one token of 4 bytes of English; the estimate must not undercount, or a long
        // chat would overflow the 4,096-token cache.
        val text = LiteRtSession.BENCH_SENTENCE.repeat(40)
        assertTrue(LiteRtSession.estimateTokens(text) >= text.length / LiteRtSession.BENCH_CHARS_PER_TOKEN)
    }
}
