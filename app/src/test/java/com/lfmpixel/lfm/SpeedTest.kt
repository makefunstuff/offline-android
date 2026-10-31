package com.lfmpixel.lfm

import com.lfmpixel.lfm.assistant.Throttle
import com.lfmpixel.lfm.assistant.speedLabel
import com.lfmpixel.lfm.assistant.withoutSpeed
import com.lfmpixel.lfm.llm.Threads
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTest {
    @Test fun autoThreadsLeaveOneFastCoreFree() {
        assertEquals(Threads(5, 6), Threads.pick(0, 6)) // Tensor G5: 1 big + 5 mid, little cores left out
        assertEquals(Threads(3, 4), Threads.pick(0, 4))
        assertEquals(Threads(2, 2), Threads.pick(0, 2))
        assertEquals(Threads(1, 1), Threads.pick(0, 1))
        assertEquals(Threads(6, 8), Threads.pick(0, 8)) // at most 6 while writing
    }

    @Test fun chosenThreadsAreKept() {
        assertEquals(Threads(4, 6), Threads.pick(4, 6))
        assertEquals(Threads(8, 8), Threads.pick(8, 6))
    }

    @Test fun throttlePushesAtMostEveryIntervalAndFlushesTheRest() {
        var t = 0L
        var pushes = 0
        val th = Throttle(50, now = { t }) { pushes++ }
        th.tick() // first piece shows at once
        repeat(10) { t += 10; th.tick() }
        assertEquals(3, pushes) // at 0, 50 and 100 ms
        th.flush()
        assertEquals(3, pushes) // nothing new since 100 ms
        t += 10; th.tick(); th.flush()
        assertEquals(4, pushes)
    }

    @Test fun speedLineCanBeHidden() {
        val meta = "LFM3-1.7B · on this phone · " + speedLabel(27.6, 420.0)
        assertEquals("LFM3-1.7B · on this phone · 28 tokens/s · first token 0.4 s", meta)
        assertEquals("LFM3-1.7B · on this phone", withoutSpeed(meta))
        assertEquals("LFM3-1.7B · on this phone", withoutSpeed("LFM3-1.7B · on this phone · 9 tokens/s"))
    }
}
