package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.CaptureSorter
import com.lfmpixel.lfm.data.Entry
import com.lfmpixel.lfm.data.EntryKind
import com.lfmpixel.lfm.data.FtsQuery
import com.lfmpixel.lfm.llm.CheckResult
import com.lfmpixel.lfm.llm.ModelCatalog
import com.lfmpixel.lfm.llm.ModelCheck
import com.lfmpixel.lfm.llm.formatBytes
import com.lfmpixel.lfm.tools.TimeArgs
import com.lfmpixel.lfm.tools.When
import com.lfmpixel.lfm.ui.screens.TodoTab
import com.lfmpixel.lfm.ui.screens.todoTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.async
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class TimeArgsTest {
    private val now = LocalDateTime.of(2026, 10, 30, 14, 8) // a Friday

    private fun parse(s: String) = TimeArgs.parse(s, now)

    @Test fun iso() {
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("2026-10-30T18:00"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("2026-10-30 18:00:00"))
        assertEquals(When(LocalDate.of(2026, 11, 6), null), parse("2026-11-06"))
    }

    @Test fun bareTimeMeansNextTimeTheClockShowsIt() {
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("18:00"))
        assertEquals(When(LocalDate.of(2026, 10, 31), LocalTime.of(9, 0)), parse("9:00"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("6pm"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 30)), parse("6:30 p.m."))
    }

    @Test fun relativeDays() {
        assertEquals(When(LocalDate.of(2026, 10, 31), LocalTime.of(9, 0)), parse("tomorrow 9:00"))
        assertEquals(When(LocalDate.of(2026, 11, 6), LocalTime.of(20, 0)), parse("friday 8pm"))
        assertEquals(When(LocalDate.of(2026, 11, 3), null), parse("tuesday"))
        // As the small model copies them from a line (docs/notebook-spec.md B.2).
        assertEquals(When(LocalDate.of(2026, 11, 6), LocalTime.of(17, 0)), parse("next Friday at 5pm"))
        assertEquals(When(LocalDate.of(2026, 11, 3), LocalTime.of(15, 0)), parse("on tuesday at 3pm"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 18)), parse("in 10 minutes"))
        assertNull(parse("whenever"))
    }

    @Test fun durations() {
        assertEquals(Duration.ofMinutes(10), TimeArgs.parseDuration("10 min"))
        assertEquals(Duration.ofMinutes(90), TimeArgs.parseDuration("1h 30m"))
        assertEquals(Duration.ofMinutes(5), TimeArgs.parseDuration("5"))
        assertEquals(Duration.ofSeconds(45), TimeArgs.parseDuration("PT45S"))
    }

    @Test fun bareHours() {
        // "at 6" in the afternoon is the coming 18:00; "at 9" at 14:08 is 21:00 today, not 09:00 tomorrow.
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("at 6"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(21, 0)), parse("9"))
        assertEquals(When(LocalDate.of(2026, 10, 31), LocalTime.of(14, 0)), parse("at 14"))
        assertEquals(When(LocalDate.of(2026, 10, 31), LocalTime.of(6, 0)), parse("tomorrow at 6"))
        // "tonight at 8" is the evening; "today at 6" at 14:08 is the coming 18:00, "today at 3" 15:00.
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(20, 0)), parse("tonight at 8"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), parse("today at 6"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(15, 0)), parse("today 3"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 30)), parse("today at 6:30"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(9, 0)), parse("today 9am"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 18)), parse("in 10 minutes"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(16, 38)), parse("in 2 and a half hours"))
    }

    @Test fun labels() {
        val today = now.toLocalDate()
        assertEquals("Today 18:00", TimeArgs.label(When(today, LocalTime.of(18, 0)), today))
        assertEquals("Tomorrow 09:00", TimeArgs.label(When(today.plusDays(1), LocalTime.of(9, 0)), today))
        assertEquals("Fri 6 Nov · 20:00", TimeArgs.label(When(LocalDate.of(2026, 11, 6), LocalTime.of(20, 0)), today))
    }
}

