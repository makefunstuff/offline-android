package com.lfmpixel.lfm.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import com.lfmpixel.lfm.web.NetException
import android.util.Log
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
                    val seconds: Int = 0,
                    /** What the turn did with the app's tools, shown under the caption with Undo and Show. */
                    val actions: List<TalkAction> = emptyList())

/** One thing a Talk turn did: "Reminder set", "Dentist at 15:00". [undo] is the action log's way back ("entry:12"), "" if none. */
data class TalkAction(val actionId: Long?, val ok: Boolean, val label: String, val title: String, val undo: String)

/**
 * How quickly the last answer came, in ms from the end of the owner's turn (when the end of speech was heard):
 * the words written down, the first word of the answer, the first audio from the voice, the first sound heard.
 */
data class TalkTiming(val heardMs: Long, val firstWordMs: Long, val firstAudioMs: Long, val firstSoundMs: Long,
                      val viaServer: Boolean, val underruns: Int = 0,
                      /** The model that wrote the answer ("Qwen3.8-27B", "LFM3-1.7B", "LFM3-Omni-2B"), and the tools it ran. */
                      val model: String = "", val tools: Int = 0) {
    override fun toString() = "heard %d ms · first word %d ms · first audio %d ms · first sound %d ms · underruns %d · %s · %s · %d tools"
        .format(heardMs, firstWordMs, firstAudioMs, firstSoundMs, underruns, if (viaServer) "server" else "phone", model, tools)
}

/**
 * The app's tools for Talk's answer model (Qwen, on the server): their specs, and a run that previews, confirms when the
 * owner wants that, runs and logs each call, as in the chat (Assistant.execute). Set by AppGraph.
 */
interface TalkTools {
    fun specs(): List<String>
    suspend fun run(call: com.lfmpixel.lfm.llm.ToolCall): com.lfmpixel.lfm.tools.ToolResult
}

/** One Talk turn, stage by stage: logged (tag lfm-voice) and kept for Settings → Models → Benchmark. Times in ms. */
data class VoiceTurn(
    val at: Long = System.currentTimeMillis(),
    /** How long the owner spoke. */
    val heardMs: Int = 0,
    /** The silence that ends a turn (voice activity detection): the owner stopped this long before it was over. */
    val endSilenceMs: Int = 0,
    /** Speech to text, all of it. */
    val asrMs: Double = 0.0,
    /** Of that, how much ran before the turn was over (heard early, during the silence). */
    val asrEarlyMs: Double = 0.0,
    val asrTokens: Int = 0,
    val asrAudioInMs: Double = 0.0,
    val speech: com.lfmpixel.lfm.llm.SpeechStats = com.lfmpixel.lfm.llm.SpeechStats(),
    val played: AudioOut.Played = AudioOut.Played(),
    /** From the end of the turn (the silence over) to the first sound. */
    val endToSoundMs: Double = 0.0,
    val spoken: Boolean = true,
    /** Stopped by the owner (or a pause) before it was all said. */
    val stopped: Boolean = false,
) {
    /** From when the owner stopped talking to the first sound of the answer; 0 if there was none. */
    val stopToSoundMs get() = if (endToSoundMs > 0) endSilenceMs + endToSoundMs else 0.0

    fun line(): String = buildString {
        fun f(v: Double) = "%.0f".format(java.util.Locale.ENGLISH, v)
        fun f2(v: Double) = "%.2f".format(java.util.Locale.ENGLISH, v)
        val sp = speech
        append(if (stopToSoundMs > 0) "stop→sound ${f(stopToSoundMs)} ms" else "no sound")
        if (stopped) append(" · stopped")
        append(" (end of turn ${endSilenceMs} · ASR ${f(asrMs)}, ${f(asrEarlyMs)} early, ${asrTokens} tok · ")
        append("read ${sp.promptTokens} tok +${sp.reusedTokens} reused ${f(sp.promptMs)} · first text ${f(sp.firstTextMs)} · first audio ${f(sp.firstAudioMs)} · ")
        append("buffer ${f(played.bufferMs)}) · ${"%.1f".format(java.util.Locale.ENGLISH, sp.stepsPerSecond)} steps/s · ")
        append("RTF ${f2(sp.rtf)} · per frame: backbone ${f2(if (sp.frames > 0) sp.frameMs / sp.frames else 0.0)}, depth ${f2(if (sp.frames > 0) sp.depthMs / sp.frames else 0.0)}, codec ${f2(if (sp.frames > 0) sp.codecMs / sp.frames else 0.0)} · ")
        append("${"%.1f".format(java.util.Locale.ENGLISH, played.audioMs / 1000)} s played, underruns ${played.underruns}, waits ${played.pauses} (${f(played.waitedMs)} ms)")
        if (!spoken) append(" · text only")
    }
}

