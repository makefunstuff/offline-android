package com.lfmpixel.lfm.assistant

import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.lfmpixel.lfm.edge.CaptureLabel
import com.lfmpixel.lfm.edge.IntentLabel
import com.lfmpixel.lfm.llm.BpeTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.File

/**
 * Liquid's d1-omni-600M decision model on the phone (litert-community/d1-omni-600M-LiteRT, CPU through LiteRT's
 * CompiledModel): the small classifiers of [com.lfmpixel.lfm.edge.Labels] when the owner's server is away. Order:
 * server, then d1 here, then the rules. d1 writes no text: each answer is read from its distribution over a question's
 * options, one graph call per question (about 85 ms a call on a desktop CPU, 4 threads; a capture asks four).
 *
 * The "when" words are not d1's: [whenWords] finds them with the phone's time reader, as the rules do. A photo's
 * kind is not asked here yet (the vision tower and its host steps are not ported).
 *
 * Files, flat in [dir]: [CONTRACT], [TOKENIZER] and the decision graph of each bucket contract.json names (only
 * [GRAPH_L128] is needed; a row longer than the largest bucket present has its state cut to fit, as the provider's
 * host does with truncate_state). With [weightCache], XNNPACK keeps the graph's unpacked weights in that file and maps
 * it, instead of holding about 1.3 GB of anonymous memory (desktop: RssAnon 1.29 GB -> 42 MB, same scores; the
 * first load writes the 1.26 GB file, about 3 s).
 */
