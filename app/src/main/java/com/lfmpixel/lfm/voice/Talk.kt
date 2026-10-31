package com.lfmpixel.lfm.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** What Talk is doing now; the screen marks it with the highlighter. */
enum class TalkPhase(val label: String) {
    Off(""),
    Loading("Getting ready"),
    Listening("Listening"),
    Hearing("Hearing you"),
    Thinking("Thinking"),
    Speaking("Speaking"),
    Paused("Paused"),
    Failed("Stopped"),
}

/** One exchange: what the owner said and what Omni answered (still growing while it speaks). */
data class TalkTurn(val id: Long, val you: String, val it: String = "", val done: Boolean = false, val stopped: Boolean = false,
                    /** How long the owner spoke. */
                    val seconds: Int = 0)

data class TalkState(
    val phase: TalkPhase = TalkPhase.Off,
    val turns: List<TalkTurn> = emptyList(),
    val error: String? = null,
    /** The phone is warm: answers may be slower (Android's thermal status). */
    val warm: Boolean = false,
    val spoken: Boolean = true,
)

/**
 * Hands-free conversation with LFM3-Omni-2B, all in this process. Each turn: listen until the owner stops talking,
 * transcribe it with Omni, then Omni answers with its text and voice at once. It listens again when the answer has
 * been heard. Half duplex: the mic is closed while it speaks, so it never hears itself; Stop interrupts.
 */
