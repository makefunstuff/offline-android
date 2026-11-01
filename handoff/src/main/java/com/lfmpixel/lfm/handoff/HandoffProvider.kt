package com.lfmpixel.lfm.handoff

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * One call, "export": the old Inkling Net's settings (its "net" preferences) as a Bundle, for Wren only. Its
 * search keys and proxy were never readable by Wren before, so they cross once, here, over Binder.
 */
class HandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "export") return null
        val ctx = context ?: return null
        // A provider's call() skips Android's permission checks: only Wren, signed with this app's own key.
        val pm = ctx.packageManager
        val callers = pm.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (INKLING !in callers || pm.checkSignatures(ctx.packageName, INKLING) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Only Wren may read these settings")
        val prefs = ctx.getSharedPreferences("net", android.content.Context.MODE_PRIVATE)
        return Bundle().apply {
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object { const val INKLING = "com.lfmpixel.lfm" }
}
