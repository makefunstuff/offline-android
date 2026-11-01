package com.lfmpixel.lfm.daylight

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.view.HapticFeedbackConstants
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** The few moments Daylight marks with a sound and a touch (docs/ux-spec.md, "Sound and haptics"). */
enum class Cue {
    /** Talk opens its ear. */
    Listen,
    /** Something was done for the owner (a tool ran). */
    Done,
    /** Something was put back. */
    Undo,
    /** A plan on the horizon was picked: touch only, no sound. */
    Tick,
}

/**
 * Sound and haptics kit: four soft cues, made here as short sine tones (no sound files to license or ship). Sounds play
 * only when Settings → Soft sounds is on and the phone's ringer is on normal; haptics go through the view, so they follow
 * the phone's own touch-feedback setting.
 */
class Feel(private val context: Context, private val scope: CoroutineScope) {
    @Volatile var soundsOn: Boolean = true

    fun cue(cue: Cue, view: View? = null) {
        view?.performHapticFeedback(when (cue) {
            Cue.Listen -> HapticFeedbackConstants.GESTURE_START
            Cue.Done -> HapticFeedbackConstants.CONFIRM
            Cue.Undo -> HapticFeedbackConstants.REJECT
            Cue.Tick -> HapticFeedbackConstants.CLOCK_TICK
        })
        if (cue == Cue.Tick || !soundsOn) return
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        scope.launch(Dispatchers.Default) { runCatching { play(tones(cue)) } }
    }

    private suspend fun play(pcm: FloatArray) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 4).build()
        try {
            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            delay(pcm.size * 1000L / RATE + 80)
        } finally {
            track.release()
        }
    }

    companion object {
        private const val RATE = 48_000
        private val cache = HashMap<Cue, FloatArray>()

        /** Two soft notes per cue: up for listen and done, down for undo. */
        fun tones(cue: Cue): FloatArray = synchronized(cache) {
            cache.getOrPut(cue) {
                when (cue) {
                    Cue.Listen -> notes(659.3 to 90, 880.0 to 140)
                    Cue.Done -> notes(880.0 to 90, 1108.7 to 180)
                    Cue.Undo -> notes(784.0 to 90, 587.3 to 160)
                    Cue.Tick -> FloatArray(0)
                }
            }
        }

        /** A felt-piano-ish note: a sine with a little second harmonic, a 6 ms attack and an exponential fade. Quiet. */
        internal fun notes(vararg n: Pair<Double, Int>): FloatArray {
            val out = ArrayList<Float>()
            n.forEach { (hz, ms) ->
                val len = RATE * ms / 1000
                val attack = RATE * 6 / 1000
                for (i in 0 until len) {
                    val t = i.toDouble() / RATE
                    val env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-5.0 * i / len)
                    val v = sin(2 * PI * hz * t) + 0.25 * sin(4 * PI * hz * t)
                    out += (0.16 * env * v / 1.25).toFloat()
                }
            }
            return out.toFloatArray()
        }
    }
}
