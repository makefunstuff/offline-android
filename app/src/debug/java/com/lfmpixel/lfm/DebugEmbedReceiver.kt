package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_EMBED [--es q "where did my tooth doctor move"]
 * Brings the search index up to date, then logs its size and speed and, with q, what Library search finds for it
 * (keywords and meaning, as search_library and Talk see it). Logs under lfm-embed.
 */
class DebugEmbedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val q = intent.getStringExtra("q")
        fun log(s: String) = Log.i("lfm-embed", s)
        g.scope.launch {
            try {
                g.semantic.sync()
                val st = g.semantic.status.value
                log("index: ${st.indexed} of ${st.entries} entries, ${st.bytes} bytes, %.1f ms an entry".format(java.util.Locale.ENGLISH, st.msPerEntry))
                if (q != null) {
                    val found = g.semantic.search(q, 6)
                    log("\"$q\" in ${g.semantic.status.value.lastSearchMs} ms: ${found.joinToString(" | ") { it.title }}")
                }
            } catch (e: Exception) {
                log("failed: $e")
            }
        }
    }
}
