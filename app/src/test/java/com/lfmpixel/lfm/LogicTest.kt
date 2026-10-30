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
        assertEquals(When(LocalDate.of(2026, 10, 30), LocalTime.of(14, 18)), parse("in 10 minutes"))
        assertNull(parse("whenever"))
    }

    @Test fun durations() {
        assertEquals(Duration.ofMinutes(10), TimeArgs.parseDuration("10 min"))
        assertEquals(Duration.ofMinutes(90), TimeArgs.parseDuration("1h 30m"))
        assertEquals(Duration.ofMinutes(5), TimeArgs.parseDuration("5"))
        assertEquals(Duration.ofSeconds(45), TimeArgs.parseDuration("PT45S"))
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
        val m = ModelCatalog.byName("LFM3-1.7B-QAD-Q4_0.gguf")!!
        assertEquals(1049615680L, m.sizeBytes)
        assertEquals(64, m.sha256.length)
        assertTrue(ModelCatalog.all.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.fileName }.toSet().size)
    }

    @Test fun precheckRejectsUnknownNamesAndWrongSizes() {
        assertTrue(ModelCheck.precheck("random.gguf", 10) is CheckResult.Unknown)
        assertTrue(ModelCheck.precheck("LFM3-1.7B-QAD-Q4_0.gguf", 10) is CheckResult.WrongSize)
        assertTrue(ModelCheck.precheck("lfm3-1.7b-qad-q4_0.gguf", 1049615680L) is CheckResult.Ok)
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
