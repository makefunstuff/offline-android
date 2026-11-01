package com.lfmpixel.lfm.web

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import com.lfmpixel.lfm.LfmApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Moving from two apps to one. The old Inkling Net kept the search provider, its keys and the proxy in its own
 * storage, which Wren can't read. So Wren carries the last Inkling Net (:handoff, assets/inkling-net-handoff.apk,
 * same key): installed as an update, it hands those settings over once, then the owner uninstalls it. Android asks
 * before each install and uninstall. The Edge server was always kept in Wren and needs nothing.
 */
class NetHandoff(private val context: Context, private val net: NetClient) {
    sealed interface Status {
        /** No old Inkling Net on the phone. */
        data object None : Status
        /** The old one is here; its settings are not over yet. */
        data object Needed : Status
        data object Working : Status
        /** Settings are over; the old app can go. */
        data object Done : Status
        data class Failed(val why: String) : Status
    }

    val status = MutableStateFlow<Status>(Status.None)

    /** Looks at what is installed; imports at once if the hand-off version is already there. */
    suspend fun refresh() {
        if (status.value == Status.Working) return
        val version = installedVersion()
        status.value = when {
            version == null -> Status.None
            net.imported() -> Status.Done
            version >= HANDOFF_VERSION -> importNow()
            else -> Status.Needed
        }
    }

    /** Installs the hand-off version over the old Inkling Net; [NetHandoffReceiver] imports once Android is done. */
    suspend fun install() {
        status.value = Status.Working
        try {
            withContext(Dispatchers.IO) {
                val pi = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(NET_PACKAGE) }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    context.assets.open(ASSET).use { input -> s.openWrite("handoff.apk", 0, -1).use { out -> input.copyTo(out); s.fsync(out) } }
                    val done = PendingIntent.getBroadcast(context, id, Intent(context, NetHandoffReceiver::class.java).setPackage(context.packageName),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(done.intentSender)
                }
            }
        } catch (e: Exception) {
            status.value = Status.Failed(e.message ?: "Could not install")
        }
    }

    /** Reads the settings from the hand-off version; only from an app signed with Wren's own key. */
    suspend fun importNow(): Status = withContext(Dispatchers.IO) {
        if (context.packageManager.checkSignatures(context.packageName, NET_PACKAGE) != PackageManager.SIGNATURE_MATCH)
            return@withContext Status.Failed("That Inkling Net is signed with another key, so its settings were not read")
        runCatching { context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "export", null, null) }.fold(
            { b -> if (b == null) Status.Failed("Inkling Net gave nothing back") else {
                net.importLegacy(b.keySet().associateWith { k -> b.getString(k) ?: b.getBoolean(k) }); Status.Done } },
            { Status.Failed("Could not read Inkling Net: ${it.message}") })
    }.also { status.value = it }

    /** Android's own "Uninstall this app?" for the old Inkling Net. */
    fun uninstallIntent(): Intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$NET_PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun installedVersion(): Long? =
        runCatching { context.packageManager.getPackageInfo(NET_PACKAGE, 0).longVersionCode }.getOrNull()

    companion object {
        const val NET_PACKAGE = "com.lfmpixel.lfm.net"
        const val AUTHORITY = "com.lfmpixel.lfm.net.handoff"
        const val ASSET = "inkling-net-handoff.apk"
        const val HANDOFF_VERSION = 2L
    }
}

/** Android's answer to the hand-off install: ask the owner to confirm, then import, or say why not. */
class NetHandoffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                g.launcher.start(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { val r = goAsync(); g.scope.launch { try { g.handoff.importNow() } finally { r.finish() } } }
            PackageInstaller.STATUS_FAILURE_ABORTED -> g.handoff.status.value =
                NetHandoff.Status.Failed("Not installed. If Android asked to allow installs from Wren, allow it, then tap again.")
            else -> g.handoff.status.value = NetHandoff.Status.Failed("Not installed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it"))
        }
    }
}
