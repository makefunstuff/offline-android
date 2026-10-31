package com.lfmpixel.lfm.edge

import android.os.SystemClock
import com.lfmpixel.lfm.settings.Settings
import com.lfmpixel.lfm.voice.SpeechChunker
import com.lfmpixel.lfm.voice.SpeechFormat
import com.lfmpixel.lfm.voice.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The server's column in Settings → Models → Benchmark: how fast it writes, how soon the first token comes for a
 * 512-token prompt (network included, as the phone sees it), and for voice how soon the first audio of an answer
 * arrives after a spoken question ends: speech to text, LFM3-1.7B's first sentence, then Omni's first audio. Each
 * is the median of three runs.
 */
class EdgeBench(private val edge: Edge, private val settings: Settings, private val scope: CoroutineScope) {
    data class Result(
        val genTps: Double, val promptTps: Double, val firstTokenMs: Double,
        /** Speech to text of a 3 s question. */
        val heardMs: Double,
        /** From the end of the question to the first word of the answer. */
        val firstWordMs: Double,
        /** From the end of the question to the first audio of the answer's voice. */
        val firstAudioMs: Double,
        /** The voice alone: a sentence in, its first audio out. */
        val ttsFirstAudioMs: Double,
        /** Seconds of speech made per second. */
        val ttsSpeed: Double,
    ) {
        fun encode() = listOf(genTps, promptTps, firstTokenMs, heardMs, firstWordMs, firstAudioMs, ttsFirstAudioMs, ttsSpeed)
            .joinToString(";") { "%.2f".format(Locale.ENGLISH, it) }

        fun text() = "Server: write %.0f tok/s · read %.0f tok/s · first token %.0f ms (512 tokens) · voice: heard %.0f ms, first word %.0f ms, first audio %.0f ms after the question · TTS first audio %.0f ms, %.1f× real time"
            .format(Locale.ENGLISH, genTps, promptTps, firstTokenMs, heardMs, firstWordMs, firstAudioMs, ttsFirstAudioMs, ttsSpeed)

        companion object {
            fun decode(s: String): Result? = s.split(';').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 8 }
                ?.let { Result(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7]) }
        }
    }

    sealed interface State {
        data object Idle : State
        data class Running(val step: String) : State
        data class Done(val result: Result) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = State.Running("Asking the server…")
        job = scope.launch {
            _state.value = try {
                val r = run()
                settings.setEdgeBench(r.encode())
                State.Done(r)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                State.Failed("The server benchmark stopped: ${e.message}")
            }
        }
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    suspend fun run(): Result {
        if (!edge.usable()) throw IllegalStateException("the server isn't answering")
        val text = EdgeSession(edge, EdgeModels.TEXT, null)
        val runs = (1..3).map { i ->
            _state.value = State.Running("Writing on the server ($i of 3)…")
            text.bench(512, 128)
        }
        val voice = (1..3).map { i ->
            _state.value = State.Running("Voice on the server ($i of 3)…")
            voiceRun()
        }
        return Result(median(runs.map { it.genTps }), median(runs.map { it.promptTps }), median(runs.map { it.firstTokenMs }),
            median(voice.map { it[0] }), median(voice.map { it[1] }), median(voice.map { it[2] }), median(voice.map { it[3] }), median(voice.map { it[4] }))
    }

    /** One spoken question, made by the server's own voice, through Talk's pipeline: heard, first word, first audio. */
    private suspend fun voiceRun(): DoubleArray {
        // The question, as audio: the server's voice at 24 kHz, taken down to 16 kHz for the ears.
        val q = mutableListOf<FloatArray>()
        val t0 = SystemClock.elapsedRealtimeNanos()
        var ttsFirst = 0L
        edge.speak("What is a good name for a black cat?", "female") { if (ttsFirst == 0L) ttsFirst = SystemClock.elapsedRealtimeNanos(); q += it; true }
        val made = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
        val pcm24 = FloatArray(q.sumOf { it.size }).also { a -> var o = 0; q.forEach { System.arraycopy(it, 0, a, o, it.size); o += it.size } }
        val pcm16 = FloatArray(pcm24.size * 2 / 3) { i -> pcm24[(i * 3) / 2] }
        val ttsSpeed = pcm24.size / 24_000.0 / made.coerceAtLeast(0.001)

        val end = SystemClock.elapsedRealtimeNanos()
        val heard = edge.transcribe(pcm16, "English")
        val heardAt = SystemClock.elapsedRealtimeNanos()
        val prompt = SpeechFormat.talkHistory(SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "today"), emptyList(), heard) + SpeechFormat.REPLY_PREFIX
        var firstWord = 0L
        var firstAudio = 0L
        val chunker = SpeechChunker()
        var first: String? = null
        // Talk's way: the first piece goes to the voice as soon as it is complete.
        EdgeSession(edge, EdgeModels.TEXT, null).generate(prompt, 120, 0.3f).collect { piece ->
            if (firstWord == 0L) firstWord = SystemClock.elapsedRealtimeNanos()
            if (first == null) chunker.feed(piece).firstOrNull()?.let { p ->
                first = p
                edge.speak(p, "female") { firstAudio = SystemClock.elapsedRealtimeNanos(); false }
            }
        }
        if (first == null) chunker.flush().firstOrNull()?.let { p -> edge.speak(p, "female") { firstAudio = SystemClock.elapsedRealtimeNanos(); false } }
        fun ms(t: Long) = if (t > 0) (t - end) / 1e6 else 0.0
        return doubleArrayOf(ms(heardAt), ms(firstWord), ms(firstAudio), if (ttsFirst > 0) (ttsFirst - t0) / 1e6 else 0.0, ttsSpeed)
    }
}
