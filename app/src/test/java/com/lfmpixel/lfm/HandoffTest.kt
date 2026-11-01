package com.lfmpixel.lfm

import androidx.test.core.app.ApplicationProvider
import com.lfmpixel.lfm.net.NetSettings
import com.lfmpixel.lfm.net.WebProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The old Inkling Net's settings, as its hand-off version exports them, land in the network module's settings. */
@RunWith(RobolectricTestRunner::class)
class HandoffTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun importsTheOldSettings() {
        val s = NetSettings(ctx)
        assertFalse(s.imported())
        s.importLegacy(mapOf("provider" to "SearXNG", "searxng_url" to "http://10.0.0.2:8888", "searxng_user" to "me",
            "searxng_password" to "pw", "wikipedia" to true, "wiki_lang" to "de", "proxy" to "127.0.0.1:9050",
            "edge_pin" to "http://8.8.8.8:1", "other" to "ignored"))
        val c = s.load()
        assertEquals(WebProvider.SearXNG, c.provider)
        assertEquals("http://10.0.0.2:8888", c.searxngUrl)
        assertEquals("pw", c.searxngPassword)
        assertTrue(c.wikipedia)
        assertEquals("de", c.wikiLang)
        assertEquals("127.0.0.1:9050", c.proxy)
        // The Edge pin always comes from Wren's own Settings, never from the old app.
        assertEquals(null, s.pinnedEdge())
        assertTrue(s.imported())
    }

    @Test fun keepsWhatWasSetUpHere() {
        val s = NetSettings(ctx)
        s.save(s.load().copy(provider = WebProvider.Brave, braveKey = "new"))
        s.importLegacy(mapOf("provider" to "SearXNG", "searxng_url" to "http://10.0.0.2:8888"))
        assertEquals(WebProvider.Brave, s.load().provider)
        assertTrue(s.imported())
    }
}
