package com.lfmpixel.lfm.assist

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.RemoteViews
import android.Manifest
import android.content.pm.PackageManager
import com.lfmpixel.lfm.LfmApp
import com.lfmpixel.lfm.voice.OmniTranscriber
import com.lfmpixel.lfm.voice.TurnRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.lfmpixel.lfm.MainActivity
import com.lfmpixel.lfm.R

/*
 * The ways into Inkling from outside the app (spec section 5, "Being the phone's assistant"): the assistant gesture,
 * a Quick Settings tile and a home-screen widget. Each only opens MainActivity with what to do; the work happens there.
 */

/** Makes Inkling choosable as the phone's digital assistant (Settings → Apps → Default apps). */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** The power-button hold or corner swipe: opens Talk or voice capture, then gets out of the way. */
class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(MainActivity.intent(context, MainActivity.OPEN_ASSIST))
        hide()
    }
}

/**
 * Android only lists an assistant that also names a speech recogniser, and makes it the phone's recogniser too. So
 * this is a real one: another app's voice input (a keyboard's mic, a search box) is heard and written down by Omni,
 * on this phone. Without Omni it says so and the app falls back to typing.
 */
class AssistRecognitionService : RecognitionService() {
    private var job: Job? = null
    private val recorder = TurnRecorder()

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener ?: return
        val g = (application as LfmApp).graph
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.safe { error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }; return
        }
        if (!g.engine.hasVoice()) { listener.safe { error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }; return }
        job?.cancel()
        job = g.scope.launch {
            try {
                listener.safe { readyForSpeech(Bundle()) }
                // Android's level is in dB, roughly -2 to 10.
                val levels = launch { recorder.level.collect { l -> listener.safe { rmsChanged(-2f + 12f * l) } } }
                val pcm = try { recorder.record(onStart = { listener.safe { beginningOfSpeech() } }) } finally { levels.cancel() }
                if (pcm == null) { listener.safe { error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }; return@launch }
                listener.safe { endOfSpeech() }
                val lang = g.settings.now().speechLanguage
                val text = OmniTranscriber(g.engine) { lang.asr }.transcribe(pcm)
                if (text.isBlank()) listener.safe { error(SpeechRecognizer.ERROR_NO_MATCH) }
                else listener.safe { results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.safe { error(SpeechRecognizer.ERROR_CLIENT) }
            }
        }
    }

    override fun onStopListening(listener: Callback?) { recorder.finish() }

    override fun onCancel(listener: Callback?) { job?.cancel(); job = null }

    override fun onDestroy() { job?.cancel(); super.onDestroy() }

    /** The caller may have gone away meanwhile. */
    private inline fun Callback.safe(block: Callback.() -> Unit) { runCatching { block() } }
}

/** Quick Settings → Capture: opens Inkling listening, as the mic on Today does. */
class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(R.string.tile_capture); updateTile() }
    }

    override fun onClick() {
        val open = { startActivityAndCollapse(pending(this, MainActivity.OPEN_CAPTURE, 1)) }
        // On the lock screen, unlock first: what is said may be private.
        if (isLocked) unlockAndRun(open) else open()
    }
}

/** The home-screen widget: Capture (the mic), Talk, and a new note. */
class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_capture).apply {
            setOnClickPendingIntent(R.id.capture, pending(context, MainActivity.OPEN_CAPTURE, 2))
            setOnClickPendingIntent(R.id.talk, pending(context, MainActivity.OPEN_TALK, 3))
            setOnClickPendingIntent(R.id.note, pending(context, MainActivity.OPEN_NOTE, 4))
        }
        manager.updateAppWidget(ids, views)
    }
}

private fun pending(context: Context, what: String, code: Int): PendingIntent =
    PendingIntent.getActivity(context, code, MainActivity.intent(context, what),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