class ModelCheckTest {
    @Test fun knownModelsArePinned() {
        val m = ModelCatalog.byName("LFM3-1.7B_mixed_int4_ekv4096.litertlm")!!
        assertEquals(1441629120L, m.sizeBytes)
        assertEquals(64, m.sha256.length)
        assertTrue(ModelCatalog.all.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
    }

    @Test fun precheckRejectsUnknownNamesAndWrongSizes() {
        // GGUF files are unknown now: the phone runs LiteRT only.
        assertTrue(ModelCheck.precheck("LFM3-1.7B-QAD-Q4_0.gguf", 1049615680L) is CheckResult.Unknown)
        assertTrue(ModelCheck.precheck("LFM3-VL-2B_int4.litertlm", 10) is CheckResult.WrongSize)
        assertTrue(ModelCheck.precheck("lfm3-vl-2b_int4.litertlm", 2071595259L) is CheckResult.Ok)
    }

    @Test fun copyAndHashComputesSha256WhileCopying() {
        val out = ByteArrayOutputStream()
        var last = 0L
        val h = ModelCheck.copyAndHash(ByteArrayInputStream("abc".toByteArray()), out, { last = it })
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
        assertEquals("abc", out.toString())
        assertEquals(3L, last)
    }

    @Test fun verifyComparesAgainstThePin() {
        val m = ModelCatalog.all.first()
        assertTrue(ModelCheck.verify(m, m.sha256.uppercase()) is CheckResult.Ok)
        assertTrue(ModelCheck.verify(m, "00".repeat(32)) is CheckResult.WrongHash)
    }

    @Test fun sizes() {
        assertEquals("1.05 GB", formatBytes(1049615680L))
        assertEquals("1.1 GB", formatBytes(1099627808L))
        assertEquals("292 MB", formatBytes(291586720L))
    }
}

class CaptureSorterTest {
    @Test fun sortsByRules() {
        CaptureSorter.sort("Remind me to call the plumber at 6pm").let {
            assertEquals("set_reminder", it.name); assertEquals("call the plumber", it.args["text"]); assertEquals("6pm", it.args["at"])
        }
        CaptureSorter.sort("remind me to water the herbs tomorrow 9:00").let {
            assertEquals("set_reminder", it.name); assertEquals("tomorrow 9:00", it.args["at"])
        }
        CaptureSorter.sort("Buy oat milk").let { assertEquals("add_todo", it.name); assertEquals("Shopping", it.args["list"]); assertEquals("Buy oat milk", it.args["text"]) }
        CaptureSorter.sort("Need to renew passport").let { assertEquals("add_todo", it.name); assertEquals("Renew passport", it.args["text"]) }
        CaptureSorter.sort("Idea: weekend market stall for old prints").let { assertEquals("add_idea", it.name); assertEquals("Weekend market stall for old prints", it.args["text"]) }
        CaptureSorter.sort("I feel calmer after the walk").let { assertEquals("add_journal", it.name) }
        CaptureSorter.sort("Boiler pressure drops below 1 bar overnight").let { assertEquals("add_note", it.name) }
    }

    /** Reminders said the way people say them must turn into a time the reminder tool can read. */
    @Test fun remindersWithRelativeAndBareTimes() {
        val now = LocalDateTime.of(2026, 10, 30, 14, 8)
        fun at(s: String) = CaptureSorter.sort(s).also { assertEquals("set_reminder", it.name) }.let { TimeArgs.parse(it.args["at"]!!, now) }
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 18)), at("Remind me to check the oven in 10 minutes"))
        assertEquals("check the oven", CaptureSorter.sort("Remind me to check the oven in 10 minutes").args["text"])
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(18, 0)), at("remind me to call mum at 6"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(15, 8)), at("remind me to stretch in an hour"))
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 38)), at("remind me to move the car in half an hour"))
        assertEquals(When(LocalDate.of(2026, 10, 31), LocalTime.of(7, 30)), at("remind me to water the herbs tomorrow at 7:30"))
        // "in" inside what to do is not the time.
        CaptureSorter.sort("remind me to check in with Sam at 6").let { assertEquals("check in with Sam", it.args["text"]); assertEquals("6", it.args["at"]) }
        CaptureSorter.sort("remind me to plug in the car at 6pm").let { assertEquals("plug in the car", it.args["text"]) }
        // The time first.
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 18)), at("remind me in 10 minutes"))
        CaptureSorter.sort("remind me in 10 minutes to check the oven").let { assertEquals("check the oven", it.args["text"]); assertEquals("in 10 minutes", it.args["at"]) }
        CaptureSorter.sort("remind me at 6 to call mum").let { assertEquals("call mum", it.args["text"]); assertEquals("6", it.args["at"]) }
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(21, 30)), at("remind me to take my pills tonight at 9:30"))
    }
}

class SmallThingsTest {
    @Test fun ftsQueryIsSafe() {
        assertEquals("boiler* press*", FtsQuery.from("Boiler, press!"))
        assertEquals("\"x* or* y*\"".replace("\"", ""), FtsQuery.from("x OR y"))
        assertNull(FtsQuery.from("  -- ** "))
    }

    @Test fun todoTabs() {
        val end = 1_000_000L
        assertEquals(TodoTab.Someday, todoTab(Entry(kind = EntryKind.Todo, title = "a"), end))
        assertEquals(TodoTab.Today, todoTab(Entry(kind = EntryKind.Todo, title = "a", dueAt = end - 1), end))
        assertEquals(TodoTab.Upcoming, todoTab(Entry(kind = EntryKind.Todo, title = "a", dueAt = end), end))
    }
}

