package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.llm.Benchmark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_BENCH: runs Settings → Models → Benchmark and logs the report (tag lfm-bench). */
class DebugBenchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        g.bench.start()
        val done = goAsync()
        g.scope.launch {
            when (val s = g.bench.state.first { it is Benchmark.State.Done || it is Benchmark.State.Failed }) {
                is Benchmark.State.Done -> s.report.text().lines().forEach { Log.i("lfm-bench", it) }
                is Benchmark.State.Failed -> Log.e("lfm-bench", s.message)
                else -> {}
            }
            done.finish()
        }
    }
}