class Talk(
    private val context: Context,
    private val models: ModelManager,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(TalkState())
    val state: StateFlow<TalkState> = _state
    private val recorder = TurnRecorder()
    /** Mic loudness while listening, 0..1. While speaking, see [out]'s level. */
    val micLevel: StateFlow<Float> = recorder.level
    val out = AudioOut()
    private val focus = AudioFocus(context, exclusive = true) { pause() }
    private var job: Job? = null
    private var turnId = 0L
    /**
     * Bumped by pause and close. Work still finishing after that (a mic block, a model step on another thread) sees
     * the change and leaves the state alone, so a stopped Talk never shows "Speaking" again.
     */
    @Volatile private var epoch = 0

    /** Debug builds: a recorded clip stands in for the mic's next turn (emulators have no real mic). */
    val injected get() = recorder.injected

    /** State changes from the loop: only while the run that makes them is still the current one. */
    private fun set(run: Int, change: (TalkState) -> TalkState) = synchronized(this) {
        if (run == epoch) _state.value = change(_state.value)
    }

    val available get() = models.hasVoice()

    /** Starts, or picks up again after a pause or an error. */
    fun start() = synchronized(this) {
        if (job?.isActive == true) return
        _state.value = _state.value.copy(phase = TalkPhase.Loading, error = null)
        val run = epoch
        job = scope.launch { runLoop(run) }
    }

    /**
     * The big button. Speaking or thinking: stop the answer and listen. Listening: pause. Paused: listen again.
     */
    fun stopOrResume() {
        when (_state.value.phase) {
            TalkPhase.Speaking, TalkPhase.Thinking -> interrupt()
            TalkPhase.Listening, TalkPhase.Hearing, TalkPhase.Loading -> pause()
            else -> start()
        }
    }

    private fun interrupt() {
        // The loop sees the cancelled answer and goes back to listening.
        interrupted = true
        models.cancelSpeech()
        out.stop()
    }

    @Volatile private var interrupted = false

    fun pause() = halt(TalkPhase.Paused)

    /** Leaves Talk and forgets the conversation ("Carry on in Chat" takes it first). */
    fun close() {
        halt(TalkPhase.Off)
        recorder.reset()
        synchronized(this) { _state.value = TalkState(spoken = _state.value.spoken) }
    }

    private fun halt(phase: TalkPhase) {
        synchronized(this) {
            epoch++
            job?.cancel()
            job = null
            _state.value = _state.value.copy(phase = phase)
        }
        if (interruptible) models.cancelSpeech()
        out.stop()
        focus.release()
    }

    /** True while Omni is answering: only then does stopping need to reach native code. */
    @Volatile private var interruptible = false

    fun setSpoken(on: Boolean) {
        synchronized(this) { _state.value = _state.value.copy(spoken = on) }
        scope.launch { settings.setTalkSpoken(on) }
    }

    private suspend fun runLoop(run: Int) {
        val p = settings.now()
        set(run) { it.copy(spoken = p.talkSpoken) }
        try {
            val session = models.omniSession()
            if (!session.canSpeak()) throw LlmException("This Omni projector file has no voice part. Get mmproj-LFM3-Omni-2B again in Settings → Models.")
            focus.take()
            while (coroutineContext.isActive) {
                checkWarm(run)
                interrupted = false
                set(run) { it.copy(phase = TalkPhase.Listening) }
                val pcm = recorder.record(onStart = { set(run) { it.copy(phase = TalkPhase.Hearing) } },
                    onRestart = { set(run) { it.copy(phase = TalkPhase.Listening) } })
                if (pcm == null) {
                    set(run) { it.copy(phase = TalkPhase.Paused) }
                    focus.release()
                    return
                }
                set(run) { it.copy(phase = TalkPhase.Thinking) }
                models.keepAlive()
                val lang = settings.now().speechLanguage
                val said = models.omniSession().transcribe(pcm, "Perform ASR in ${lang.asr}.").trim()
                // Stop while it was transcribing: listen again instead of answering.
                if (said.isBlank() || interrupted) continue
                answer(run, said, (pcm.size + 8_000) / 16_000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "talk failed", e)
            out.stop()
            focus.release()
            set(run) { it.copy(phase = TalkPhase.Failed, error = e.message ?: "Talk stopped") }
        }
    }

    private suspend fun answer(run: Int, said: String, seconds: Int) {
        val p = settings.now()
        val id = ++turnId
        val before = _state.value.turns.filter { it.done && it.it.isNotBlank() }.map { it.you to it.it }
        set(run) { it.copy(turns = it.turns + TalkTurn(id, said, seconds = seconds)) }
        val spoken = _state.value.spoken
        val history = SpeechFormat.talkHistory(
            SpeechFormat.talkSystem(p.speechLanguage, SimpleDateFormat("EEEE d MMMM yyyy", Locale.UK).format(Date())), before, said)
        if (spoken) out.start()
        val text = StringBuilder()
        val t0 = System.nanoTime()
        var firstAudio = 0L
        var steps = 0
        // A long answer on a slow phone must not be unloaded under it: count it as use as it goes.
        val alive = { if (++steps % 100 == 0) scope.launch { models.keepAlive() } }
        interruptible = true
        val finished = try {
            models.omniSession().talkReply(history, SpeechFormat.REPLY_PREFIX, 1200, 0.3f, spoken,
                onText = { piece ->
                    text.append(piece)
                    alive()
                    set(run) { st ->
                        st.copy(phase = if (!spoken) TalkPhase.Speaking else st.phase,
                            turns = st.turns.map { if (it.id == id) it.copy(it = text.toString().trim()) else it })
                    }
                    !interrupted && run == epoch
                },
                onAudio = { pcm ->
                    alive()
                    if (firstAudio == 0L) {
                        firstAudio = System.nanoTime()
                        set(run) { it.copy(phase = TalkPhase.Speaking) }
                    }
                    out.write(pcm)
                    !interrupted && run == epoch
                })
        } finally {
            interruptible = false
            // Cut off by a pause (Home, the screen locking, a call): the turn still counts, marked as stopped.
            synchronized(this) {
                _state.value = _state.value.copy(turns = _state.value.turns.map { if (it.id == id && !it.done) it.copy(done = true, stopped = true) else it })
            }
        }
        Log.i(TAG, "answer in %.1f s, first audio after %.1f s: %s".format((System.nanoTime() - t0) / 1e9,
            if (firstAudio == 0L) 0.0 else (firstAudio - t0) / 1e9, text))
        models.keepAlive()
        if (spoken && finished && !interrupted) out.finish() else out.stop()
        val stopped = interrupted || !finished
        set(run) { st -> st.copy(turns = st.turns.map { if (it.id == id) it.copy(done = true, stopped = stopped) else it }) }
    }

    private fun checkWarm(run: Int) {
        val pm = context.getSystemService(PowerManager::class.java)
        set(run) { it.copy(warm = (pm?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) }
    }

    companion object { private const val TAG = "lfm-talk" }
}
