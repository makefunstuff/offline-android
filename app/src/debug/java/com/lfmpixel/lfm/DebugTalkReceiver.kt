package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_TALK [--es file question.f32]
 * Keeps what Talk and read-aloud say next in the app's files (last-talk.f32, last-read.f32: 24 kHz mono float32), and,
 * with a file (16 kHz mono float32 in the app's files), hands it to Talk as the owner's next turn in place of the mic.
 */
class DebugTalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        g.talk.out.keepLastIn = File(context.filesDir, "last-talk.f32")
        g.readAloud.keepLastIn(File(context.filesDir, "last-read.f32"))
        val name = intent.getStringExtra("file") ?: return
        val bytes = File(context.filesDir, name).readBytes()
        val pcm = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
        g.talk.injected.trySend(pcm)
        Log.i("lfm-talk", "debug turn: %.1f s of audio".format(pcm.size / 16000f))
    }
}
