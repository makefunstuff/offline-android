package com.lfmpixel.lfm.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Plays Omni's voice (24 kHz mono float) as it is made. The model hands audio over faster than it plays, so it goes
 * into a queue that a player thread writes to the speaker; the model is then free sooner. Playback starts once
 * [prebufferMs] of audio is waiting (or the stretch is complete), so a phone that makes speech a little slower than
 * it plays still sounds smooth.
 */
class AudioOut(private val rate: Int = 24_000, private val prebufferMs: Int = 700) {
    private val _level = MutableStateFlow(0f)
    /** Loudness of what is playing now, 0..1, for the bars on the Talk screen. */
    val level: StateFlow<Float> = _level
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    /** Debug builds keep the last thing said here (raw float PCM), so emulator tests can check it. */
    var keepLastIn: File? = null

    private class Run(val queue: LinkedBlockingQueue<FloatArray> = LinkedBlockingQueue()) {
        @Volatile var stopped = false
        val done = java.util.concurrent.CountDownLatch(1)
        var onDone: (() -> Unit)? = null
    }

    private var run: Run? = null
    private val END = FloatArray(0)

    /** Starts a new stretch of speech; anything still playing stops. */
    @Synchronized
    fun start() {
        stop()
        val r = Run()
        run = r
        _playing.value = true
        val keep = keepLastIn?.let { runCatching { FileOutputStream(it) }.getOrNull() }
        thread(name = "speech-out", priority = Thread.MAX_PRIORITY) { play(r, keep) }
    }

    /** Adds audio to the stretch started last. */
    fun write(pcm: FloatArray) { run?.takeIf { !it.stopped }?.queue?.add(pcm) }

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
        run?.let { it.stopped = true; it.queue.clear(); it.queue.add(END) }
        run = null
        _level.value = 0f
        _playing.value = false
    }

    private fun play(r: Run, keep: FileOutputStream?) {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(min, rate * 4 / 5)) // 200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var written = 0L
        try {
            // Wait for a little audio first. If the model falls behind and the queue runs dry, wait again for a longer
            // stretch each time, so a slow phone speaks in whole phrases instead of stuttering frame by frame.
            val pending = ArrayDeque<FloatArray>()
            var ended = false
            var target = rate * prebufferMs / 1000
            var started = false
            while (!r.stopped) {
                if (pending.isEmpty()) {
                    val next = r.queue.poll()
                    if (next === END) break
                    if (next != null) pending.addLast(next)
                    else {
                        if (started) { target = minOf(target * 2, rate * 4); track.pause() }
                        var have = 0
                        while (!r.stopped && have < target) {
                            val pcm = r.queue.take()
                            if (pcm === END) { ended = true; break }
                            pending.addLast(pcm); have += pcm.size
                        }
                        if (pending.isEmpty()) break
                        track.play()
                        started = true
                    }
                }
                val pcm = pending.removeFirst()
                keep?.write(ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(pcm) }.array())
                // Written in 40 ms slices, so the level follows the voice and Stop is quick.
                var i = 0
                while (i < pcm.size && !r.stopped) {
                    val n = minOf(rate / 25, pcm.size - i)
                    var sum = 0.0
                    for (k in i until i + n) sum += pcm[k] * pcm[k]
                    _level.value = (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
                    val w = track.write(pcm, i, n, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    i += w
                    written += w
                }
                if (ended && pending.isEmpty()) break
            }
            if (!r.stopped) {
                // Let the last of it play out before saying it is done (the track may still be paused for a refill).
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                val end = System.nanoTime() + 2_000_000_000L
                while (!r.stopped && track.playbackHeadPosition < written && System.nanoTime() < end) Thread.sleep(20)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { track.stop() }
            track.release()
            runCatching { keep?.close() }
            if (run === r || run == null) { _level.value = 0f; _playing.value = run != null && run !== r }
            synchronized(r) { r.done.countDown(); r.onDone?.invoke() }
        }
    }
}
