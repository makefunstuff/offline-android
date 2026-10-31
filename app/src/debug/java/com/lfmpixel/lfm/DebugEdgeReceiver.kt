package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.edge.EdgeBench
import com.lfmpixel.lfm.edge.EdgeKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_EDGE [--es url http://192.168.1.138:18101] [--es keyfile edge.key] [--ez on true] [--ez bench true]
 *   [--es answer lfm3-1.7b]  (the answer model's id: another served model stands in for Qwen, to test its path)
 * Sets Edge mode as Settings → Your server would (the key read from a file in the app's files, then deleted), and
 * with bench runs the server benchmark and logs it (tag lfm-edge).
 */
class DebugEdgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        g.scope.launch {
            val p = g.settings.now()
            intent.getStringExtra("url")?.let { url ->
                val keyFile = intent.getStringExtra("keyfile")?.let { java.io.File(context.filesDir, it) }
                val sealed = keyFile?.takeIf { it.exists() }?.let { f -> EdgeKey.seal(f.readText().trim()).also { f.delete() } } ?: p.edgeKeySealed
                g.settings.setEdgeServer(url, sealed)
            }
            if (intent.hasExtra("on")) g.settings.setEdgeOn(intent.getBooleanExtra("on", true))
            intent.getStringExtra("answer")?.let { g.edge.answerId = it.ifBlank { com.lfmpixel.lfm.edge.EdgeModels.ANSWER }; g.edge.answerFailed() }
            Log.i("lfm-edge", "debug: edge ${g.settings.now().edgeOn} at ${g.settings.now().edgeUrl}; check: " +
                runCatching { "${g.edge.check()} ms" }.getOrElse { "failed: ${it.message}" })
            if (intent.getBooleanExtra("bench", false)) {
                g.edgeBench.start()
                when (val s = g.edgeBench.state.first { it is EdgeBench.State.Done || it is EdgeBench.State.Failed }) {
                    is EdgeBench.State.Done -> Log.i("lfm-edge", "bench: " + s.result.text())
                    is EdgeBench.State.Failed -> Log.e("lfm-edge", "bench: " + s.message)
                    else -> {}
                }
            }
        }
    }
}
