package com.lfmpixel.lfm.voice

import android.os.Build
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.llm.SpeechStats
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.sqrt

/** What the voice benchmark measured, stage by stage, and the same as plain text for Copy results. Times in ms. */
data class VoiceReport(
    val at: LocalDateTime,
    val backend: String,
    val loadMs: Double,
    val tts: SpeechStats,
    val questionMs: Double,
    /** Voice activity detection on the spoken question: speech heard, the pause noticed, the turn over (after it stopped). */
    val vadStartMs: Double, val vadPauseMs: Double, val vadEndMs: Double,
    val asrMs: Double, val asrAudioInMs: Double, val asrTokens: Int, val asrText: String,
    val prewarmMs: Double, val prewarmTokens: Int,
    val heardInMs: Double,
    val turns: List<Turn>,
    val log: List<VoiceTurn>,
) {
    data class Turn(val stats: SpeechStats, val sim: PlaybackSim, val text: String, val rtfLearned: Double,
                    /** The conversation and the question read in ahead, as Talk does while the owner's silence passes. */
                    val readInMs: Double = 0.0)

    /** From the owner stopping to the first sound, for the first answer: what Talk would do with this phone's numbers. */
    val stopToSoundMs: Double get() {
        val t = turns.firstOrNull() ?: return 0.0
        // The early hearing starts at the pause; the answer starts once both it and the end of the turn are in.
        return maxOf(vadEndMs, vadPauseMs + asrMs + heardInMs) + t.sim.startMs
    }

    fun text(): String = buildString {
        fun f(v: Double) = "%.0f".format(Locale.ENGLISH, v)
        fun f1(v: Double) = "%.1f".format(Locale.ENGLISH, v)
        fun f2(v: Double) = "%.2f".format(Locale.ENGLISH, v)
        appendLine("Wren voice benchmark · ${at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH))}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE}")
        appendLine("Omni on $backend · load ${f1(loadMs / 1000)} s")
        appendLine("You stop → voice starts (estimate, answer 1): ${f(stopToSoundMs)} ms")
        appendLine("VAD: speech heard after ${f(vadStartMs)} ms · pause noticed ${f(vadPauseMs)} ms after you stop · turn over after ${f(vadEndMs)} ms")
        appendLine("ASR (Omni audio in): ${f1(questionMs / 1000)} s of speech · ${f(asrMs)} ms (audio in + prompt ${f(asrAudioInMs)}, $asrTokens tokens) · \"$asrText\"")
        appendLine("Read in ahead: instructions ${prewarmTokens} tokens ${f(prewarmMs)} ms · with your words ${f(heardInMs)} ms")
        appendLine("TTS (question): ${tts.frames} frames, first audio ${f(tts.firstAudioMs)} ms, RTF ${f2(tts.rtf)}")
        turns.forEachIndexed { i, t ->
            val s = t.stats
            val pf = { v: Double -> if (s.frames > 0) f2(v / s.frames) else "0" }
            appendLine("Answer ${i + 1}: conversation read in ahead ${f(t.readInMs)} ms, then ${s.promptTokens} tokens (+${s.reusedTokens} kept) ${f(s.promptMs)} ms · first text ${f(s.firstTextMs)} · first audio ${f(s.firstAudioMs)} ms")
            appendLine("  LLM ${f1(s.stepsPerSecond)} steps/s (text ${f1(s.textTps)} tok/s, ${s.textTokens} tok + ${s.frames} frames) · per frame: backbone ${pf(s.frameMs)}, depth ${pf(s.depthMs)}, codec ${pf(s.codecMs)} ms")
            appendLine("  Speech ${f1(s.audioMs / 1000)} s in ${f1(s.genMs / 1000)} s: RTF ${f2(s.rtf)} · playback: starts ${f(t.sim.startMs)} ms in, underruns ${t.sim.underruns}, waits at pauses ${t.sim.pauses} (${f(t.sim.waitedMs)} ms)")
            appendLine("  \"${t.text.take(120)}\"")
        }
        if (log.isNotEmpty()) {
            appendLine("Recent Talk turns:")
            log.forEach { appendLine("  " + it.line()) }
        }
    }.trimEnd()
}

/**
 * Settings → Models → Benchmark → Voice: Omni speaks a question, Talk's voice activity detection and speech to text
 * hear it back, then Omni answers it twice as in Talk. Playback is replayed in virtual time with Talk's own jitter
 * buffer ([simulatePlayback]), so the numbers say whether it would stutter without making a sound.
 */
