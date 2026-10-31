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
import kotlinx.coroutines.launch

/**
 * Keeps Inkling running while it works on an answer (spec section 7: long jobs in a foreground service with a stop
 * button), so a scanned PDF read page by page or a queue of messages carries on when you leave the app. It starts
 * with a turn while Inkling is on screen and ends when nothing is left to do. Stop ends the answer and holds the
 * queue until Inkling is opened again.
 */
class WorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answers in progress", NotificationManager.IMPORTANCE_LOW))
        startForeground(ID, note("Inkling is answering", ""), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val g = (application as LfmApp).graph
        scope.launch {
            combine(g.assistant.busy, g.assistant.streaming, g.assistant.queue, g.heat.heat) { busy, s, q, heat -> Triple(busy, s, q.size to heat) }
                .collectLatest { (busy, s, more) ->
                    if (!busy) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return@collectLatest }
                    val (waiting, heat) = more
                    val title = when {
                        heat.holdsEverything -> "Waiting for the phone to cool"
                        s?.note?.isNotEmpty() == true -> s.note.removeSuffix("…")
                        s != null && s.text.isEmpty() -> "Inkling is thinking"
                        else -> "Inkling is answering"
                    }
                    nm.notify(ID, note(title, if (waiting > 0) "$waiting more waiting" else "On this phone"))
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) (application as LfmApp).graph.assistant.stopAll()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 4402
        private const val ACTION_STOP = "com.lfmpixel.lfm.STOP_WORK"

        /** Only while Inkling is on screen: Android refuses a foreground service started from the background. */
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkService::class.java)) }
        }
    }
}
