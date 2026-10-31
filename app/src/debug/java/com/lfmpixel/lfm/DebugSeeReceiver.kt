package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.launch
import java.io.File

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_SEE --es file clip.mp4 --es as media|document [--es q "What happens?"]
 * Reads a file from the app's files folder and asks about it, as the photo or document picker does.
 */
class DebugSeeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val f = File(context.filesDir, intent.getStringExtra("file") ?: "photo.jpg")
        val asDocument = intent.getStringExtra("as") == "document"
        val q = intent.getStringExtra("q")
        val done = goAsync()
        g.scope.launch {
            try {
                val uri = Uri.fromFile(f)
                val req = if (asDocument) g.prep.document(uri, q ?: com.lfmpixel.lfm.see.SeeRules.defaultQuestion(com.lfmpixel.lfm.see.SeeKind.Document))
                    else g.prep.picked(uri, q ?: "What is this?")
                Log.i("lfm-see", "${req.kind} ${req.label}: ${req.images.size} pictures, ${req.pages.size} pages")
                g.assistant.ask(req)
            } catch (e: Exception) {
                Log.e("lfm-see", "failed", e)
            } finally {
                done.finish()
            }
        }
    }
}
