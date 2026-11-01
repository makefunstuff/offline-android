package com.lfmpixel.lfm.crash

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File

/**
 * The closings no Java handler sees: a crash in native code (LiteRT-LM, the voice bridge), an ANR, the low-memory
 * killer. Android keeps a short list of why the app's process ended (ApplicationExitInfo); on the next launch the
 * ones not read before go into the crash log, on this phone only like the rest.
 */
object Exits {
    /** What one ApplicationExitInfo says, without the Android object, so the rules can be tested. */
    data class Exit(val reason: Int, val at: Long, val description: String?, val importance: Int, val pssKb: Long, val rssKb: Long,
                    val trace: String? = null, val status: Int = 0)

    /**
     * The reports to add for [exits], newest first from Android, and the time to remember as read. [seen] is the last
     * one read before, or null on the first launch with the log: then Java crashes are taken too, as no handler of
     * this build wrote them (the crash the owner saw before updating).
     */
    fun reports(exits: List<Exit>, seen: Long?, build: BuildInfo): Pair<List<Pair<Long, String>>, Long?> {
        val fresh = exits.filter { seen == null || it.at > seen }
        val out = fresh.mapNotNull { e ->
            val what = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Wren closed: a crash in native code (the model runtime or the voice bridge)"
                ApplicationExitInfo.REASON_ANR -> "Wren closed: it stopped responding (ANR)"
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Wren closed: it failed to start"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Wren closed by Android: too much CPU or memory"
                // Killed for memory while in use (on screen, or answering with its notice up); in the background it is
                // ordinary Android housekeeping, not a crash.
                ApplicationExitInfo.REASON_LOW_MEMORY -> if (e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
                    "Wren closed by Android: the phone ran out of memory" else null
                // Killed by a signal while on screen: how a phone without low-memory reports shows the memory killer, and
                // how a native library that kills its own process ends. Otherwise (a task killer on swipe-away) not a crash.
                ApplicationExitInfo.REASON_SIGNALED -> if (e.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                    "Wren closed: stopped by signal ${e.status}${if (e.status == 9) " (often the phone running out of memory)" else ""}" else null
                ApplicationExitInfo.REASON_CRASH -> if (seen == null) "Wren closed: a crash, before crash reports were kept" else null
                else -> null
            } ?: return@mapNotNull null
            e.at to CrashLog.report(listOfNotNull(what,
                e.description?.takeIf { it.isNotBlank() }?.let { "Android says: $it" },
                "Memory: ${e.pssKb / 1024} MB in use (${e.rssKb / 1024} MB resident) · importance ${e.importance}"),
                e.trace ?: "No Java stack trace for this kind of closing.", build, e.at)
        }
        return out to (exits.maxOfOrNull { it.at }?.let { maxOf(it, seen ?: 0) } ?: seen)
    }

    /** On launch, off the main thread: adds what Android saw since last time to [log]. */
    fun collect(context: Context, log: CrashLog, build: BuildInfo) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(log.dir, "exits-seen")
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrNull()
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 8) }.getOrDefault(emptyList())
        val exits = infos.map { i ->
            // An ANR comes with the threads' stacks as text; a native crash's tombstone is binary, so only its summary.
            val trace = if (i.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                i.traceInputStream?.use { s -> String(s.readNBytes(CrashLog.MAX_TRACE + 1)) }
            }.getOrNull() else null
            Exit(i.reason, i.timestamp, i.description, i.importance, i.pss, i.rss, trace, i.status)
        }
        val (reports, read) = reports(exits, seen, build)
        // Read first: a crash while writing them, or one at the very start of this launch (which the Java handler
        // writes with its trace), must not bring the same exits back next time.
        runCatching { log.dir.mkdirs(); seenFile.writeText((read ?: 0L).toString()) }
        // Only a closing of the last two days is "last time" for the note on launch; older ones are listed quietly.
        val recent = System.currentTimeMillis() - RECENT_MS
        reports.reversed().forEach { (at, text) -> log.recordText(text, fatal = true, at = at, point = at >= recent) }
    }

    private const val RECENT_MS = 48 * 3_600_000L
}