class Decide private constructor(
    private val tok: D1Tokens,
    private val temperatures: Map<String, Double>,
    private val graphs: Map<Int, File>,
    private val threads: Int,
    private val weightCache: File?,
) : Closeable {
    private val lock = Mutex()
    private var loaded: Graph? = null

    /** The distribution of each question over [state], in option order ([yes, no] for a noul). */
    suspend fun probabilities(state: String, questions: List<D1Question>): List<DoubleArray> = withContext(Dispatchers.Default) {
        lock.withLock { questions.map { score(state, it) } }
    }

    /** A notebook line sorted: kind, urgency, energy and sensitive from d1; the "when" words from the phone's rules. */
    suspend fun capture(line: String): CaptureLabel {
        val t0 = SystemClock.elapsedRealtime()
        val state = CAPTURE_STATE + line.take(400)
        val p = probabilities(state, listOf(KIND, URGENCY, ENERGY, TOPIC))
        val topic = TOPIC.names[p[3].argmax()]
        return CaptureLabel(KIND.names[p[0].argmax()], whenWords(line), URGENCY.names[p[1].argmax()], ENERGY.names[p[2].argmax()],
            topic != "everyday", SystemClock.elapsedRealtime() - t0)
    }

    /** What the owner wants from a Talk turn with the camera on: look, act or chat. */
    suspend fun intent(said: String): IntentLabel {
        val t0 = SystemClock.elapsedRealtime()
        val p = probabilities(said.take(300), listOf(INTENT))[0]
        return IntentLabel(INTENT.names[p.argmax()], SystemClock.elapsedRealtime() - t0)
    }

    private fun score(state: String, q: D1Question): DoubleArray {
        val largest = graphs.keys.max()
        var row = D1Prompt.encode(tok, state, q)
        if (row.ids.size > largest) row = D1Prompt.encode(tok, state, q, largest)
        val L = graphs.keys.filter { it >= row.ids.size }.min()
        val scores = graph(L).run(row.ids, D1Question.QTYPES.indexOf(q.type))
        return D1Prompt.readout(DoubleArray(q.options) { scores[row.markers[it]].toDouble() }, q, true, temperatures)
    }

    /** One bucket's graph stays loaded; another bucket replaces it (each holds the whole model). */
    private fun graph(L: Int): Graph {
        loaded?.let { if (it.L == L) return it; it.close() }
        return Graph(L, graphs.getValue(L), threads, weightCache?.let { File(it.parentFile, "${it.name}.L$L") }).also { loaded = it }
    }

    override fun close() {
        loaded?.close()
        loaded = null
    }

    /** decide_<L>: ids, prefix, media, pad, keep_right, qtype_onehot in; scores out. A text request has no media prefix. */
    private class Graph(val L: Int, file: File, threads: Int, cache: File?) : Closeable {
        private val sig = "decide_$L"
        private val model = CompiledModel.create(file.path, CompiledModel.Options(Accelerator.CPU).apply {
            cpuOptions = CompiledModel.CpuOptions(threads, null, cache?.path)
        })
        private val names = listOf("ids", "prefix", "media", "pad", "keep_right", "qtype_onehot")
        private val ins: Map<String, TensorBuffer> = names.associateWith { model.createInputBuffer(it, sig) }
        private val out: TensorBuffer = model.createOutputBuffer("scores", sig)
        private val idBuf = IntArray(L)
        private val padBuf = FloatArray(L)

        init {
            // Never change for text: no media rows, every position may read its right neighbour.
            ins.getValue("prefix").writeFloat(FloatArray(L * 1024))
            ins.getValue("media").writeFloat(FloatArray(L))
            ins.getValue("keep_right").writeFloat(FloatArray(L) { 1f })
        }

        fun run(ids: IntArray, qtype: Int): FloatArray {
            idBuf.fill(0); ids.copyInto(idBuf)
            padBuf.fill(0f); padBuf.fill(1f, 0, ids.size)
            ins.getValue("ids").writeInt(idBuf)
            ins.getValue("pad").writeFloat(padBuf)
            ins.getValue("qtype_onehot").writeFloat(FloatArray(3).also { it[qtype] = 1f })
            model.run(ins, mapOf("scores" to out), sig)
            return out.readFloat()
        }

        override fun close() {
            ins.values.forEach { it.close() }
            out.close()
            model.close()
        }
    }

    companion object {
        const val CONTRACT = "d1-omni-600M_contract.json"
        const val TOKENIZER = "d1-omni-600M_tokenizer.json"
        const val GRAPH_L128 = "d1-omni-600M_decide_L128_fp16.tflite"

        /** The capture questions read the line as this (it scored better than the bare line on the label set). */
        const val CAPTURE_STATE = "A line the owner wrote in their notebook: "

        // Labels.kt's CAPTURE_SYSTEM / INTENT_SYSTEM definitions as d1 questions (d1_golden.py holds the same).
        val KIND = D1Question.choice("What kind of line did the owner write in their notebook?",
            "todo" to "an action to do (buy, call, fix, send)",
            "event" to "happens at a set day or time (appointment, meeting, party)",
            "idea" to "a possibility or \"what if\"",
            "note" to "a fact to keep",
            "journal" to "how they feel or what happened to them")
        val URGENCY = D1Question.choice("How urgent is it?", "now" to "today or blocking", "soon" to "this week", "later" to "no hurry")
        val ENERGY = D1Question.choice("How much energy does it take?", "low" to "quick or easy", "high" to "long, hard or emotionally heavy")
        /** Sensitive = anything but "everyday": a topic choice reads far better than one yes/no question (AUC 0.89 vs 0.77). */
        val TOPIC = D1Question.choice("What is it about?",
            "health" to "health, medicine, doctors or therapy",
            "money" to "money, bills, bank or taxes",
            "passwords" to "passwords, PINs or logins",
            "legal" to "legal trouble, courts or fines",
            "intimate" to "intimate matters or relationships",
            "everyday" to "everyday things")
        val INTENT = D1Question.choice("The owner is talking to their assistant while the phone's camera is on. What do they want?",
            "look" to "a question about what the camera sees right now (what is this, read this, how many, what colour)",
            "act" to "asks to add, remind, note, plan, set or change something",
            "chat" to "anything else")

        /** Opens d1 from [dir], or null when its files are not there. Checks the tokenizer's ids against the contract. */
        fun open(dir: File, threads: Int = 4, weightCache: File? = null): Decide? {
            val contract = File(dir, CONTRACT).takeIf { it.isFile } ?: return null
            val tokFile = File(dir, TOKENIZER).takeIf { it.isFile } ?: return null
            val c = JSONObject(contract.readText())
            val byL = c.getJSONObject("graphs").getJSONObject("decide").getJSONObject("by_L")
            val graphs = byL.keys().asSequence().associate { it.toInt() to File(dir, byL.getString(it)) }.filterValues { it.isFile }
            if (graphs.isEmpty()) return null
            val t = c.getJSONObject("temperatures")
            val temps = t.keys().asSequence().associateWith { t.getDouble(it) }
            val tok = tokens(BpeTokenizer.load(tokFile))
            val want = c.getJSONObject("token_ids")
            for (k in want.keys()) check(tok.id(k) == want.getInt(k)) { "$TOKENIZER gives $k id ${tok.id(k)}, contract ${want.getInt(k)}" }
            return Decide(tok, temps, graphs, threads, weightCache)
        }

        fun tokens(t: BpeTokenizer): D1Tokens = object : D1Tokens {
            override fun encode(text: String) = t.encode(text)
            override fun id(token: String) = t.id(token) ?: error("no token $token")
        }

        private val timeStart = Regex("""^(?:today|tonight|tomorrow|mon|tue|wed|thu|fri|sat|sun|at|on|in|next|this|\d)""", RegexOption.IGNORE_CASE)
        private val dated = Regex("""(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d[:.]\d\d|\d\s*[ap]\.?m\b|^in\s+\S+\s+\w+$|^at\s+\d)""", RegexOption.IGNORE_CASE)

        /**
         * The words in [line] that say a time the phone can read ("tuesday at 3", "tomorrow 9:30"), or "": the longest
         * run of up to five words [com.lfmpixel.lfm.tools.TimeArgs] parses that names a day, a clock time or "in …".
         * [com.lfmpixel.lfm.assistant.InkLine.withLabel] makes a reminder of it, as with the server's "when".
         */
        fun whenWords(line: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
            val words = Regex("""\S+""").findAll(line).toList()
            var best = ""
            for (i in words.indices) {
                if (!timeStart.containsMatchIn(words[i].value)) continue
                for (j in minOf(words.size, i + 5) downTo i + 1) {
                    // The line's own words, so the reminder text can be cut around them; punctuation off the ends.
                    val s = line.substring(words[i].range.first, words[j - 1].range.last + 1).trim(',', '.', ';', '!', '?', ':')
                    if (s.length <= best.length) break
                    val read = s.replace(Regex("""[,;]"""), "")
                    if (dated.containsMatchIn(read) && com.lfmpixel.lfm.tools.TimeArgs.parse(read, now) != null) { best = s; break }
                }
            }
            return best
        }

        private fun DoubleArray.argmax(): Int = indices.maxBy { this[it] }
    }
}
