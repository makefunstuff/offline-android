package com.lfmpixel.lfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.crash.Crashes
import com.lfmpixel.lfm.data.LfmDatabase
import com.lfmpixel.lfm.memory.CorrectionKind
import com.lfmpixel.lfm.memory.MemoryStore
import com.lfmpixel.lfm.memory.Sealer
import com.lfmpixel.lfm.voice.AudioOut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The crash causes LFMP-19 fixed, each as it would happen on the phone, ending without an exception escaping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class CrashFixesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: LfmDatabase

    @Before fun setUp() { db = LfmDatabase.inMemory(context) }
    @After fun tearDown() { db.close() }

    /** The keystore refusing for a moment (keystore2 busy, after an update): AndroidKeyStore throws ProviderException. */
    private class BusyKeystore : Sealer {
        override fun seal(plain: String): String = throw java.security.ProviderException("Keystore operation failed")
        override fun open(sealed: String): String? = null
        override fun forget() {}
    }

    private fun memory(scope: CoroutineScope) = MemoryStore(db, BusyKeystore(), embedder = { null }, search = { _, _ -> emptyList() },
        journalLocked = { false }, learning = { true }, scope = scope)

    @Test fun aTalkTurnOrAMovedNoteIsNotLearnedWhenTheKeystoreRefuses() = runBlocking {
        val m = memory(this)
        // Before: each threw out of AppGraph.scope (Talk) or out of the editor's save (a note moved to another kind).
        m.heard("Remind me that Ana's birthday is in March")
        m.corrected(CorrectionKind.Kind, "Buy oat milk", "Note", "Todo")
        assertTrue(m.corrections.value.isEmpty())
        assertEquals(0, db.learned().ofType("said").size + db.learned().ofType("correction").size)
    }

    @Test fun aRefusedSpeakerTrackIsSilenceNotACrash() = runBlocking {
        var escaped: Throwable? = null
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped = e }
        try {
            val out = AudioOut()
            // What AudioTrack.Builder.build() throws when AudioFlinger has no track to give.
            out.newTrack = { throw UnsupportedOperationException("Cannot create AudioTrack") }
            out.start()
            out.write(FloatArray(2400))
            // Before: the player thread died before the stretch ended, so this waited for ever (and the app closed).
            withTimeout(5_000) { out.finish() }
            assertNull(escaped)
            assertEquals(false, out.playing.value)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test fun absurdTimesAreNoTimeNotAThrow() {
        val now = java.time.LocalDateTime.of(2026, 11, 1, 9, 0)
        // Before: DateTimeException / NumberFormatException / ArithmeticException, out of the Ink line's direct tool run.
        assertNull(com.lfmpixel.lfm.tools.TimeArgs.parse("in 999999999999999 days", now))
        assertNull(com.lfmpixel.lfm.tools.TimeArgs.parseDuration("123456789012345678901234"))
        assertNull(com.lfmpixel.lfm.tools.TimeArgs.parseDuration("99999999999999999999 minutes"))
        assertNull(com.lfmpixel.lfm.tools.TimeArgs.parseDuration("9999999999999999 hours"))
        assertNull(com.lfmpixel.lfm.tools.TimeArgs.parseDuration("${Long.MAX_VALUE}"))
        // Ordinary ones still work.
        assertEquals(java.time.LocalTime.of(9, 10), com.lfmpixel.lfm.tools.TimeArgs.parse("in 10 minutes", now)?.time)
        assertEquals(java.time.Duration.ofMinutes(90), com.lfmpixel.lfm.tools.TimeArgs.parseDuration("1h 30m"))
        assertEquals(java.time.Duration.ofMinutes(25), com.lfmpixel.lfm.tools.TimeArgs.parseDuration("25"))
    }

    @Test fun aFailedBackgroundJobIsWrittenDownAndTheRestGoesOn() = runBlocking {
        Crashes.install(context)
        Crashes.log!!.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + Crashes.keepRunning)
        var after = false
        scope.launch { throw IllegalStateException("server reply was not JSON") }
        scope.launch { yield(); after = true }
        repeat(10) { yield() }
        assertTrue("a sibling job still ran", after)
        val r = Crashes.log!!.reports().single()
        assertTrue(r.text, r.title.contains("server reply was not JSON"))
        assertTrue("caught, not a closed app", !r.fatal)
        assertNull(Crashes.log!!.unseen())
    }
}
