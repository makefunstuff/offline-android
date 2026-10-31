package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.llm.BackendChoice
import com.lfmpixel.lfm.llm.PromptFormat
import com.lfmpixel.lfm.llm.PromptMessage
import com.lfmpixel.lfm.llm.TextSession
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_BACKEND --es backend LiteRtCpu: on that backend, a two-turn chat,
 * then a new conversation, whose answer must match the same conversation on a freshly loaded model (LiteRT-LM keeps
 * LFM3's running state across sessions of one engine, issue #3165). Logs under lfm-backend.
 */
class DebugBackendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val choice = BackendChoice.valueOf(intent.getStringExtra("backend") ?: "LiteRtCpu")
        fun log(s: String) = Log.i("lfm-backend", s)
        suspend fun TextSession.ask(history: List<PromptMessage>) =
            generate(PromptFormat.build("Answer briefly.", emptyList(), history, false), 32, temp = 0f).toList().joinToString("")
                .substringBefore(PromptFormat.IM_END).trim()
        g.scope.launch {
            val before = g.settings.now().backend
            try {
                g.settings.setBackend(choice)
                g.engine.unloadAll()
                val s = g.engine.textSession()
                log("backend ${s.backend} (asked ${choice.name}), ${s.path.substringAfterLast('/')}")
                val a = mutableListOf(PromptMessage(PromptMessage.Role.User, "My cat is called Pepper. Remember that."))
                var t0 = System.nanoTime()
                a += PromptMessage(PromptMessage.Role.Assistant, s.ask(a))
                log("A1 ${(System.nanoTime() - t0) / 1_000_000} ms: ${a.last().content}")
                a += PromptMessage(PromptMessage.Role.User, "What is my cat called?")
                t0 = System.nanoTime()
                val a2 = s.ask(a)
                log("A2 ${(System.nanoTime() - t0) / 1_000_000} ms (extends A1): $a2")
                val b = listOf(PromptMessage(PromptMessage.Role.User, "Name three colours of the rainbow."))
                val afterChat = s.ask(b)
                log("B after A: $afterChat")
                log(s.runInfo().lines().filter { it.isNotBlank() }.joinToString(" · "))
                g.engine.unloadAll()
                val fresh = g.engine.textSession().ask(b)
                log("B fresh: $fresh")
                log(if (afterChat == fresh) "SAME: no state carried over" else "DIFFERENT: state carried over")
                log(if (a2.contains("Pepper", ignoreCase = true)) "RECALL OK" else "RECALL FAILED")
            } catch (e: Exception) {
                Log.e("lfm-backend", "failed", e)
            } finally {
                g.settings.setBackend(before)
                g.engine.unloadAll()
            }
        }
    }
}
