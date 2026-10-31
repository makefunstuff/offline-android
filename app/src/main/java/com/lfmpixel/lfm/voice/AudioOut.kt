package com.lfmpixel.lfm.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Plays Omni's voice (24 kHz mono float) as it is made. The model hands audio over into a queue that a player thread
 * (audio priority) writes to the speaker; when to start, and where to wait if the model falls behind, is [Jitter]'s
 * call: it starts quickly when speech is made faster than it plays, waits up front for what it will lack when it is
 * slower, and waits only at a pause in the voice. Every wait and underrun is counted ([last]) and logged (tag
 * lfm-voice).
 */
class AudioOut(private val rate: Int = 24_000, prior: Jitter.Prior = Jitter.prior, learnLength: Boolean = true) {
    private val _level = MutableStateFlow(0f)
    /** Loudness of what is playing now, 0..1, for the bars on the Talk screen. */
    val level: StateFlow<Float> = _level
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** Debug builds keep the last thing said here (raw float PCM), so emulator tests can check it. */
    var keepLastIn: File? = null

    /** The rules, and what it learned of this phone's speed: kept from one stretch to the next. */
    val jitter = Jitter(rate, prior, learnLength)

    /** How the last stretch played. */
    data class Played(
        /** From start() to the first sound. */
        val toSoundMs: Double = 0.0,
        /** From the first audio from the model to the first sound: the buffer's own wait. */
        val bufferMs: Double = 0.0,
        val audioMs: Double = 0.0,
        val underruns: Int = 0,
        /** Waits at a pause in the voice, while the model caught up. */
        val pauses: Int = 0,
        val waitedMs: Double = 0.0,
        /** The track's own count: the speaker ran out of audio (counts the planned waits too). */
        val trackUnderruns: Int = 0,
        val rtf: Double = 0.0,
    )

    @Volatile var last = Played()
        private set

    private class Run(val queue: LinkedBlockingQueue<FloatArray> = LinkedBlockingQueue()) {
        @Volatile var stopped = false
        val done = java.util.concurrent.CountDownLatch(1)
        var onDone: (() -> Unit)? = null
        val startedAt = SystemClock.elapsedRealtimeNanos()
        @Volatile var firstAudioAt = 0L
    }

    @Volatile private var run: Run? = null
    /** The stretch stopped last, until its player has let go: see [awaitStopped]. */
    @Volatile private var stopping: Run? = null
    /** Called on the player thread when a stretch is first heard. */
    @Volatile var onSound: (() -> Unit)? = null
    private val END = FloatArray(0)

    /** Starts a new stretch of speech; anything still playing stops. */
    @Synchronized
    fun start() {
        stop()
        val r = Run()
        run = r
        synchronized(jitter) { jitter.begin() }
        _playing.value = true
        val keep = keepLastIn?.let { runCatching { FileOutputStream(it) }.getOrNull() }
        thread(name = "speech-out") {
            // Above the model's threads, so the speaker is fed even when every core is busy making the next frame.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            play(r, keep)
        }
    }

    /** Adds audio to the stretch started last. */
    fun write(pcm: FloatArray) {
        val r = run?.takeIf { !it.stopped } ?: return
        if (pcm.isEmpty()) return
        val now = SystemClock.elapsedRealtimeNanos()
        if (r.firstAudioAt == 0L) r.firstAudioAt = now
        synchronized(jitter) { jitter.arrived(pcm.size, now / 1e6) }
        r.queue.add(pcm)
    }

    /** The stretch should last about [ms] in all (a guess from its text), so the buffer knows what is still to come. */
    fun expect(ms: Double) = synchronized(jitter) { jitter.expect(ms) }

    /** About [ms] more speech is coming on top of what has come so far. */
    fun expectMore(ms: Double) = synchronized(jitter) { jitter.expectMore(ms) }

    /** No more audio for this stretch: waits until it has all been heard (or was stopped). */
    suspend fun finish() {
        val r = synchronized(this) { run } ?: return
        r.queue.add(END)
        suspendCancellableCoroutine { cont ->
            synchronized(r) {
                if (r.done.count == 0L) cont.resume(Unit) else r.onDone = { cont.resume(Unit) }
            }
            cont.invokeOnCancellation { stop() }
        }
    }

    /** Stops at once, dropping what is still queued. */
    @Synchronized
    fun stop() {
        run?.let { it.stopped = true; it.queue.clear(); it.queue.add(END); stopping = it }
        run = null
        _level.value = 0f
        _playing.value = false
    }

