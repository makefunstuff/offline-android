package com.lfmpixel.lfm

import com.lfmpixel.lfm.see.SeeKind
import com.lfmpixel.lfm.see.SeeModel
import com.lfmpixel.lfm.see.SeeRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeeTest {
    @Test fun fitKeepsShapeAndNeverUpscales() {
        assertEquals(768 to 576, SeeRules.fit(4000, 3000, 768))
        assertEquals(576 to 768, SeeRules.fit(3000, 4000, 768))
        assertEquals(300 to 200, SeeRules.fit(300, 200, 768))
    }

    @Test fun videoFramesAreTwoASecondUpToTenSeconds() {
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L), SeeRules.frameTimes(3000))
        assertEquals(20, SeeRules.frameTimes(60_000).size)
        assertEquals(9500L, SeeRules.frameTimes(60_000).last())
        assertEquals(listOf(0L), SeeRules.frameTimes(0))
    }

    @Test fun omniForPhotosAndVideoVlForDocuments() {
        assertEquals(SeeModel.Omni, SeeRules.pick(SeeKind.Photo, hasOmni = true, hasVision = true, hasText = true, needsPictures = true))
        assertEquals(SeeModel.Omni, SeeRules.pick(SeeKind.Video, true, true, true, true))
        assertEquals(SeeModel.Vision, SeeRules.pick(SeeKind.Document, true, true, true, true))
        // Each stands in for the other.
        assertEquals(SeeModel.Vision, SeeRules.pick(SeeKind.Photo, false, true, true, true))
        assertEquals(SeeModel.Omni, SeeRules.pick(SeeKind.Document, true, false, true, true))
        // A PDF with a text layer can be answered by the text model alone.
        assertEquals(SeeModel.Text, SeeRules.pick(SeeKind.Document, true, false, true, needsPictures = false))
        assertNull(SeeRules.pick(SeeKind.Photo, false, false, true, true))
    }

    @Test fun promptHasOneMarkerPerPicture() {
        val p = SeeRules.prompt(SeeKind.Video, "What happens?", 6, 3)
        assertEquals(6, Regex("<__media__>").findAll(p).count())
        assertTrue(p.contains("6 frames from a 3-second video"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think></think>"))
        assertEquals(1, Regex("<__media__>").findAll(SeeRules.prompt(SeeKind.Photo, "What is this?", 1)).count())
    }

    @Test fun documentTextIsCutToFitAndSaysSo() {
        val pages = listOf("a".repeat(500), "b".repeat(500), "c".repeat(5000))
        val t = SeeRules.documentText(pages, 2000)
        assertTrue(t.length <= 2100)
        assertTrue(t.startsWith("--- Page 1 ---"))
        assertTrue(t.contains("--- Page 3 ---"))
        assertTrue(t.endsWith("[The rest of the document was cut to fit.]"))
        assertEquals(false, SeeRules.documentText(listOf("short"), 2000).contains("cut"))
    }

    @Test fun cleanDropsThinkingAndEndTokens() {
        assertEquals("A fern.", SeeRules.clean("<think>hmm</think>A fern.<|im_end|>junk"))
    }
}
