package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.daylight.DayClock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_CLOCK --es at 06:40 (or --es at off): moves Daylight's clock to that
 * time today, to see the sky, the horizon and the widget at any hour (debug builds only).
 */
class DebugClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = intent.getStringExtra("at") ?: "off"
        DayClock.offset.value = if (at == "off") 0L else {
            val zone = ZoneId.systemDefault()
            LocalDate.now().atTime(LocalTime.parse(at)).atZone(zone).toInstant().toEpochMilli() - System.currentTimeMillis()
        }
        Log.i("lfm-daylight", "clock: $at (offset ${DayClock.offset.value} ms)")
        com.lfmpixel.lfm.daylight.HorizonWidget.refresh(context)
    }
}
