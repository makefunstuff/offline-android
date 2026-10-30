package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_VOICE --es file voice.f32
 * Reads 16 kHz mono float32 PCM from the app's files folder, transcribes it with Omni and sends the
 * text to the assistant as a voice message, exactly as the mic button does after recording.
 */
class DebugVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val f = File(context.filesDir, intent.getStringExtra("file") ?: "voice.f32")
        val done = goAsync()
        g.scope.launch {
            try {
                val bytes = f.readBytes()
                val pcm = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
                val t0 = System.nanoTime()
                val text = com.lfmpixel.lfm.voice.OmniTranscriber(g.engine).transcribe(pcm)
                Log.i("lfm-voice", "transcribed %.1f s of audio in %.1f s: %s".format(pcm.size / 16000f, (System.nanoTime() - t0) / 1e9, text))
                g.assistant.send(text, pcm.size / 16000, "voice")
            } catch (e: Exception) {
                Log.e("lfm-voice", "failed", e)
            } finally {
                done.finish()
            }
        }
    }
}
