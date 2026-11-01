package com.lfmpixel.lfm.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a report says about the build and the phone. */
data class BuildInfo(val sha: String, val version: String, val buildType: String, val android: String, val sdk: Int, val device: String)

/**
 * Inkling's own crash log: the last [keep] reports, plain text in app-private storage ([dir]). Nothing is sent
 * anywhere; Settings → Crash reports shows them, to copy or share one by hand. [record] runs inside the
 * uncaught-exception handler, so it never throws.
 */
class CrashLog(val dir: File, private val keep: Int = 10) {
    class Report(val file: File, val at: Long, val fatal: Boolean, val text: String) {
        /** The exception's own line ("java.lang.IllegalStateException: …"), the report's title. */
        val title: String get() = text.lineSequence().dropWhile { it.isNotBlank() }.firstOrNull { it.isNotBlank() }?.trim() ?: file.name
    }

    private val marker get() = File(dir, UNSEEN)

    /** Writes a report and keeps the newest [keep]. A fatal one is pointed out on the next launch ([unseen]). */
    fun record(t: Throwable, thread: String, fatal: Boolean, build: BuildInfo, now: Long = System.currentTimeMillis()): File? =
        try { write(format(t, thread, fatal, build, now), fatal, now) } catch (_: Throwable) { null }

    /** A report already written out: a closing Android saw but no Java handler did (native crash, ANR, low memory). */
    fun recordText(text: String, fatal: Boolean, at: Long): File? = try { write(text, fatal, at) } catch (_: Throwable) { null }

    @Synchronized
    private fun write(text: String, fatal: Boolean, now: Long): File {
        dir.mkdirs()
        var f = File(dir, "${if (fatal) FATAL else CAUGHT}-$now.txt")
        var n = 1
        while (f.exists()) f = File(dir, "${if (fatal) FATAL else CAUGHT}-$now-${n++}.txt")
        f.writeText(text)
        if (fatal) marker.writeText(f.name)
        // Over [keep]: the oldest caught ones go first, so a job failing over and over never pushes out a closed app.
        val all = files()
        if (all.size > keep) {
            val (closed, caught) = all.partition { it.name.startsWith(FATAL) }
            (caught.reversed() + closed.reversed()).take(all.size - keep).forEach { it.delete() }
        }
        return f
    }

    /** Every report, newest first. */
    fun reports(): List<Report> = files().mapNotNull { read(it) }

    /** The last fatal report not yet pointed out, if it is still kept. */
    fun unseen(): Report? = runCatching { marker.takeIf { it.exists() }?.readText()?.trim()?.let { read(File(dir, it)) } }.getOrNull()

    fun markSeen() { marker.delete() }

    fun delete(r: Report) { r.file.delete(); if (unseen() == null) markSeen() }

    fun clear() { files().forEach { it.delete() }; markSeen() }

    private fun files(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") && (f.name.startsWith(FATAL) || f.name.startsWith(CAUGHT)) }
        .orEmpty().sortedWith(compareByDescending<File> { at(it) }.thenByDescending { it.name })

    private fun at(f: File) = f.name.removeSuffix(".txt").split('-').getOrNull(1)?.toLongOrNull() ?: f.lastModified()

    private fun read(f: File): Report? = runCatching { Report(f, at(f), f.name.startsWith(FATAL), f.readText()) }.getOrNull()

    companion object {
        private const val FATAL = "fatal"
        private const val CAUGHT = "caught"
        private const val UNSEEN = "unseen"
        /** A stack overflow's trace is thousands of lines: the report keeps the start, which says where. */
        const val MAX_TRACE = 48 * 1024

        fun format(t: Throwable, thread: String, fatal: Boolean, build: BuildInfo, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val sw = StringWriter()
            runCatching { t.printStackTrace(PrintWriter(sw)) }.onFailure { sw.write(t.javaClass.name) }
            return report(listOf("Thread: $thread", if (fatal) "Inkling closed" else "Caught; Inkling kept running"), sw.toString(), build, now, zone)
        }

        /** The report's header (when, build, phone, then [lines]), a blank line, and [body] cut at [MAX_TRACE]. */
        fun report(lines: List<String>, body: String, build: BuildInfo, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
            val trace = body.let { if (it.length > MAX_TRACE) it.take(MAX_TRACE) + "\n… (cut at ${MAX_TRACE / 1024} KB)" else it }
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ENGLISH).format(Instant.ofEpochMilli(now).atZone(zone))
            return buildString {
                appendLine("Inkling crash report")
                appendLine("When: $time")
                appendLine("Build: ${build.sha} (${build.version}, ${build.buildType})")
                appendLine("Android: ${build.android} (API ${build.sdk}) · ${build.device}")
                lines.forEach { appendLine(it) }
                appendLine()
                append(trace.trimEnd())
                appendLine()
            }
        }
    }
}
