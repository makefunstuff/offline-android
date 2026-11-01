package com.lfmpixel.lfm

import androidx.compose.runtime.mutableStateListOf
import com.lfmpixel.lfm.assistant.Organizer
import com.lfmpixel.lfm.assistant.Steps
import com.lfmpixel.lfm.edge.EdgeUnreachable
import com.lfmpixel.lfm.ui.screens.OrgJob
import com.lfmpixel.lfm.ui.screens.StepsDraft
import com.lfmpixel.lfm.web.NetException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * LFMP-26: Smaller steps on every path a model run can take (server down, a library that won't load, bad output, a
 * run that goes quiet, Stop, Try again) and the taps on the sheet, each ending in steps to show and nothing thrown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class SmallerStepsTest {
    private val task = "Do the taxes"
    private val answer = "- Find last year's letter (5 min)\n- Open the tax site (2 min)\n- Fill in page one (15 min)"

    private fun fails(t: Throwable): (String, Int) -> Flow<String> = { _, _ -> flow { throw t } }

    @Test fun aServerThatIsDownWithNoModelOnThePhoneGivesACalmStart() = runTest {
        val reported = mutableListOf<Throwable>()
        // What ModelManager.textSession throws in Edge mode with the server away and no LFM3 on the phone.
        val job = OrgJob(this, fails(EdgeUnreachable("Your server can't be reached: timeout")), report = { reported += it })
        job.start(Steps.ask(task), Steps.MAX_TOKENS)
        advanceUntilIdle()
        assertTrue(job.finished); assertFalse(job.running); assertTrue(job.failed)
        val draft = StepsDraft(task).apply { answer(job.text, job.failed) }
        assertTrue(draft.noModel && draft.byRules)
        assertEquals(Steps.starter(task), draft.steps.toList())
        // A server that is simply away is no crash: nothing goes to the crash log.
        assertTrue(reported.isEmpty())
    }

    @Test fun offlineAndARefusedServerEndTheSameWay() = runTest {
        for (t in listOf(NetException("Wren is offline. Turn Offline off in Settings → Privacy."), NetException("HTTP 503: Loading model", "edge-refused"))) {
            val job = OrgJob(this, fails(t))
            job.start("x", 10)
            advanceUntilIdle()
            assertTrue(job.finished && job.failed)
        }
    }

    @Test fun aModelLibraryThatWontLoadIsCaughtAndWrittenDown() = runTest {
        // An Error (not an Exception) from the runtime: the sheet falls back and it is written down as caught.
        val reported = mutableListOf<Throwable>()
        val job = OrgJob(this, fails(UnsatisfiedLinkError("dlopen failed: libLiteRtLm.so")), report = { reported += it })
        job.start("x", 10)
        advanceUntilIdle()
        assertTrue(job.finished && job.failed)
        assertEquals(1, reported.size)
        // And a native session that throws mid-answer keeps what it wrote but still ends.
        val mid = OrgJob(this, { _, _ -> flow { emit("- Find the letter (5 min)"); throw IllegalStateException("Prefill task finished in cancelled state") } })
        mid.start("x", 10)
        advanceUntilIdle()
        assertTrue(mid.finished && mid.failed)
        assertEquals(Steps.starter(task), StepsDraft(task).apply { answer(mid.text, mid.failed) }.steps.toList())
    }

    @Test fun aModelStuckLoadingIsGivenUp() = runTest {
        val job = OrgJob(this, { _, _ -> flow<String> { awaitCancellation() } })
        job.start("x", 10)
        advanceTimeBy(OrgJob.FIRST_WORD_MS - 1); runCurrent()
        assertTrue(job.running)
        advanceTimeBy(2); runCurrent()
        assertTrue(job.finished && job.failed && !job.running)
    }

    @Test fun theServerTimeoutThenAColdLoadOnThePhoneStillAnswers() = runTest {
        // 90 s for the network to give the server up, then minutes for LiteRT to load on the GPU, fail, and load on the CPU.
        val job = OrgJob(this, { _, _ -> flow { delay(90_000 + 150_000); emit(answer) } })
        job.start("x", 10)
        advanceUntilIdle()
        assertTrue(job.finished); assertFalse(job.failed)
        assertEquals(answer, job.text)
    }

    @Test fun anAnswerThatStopsMidwayIsGivenUpKeepingWhatItWrote() = runTest {
        val job = OrgJob(this, { _, _ -> flow { emit(answer); awaitCancellation() } })
        job.start("x", 10)
        advanceTimeBy(OrgJob.FIRST_WORD_MS + 2 * OrgJob.QUIET_MS + 1); runCurrent()
        assertTrue(job.finished && !job.failed)
        assertEquals(3, StepsDraft(task).apply { answer(job.text, job.failed) }.steps.size)
    }

    @Test fun aCancellationFromInsideTheModelStillEndsTheRun() = runTest {
        val job = OrgJob(this, { _, _ -> flow<String> { throw kotlinx.coroutines.CancellationException("inner timeout") } })
        job.start("x", 10)
        advanceUntilIdle()
        assertTrue(job.finished && job.failed && !job.running)
    }

    @Test fun anEmptyAnswerIsNotNoModel() = runTest {
        val job = OrgJob(this, { _, _ -> flow { emit("") } })
        job.start("x", 10)
        advanceUntilIdle()
        assertTrue(job.finished); assertFalse(job.failed)
        val d = StepsDraft(task).apply { answer(job.text, job.failed) }
        assertTrue(d.byRules); assertFalse(d.noModel)
    }

    @Test fun aSlowButSteadyAnswerIsNotGivenUp() = runTest {
        val job = OrgJob(this, { _, _ -> flow { answer.lines().forEach { delay(OrgJob.QUIET_MS / 2); emit(it) } } })
        job.start("x", 10)
        advanceUntilIdle()
        assertTrue(job.finished); assertFalse(job.failed)
    }

    @Test fun stopEndsAtOnceAndKeepsWhatWasWritten() = runTest {
        // A run that, stopped, still winds down (a native call or a request finishing) before it lets go.
        val job = OrgJob(this, { _, _ -> flow {
            emit(answer.lines().take(2).joinToString("\n"))
            try { awaitCancellation() } finally { withContext(NonCancellable) { delay(30_000) } }
        } })
        job.start("x", 10)
        runCurrent()
        job.stop()
        // At once, not when the run has let go.
        assertTrue(job.finished && !job.running && !job.failed)
        assertEquals(2, StepsDraft(task).apply { answer(job.text, job.failed) }.steps.size)
        advanceUntilIdle()
        assertTrue(job.finished && !job.failed)
        assertEquals(2, Organizer.items(job.text, 6).size)
    }

    @Test fun anOldRunNeverWritesOverTryAgain() = runTest {
        var calls = 0
        val job = OrgJob(this, { _, _ ->
            val n = ++calls
            flow {
                if (n == 1) { try { awaitCancellation() } finally { withContext(NonCancellable) { delay(5_000) } } }
                else { delay(10_000); emit(answer) }
            }
        })
        job.start("x", 10)
        runCurrent()
        job.stop()
        job.start("x", 10)
        // The first run lets go at 5 s; before, its finally then set finished while Try again was still writing.
        advanceTimeBy(6_000); runCurrent()
        assertTrue(job.running); assertFalse(job.finished)
        advanceUntilIdle()
        assertTrue(job.finished && !job.failed)
        assertEquals(3, StepsDraft(task).apply { answer(job.text, job.failed) }.steps.size)
    }

    @Test fun badModelOutputGivesTheStartByRules() {
        val bad = listOf("", "   ", "Sure! I can help with that.", "- ", "1)", "- None", "[\"Find the letter\", \"Open it\"]",
            "{\"steps\": [{\"text\": \"Find it\"}]}", "- Only one step (5 min)",
            "- " + "x".repeat(400) + "\n- " + "y".repeat(400))
        for (text in bad) {
            val d = StepsDraft(task).apply { answer(text, failed = false) }
            assertEquals(text, Steps.starter(task), d.steps.toList())
            assertTrue(d.byRules); assertFalse(d.noModel)
        }
    }

    @Test fun aLongOrStrangeAnswerIsCutAndQuick() {
        val many = (1..200).joinToString("\n") { "$it. Step number $it (${it % 15 + 1} min)" }
        assertEquals(6, Steps.parse(many).size)
        // A small model running on: long runs of spaces and dashes in one line, which the size regexes backtrack over.
        val odd = listOf("- Find" + " ".repeat(4_000) + "it", "- " + "- ".repeat(3_000) + "5 min", "- Call" + " -".repeat(2_000) + " 10 m",
            "- " + "9".repeat(5_000) + " min", "- Wait (999 min)", "- Rest (0 min)", "- Sleep (9.9 h)")
        val t0 = System.nanoTime()
        odd.forEach { Steps.parse(it); Organizer.items(it, 6); Organizer.sized(it.removePrefix("- ")) }
        // Sized from the end, as before, for an ordinary line.
        assertEquals(Organizer.Sized("Call Ana", 10), Organizer.sized("Call Ana" + " ".repeat(3) + "- 10 minutes."))
        assertEquals(Organizer.Sized("x".repeat(150), 5), Organizer.sized("x".repeat(150) + " (5 min)"))
        assertTrue("parsing took ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 10_000_000_000L)
        val sizes = Steps.parse(odd.takeLast(3).joinToString("\n")).map { it.minutes }
        assertTrue(sizes.all { it in 1..60 })
    }

    @Test fun aStepLeftOutTwiceAndAddTappedTwiceAreSafe() {
        // Before: × removed by place (removeAt(i)); a second tap before the sheet redrew reached past the list's end.
        val before = mutableStateListOf(*Steps.starter(task).toTypedArray())
        before.removeAt(2)
        assertTrue(runCatching { before.removeAt(2) }.exceptionOrNull() is IndexOutOfBoundsException)

        // Try again after no model answered: while it runs, the sheet no longer says no model could.
        val again = StepsDraft(task).apply { answer("", failed = true) }
        assertTrue(again.noModel)
        again.asking()
        assertFalse(again.noModel || again.byRules)

        val d = StepsDraft(task).apply { rules() }
        val last = d.steps.last()
        d.remove(last); d.remove(last)
        assertEquals(2, d.steps.size)
        assertEquals(2, d.take()?.size)
        assertNull(d.take())
        // All left out: nothing to add.
        val empty = StepsDraft(task).apply { rules() }
        empty.steps.toList().forEach(empty::remove)
        assertNull(empty.take())
    }
}