data class TalkState(
    val phase: TalkPhase = TalkPhase.Off,
    /** The last answer came from the owner's server (Edge mode), not the phone. */
    val viaServer: Boolean = false,
    val timing: TalkTiming? = null,
    val turns: List<TalkTurn> = emptyList(),
    val error: String? = null,
    /** The phone is warm: answers may be slower (Android's thermal status). */
    val warm: Boolean = false,
    /** Who is answering this turn and where ("Qwen3.8-27B on your server"), while it thinks; "" before that is known. */
    val answering: String = "",
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
    private val _turnLog = MutableStateFlow<List<VoiceTurn>>(emptyList())
    /** The last few turns, stage by stage (Settings → Models → Benchmark). */
    val turnLog: StateFlow<List<VoiceTurn>> = _turnLog
    /** The conversation being read in ahead, and a turn heard early: both dropped when Talk pauses or closes. */
    @Volatile private var warm: Job? = null
    @Volatile private var early: Early? = null
    /** Milliseconds of speech per character of text, learnt from each answer: how long an answer will take to say. */
    @Volatile var msPerChar = 70.0
        private set

    /** What the speaker's buffer learnt of this phone last time (Settings), so even the first answer waits enough. */
    suspend fun loadPace() = Jitter.load(settings)
    private val focus = AudioFocus(context, exclusive = true) { pause() }
    /** The app's tools, for the answer model in Edge mode. */
    @Volatile var tools: TalkTools? = null
    /** What Wren learned and the notes near what was said (memory/MemoryStore.kt); set by AppGraph. */
    @Volatile var memory: com.lfmpixel.lfm.memory.MemoryStore? = null
    /** What the camera sees now (Look), for the answer: a short line and how old it is, or null. */
    @Volatile var seeing: (() -> String?)? = null
    /** With Look on: the answer to a question about the view, from the VL model, or null for the answer model to take it. */
    @Volatile var lookAnswer: (suspend (String) -> String?)? = null
    private var talkSession: com.lfmpixel.lfm.edge.EdgeSession? = null
    private var job: Job? = null
    private var turnId = 0L
    /**
     * What the answer model wrote in each server turn with tools, tool calls and their results included, by turn id.
     * The next turn's history has them, so the model sees that earlier answers came from tools (with only the words,
     * Qwen took "Done, ticked off" as the way to answer and stopped calling tools: it made up a battery level).
     */
    private val exchanges = java.util.concurrent.ConcurrentHashMap<Long, List<com.lfmpixel.lfm.llm.PromptMessage>>()
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

    val available get() = demo || models.hasVoice() || models.edge?.configured?.value == true

    /**
     * Debug builds only (DebugTalkReceiver): Talk with no voice model, for trying the screen on an emulator. It sits
     * listening and shows the turns handed to [demoTurn]; release builds never set it.
     */
    @Volatile var demo = false

    fun demoTurn(you: String, answer: String, actions: List<TalkAction>) = synchronized(this) {
        _state.value = _state.value.copy(phase = TalkPhase.Listening,
            turns = _state.value.turns + TalkTurn(System.nanoTime(), you, answer, done = true, actions = actions))
    }

    /** Starts, or picks up again after a pause or an error. */
    fun start() = synchronized(this) {
        if (demo) { _state.value = _state.value.copy(phase = TalkPhase.Listening, error = null); return }
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
        exchanges.clear()
        models.edge?.gateway?.talkClosed()
    }

    private fun halt(phase: TalkPhase) {
        synchronized(this) {
            epoch++
            job?.cancel()
            job = null
            early?.drop()
            early = null
            warm?.cancel()
            _state.value = _state.value.copy(phase = phase)
        }
        if (interruptible) models.cancelSpeech()
        out.stop()
        focus.release()
    }

    /** The session that answered last, for its stage numbers. */
    @Volatile private var omniUsed: OmniVoice? = null

    /** True while Omni is answering: only then does stopping need to reach native code. */
    @Volatile private var interruptible = false

    fun setSpoken(on: Boolean) {
        synchronized(this) { _state.value = _state.value.copy(spoken = on) }
        scope.launch { settings.setTalkSpoken(on) }
    }

    private suspend fun runLoop(run: Int) {
        val p = settings.now()
        set(run) { it.copy(spoken = p.talkSpoken) }
        // The gateway is asked for voice mode (Qwen) once this Talk, before anything else: the swap takes a minute.
        models.edge?.takeIf { it.configured.value }?.gateway?.talkOpened()
        loadPace()
        try {
            // Edge mode needs nothing on the phone; Omni loads only for a turn the server can't take.
            if (models.edge?.usableNow() != true) {
                Log.i(TAG, "talk: server not in use (${models.edge?.state?.value ?: "Edge mode off"}): Omni on this phone")
                val session = models.omniSession()
            }
            // Asked now, so a sleeping Qwen is woken while the owner speaks, not after.
            models.edge?.takeIf { it.usable() }?.let { e -> scope.launch { runCatching { e.answerModel() } } }
            focus.take()
            while (coroutineContext.isActive) {
                checkWarm(run)
                interrupted = false
                set(run) { it.copy(phase = TalkPhase.Listening) }
                val lang = settings.now().speechLanguage
                // While the owner speaks, Omni reads the conversation so far in, so the answer starts sooner. Not when
                // the server answers (Edge mode): that would load Omni for nothing. Asked each turn, an Offline server
                // again too, so Qwen answers again as soon as the server is back.
                val local = models.edge?.usableNow() != true
                val prefix = SpeechFormat.talkPrefix(system(), doneTurns())
                warm = if (local) scope.launch { runCatching { models.omniSession().talkPrewarm(prefix) } } else null
                // Heard early: once the owner pauses, transcribe what they said while the silence that ends the turn
                // passes; if they go on, it is dropped.
                early = null
                val pcm = recorder.record(onStart = { set(run) { it.copy(phase = TalkPhase.Hearing) } },
                    onRestart = { set(run) { it.copy(phase = TalkPhase.Listening) } },
                    onPause = { heard -> if (local) synchronized(this) { early?.drop(); early = if (run == epoch) Early(heard, lang) else null } },
                    onResume = { early?.drop(); early = null })
                if (pcm == null) {
                    early?.drop()
                    set(run) { it.copy(phase = TalkPhase.Paused) }
                    focus.release()
                    return
                }
                val ended = System.nanoTime()
                val endedAt = SystemClock.elapsedRealtime()
                // Asked again: the server may have come back (or gone) while the owner spoke.
                val viaServer = models.edge?.usable() == true
                set(run) { it.copy(phase = TalkPhase.Thinking, answering = if (viaServer) "" else PHONE_ANSWER) }
                if (!viaServer) Log.i(TAG, "talk: server not in use (${models.edge?.state?.value ?: "Edge mode off"}): Omni on this phone")
                if (viaServer) {
                    val heard = try { models.edge!!.voice.transcribe(pcm, lang.asr) } catch (e: NetException) {
                        // The server went away: this turn is heard on the phone, if Omni is there.
                        Log.w(TAG, "talk: server speech to text failed (${e.message}): Omni on this phone")
                        set(run) { it.copy(answering = PHONE_ANSWER) }
                        if (!models.hasVoice()) throw LlmException("Your server can't be reached, and Omni isn't on this phone for Talk offline.")
                        null
                    }
                    if (heard != null) {
                        if (heard.isBlank() || interrupted) continue
                        val secs = (pcm.size + 8_000) / 16_000
                        if (answerViaServer(run, heard, secs, endedAt, SystemClock.elapsedRealtime() - endedAt)) continue
                        // The server went away before it said anything: the phone answers this turn, if Omni is there.
                        Log.w(TAG, "talk: server answer failed before any words: " + if (models.hasVoice()) "Omni on this phone" else "no phone model")
                        set(run) { it.copy(answering = PHONE_ANSWER) }
                        if (!models.hasVoice()) { set(run) { it.copy(error = "Your server stopped answering. Try again in a moment.") }; continue }
                        early?.drop(); early = null
                        answer(run, heard, secs, VoiceTurn(heardMs = pcm.size / 16, endSilenceMs = recorder.endSilenceMs), ended)
                        continue
                    }
                }
                models.keepAlive()
                warm?.join()
                val e = early
                val heard = if (e != null) runCatching { e.result.await() }.getOrNull() else null
                val asrStart = heard?.started ?: System.nanoTime()
                val said = heard?.text ?: models.omniSession().transcribe(pcm, "Perform ASR in ${lang.asr}.").trim()
                val asrEnd = heard?.ended ?: System.nanoTime()
                val asr = models.omniSession().stats()
                // Stop while it was transcribing: listen again instead of answering.
                if (said.isBlank() || interrupted) continue
                val turn = VoiceTurn(heardMs = pcm.size / 16, endSilenceMs = recorder.endSilenceMs,
                    asrMs = (asrEnd - asrStart) / 1e6, asrEarlyMs = ((minOf(ended, asrEnd) - asrStart) / 1e6).coerceAtLeast(0.0),
                    asrTokens = heard?.tokens ?: asr.decodeTokens, asrAudioInMs = heard?.audioInMs ?: asr.prefillMs)
                answer(run, said, (pcm.size + 8_000) / 16_000, turn, ended)
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

    /**
     * Edge mode's turn: LFM3-1.7B on the server writes the answer (streamed), each sentence goes to the server's
     * voice (Omni's /tts, streamed) as soon as it ends, and the audio plays through the jitter buffer while the rest
     * is still being written and spoken. If the server fails mid-answer, the turn ends there (marked stopped) and
     * says so. Returns false if it failed before any answer came: the turn is taken back for the phone to answer.
     */
    private suspend fun answerViaServer(run: Int, said: String, seconds: Int, endedAt: Long, heardMs: Long): Boolean {
        val p = settings.now()
        val edge = models.edge ?: return false
        val id = ++turnId
        val earlier = _state.value.turns.filter { it.done && it.it.isNotBlank() }
        val before = earlier.map { it.you to it.it }
        set(run) { it.copy(turns = it.turns + TalkTurn(id, said, seconds = seconds), viaServer = true) }
        val spoken = _state.value.spoken
        // The profile goes in the system prompt (it rarely changes, so the server keeps it read in); the notes and facts
        // near what was said go with this turn. A spoken answer can't wait long for them: 600 ms at most.
        val recall = memory?.let { m -> withTimeoutOrNull(600) { runCatching { m.context(said, TALK_CONTEXT) }.getOrNull() } }
        recall?.let { Log.i(TAG, "talk: recall ${it.system.length} + ${it.turn.length} chars in ${it.ms} ms") }
        val prompt = SpeechFormat.talkHistory(
            SpeechFormat.talkSystem(p.speechLanguage, SimpleDateFormat("EEEE d MMMM yyyy", Locale.UK).format(Date())) + recall?.system.orEmpty(),
            before, said + recall?.turn.orEmpty()) + SpeechFormat.REPLY_PREFIX
        val text = StringBuilder()
        var firstWord = 0L
        var firstAudio = 0L
        var model = ""
        var ran = 0
        val chunker = SpeechChunker()
        val pieces = Channel<String>(Channel.UNLIMITED)
        val voice = p.speechVoice.name.lowercase()
        if (spoken) out.start()
        val startedOut = SystemClock.elapsedRealtime()
        var finished = false
        var failure: Exception? = null
        try {
            kotlinx.coroutines.coroutineScope {
                // The voice: one piece at a time, in order, each streamed into the player as it is made.
                val speaker = if (spoken) launch {
                    for (piece in pieces) {
                        if (interrupted) break
                        edge.voice.speak(piece, voice) { pcm ->
                            if (firstAudio == 0L) { firstAudio = SystemClock.elapsedRealtime(); set(run) { it.copy(phase = TalkPhase.Speaking) } }
                            out.write(pcm)
                            !interrupted && run == epoch
                        }
                    }
                } else null
                // Look on and a question about the view: the VL model answers it on the newest frame (a label decides).
                val direct = lookAnswer?.invoke(said)
                if (direct != null) {
                    firstWord = SystemClock.elapsedRealtime()
                    text.append(direct)
                    model = com.lfmpixel.lfm.edge.EdgeModels.label(com.lfmpixel.lfm.edge.EdgeModels.VISION)
                    Log.i(TAG, "talk: $model answers about the camera's view")
                    set(run) { st -> st.copy(phase = if (!spoken) TalkPhase.Speaking else st.phase,
                        turns = st.turns.map { if (it.id == id) it.copy(it = direct) else it }) }
                    if (spoken) chunker.feed(direct).forEach { pieces.send(it) }
                } else {
                // The answer model (Qwen) when the server has it loaded, else LFM3-1.7B: either one with the app's tools.
                // No phone model behind it: if the server fails, this turn goes back to Omni on the phone, voice and all.
                val session = talkSession ?: com.lfmpixel.lfm.edge.EdgeSession(edge, com.lfmpixel.lfm.edge.EdgeModels.TEXT, null, answers = true)
                    .also { talkSession = it }
                val big = session.refresh() != null
                // Which model writes this turn, for the log and the line under "Thinking".
                val writer = com.lfmpixel.lfm.edge.EdgeModels.label(if (big) edge.answerId else com.lfmpixel.lfm.edge.EdgeModels.TEXT)
                Log.i(TAG, "talk: $writer answers on your server" + if (big) "" else " (${edge.answerId} not loaded there)")
                set(run) { it.copy(answering = "$writer on your server") }
                val toolBox = tools
                val history = if (toolBox == null) prompt else null
                val msgs = mutableListOf<com.lfmpixel.lfm.llm.PromptMessage>()
                if (toolBox != null) {
                    earlier.forEach { t ->
                        msgs += com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.User, t.you)
                        msgs += exchanges[t.id] ?: listOf(com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.Assistant, t.it))
                    }
                    msgs += com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.User, SpeechFormat.talkUser(said, seeing?.invoke()) + recall?.turn.orEmpty())
                }
                val from = msgs.size
                for (round in 0 until 3) {
                    val raw = StringBuilder()
                    var cut = false
                    val p2 = history ?: com.lfmpixel.lfm.llm.PromptFormat.build(SpeechFormat.talkToolSystem(p.speechLanguage) + recall?.system.orEmpty(),
                        toolBox!!.specs(), msgs, thinking = false)
                    session.generate(p2, if (toolBox != null) 400 else 300, 0.3f).collect { piece ->
                        if (interrupted || run != epoch) throw CancellationException("Stopped")
                        raw.append(piece)
                        // What is said and shown: the words before any tool call (calls come whole, at the end).
                        if (cut) return@collect
                        // LFM3-1.7B, after a tool's result, sometimes closes a thought it never opened and says it all
                        // again: what follows a stray </think> is never said.
                        val stop = listOf("<tool_call>", "</think>").firstOrNull { it in piece }
                        val words = if (stop != null) { cut = true; piece.substringBefore(stop) } else piece
                        if (words.isEmpty()) return@collect
                        if (firstWord == 0L) firstWord = SystemClock.elapsedRealtime()
                        text.append(words)
                        set(run) { st ->
                            st.copy(phase = if (!spoken) TalkPhase.Speaking else st.phase,
                                turns = st.turns.map { if (it.id == id) it.copy(it = text.toString().trim()) else it })
                        }
                        if (spoken) chunker.feed(words).forEach { pieces.send(it) }
                    }
                    model = com.lfmpixel.lfm.edge.EdgeModels.label(session.answeredBy)
                    val out = com.lfmpixel.lfm.llm.ModelOutput.parse(raw.toString(), false)
                    if (toolBox == null || out.toolCalls.isEmpty()) {
                        // Kept only when a tool ran: a plain answer is just its words, as before.
                        if (toolBox != null && msgs.size > from) exchanges[id] = msgs.drop(from) +
                            com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.Assistant, "<think></think>$raw")
                        break
                    }
                    msgs += com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.Assistant, "<think></think>$raw")
                    for (call in out.toolCalls) {
                        if (interrupted || run != epoch) throw CancellationException("Stopped")
                        val r = toolBox.run(call)
                        ran++
                        // Logged actions (and failures) show under the caption; look-ups like daily_brief don't.
                        if (r.cardLabel.isNotEmpty() && (r.actionId != null || !r.ok)) set(run) { st ->
                            st.copy(turns = st.turns.map { if (it.id == id) it.copy(actions = it.actions + TalkAction(r.actionId, r.ok, r.cardLabel, r.cardTitle, r.undo)) else it })
                        }
                        msgs += com.lfmpixel.lfm.llm.PromptMessage(com.lfmpixel.lfm.llm.PromptMessage.Role.Tool, r.forModel)
                    }
                    if (text.isNotEmpty() && !text.endsWith(" ")) text.append(' ')
                }
                }
                if (spoken) chunker.flush().forEach { pieces.send(it) }
                pieces.close()
                speaker?.join()
            }
            finished = !interrupted
        } catch (e: CancellationException) {
            if (run == epoch && !interrupted) throw e
        } catch (e: Exception) {
            Log.w(TAG, "server answer failed", e)
            failure = e
        } finally {
            pieces.close()
            synchronized(this) {
                _state.value = _state.value.copy(turns = _state.value.turns.map { if (it.id == id && !it.done) it.copy(done = true, stopped = true) else it })
            }
        }
        // Taken back only if nothing happened yet: once a tool ran, answering the turn again would run it twice.
        if (failure != null && text.isEmpty() && firstAudio == 0L && ran == 0) {
            out.stop()
            synchronized(this) { _state.value = _state.value.copy(turns = _state.value.turns.filter { it.id != id }) }
            return false
        }
        if (failure != null) set(run) { it.copy(error = "Your server stopped answering mid-way." +
            (if (ran > 0) " What it already did stays done." else "")) }
        if (spoken && finished) out.finish() else out.stop()
        val played = out.last
        val timing = TalkTiming(heardMs, if (firstWord > 0) firstWord - endedAt else 0, if (firstAudio > 0) firstAudio - endedAt else 0,
            if (played.toSoundMs > 0) (startedOut - endedAt + played.toSoundMs).toLong() else 0, viaServer = true, underruns = played.underruns,
            model = model, tools = ran)
        Log.i(TAG, "server answer: $timing: $text")
        val stopped = interrupted || !finished
        set(run) { st -> st.copy(timing = timing, turns = st.turns.map { if (it.id == id) it.copy(done = true, stopped = stopped) else it }) }
        return true
    }

    /** The finished exchanges, oldest first, as Omni reads them back. */
    private fun doneTurns() = _state.value.turns.filter { it.done && it.it.isNotBlank() }.map { it.you to it.it }

    /** Omni on the phone: a short profile only (its context is small, and a stable start stays read in). */
    private suspend fun system(): String =
        SpeechFormat.talkSystem(settings.now().speechLanguage, SimpleDateFormat("EEEE d MMMM yyyy", Locale.UK).format(Date())) +
            (memory?.let { m -> runCatching { m.context("", 4096, notes = false).system }.getOrNull() }.orEmpty())

    private class Heard(val text: String, val started: Long, val ended: Long, val tokens: Int, val audioInMs: Double)

    /** A turn transcribed early, at the owner's first pause; then the conversation with it read in too. */
    private inner class Early(pcm: FloatArray, lang: SpeechLanguage) {
        @Volatile private var dropped = false
        val result = scope.async {
            val started = System.nanoTime()
            val session = models.omniSession()
            val text = session.transcribe(pcm, "Perform ASR in ${lang.asr}.", stop = { dropped }).trim()
            val ended = System.nanoTime()
            val st = session.stats()
            if (dropped) throw CancellationException("dropped")
            if (text.isNotBlank()) runCatching { session.talkPrewarm(SpeechFormat.talkHistory(system(), doneTurns(), text)) }
            Heard(text, started, ended, st.decodeTokens, st.prefillMs)
        }

        fun drop() { dropped = true; result.cancel() }
    }

    private suspend fun answer(run: Int, said: String, seconds: Int, heard: VoiceTurn = VoiceTurn(), ended: Long = System.nanoTime()) {
        val id = ++turnId
        val before = doneTurns()
        set(run) { it.copy(turns = it.turns + TalkTurn(id, said, seconds = seconds), viaServer = false) }
        val spoken = _state.value.spoken
        val history = SpeechFormat.talkHistory(system(), before, said)
        // "Speaking" from the first sound, not the first audio: the buffer may wait a while first.
        out.onSound = { set(run) { it.copy(phase = TalkPhase.Speaking) } }
        if (spoken) out.start()
        val text = StringBuilder()
        val t0 = System.nanoTime()
        var firstAudio = 0L
        var steps = 0
        // A long answer on a slow phone must not be unloaded under it: count it as use as it goes.
        val alive = { if (++steps % 100 == 0) scope.launch { models.keepAlive() } }
        interruptible = true
        val finished = try {
            val session = models.omniSession()
            omniUsed = session
            session.talkReply(history, SpeechFormat.REPLY_PREFIX, 1200, 0.3f, spoken,
                onText = { piece ->
                    text.append(piece)
                    // Text runs ahead of the voice: the buffer learns how much speech is still to come.
                    if (spoken) out.expect(text.length * msPerChar)
                    alive()
                    set(run) { st ->
                        st.copy(phase = if (!spoken) TalkPhase.Speaking else st.phase,
                            turns = st.turns.map { if (it.id == id) it.copy(it = text.toString().trim()) else it })
                    }
                    !interrupted && run == epoch
                },
                onAudio = { pcm ->
                    alive()
                    if (firstAudio == 0L) firstAudio = System.nanoTime()
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
        val speech = omniUsed?.lastSpeech ?: com.lfmpixel.lfm.llm.SpeechStats()
        val complete = spoken && finished && !interrupted
        // A stopped answer: its own numbers once its player has let go (what it played, and any underruns).
        val played = if (!spoken) AudioOut.Played() else if (complete) out.last else out.awaitStopped()
        if (complete && text.isNotBlank() && played.audioMs > 1000) msPerChar = 0.7 * msPerChar + 0.3 * played.audioMs / text.length
        val sound = if (spoken && played.toSoundMs > 0) (t0 - ended) / 1e6 + played.toSoundMs else 0.0
        val turn = heard.copy(speech = speech, played = played, endToSoundMs = sound, spoken = spoken, stopped = !finished || interrupted)
        Log.i(AudioOut.TAG, "turn: " + turn.line())
        if (complete) settings.setVoicePace(Jitter.prior.rtf, Jitter.prior.usualMs)
        _turnLog.value = (_turnLog.value + turn).takeLast(10)
        val stopped = interrupted || !finished
        val sinceEnd = (t0 - ended) / 1_000_000
        val timing = TalkTiming(sinceEnd, 0, if (firstAudio > 0) (firstAudio - ended) / 1_000_000 else 0,
            sound.toLong(), viaServer = false, underruns = played.underruns, model = "LFM3-Omni-2B")
        Log.i(TAG, "phone answer: $timing")
        set(run) { st -> st.copy(timing = timing, turns = st.turns.map { if (it.id == id) it.copy(done = true, stopped = stopped) else it }) }
    }

    private fun checkWarm(run: Int) {
        val pm = context.getSystemService(PowerManager::class.java)
        set(run) { it.copy(warm = (pm?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE) }
    }

    companion object {
        private const val TAG = "lfm-talk"
        const val PHONE_ANSWER = "Omni on this phone"
        /** The memory budget for a spoken answer on the server: a mid-size one ([com.lfmpixel.lfm.memory.Recall.budget]), so the prompt stays quick. */
        const val TALK_CONTEXT = 8_000
    }
}
