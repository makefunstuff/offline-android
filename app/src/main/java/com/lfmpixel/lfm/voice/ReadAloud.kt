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
    private val out = AudioOut()
    private val focus = AudioFocus(context, exclusive = false) { stop() }
    private var job: Job? = null

    val available get() = models.hasVoice()

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
                val session = models.omniSession()
                if (!session.canSpeak()) throw LlmException("This Omni projector file has no voice part.")
                focus.take()
                out.start()
                for (chunk in SpeechFormat.chunks(speech)) {
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
