package com.lfmpixel.lfm

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.lfmpixel.lfm.assistant.Quotes
import kotlinx.coroutines.launch

/**
 * The share sheet's way in (notebook-spec proposal 5). It has no screen: it saves the shared text as a quote on
 * today's page, then brings Inkling's own task forward to show it. Sharing never starts a second Inkling inside the
 * other app's task, which would take over the lock prompt and the activity the tools use.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quote = if (intent?.action == Intent.ACTION_SEND) Quotes.fromShare(
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(), intent.getStringExtra(Intent.EXTRA_SUBJECT)) else null
        if (quote == null || savedInstanceState != null) { finish(); return }
        val g = (application as LfmApp).graph
        lifecycleScope.launch {
            val id = g.db.entries().insert(quote)
            startActivity(MainActivity.intent(this@ShareActivity, MainActivity.OPEN_SHARED + id))
            finish()
        }
    }
}
