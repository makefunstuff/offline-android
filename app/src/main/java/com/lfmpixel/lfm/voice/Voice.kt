package com.lfmpixel.lfm.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Speech to text. The seam for voice capture: Omni in-process today; anything else can stand in. */
interface Transcriber {
    val available: Boolean
    suspend fun transcribe(pcm16k: FloatArray): String
}

/** LFM3-Omni-2B through llama.cpp's mtmd audio input, in this process: no audio leaves the phone. */
class OmniTranscriber(private val models: ModelManager, private val language: () -> String = { "English" }) : Transcriber {
    override val available get() = models.hasVoice() || models.edge?.configured?.value == true
    override suspend fun transcribe(pcm16k: FloatArray): String {
        if (pcm16k.size < 1600) throw LlmException("Too short to hear anything")
        // Edge mode: Omni on the owner's server writes it down; the phone's Omni if the server can't be reached.
        models.edge?.takeIf { it.usable() }?.let { edge ->
            try { return edge.transcribe(pcm16k, language()) } catch (e: com.lfmpixel.lfm.web.NetException) {
                if (!models.hasVoice()) throw LlmException("Your server can't be reached, and Omni isn't on this phone: ${e.message}")
            }
        }
        return models.omniSession().transcribe(pcm16k, "Perform ASR in ${language()}.")
    }
}

enum class VoiceState { Idle, Listening, Transcribing }

/** Records 16 kHz mono from the mic until stopped, then hands the audio to a [Transcriber]. */
class VoiceRecorder(private val transcriber: Transcriber) {
    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    @Volatile private var recording = false
    val available get() = transcriber.available

    fun stop() { recording = false }

    /** Records until [stop] (or 60 s), then transcribes. Returns the text and the length in seconds. */
    @SuppressLint("MissingPermission") // the caller asks for RECORD_AUDIO first
    suspend fun listen(): Pair<String, Int> = try { record() } finally { _state.value = VoiceState.Idle }

    @SuppressLint("MissingPermission")
    private suspend fun record(): Pair<String, Int> {
        val pcm = withContext(Dispatchers.IO) {
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, rate))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw LlmException("The microphone is busy")
            val out = FloatArray(rate * 60)
            var n = 0
            val buf = FloatArray(rate / 10)
            recording = true
            _state.value = VoiceState.Listening
            try {
                rec.startRecording()
                while (recording && coroutineContext.isActive && n < out.size) {
                    val got = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (got <= 0) break
                    val take = minOf(got, out.size - n)
                    System.arraycopy(buf, 0, out, n, take)
                    n += take
                    _level.value = (0 until got).maxOf { abs(buf[it]) }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                recording = false
                _level.value = 0f
            }
            out.copyOf(n)
        }
        _state.value = VoiceState.Transcribing
        return transcriber.transcribe(pcm) to (pcm.size / 16_000)
    }
}
