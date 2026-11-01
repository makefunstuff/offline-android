package com.lfmpixel.lfm.crash

import android.content.Context
import android.os.Build
import com.lfmpixel.lfm.BuildConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The crash log wired into the app: every uncaught exception is written down before Android closes Inkling, and an
 * exception that escapes a background job of the app's own scope is written down and Inkling keeps running.
 */
object Crashes {
    @Volatile var log: CrashLog? = null
        private set

    /** The fatal report to point out on this launch ("Inkling closed unexpectedly last time · View"), until seen. */
    val unseen = MutableStateFlow<CrashLog.Report?>(null)

    val build: BuildInfo get() = BuildInfo(BuildConfig.GIT_SHA, BuildConfig.VERSION_NAME, BuildConfig.BUILD_TYPE,
        Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT, listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" "))

    /** First thing in Application.onCreate, so a crash while the app is put together is kept too. */
    fun install(context: Context) {
        val l = CrashLog(java.io.File(context.filesDir, "crashes"))
        log = l
        unseen.value = l.unseen()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(handler(l, previous) { build })
    }

    /** Writes the report, then lets [previous] (Android's own) close the app as before. */
    fun handler(l: CrashLog, previous: Thread.UncaughtExceptionHandler?, build: () -> BuildInfo) =
        Thread.UncaughtExceptionHandler { thread, e ->
            try { l.record(e, thread.name, fatal = true, build = runCatching(build).getOrElse { UNKNOWN }) } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }

    /** For the app's long-lived scope: a job that fails is written down and logged; the rest of Inkling goes on. */
    val keepRunning = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("lfm", "background job failed", e)
        log?.record(e, Thread.currentThread().name, fatal = false, build = runCatching { build }.getOrElse { UNKNOWN })
    }

    fun markSeen() {
        log?.markSeen()
        unseen.value = null
    }

    private val UNKNOWN = BuildInfo("unknown", "?", "?", "?", 0, "?")
}
