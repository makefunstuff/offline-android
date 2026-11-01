package com.lfmpixel.lfm

import com.lfmpixel.lfm.crash.BuildInfo
import com.lfmpixel.lfm.crash.CrashLog
import com.lfmpixel.lfm.crash.Crashes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneOffset

class CrashLogTest {
    private val build = BuildInfo("f41b8eb", "0.1.0", "debug", "16", 36, "Google Pixel 10 Pro")
    private fun log(keep: Int = 10) = CrashLog(Files.createTempDirectory("crashes").toFile(), keep)

    @Test fun aReportSaysWhatWhereAndWhen() {
        val text = CrashLog.format(IllegalStateException("engine closed"), "main", true, build, 1_761_998_400_000, ZoneOffset.UTC)
        assertTrue(text, text.contains("When: 2025-11-01 12:00:00 +00:00"))
        assertTrue(text.contains("Build: f41b8eb (0.1.0, debug)"))
        assertTrue(text.contains("Android: 16 (API 36) · Google Pixel 10 Pro"))
        assertTrue(text.contains("Thread: main"))
        assertTrue(text.contains("java.lang.IllegalStateException: engine closed"))
        assertTrue(text.contains("at com.lfmpixel.lfm.CrashLogTest"))
    }

    @Test fun keepsTheLastTenNewestFirst() {
        val l = log()
        repeat(13) { l.record(RuntimeException("crash $it"), "main", fatal = it % 2 == 0, build = build, now = 1000L + it) }
        val all = l.reports()
        assertEquals(10, all.size)
        assertEquals("java.lang.RuntimeException: crash 12", all.first().title)
        assertEquals("java.lang.RuntimeException: crash 3", all.last().title)
        assertEquals(10, l.dir.listFiles { f -> f.name.endsWith(".txt") }!!.size)
    }

    @Test fun twoInTheSameMillisecondBothKept() {
        val l = log()
        l.record(RuntimeException("a"), "main", true, build, 5)
        l.record(RuntimeException("b"), "worker", true, build, 5)
        assertEquals(2, l.reports().size)
    }

    @Test fun aFatalOneIsPointedOutOnceOnTheNextLaunch() {
        val l = log()
        l.record(RuntimeException("caught"), "main", fatal = false, build = build, now = 1)
        assertNull("a caught one is not a closed app", l.unseen())
        l.record(OutOfMemoryError("big"), "DefaultDispatcher-worker-1", fatal = true, build = build, now = 2)
        // The next launch reads the same folder.
        val next = CrashLog(l.dir)
        assertEquals("java.lang.OutOfMemoryError: big", next.unseen()?.title)
        next.markSeen()
        assertNull(CrashLog(l.dir).unseen())
        assertEquals(2, next.reports().size)
    }

    @Test fun deletingTheUnseenOneClearsTheNote() {
        val l = log()
        l.record(RuntimeException("x"), "main", true, build, 1)
        l.delete(l.reports().single())
        assertNull(l.unseen())
        assertTrue(l.reports().isEmpty())
    }

    @Test fun aHugeTraceIsCut() {
        fun deep(n: Int): Nothing = if (n == 0) throw StackOverflowError("deep") else deep(n - 1)
        val e = runCatching { deep(4000) }.exceptionOrNull()!!
        val text = CrashLog.format(e, "main", true, build, 0)
        assertTrue(text.length < CrashLog.MAX_TRACE + 1024)
        assertTrue(text.contains("(cut at"))
    }

    @Test fun recordNeverThrows() {
        // A folder that can't be made: the handler must still pass the crash on.
        val file = Files.createTempFile("not-a-dir", "").toFile()
        assertNull(CrashLog(java.io.File(file, "crashes")).record(RuntimeException(), "main", true, build))
    }

    @Test fun theHandlerWritesThenPassesTheCrashOn() {
        val l = log()
        var passed: Throwable? = null
        val e = IllegalArgumentException("bad")
        val h = Crashes.handler(l, { _, t -> passed = t }) { build }
        h.uncaughtException(Thread.currentThread(), e)
        assertSame(e, passed)
        assertNotNull(l.unseen())
        // A broken build lookup still writes the report.
        Crashes.handler(l, null) { error("no build") }.uncaughtException(Thread.currentThread(), e)
        assertEquals(2, l.reports().size)
        assertFalse(l.reports().any { it.text.isBlank() })
    }
}
