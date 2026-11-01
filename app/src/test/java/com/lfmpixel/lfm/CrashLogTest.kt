package com.lfmpixel.lfm

import com.lfmpixel.lfm.crash.BuildInfo
import com.lfmpixel.lfm.crash.CrashLog
import com.lfmpixel.lfm.crash.Crashes
import com.lfmpixel.lfm.crash.Exits
import android.app.ApplicationExitInfo
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
        repeat(13) { l.record(RuntimeException("crash $it"), "main", fatal = true, build = build, now = 1000L + it) }
        val all = l.reports()
        assertEquals(10, all.size)
        assertEquals("java.lang.RuntimeException: crash 12", all.first().title)
        assertEquals("java.lang.RuntimeException: crash 3", all.last().title)
        assertEquals(10, l.dir.listFiles { f -> f.name.endsWith(".txt") }!!.size)
    }

    @Test fun aJobFailingOverAndOverNeverPushesOutAClosedApp() {
        val l = log()
        l.record(RuntimeException("the real crash"), "main", fatal = true, build = build, now = 1)
        repeat(15) { l.record(RuntimeException("again $it"), "worker", fatal = false, build = build, now = 100L + it) }
        val all = l.reports()
        assertEquals(10, all.size)
        assertEquals("java.lang.RuntimeException: the real crash", all.single { it.fatal }.title)
        assertEquals("java.lang.RuntimeException: the real crash", l.unseen()?.title)
        // Only closed apps: the oldest of those go.
        repeat(12) { l.record(RuntimeException("closed $it"), "main", fatal = true, build = build, now = 1000L + it) }
        assertTrue(l.reports().all { it.fatal })
        assertEquals(10, l.reports().size)
    }

    @Test fun whatAndroidSawEndTheLastRunIsWrittenDown() {
        val anr = Exits.Exit(ApplicationExitInfo.REASON_ANR, 5_000, "Input dispatching timed out", 100, 900_000, 1_200_000, "\"main\" prio=5\n  at Foo.bar")
        val native = Exits.Exit(ApplicationExitInfo.REASON_CRASH_NATIVE, 4_000, "crash", 100, 2_000_000, 3_000_000)
        val lmkBack = Exits.Exit(ApplicationExitInfo.REASON_LOW_MEMORY, 3_000, null, 400, 0, 0)
        val lmkFront = Exits.Exit(ApplicationExitInfo.REASON_LOW_MEMORY, 2_500, null, 100, 4_000_000, 4_500_000)
        val java = Exits.Exit(ApplicationExitInfo.REASON_CRASH, 2_000, "crash", 100, 0, 0)
        val user = Exits.Exit(ApplicationExitInfo.REASON_USER_REQUESTED, 1_000, null, 100, 0, 0)
        val all = listOf(anr, native, lmkBack, lmkFront, java, user)
        // First launch with the log: Java crashes of the build before are taken too; background memory kills never.
        val (first, read) = Exits.reports(all, null, build)
        assertEquals(listOf(5_000L, 4_000L, 2_500L, 2_000L), first.map { it.first })
        assertEquals(5_000L, read)
        assertTrue(first[0].second.contains("stopped responding (ANR)"))
        assertTrue(first[0].second.contains("at Foo.bar"))
        assertTrue(first[1].second.contains("native code"))
        assertTrue(first[1].second.contains("Memory: 1953 MB in use"))
        assertTrue(first[2].second.contains("ran out of memory"))
        // Later launches: only what is new, and no Java crash (this build's own handler wrote those).
        val (later, read2) = Exits.reports(all + Exits.Exit(ApplicationExitInfo.REASON_CRASH, 6_000, "crash", 100, 0, 0), 4_500, build)
        assertEquals(listOf(5_000L), later.map { it.first })
        assertEquals(6_000L, read2)
        assertTrue(Exits.reports(emptyList(), 7_000, build).first.isEmpty())
    }

    @Test fun aKillBySignalWhileInUseIsWrittenDown() {
        // A phone without low-memory reports shows the memory killer as a signal; before, these were dropped.
        val front = Exits.Exit(ApplicationExitInfo.REASON_SIGNALED, 2_000, null, 100, 3_000_000, 3_500_000, status = 9)
        val back = Exits.Exit(ApplicationExitInfo.REASON_SIGNALED, 1_000, null, 400, 0, 0, status = 9)
        // Its answering notice up, swiped away: some phones' task killers end it so, which is no crash.
        val service = Exits.Exit(ApplicationExitInfo.REASON_SIGNALED, 1_500, null, 125, 0, 0, status = 9)
        val (out, _) = Exits.reports(listOf(front, service, back), 500, build)
        assertEquals(listOf(2_000L), out.map { it.first })
        assertTrue(out[0].second.contains("stopped by signal 9"))
    }

    @Test fun anOldClosingIsListedButNotPointedOut() {
        val l = log()
        l.recordText("Wren crash report\n\nlast week's ANR", fatal = true, at = 1, point = false)
        assertNull(l.unseen())
        assertEquals(1, l.reports().size)
        l.recordText("Wren crash report\n\nlast night's", fatal = true, at = 2)
        assertNotNull(l.unseen())
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
