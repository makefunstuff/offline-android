package com.lfmpixel.lfm.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R
import com.lfmpixel.lfm.llm.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps LFM running while a model downloads (a long job gets a foreground service and a notification). */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        foreground()
        val downloads = (application as LfmApp).graph.downloads
        scope.launch {
            downloads.items.collectLatest { items ->
                val now = items.values.firstOrNull { it.state == ModelDownloads.State.Running || it.state == ModelDownloads.State.Checking }
                // Only up to the last start: one that came in meanwhile (a new download) is answered in onStartCommand.
                if (now == null && !downloads.active) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(lastStart); return@collectLatest }
                if (now != null) nm.notify(ID, note(
                    if (now.state == ModelDownloads.State.Checking) "Checking ${now.model.family}" else "Downloading ${now.model.family}",
                    now.done, now.model.sizeBytes))
            }
        }
    }

    private fun note(title: String, done: Long, total: Long): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_lfm).setContentTitle(title)
            .setContentText(if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else "From Hugging Face")
            .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).build()
    }

    /** Android 15+ stops a data-sync service after 6 hours a day: pause the downloads (they resume later) and stop. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as LfmApp).graph.downloads.pauseAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private var lastStart = 0

    /**
     * Every startForegroundService is answered with startForeground, also one that lands while the service is stopping.
     * Android 15+ refuses it once the day's 6 hours of data sync are used: the downloads pause instead of the app closing.
     */
    private fun foreground(): Boolean = try {
        startForeground(ID, note("Starting download", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); true
    } catch (e: Exception) {
        android.util.Log.w("lfm-download", "download notice refused", e)
        (application as LfmApp).graph.downloads.pauseAll()
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val downloads = (application as LfmApp).graph.downloads
        val running = downloads.items.value.values.any { it.state == ModelDownloads.State.Running || it.state == ModelDownloads.State.Checking }
        if (!foreground() || (!running && !downloads.active)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private companion object {
        const val CHANNEL = "downloads"
        const val ID = 4401
    }
}
