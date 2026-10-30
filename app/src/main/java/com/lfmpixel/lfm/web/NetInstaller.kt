package com.lfmpixel.lfm.web

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.lfmpixel.lfm.LfmApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Installs LFM Net from the copy built into LFM (assets/lfm-net.apk, same key), so the owner can add the helper
 * without a computer or the network. Android shows its own install prompt; nothing installs without that tap.
 */
class NetInstaller(private val context: Context) {
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data object Installed : Status
        data class Failed(val why: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.Idle)

    fun bundled() = runCatching { context.assets.open(ASSET).close(); true }.getOrDefault(false)

    suspend fun install() {
        status.value = Status.Working
        try {
            withContext(Dispatchers.IO) {
                val pi = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(com.lfmpixel.lfm.ipc.NetContract.NET_PACKAGE) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    context.assets.open(ASSET).use { input -> s.openWrite("lfm-net.apk", 0, -1).use { out -> input.copyTo(out); s.fsync(out) } }
                    val done = PendingIntent.getBroadcast(context, id, Intent(context, NetInstallReceiver::class.java).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(done.intentSender)
                }
            }
        } catch (e: Exception) {
            status.value = Status.Failed(e.message ?: "Could not install")
        }
    }

    companion object { const val ASSET = "lfm-net.apk" }
}

/** Android's answer to an install: ask the owner to confirm, then done or failed. */
class NetInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                g.launcher.start(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { g.netInstaller.status.value = NetInstaller.Status.Installed; g.net.refresh() }
            PackageInstaller.STATUS_FAILURE_ABORTED -> g.netInstaller.status.value =
                NetInstaller.Status.Failed("Not installed. If Android asked to allow installs from LFM, allow it, then tap Install again.")
            else -> g.netInstaller.status.value = NetInstaller.Status.Failed("Not installed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it"))
        }
    }
}
