package com.lfmpixel.lfm.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lfmpixel.lfm.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Records one spoken turn from the mic: from when the owner starts speaking until a pause, with 300 ms before the
 * first word. The background level it learns is kept from turn to turn, so an answer given straight away is not
 * taken for noise. Used by Talk and by the speech recogniser other apps can call.
 */
class TurnRecorder {
    private val _level = MutableStateFlow(0f)
    /** Mic loudness while listening, 0..1. */
    val level: StateFlow<Float> = _level
    private var end = Endpointer()

    /** Debug builds: a recorded clip plays into the mic's place at the mic's pace (emulators have no real mic). */
    val injected = Channel<FloatArray>(Channel.CONFLATED)

    @Volatile private var finishNow = false

    /** Ends the turn being recorded now, with what was heard so far (another app's "stop listening"). */
    fun finish() { finishNow = true }

    /** Forget the room: the next turn learns the background again (a new Talk, another place). */
    fun reset() { end = Endpointer() }

    /** When the last turn ended: the silence that ended it began this long before (ms), for the voice log. */
    var endSilenceMs = 0
        private set

    /**
     * Records one turn. [onStart] runs when speech begins, [onRestart] when it turned out to be a click. Null when
     * nothing was said for a while. [onPause] gets the turn so far once the owner has been quiet for [PAUSE_MS]: they
     * have probably finished, so it can be heard (transcribed) while the rest of the silence that ends the turn
     * passes; [onResume] says they went on after all, so that early hearing is no use.
     */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    suspend fun record(onStart: () -> Unit = {}, onRestart: () -> Unit = {}, onPause: (FloatArray) -> Unit = {},
                       onResume: () -> Unit = {}): FloatArray? = withContext(Dispatchers.IO) {
        val rate = 16_000
        val block = rate * 30 / 1000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw LlmException("The microphone is busy") }
        val end = end.also { it.reset() }
        val preroll = ArrayDeque<FloatArray>()
        val turn = ArrayList<FloatArray>()
        val buf = FloatArray(block)
        var clip: FloatArray? = null
        var clipPos = 0
        var paused = false
        finishNow = false
        try {
            rec.startRecording()
            while (coroutineContext.isActive) {
                if (finishNow) return@withContext if (turn.isEmpty()) null else join(turn)
                var got = 0
                while (got < block) {
                    val n = rec.read(buf, got, block - got, AudioRecord.READ_BLOCKING)
                    if (n <= 0) throw LlmException("The microphone stopped")
                    got += n
                }
                if (clip == null) clip = injected.tryReceive().getOrNull()?.also { clipPos = 0 }
                clip?.let { c ->
                    for (k in 0 until block) buf[k] = c.getOrElse(clipPos + k) { 0f }
                    clipPos += block
                    if (clipPos > c.size + rate * 2) clip = null
                }
                var sum = 0.0
                for (x in buf) sum += x * x
                val rms = sqrt(sum / block).toFloat()
                _level.value = (rms * 8).coerceIn(0f, 1f)
                val chunk = buf.copyOf()
                when (end.feed(rms)) {
                    Endpointer.Event.Started -> { onStart(); paused = false; turn.addAll(preroll); turn.add(chunk) }
                    Endpointer.Event.Ended -> {
                        turn.add(chunk)
                        endSilenceMs = end.endMs
                        // Still the pause that was heard early (any sound since would have called onResume).
                        return@withContext join(turn)
                    }
                    Endpointer.Event.Idle -> return@withContext null
                    Endpointer.Event.None -> if (end.inSpeech) {
                        turn.add(chunk)
                        val quietMs = end.pausedMs
                        if (!paused && quietMs >= PAUSE_MS) {
                            paused = true
                            // Only the speech and the pause's start: what follows is silence that changes nothing.
                            onPause(join(turn.subList(0, maxOf(1, turn.size - (quietMs - PAUSE_KEEP_MS) / 30))))
                        } else if (paused && quietMs == 0) {
                            paused = false
                            onResume()
                        }
                    } else {
                        if (paused) { paused = false; onResume() }
                        if (turn.isNotEmpty()) { turn.clear(); onRestart() }
                        preroll.addLast(chunk)
                        if (preroll.size > 10) preroll.removeFirst()
                    }
                }
            }
            null
        } finally {
            runCatching { rec.stop() }
            rec.release()
            _level.value = 0f
        }
    }

    companion object {
        /** Quiet this long inside a turn: hear it already (the turn ends after Endpointer's 800 ms). */
        const val PAUSE_MS = 300
        /** Silence kept at the end of what is heard early. */
        const val PAUSE_KEEP_MS = 150
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var i = 0
        for (p in parts) { System.arraycopy(p, 0, out, i, p.size); i += p.size }
        return out
    }
}

/**
 * Audio focus: Talk takes it outright (music pauses while it listens and speaks, and does not reach the mic);
 * read-aloud asks others to duck. Losing it (a call, an alarm) calls [onLoss].
 */
class AudioFocus(context: Context, exclusive: Boolean, private val onLoss: () -> Unit = {}) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(
        if (exclusive) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        // A call, an alarm or navigation took the speaker: let it have it.
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { held = false; onLoss() }
        }
        .build()
    @Volatile private var held = false

    fun take() { if (!held) held = am?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
    fun release() { if (held) { am?.abandonAudioFocusRequest(request); held = false } }
}
