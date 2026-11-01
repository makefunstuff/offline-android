package com.lfmpixel.lfm.ui

import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.ui.theme.LfmMotion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Battery Saver and "Remove animations" are watched live: an open screen follows them without reopening. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = LfmApp::class)
class MotionTest {
    private val app = ApplicationProvider.getApplicationContext<LfmApp>()

    @Test fun batterySaverTurnsMotionOffAndOnLive() {
        val reduced = LfmMotion.live(app)
        assertFalse(reduced.value)
        val power = shadowOf(app.getSystemService(PowerManager::class.java))
        power.setIsPowerSaveMode(true)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(reduced.value)
        power.setIsPowerSaveMode(false)
        app.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(reduced.value)
    }

    @Test fun removeAnimationsIsFollowedLive() {
        val reduced = LfmMotion.live(app)
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(reduced.value)
    }
}