class VoiceBench(private val models: ModelManager, private val settings: Settings, private val talk: Talk,
                 private val readAloud: ReadAloud, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Running(val step: String) : State
        data class Done(val report: VoiceReport) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = State.Running("Starting…")
        job = scope.launch {
            _state.value = try { State.Done(run()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                State.Failed(e.message ?: "The voice benchmark could not run")
            }
        }
    }

    private fun step(s: String) { _state.value = State.Running(s) }

    private suspend fun run(): VoiceReport {
        if (!models.hasVoice()) throw LlmException("Voice needs LFM3-Omni-2B and its projector. Import them in Settings → Models.")
        val log = talk.turnLog.value
        if (talk.state.value.phase != TalkPhase.Off && talk.state.value.phase != TalkPhase.Paused) talk.pause()
        readAloud.stop()
        talk.loadPace()
        val p = settings.now()
        step("Loading Omni…")
        val t0 = System.nanoTime()
        val s = models.omniSession()
        val loadMs = (System.nanoTime() - t0) / 1e6

        step("Speaking the question…")
        val q24 = ArrayList<FloatArray>()
        s.speak(SpeechFormat.ttsPrompt(QUESTION, SpeechLanguage.EnglishUK, p.speechVoice), 200) { q24.add(it); true }
        val tts = s.speechStats()
        val q16 = to16k(q24)

        step("Hearing it (voice activity, speech to text)…")
        val vad = vad(q16)
        val asrStart = System.nanoTime()
        val said = s.transcribe(q16, "Perform ASR in English.").trim()
        val asrMs = (System.nanoTime() - asrStart) / 1e6
        val asr = s.stats()

        step("Reading Talk's instructions in…")
        val system = SpeechFormat.talkSystem(SpeechLanguage.EnglishUK, "Saturday 31 October 2026")
        val pw0 = System.nanoTime()
        val warmTokens = s.talkPrewarm(SpeechFormat.talkPrefix(system, emptyList()))
        val prewarmMs = (System.nanoTime() - pw0) / 1e6

        val turns = mutableListOf<VoiceReport.Turn>()
        val done = mutableListOf<Pair<String, String>>()
        // Starts from what Talk knows, as Talk would; what it learns is kept for Talk too.
        val jitter = Jitter()
        var heardInMs = 0.0
        for ((i, question) in listOf(said.ifBlank { QUESTION }, FOLLOW_UP).withIndex()) {
            step("Answer ${i + 1} of 2…")
            // As Talk does once it has heard the owner: the conversation with their words read in, then the answer.
            val h0 = System.nanoTime()
            s.talkPrewarm(SpeechFormat.talkHistory(system, done, question))
            val readIn = (System.nanoTime() - h0) / 1e6
            if (i == 0) heardInMs = readIn
            val chunks = ArrayList<Pair<Double, FloatArray>>()
            val textAt = ArrayList<Pair<Double, Int>>()
            val text = StringBuilder()
            val r0 = System.nanoTime()
            s.talkReply(SpeechFormat.talkHistory(system, done, question), SpeechFormat.REPLY_PREFIX, 1200, 0.3f, true,
                onText = { text.append(it); textAt.add((System.nanoTime() - r0) / 1e6 to text.length); true },
                onAudio = { pcm -> chunks.add((System.nanoTime() - r0) / 1e6 to pcm); true })
            val st = s.speechStats()
            // As Talk tells its buffer: the answer's length guessed from the text so far.
            val sim = simulatePlayback(jitter, chunks) { t -> (textAt.lastOrNull { it.first <= t }?.second ?: 0) * talk.msPerChar }
            turns += VoiceReport.Turn(st, sim, text.toString().trim(), jitter.rtf, readIn)
            done += question to text.toString().trim()
        }
        models.keepAlive()
        settings.setVoicePace(Jitter.prior.rtf, Jitter.prior.usualMs)
        return VoiceReport(LocalDateTime.now(), com.lfmpixel.lfm.llm.Backend.LiteRtCpu.label, loadMs, tts, q16.size / 16.0, vad[0], vad[1], vad[2],
            asrMs, asr.prefillMs, asr.decodeTokens, said, prewarmMs, warmTokens, heardInMs, turns, log)
    }

    /** Talk's endpointer over the question, with quiet room before and after: when it heard speech start, noticed the pause, ended the turn, from the speech's own start and end. */
    private fun vad(q16: FloatArray): DoubleArray {
        val block = 480
        val lead = 16 * 900
        val audio = FloatArray(lead + q16.size + 16 * 1500)
        val rnd = java.util.Random(1)
        for (k in audio.indices) audio[k] = (rnd.nextGaussian() * 0.002).toFloat()
        for (k in q16.indices) audio[lead + k] += q16[k]
        val rms = DoubleArray(audio.size / block) { b ->
            var sum = 0.0
            for (k in b * block until (b + 1) * block) sum += audio[k] * audio[k]
            sqrt(sum / block)
        }
        val voiced = rms.indices.filter { rms[it] > 0.02 }
        val firstVoice = (voiced.firstOrNull() ?: 0) * 30.0
        val lastVoice = ((voiced.lastOrNull() ?: 0) + 1) * 30.0
        val e = Endpointer()
        var start = 0.0; var pause = 0.0; var end = 0.0
        for (b in rms.indices) {
            val ev = e.feed(rms[b].toFloat())
            val t = (b + 1) * 30.0
            if (ev == Endpointer.Event.Started && start == 0.0) start = t - firstVoice
            if (pause == 0.0 && e.pausedMs >= TurnRecorder.PAUSE_MS) pause = t - lastVoice
            if (ev == Endpointer.Event.Ended) { end = t - lastVoice; break }
        }
        return doubleArrayOf(start, pause, end)
    }

    private fun to16k(parts: List<FloatArray>): FloatArray {
        val n = parts.sumOf { it.size }
        val a = FloatArray(n)
        var o = 0
        parts.forEach { System.arraycopy(it, 0, a, o, it.size); o += it.size }
        return FloatArray(n * 2 / 3) { i ->
            val x = i * 1.5; val k = x.toInt(); val f = (x - k).toFloat()
            a[k] * (1 - f) + (if (k + 1 < n) a[k + 1] else 0f) * f
        }
    }

    companion object {
        const val QUESTION = "What is a nice thing to cook for dinner tonight?"
        const val FOLLOW_UP = "And how long does that take?"
    }
}
