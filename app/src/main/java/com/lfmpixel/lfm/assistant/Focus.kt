package com.lfmpixel.lfm.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One Focus run (notebook-spec A.1, proposal 5): which to-do, how long, and when it started. While paused,
 * [pausedLeftMs] holds what was left and [startedAt] means nothing.
 */
data class FocusRun(val id: Long, val title: String, val totalMs: Long, val startedAt: Long, val pausedLeftMs: Long = -1) {
    val paused get() = pausedLeftMs >= 0
    fun leftMs(now: Long): Long = if (paused) pausedLeftMs else (totalMs - (now - startedAt)).coerceAtLeast(0)
    /** How much of the disc is still there, 1 at the start and 0 when the time is up. */
    fun fraction(now: Long): Float = if (totalMs <= 0) 0f else (leftMs(now).toFloat() / totalMs).coerceIn(0f, 1f)
    fun pause(now: Long) = if (paused) this else copy(pausedLeftMs = leftMs(now))
    fun resume(now: Long) = if (!paused) this else copy(startedAt = now - (totalMs - pausedLeftMs), pausedLeftMs = -1)
    fun plus(ms: Long, now: Long) = if (paused) copy(totalMs = totalMs + ms, pausedLeftMs = pausedLeftMs + ms)
        // Time already up: the extra minutes count from now, not from when it ran out.
        else if (leftMs(now) == 0L) copy(totalMs = (now - startedAt) + ms) else copy(totalMs = totalMs + ms)
}

/**
 * The Focus timer and the cues for "Where was I" (proposal 7), kept in preferences so they outlive the app being
 * closed. The end of a run is an exact alarm that posts one soft notification.
 */
class Focus(private val context: Context) {
    private val prefs = context.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val _run = MutableStateFlow(read())
    val run: StateFlow<FocusRun?> = _run

    private fun read(): FocusRun? {
        val id = prefs.getLong("id", -1)
        if (id < 0) return null
        return FocusRun(id, prefs.getString("title", "").orEmpty(), prefs.getLong("total", 0), prefs.getLong("started", 0), prefs.getLong("pausedLeft", -1))
    }

    private fun write(r: FocusRun?) {
        _run.value = r
        prefs.edit().apply {
            if (r == null) remove("id").remove("title").remove("total").remove("started").remove("pausedLeft")
            else putLong("id", r.id).putString("title", r.title).putLong("total", r.totalMs).putLong("started", r.startedAt).putLong("pausedLeft", r.pausedLeftMs)
        }.apply()
        // The last thing focused on stays for "Where was I", after the run is over.
        if (r != null) prefs.edit().putLong("lastId", r.id).putString("lastTitle", r.title).apply()
        schedule(r)
    }

    fun start(id: Long, title: String, minutes: Int, now: Long = System.currentTimeMillis()) =
        write(FocusRun(id, title, minutes.coerceIn(1, 240) * 60_000L, now))
    fun pause(now: Long = System.currentTimeMillis()) { _run.value?.let { write(it.pause(now)) } }
    fun resume(now: Long = System.currentTimeMillis()) { _run.value?.let { write(it.resume(now)) } }
    fun plus(minutes: Int, now: Long = System.currentTimeMillis()) { _run.value?.let { write(it.plus(minutes * 60_000L, now)) } }
    fun stop() = write(null)

    /** When Wren last left the screen. */
    fun left(at: Long) = prefs.edit().putLong("leftAt", at).apply()
    val leftAt get() = prefs.getLong("leftAt", 0)
    val lastTitle get() = prefs.getString("lastTitle", null)
    val lastId get() = prefs.getLong("lastId", -1).takeIf { it >= 0 }
    /** "Where was I" was seen (or closed) for the time away that ended at [leftAt]. */
    var seenFor: Long
        get() = prefs.getLong("seenFor", 0)
        set(v) { prefs.edit().putLong("seenFor", v).apply() }

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun pending() = PendingIntent.getBroadcast(context, 7_001, Intent(context, FocusReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(r: FocusRun?) {
        alarms.cancel(pending())
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
        if (r == null || r.paused) return
        val at = r.startedAt + r.totalMs
        if (at <= System.currentTimeMillis()) return
        if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending())
    }

    /** After a restart alarms are gone: put the running one back. */
    fun reschedule() = schedule(_run.value)

    companion object {
        const val CHANNEL = "focus"
        const val NOTIFICATION = 7_001
        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Focus timer", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "One soft note when a Focus timer ends"
                })
        }
    }
}

/** Time's up: one soft notification, no alarm sound loop and no judgment. */
class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as com.lfmpixel.lfm.LfmApp).graph
        val r = graph.focus.run.value ?: return
        if (r.paused || r.leftMs(System.currentTimeMillis()) > 5_000) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 7_002, MainActivity.intent(context, MainActivity.OPEN_FOCUS), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Focus.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lfm).setContentTitle("Time's up: ${r.title}")
            .setContentText("Done, or 5 more minutes? Both are fine.")
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).build()
        context.getSystemService(NotificationManager::class.java).notify(Focus.NOTIFICATION, n)
    }
}
