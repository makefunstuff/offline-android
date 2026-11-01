package com.lfmpixel.lfm.voice

import android.util.Log
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.ModelManager
import com.lfmpixel.lfm.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Reads an answer out loud with Omni's voice, in this process: the text never leaves the app. */
class ReadAloud(context: android.content.Context, private val models: ModelManager, private val settings: Settings,
                private val scope: CoroutineScope) {
    data class State(val id: Long? = null, val preparing: Boolean = false, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    // This phone's speed as Talk knows it, but not the length: long texts would teach Talk's buffer to wait too long.
    private val out = AudioOut(learnLength = false)
    private val focus = AudioFocus(context, exclusive = false) { stop() }
    private var job: Job? = null

    val available get() = models.hasVoice() || models.edge?.configured?.value == true

    /** Debug builds keep the last thing read here. */
    fun keepLastIn(f: java.io.File?) { out.keepLastIn = f }

    /** Reads message [id]'s [text]; the same message again stops it. */
    fun toggle(id: Long, text: String) {
        if (_state.value.id == id) { stop(); return }
        stop()
        val speech = SpeechFormat.clean(text)
        if (speech.isBlank()) return
        _state.value = State(id, preparing = true)
        job = scope.launch {
            try {
                val p = settings.now()
                Jitter.load(settings)
                // Edge mode: the server's voice, streamed; the phone's Omni if the server can't be reached.
                val edge = models.edge?.takeIf { it.usable() }
                if (edge != null) {
                    var heard = false
                    val viaServer = try {
                        focus.take()
                        out.start()
                        for (chunk in SpeechFormat.chunks(speech)) {
                            val done = edge.speak(chunk, p.speechVoice.name.lowercase()) { pcm ->
                                heard = true
                                if (_state.value.preparing) _state.value = _state.value.copy(preparing = false)
                                out.write(pcm)
                                _state.value.id == id
                            }
                            if (!done || _state.value.id != id) return@launch
                        }
                        out.finish()
                        true
                    } catch (e: com.lfmpixel.lfm.web.NetException) {
                        if (heard || !models.hasVoice()) throw e
                        out.stop()
                        false
                    }
                    if (viaServer) return@launch
                }
                val session = models.omniSession()
                focus.take()
                out.start()
                for (chunk in SpeechFormat.chunks(speech)) {
                    // What this piece adds (about 65 ms a character, 200 for Chinese and Japanese): if speech is slower
                    // than it plays, the waits fall between pieces (sentences) rather than all before the first.
                    val cjk = chunk.any { Character.isIdeographic(it.code) || it in '\u3040'..'\u30ff' }
                    out.expectMore(chunk.length * if (cjk) 200.0 else 65.0)
                    models.keepAlive()
                    // About 12.5 frames a second of speech; a generous cap so a long piece cannot run away. Chinese
                    // and Japanese say more per character.
                    val cap = 40 + chunk.length * (if (chunk.any { Character.isIdeographic(it.code) || it in '\u3040'..'\u30ff' }) 5 else 2)
                    val done = session.speak(SpeechFormat.ttsPrompt(chunk, p.speechLanguage, p.speechVoice), cap) { pcm ->
                        if (_state.value.preparing) _state.value = _state.value.copy(preparing = false)
                        out.write(pcm)
                        _state.value.id == id
                    }
                    if (!done || _state.value.id != id) return@launch
                }
                out.finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("lfm-speech", "read aloud failed", e)
                _state.value = State(error = e.message ?: "Could not read it out")
                return@launch
            } finally {
                // However it ended, the player lets go of the speaker (stop after finish does nothing).
                if (_state.value.id == id || _state.value.id == null) { out.stop(); focus.release() }
                if (_state.value.id == id) _state.value = State()
            }
        }
    }

    fun stop() {
        if (_state.value.id == null && job?.isActive != true) return
        _state.value = State()
        models.cancelSpeech()
        out.stop()
        focus.release()
        job?.cancel()
        job = null
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}
