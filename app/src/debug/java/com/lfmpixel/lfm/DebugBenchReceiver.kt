package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.llm.Benchmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_BENCH [--ez voice true]: runs Settings → Models → Benchmark and logs the report (tag lfm-bench). */
class DebugBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        // --ez voice true: the voice benchmark instead (tag lfm-voice).
        if (intent.getBooleanExtra("voice", false)) {
            g.voiceBench.start()
            g.scope.launch {
                when (val s = g.voiceBench.state.first { it is com.lfmpixel.lfm.voice.VoiceBench.State.Done || it is com.lfmpixel.lfm.voice.VoiceBench.State.Failed }) {
                    is com.lfmpixel.lfm.voice.VoiceBench.State.Done -> s.report.text().lines().forEach { Log.i("lfm-voice", it) }
                    is com.lfmpixel.lfm.voice.VoiceBench.State.Failed -> Log.e("lfm-voice", s.message)
                    else -> {}
                }
            }
            return
        }
        g.bench.start()
        // Not goAsync: a benchmark of every backend takes minutes, far past a receiver's time limit.
        g.scope.launch {
            when (val s = g.bench.state.first { it is Benchmark.State.Done || it is Benchmark.State.Failed }) {
                is Benchmark.State.Done -> s.report.text().lines().forEach { Log.i("lfm-bench", it) }
                is Benchmark.State.Failed -> Log.e("lfm-bench", s.message)
                else -> {}
            }
        }
    }
}
