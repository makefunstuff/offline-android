package com.lfmpixel.lfm.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps Wren running while it works on an answer (spec section 7: long jobs in a foreground service with a stop
 * button), so a scanned PDF read page by page or a queue of messages carries on when you leave the app. It starts
 * as Wren leaves the screen with work under way (MainActivity.onPause) and ends when nothing is left to do. Stop ends the answer and holds the
 * queue until Wren is opened again.
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        // Again here: an older instance's onDestroy may have run after start() set it.
        started = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answers in progress", NotificationManager.IMPORTANCE_LOW))
        foreground()
        val g = (application as LfmApp).graph
        scope.launch {
            // Only the words of the notice, so a streamed token doesn't post it again.
            combine(g.assistant.busy, g.assistant.streaming, g.assistant.queue, g.heat.heat) { busy, s, q, heat ->
                if (!busy) null else {
                    val title = when {
                        heat.holdsEverything -> "Waiting for the phone to cool"
                        s?.note?.isNotEmpty() == true -> s.note.removeSuffix("…")
                        s != null && s.text.isEmpty() -> "Wren is thinking"
                        else -> "Wren is answering"
                    }
                    title to (if (q.isNotEmpty()) "${q.size} more waiting" else "On this phone")
                }
            }.distinctUntilChanged().collectLatest { words ->
                // Only up to the last start: one that came in meanwhile (Wren left again) is answered in onStartCommand.
                if (words == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStart); return@collectLatest }
                shown = words
                nm.notify(ID, note(words.first, words.second))
            }
        }
    }

    private fun note(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_lfm).setContentTitle(title).setContentText(text)
        .setProgress(0, 0, true).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "Stop", PendingIntent.getService(this, 1, Intent(this, WorkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE))
        .build()

    private var lastStart = 0
    private var shown = "Wren is answering" to ""

    /**
     * Every startForegroundService must be answered with startForeground, also one that lands while the service is
     * stopping (Android otherwise closes the app: "did not then call Service.startForeground()").
     */
    private fun foreground() {
        try { startForeground(ID, note(shown.first, shown.second), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        catch (e: Exception) { android.util.Log.w("lfm", "work notice refused", e) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val g = (application as LfmApp).graph
        when (intent?.action) {
            ACTION_STOP -> g.assistant.stopAll()
            // Wren is on screen again: the notice is not needed (onCreate already answered the start it came after).
            ACTION_BACK -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); return START_NOT_STICKY }
            else -> foreground()
        }
        // Nothing left to do by the time this start came: stop, now with the latest start.
        if (!g.assistant.busy.value) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { started = false; scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 4402
        private const val ACTION_STOP = "com.lfmpixel.lfm.STOP_WORK"
        private const val ACTION_BACK = "com.lfmpixel.lfm.WORK_BACK"
        /** Started and not yet destroyed: [back] has something to end. */
        @Volatile private var started = false

        /** Only while Wren is on screen: Android refuses a foreground service started from the background. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkService::class.java)); started = true }
        }

        /**
         * Wren is back on screen. Not stopService: back within moments (a permission dialog, the share sheet, the
         * shade), it could land before onCreate's startForeground, and Android closes an app whose foreground service
         * stops before that. A start of its own goes after the first, so the service ends itself once it is foreground.
         */
        fun back(context: Context) {
            if (started) runCatching { context.startService(Intent(context, WorkService::class.java).setAction(ACTION_BACK)) }
        }
    }
}
