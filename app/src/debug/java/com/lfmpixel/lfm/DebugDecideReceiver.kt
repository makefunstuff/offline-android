package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.lfmpixel.lfm.assistant.Decide
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_DECIDE [--es dir /sdcard/Android/data/com.lfmpixel.lfm/files/d1]
 *     [--ez cache true] [--ei threads 4] [--ei limit 0]
 * d1 on the phone over the label set in dir/labels_eval.tsv (C line kind urgency energy sensitive / T said intent):
 * each label and its time, then accuracy, median ms, load time and memory. Writes dir/decide_results.json. Logs under lfm-d1.
 */
class DebugDecideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as LfmApp).graph
        val dir = File(intent.getStringExtra("dir") ?: File(context.getExternalFilesDir(null), "d1").path)
        val cache = intent.getBooleanExtra("cache", true)
        val threads = intent.getIntExtra("threads", 4)
        val limit = intent.getIntExtra("limit", 0)
        fun log(s: String) = Log.i("lfm-d1", s)
        g.scope.launch {
            try {
                log("dir $dir cache $cache threads $threads; before: ${mem()}")
                var t0 = SystemClock.elapsedRealtime()
                val d = Decide.open(dir, threads, if (cache) File(context.noBackupFilesDir, "d1-omni-600M_xnnpack.cache") else null)
                    ?: run { log("no d1 files in $dir"); return@launch }
                log("opened in ${SystemClock.elapsedRealtime() - t0} ms")
                t0 = SystemClock.elapsedRealtime()
                val warm = d.capture("buy milk")
                log("first capture (compiles L128) ${SystemClock.elapsedRealtime() - t0} ms: $warm; ${mem()}")
                val rows = File(dir, "labels_eval.tsv").readLines().map { it.split('\t') }.let { if (limit > 0) it.take(limit) else it }
                val out = JSONArray()
                val ok = HashMap<String, Int>()
                val n = HashMap<String, Int>()
                val cms = ArrayList<Long>()
                val tms = ArrayList<Long>()
                for (r in rows) {
                    when (r[0]) {
                        "C" -> {
                            val l = d.capture(r[1])
                            cms += l.ms
                            val pred = mapOf("kind" to l.kind, "urgency" to l.urgency, "energy" to l.energy, "sensitive" to l.sensitive.toString())
                            val gold = mapOf("kind" to r[2], "urgency" to r[3], "energy" to r[4], "sensitive" to r[5])
                            for ((k, v) in gold) { n.merge(k, 1, Int::plus); if (pred[k] == v) ok.merge(k, 1, Int::plus) }
                            out.put(JSONObject().put("line", r[1]).put("pred", JSONObject(pred)).put("when", l.whenWords).put("ms", l.ms))
                            log("C ${l.ms} ms ${r[1]} -> $pred when '${l.whenWords}'")
                        }
                        "T" -> {
                            val l = d.intent(r[1])
                            tms += l.ms
                            n.merge("intent", 1, Int::plus); if (l.intent == r[2]) ok.merge("intent", 1, Int::plus)
                            out.put(JSONObject().put("said", r[1]).put("pred", l.intent).put("ms", l.ms))
                            log("T ${l.ms} ms ${r[1]} -> ${l.intent}")
                        }
                    }
                }
                val acc = JSONObject().apply { for ((k, v) in n) put(k, (ok[k] ?: 0).toDouble() / v) }
                val summary = JSONObject().put("accuracy", acc).put("capture_ms_median", cms.sorted().getOrNull(cms.size / 2))
                    .put("intent_ms_median", tms.sorted().getOrNull(tms.size / 2)).put("memory", mem()).put("threads", threads).put("cache", cache)
                log("SUMMARY $summary")
                File(dir, "decide_results.json").writeText(JSONObject().put("summary", summary).put("rows", out).toString(1))
                d.close()
            } catch (e: Throwable) {
                Log.e("lfm-d1", "failed", e)
            }
        }
    }

    private fun mem(): String = File("/proc/self/status").readLines()
        .filter { it.startsWith("VmHWM") || it.startsWith("VmRSS") || it.startsWith("RssAnon") || it.startsWith("RssFile") }
        .joinToString(" ") { it.replace(Regex("""\s+"""), " ") }
}