    /** After [stop]: waits (up to [ms]) for the stopped stretch's player to finish, so [last] is its own numbers. */
    suspend fun awaitStopped(ms: Long = 500): Played = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Off the main thread; a player that did not let go in time has no numbers of its own yet.
        if (stopping?.done?.await(ms, TimeUnit.MILLISECONDS) == false) Played() else last
    }

    private fun play(r: Run, keep: FileOutputStream?) {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 5)) // 200 ms: the jitter buffer is the queue, not the track
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val slice = rate * Jitter.SLICE_MS / 1000
        var written = 0L
        var queued = 0L // samples waiting in [pending]
        val pending = ArrayDeque<FloatArray>()
        var head = 0 // read position in pending.first()
        var ended = false
        var started = false
        var soundAt = 0L
        var lastQuiet = true
        fun ms(samples: Long) = samples * 1000.0 / rate
        fun take(block: Boolean): Boolean {
            val next = if (block) r.queue.poll(20, TimeUnit.MILLISECONDS) else r.queue.poll()
            if (next == null) return false
            if (next === END) ended = true else { pending.addLast(next); queued += next.size }
            return true
        }
        fun need(dry: Boolean) = synchronized(jitter) { if (dry) jitter.resumeMs(ms(written)) else jitter.needMs(ms(written)) }
        // Waits until enough audio is waiting (or all of it has come); returns how long it waited.
        fun refill(dry: Boolean = false): Double {
            val t0 = SystemClock.elapsedRealtimeNanos()
            while (!r.stopped && !ended && ms(queued) < need(dry)) { take(true); while (take(false)) {} }
            return (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }
        try {
            while (!r.stopped) {
                while (take(false)) {}
                if (!started) {
                    refill()
                    if (r.stopped || queued == 0L) break
                    track.play()
                    started = true
                    soundAt = SystemClock.elapsedRealtimeNanos()
                    onSound?.invoke()
                    continue
                }
                if (queued == 0L) {
                    if (ended) break
                    // The speaker still has audio of its own: wait for the next frame, nothing is heard yet.
                    if (written - (track.playbackHeadPosition.toLong() and 0xffffffffL) > slice) { take(true); continue }
                    // Ran dry: at a pause in the voice it is only a longer pause; mid-word it is an underrun. A wait
                    // the speaker's own last audio covered was not heard at all.
                    val left = ms(written - (track.playbackHeadPosition.toLong() and 0xffffffffL))
                    val waited = refill(dry = true)
                    if (waited <= left) continue
                    synchronized(jitter) { if (lastQuiet) jitter.paused(waited) else jitter.underrun(waited) }
                    if (!lastQuiet) Log.w(TAG, "underrun: speech ran out mid-word after %.1f s, waited %.0f ms (RTF %.2f)"
                        .format(ms(written) / 1000, waited, jitter.rtf))
                    continue
                }
                val pcm = pending.first()
                val n = minOf(slice, pcm.size - head)
                val quiet = synchronized(jitter) { jitter.quiet(pcm, head, n) }
                lastQuiet = quiet
                val pauseHere = synchronized(jitter) { jitter.pauseHere(ms(queued), quiet, ended, ms(written)) }
                if (pauseHere) {
                    val waited = refill()
                    if (waited >= 20) {
                        synchronized(jitter) { jitter.paused(waited) }
                        Log.i(TAG, "waited %.0f ms at a pause after %.1f s, for the model to catch up (RTF %.2f)"
                            .format(waited, ms(written) / 1000, jitter.rtf))
                    }
                    if (r.stopped) break
                }
                var sum = 0.0
                for (k in head until head + n) sum += pcm[k] * pcm[k]
                _level.value = (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
                keep?.write(ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(pcm, head, n) }.array())
                val w = track.write(pcm, head, n, AudioTrack.WRITE_BLOCKING)
                if (w <= 0) break
                head += w
                written += w
                queued -= w
                if (head >= pcm.size) { pending.removeFirst(); head = 0 }
            }
            if (!r.stopped && started) {
                // Let the last of it play out before saying it is done.
                val end = System.nanoTime() + 2_000_000_000L
                while (!r.stopped && track.playbackHeadPosition < written && System.nanoTime() < end) Thread.sleep(20)
            }
        } catch (e: Exception) {
            Log.w(TAG, "speech out failed", e)
        } finally {
            val trackUnderruns = runCatching { track.underrunCount }.getOrDefault(0)
            runCatching { track.stop() }
            track.release()
            runCatching { keep?.close() }
            // A stretch already replaced by a newer one keeps its numbers to itself.
            if (run === r || run == null) synchronized(jitter) {
                if (!r.stopped) jitter.ended(ms(written))
                last = Played(
                    toSoundMs = if (soundAt > 0) (soundAt - r.startedAt) / 1e6 else 0.0,
                    bufferMs = if (soundAt > 0 && r.firstAudioAt > 0) (soundAt - r.firstAudioAt) / 1e6 else 0.0,
                    audioMs = ms(written), underruns = jitter.underruns, pauses = jitter.pauses,
                    waitedMs = jitter.waitedMs, trackUnderruns = trackUnderruns, rtf = jitter.rtf)
            }
            if (written > 0 && !r.stopped) Log.i(TAG, "played %.1f s: buffer %.0f ms before the first sound, %d underruns, %d waits at a pause (%.0f ms), track underruns %d, RTF %.2f"
                .format(last.audioMs / 1000, last.bufferMs, last.underruns, last.pauses, last.waitedMs, trackUnderruns, last.rtf))
            if (run === r || run == null) { _level.value = 0f; _playing.value = run != null && run !== r }
            synchronized(r) { r.done.countDown(); r.onDone?.invoke() }
        }
    }

    companion object { const val TAG = "lfm-voice" }
}