class Phase7LogicTest {
    /** Two tools asking at once: the second waits for the first answer instead of replacing its request. */
    @Test fun permissionRequestsGoOneAtATime() = kotlinx.coroutines.runBlocking {
        val granted = mutableSetOf<String>()
        val launched = mutableListOf<Pair<List<String>, kotlinx.coroutines.CompletableDeferred<Boolean>>>()
        val gate = com.lfmpixel.lfm.tools.SerialPermissionGate({ p -> granted.containsAll(p) }, { p, a -> launched += p to a })
        val bg = kotlinx.coroutines.Dispatchers.Default
        val a = async(bg) { gate.ensure(listOf("mic")) }
        kotlinx.coroutines.delay(50)
        val b = async(bg) { gate.ensure(listOf("contacts")) }
        kotlinx.coroutines.delay(50)
        val c = async(bg) { gate.ensure(listOf("mic")) }
        kotlinx.coroutines.withTimeout(2_000) { while (launched.isEmpty()) kotlinx.coroutines.delay(5) }
        kotlinx.coroutines.delay(100)
        assertEquals("only one request on screen", 1, launched.size)
        val first = launched[0]
        granted += first.first; first.second.complete(true)
        kotlinx.coroutines.withTimeout(2_000) { while (launched.size < 2) kotlinx.coroutines.delay(5) }
        kotlinx.coroutines.delay(100)
        assertEquals("the second asks once the first is answered; the repeat of the first doesn't ask again", 2, launched.size)
        launched[1].second.complete(false)
        assertEquals(listOf(true, false, true).sorted(), listOf(a.await(), b.await(), c.await()).sorted())
    }

    @Test fun volumeLevels() {
        val v = com.lfmpixel.lfm.tools.VolumeTool
        assertEquals(40, v.target("40", 10)); assertEquals(40, v.target("40%", 10)); assertEquals(50, v.target("0.5", 10))
        assertEquals(70, v.target("up", 50)); assertEquals(0, v.target("down", 10)); assertEquals(0, v.target("mute", 60))
        assertEquals(100, v.target("max", 3)); assertEquals(100, v.target("140", 3))
        assertEquals(android.media.AudioManager.STREAM_ALARM, v.stream("alarm"))
        assertEquals(android.media.AudioManager.STREAM_MUSIC, v.stream(""))
    }

    @Test fun heatSteps() {
        val H = com.lfmpixel.lfm.device.Heat.Companion
        val (Normal, Warm, Hot, TooHot) = com.lfmpixel.lfm.device.Heat.entries
        assertEquals(Normal, H.of(android.os.PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals(Warm, H.of(android.os.PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals(Hot, H.of(android.os.PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals(TooHot, H.of(android.os.PowerManager.THERMAL_STATUS_SHUTDOWN))
        // Tensor G5: 6 big cores; Auto writes with 5.
        assertEquals(0, H.threadCap(Normal, 6)); assertEquals(4, H.threadCap(Warm, 6))
        assertEquals(3, H.threadCap(Hot, 6)); assertEquals(2, H.threadCap(TooHot, 6))
        assertEquals(2, H.threadCap(Warm, 2))
        assertTrue(Hot.holdsLongJobs && !Hot.holdsEverything && TooHot.holdsEverything && !Warm.holdsLongJobs)
    }

    @Test fun lockClosesAfterAMinuteAway() {
        val L = com.lfmpixel.lfm.device.AppLock
        val off = com.lfmpixel.lfm.settings.LockMode.Off
        val app = com.lfmpixel.lfm.settings.LockMode.App
        assertTrue(!L.shouldClose(off, 1_000, 1_000_000))
        assertTrue(!L.shouldClose(app, 1_000, 1_000 + 59_000))
        assertTrue(L.shouldClose(app, 1_000, 1_000 + 60_000))
        assertTrue("never hidden yet", !L.shouldClose(app, 0, 1_000_000))
    }
}

/** ux-spec-v1 section 8: text at least 4.5:1, control edges at least 3:1, in both themes. */
class ContrastTest {
    private fun lum(c: androidx.compose.ui.graphics.Color): Double {
        fun ch(v: Float) = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }
    private fun ratio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun paletteMeetsTheBar() {
        for (c in listOf(com.lfmpixel.lfm.ui.theme.LightColors, com.lfmpixel.lfm.ui.theme.DarkColors)) {
            for (bg in listOf(c.bg, c.fill)) {
                assertTrue("text ${c.dark}", ratio(c.text, bg) >= 4.5)
                assertTrue("text2 ${c.dark}", ratio(c.text2, bg) >= 4.5)
                assertTrue("text3 ${c.dark}", ratio(c.text3, bg) >= 4.5)
                assertTrue("control edges ${c.dark}", ratio(c.control, bg) >= 3.0)
            }
            assertTrue("ink on the highlighter", ratio(c.onHighlight, c.highlight) >= 4.5)
            // Ink buttons: the page colour on ink.
            assertTrue(ratio(c.bg, c.text) >= 4.5)
        }
    }
}
