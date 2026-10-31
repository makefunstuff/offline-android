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
    private val cpu = BackendSpeed(9.0, 100.0, true)

    @Test fun autoStaysOnTheCpuUntilMeasured() {
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Auto, all, emptyMap()))
        // A GPU measured but no CPU to compare with: still the CPU.
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Auto, all, mapOf(Backend.LiteRtGpu to BackendSpeed(20.0, 300.0, true))))
    }

    @Test fun autoTakesTheQuickestBackendThatAnsweredRight() {
        val speeds = mapOf(Backend.Cpu to cpu, Backend.Vulkan to BackendSpeed(4.3, 13.0, true),
            Backend.LiteRtGpu to BackendSpeed(18.0, 300.0, true), Backend.LiteRtCpu to BackendSpeed(14.0, 110.0, true))
        assertEquals(Backend.LiteRtGpu, Backends.pick(BackendChoice.Auto, all, speeds))
    }

    @Test fun autoNeverPicksABackendThatAnsweredWrong() {
        val speeds = mapOf(Backend.Cpu to cpu, Backend.Vulkan to BackendSpeed(40.0, 900.0, false))
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Auto, all, speeds))
    }

    @Test fun autoNeedsAClearMargin() {
        // Within 10% of the CPU's turn time: not worth leaving the CPU, which works everywhere.
        val close = mapOf(Backend.Cpu to cpu, Backend.LiteRtGpu to BackendSpeed(9.5, 105.0, true))
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Auto, all, close))
    }

    @Test fun autoSkipsBackendsThePhoneNoLongerHas() {
        val speeds = mapOf(Backend.Cpu to cpu, Backend.LiteRtGpu to BackendSpeed(18.0, 300.0, true))
        // The .litertlm file was deleted: LiteRT-LM is not available any more.
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Auto, setOf(Backend.Cpu, Backend.Vulkan), speeds))
    }

    @Test fun aManualPickHoldsWhileAvailable() {
        assertEquals(Backend.Vulkan, Backends.pick(BackendChoice.Vulkan, all, emptyMap()))
        assertEquals(Backend.LiteRtCpu, Backends.pick(BackendChoice.LiteRtCpu, all, mapOf(Backend.Cpu to cpu)))
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.Cpu, all, mapOf(Backend.LiteRtGpu to BackendSpeed(50.0, 900.0, true))))
        // Omni and VL have no LiteRT-LM file: a LiteRT pick leaves them on the CPU.
        assertEquals(Backend.Cpu, Backends.pick(BackendChoice.LiteRtGpu, setOf(Backend.Cpu, Backend.Vulkan), emptyMap()))
    }

    @Test fun turnTimeWeighsReadingAndWriting() {
        // 400 tokens read, 150 written.
        assertEquals(400 / 100.0 + 150 / 10.0, BackendSpeed(10.0, 100.0, true).turnSeconds, 1e-9)
        assertEquals(Double.MAX_VALUE, BackendSpeed(10.0, 100.0, false).turnSeconds, 0.0)
        assertEquals(Double.MAX_VALUE, BackendSpeed(0.0, 0.0, true).turnSeconds, 0.0)
    }

    @Test fun checksNeedTwoOfThreeRight() {
        assertTrue(Backends.passes(listOf("Tokyo.", "42", "7")))
        assertTrue(Backends.passes(listOf("tokyo", "17 + 25 = 42", "seven")))
        assertFalse(Backends.passes(listOf("", "", "")))
        assertFalse(Backends.passes(listOf("!!!!!!", "<unk><unk>", "7")))
    }

    @Test fun speedsSurviveSettings() {
        val map = mapOf(
            "LFM3-1.7B-QAD-Q4_0.gguf" to mapOf(Backend.Cpu to BackendSpeed(9.1, 120.5, true), Backend.Vulkan to BackendSpeed(4.3, 13.4, false)),
            "LFM3-1.7B_mixed_int4_ekv4096.litertlm" to mapOf(Backend.LiteRtGpu to BackendSpeed(18.25, 310.0, true)),
        )
        assertEquals(map, Backends.decode(Backends.encode(map)))
        assertEquals(emptyMap<String, Map<Backend, BackendSpeed>>(), Backends.decode(""))
        // Junk and backends from a later version are skipped, not fatal.
        assertEquals(1, Backends.decode("x.gguf|Npu=1,2,1;bad;y.gguf|Cpu=3,4,1").size)
    }

    @Test fun theLiteRtFileIsPinned() {
        val m = ModelCatalog.byName("LFM3-1.7B_mixed_int4_ekv4096.litertlm")
        assertNotNull(m)
        assertEquals(ModelRole.TextLiteRt, m!!.role)
        assertEquals("litert-community/LFM3-1.7B-LiteRT", m.repo)
        assertEquals(1441629120L, m.sizeBytes)
        assertEquals("327486bf01d0b1eefd727b7aadec8627a2b8db143349262857ac98403109d72b", m.sha256)
        assertTrue(ModelCheck.precheck(m.fileName, m.sizeBytes) is CheckResult.Ok)
        assertTrue(ModelCheck.precheck(m.fileName, m.sizeBytes - 1) is CheckResult.WrongSize)
        // Offered for download, but not part of first run's essentials.
        assertTrue(ModelCatalog.groups.any { m in it.variants })
        assertFalse(m in ModelCatalog.essentials)
    }

    @Test fun liteRtTokenEstimateErrsHigh() {
        // LFM3's tokenizer makes about one token of 4 bytes of English; the estimate must not undercount, or a long
        // chat would overflow the 4,096-token cache.
        val text = LiteRtSession.BENCH_SENTENCE.repeat(40)
        assertTrue(LiteRtSession.estimateTokens(text) >= text.length / LiteRtSession.BENCH_CHARS_PER_TOKEN)
    }
}
