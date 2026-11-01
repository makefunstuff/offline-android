package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_TALK [--es file question.f32]
 * Keeps what Talk and read-aloud say next in the app's files (last-talk.f32, last-read.f32: 24 kHz mono float32), and,
 * with a file (16 kHz mono float32 in the app's files), hands it to Talk as the owner's next turn in place of the mic.
 *
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_TALK --es say "…" --es answer "…" [--es remind "…" --es at 2026-11-01T17:00]
 * With no voice model (an emulator): Talk shows that exchange as if it had been spoken; with remind, the reminder is
 * really set through the app's tools, so Undo and Show work.
 */
class DebugTalkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        g.talk.out.keepLastIn = File(context.filesDir, "last-talk.f32")
        g.readAloud.keepLastIn(File(context.filesDir, "last-read.f32"))
        intent.getStringExtra("say")?.let { say ->
            g.talk.demo = true
            val answer = intent.getStringExtra("answer") ?: ""
            val remind = intent.getStringExtra("remind")
            g.scope.launch {
                val actions = if (remind == null) emptyList() else {
                    val r = g.assistant.execute(com.lfmpixel.lfm.llm.ToolCall("set_reminder", mapOf("text" to remind, "at" to (intent.getStringExtra("at") ?: ""))), "voice", afterWebText = false)
                    listOf(com.lfmpixel.lfm.voice.TalkAction(r.actionId, r.ok, r.cardLabel, r.cardTitle, r.undo))
                }
                g.talk.demoTurn(say, answer, actions)
            }
            return
        }
        val name = intent.getStringExtra("file") ?: return
        val bytes = File(context.filesDir, name).readBytes()
        val pcm = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
        g.talk.injected.trySend(pcm)
        Log.i("lfm-talk", "debug turn: %.1f s of audio".format(pcm.size / 16000f))
    }
}
