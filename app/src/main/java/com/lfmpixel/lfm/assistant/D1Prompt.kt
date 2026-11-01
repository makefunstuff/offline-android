package com.lfmpixel.lfm.assistant

import kotlin.math.exp

/**
 * One d1 question (the provider's Decision Index schema): a [type] of choice, score or noul, [instructions], and
 * [criteria] in order: option name to description for a choice ("" for a bare name), level descriptions lowest first
 * for a score, and for a noul optional "true" / "false" wordings.
 */
data class D1Question(val type: String, val instructions: String, val criteria: List<Pair<String, String>> = emptyList()) {
    init {
        require(type in QTYPES) { "question type must be choice, score or noul, got $type" }
        require(type != "choice" || criteria.size >= 2) { "a choice needs at least two options" }
        require(type != "score" || criteria.size in 2..10) { "a score needs 2 to 10 levels" }
    }

    val options: Int get() = if (type == "noul") 2 else criteria.size
    val names: List<String> get() = criteria.map { it.first }

    companion object {
        /** The qtype_onehot order: [choice, score, noul]. */
        val QTYPES = listOf("choice", "score", "noul")
        fun choice(instructions: String, vararg options: Pair<String, String>) = D1Question("choice", instructions, options.toList())
        fun noul(instructions: String, yes: String = "", no: String = "") =
            D1Question("noul", instructions, listOfNotNull(("true" to yes).takeIf { yes.isNotEmpty() }, ("false" to no).takeIf { no.isNotEmpty() }))
    }
}

/** The ids d1 reads for one question over one state, and where each option's `<|mask|>` marker is. */
class D1Row(val ids: IntArray, val markers: IntArray)

/** Text to ids for [D1Prompt]: HF `tokenizers` semantics (added tokens matched as single ids, no BOS), and a token's id. */
interface D1Tokens {
    fun encode(text: String): IntArray
    fun id(token: String): Int
}

/**
 * The request side of d1-omni-600M for text: the provider's prompt.py (`encode`, `render_options`, `escape`) and the
 * LiteRT port's read-out (d1_host.readout_f64), line for line. A row is
 * `<|startoftext|> <|reserved_7|> state <|reserved_8|> instructions`, then per option `<|reserved_9|> <|mask|> option
 * <|reserved_10|>`, then `<|reserved_11|>`. D1PromptTest checks it against ids from the Python code (d1_golden.tsv).
 */
object D1Prompt {
    const val BOS = "<|startoftext|>"
    const val STATE = "<|reserved_7|>"
    const val Q = "<|reserved_8|>"
    const val OPT = "<|reserved_9|>"
    const val OPT_END = "<|reserved_10|>"
    const val DECIDE = "<|reserved_11|>"
    const val MARKER = "<|mask|>"
    /** The provider's limit for a text request (contract.json modes.text.max_len). */
    const val TEXT_MAX_LEN = 16_384
    private const val PER_OPTION = 24
    private val special = Regex("""<\|([A-Za-z0-9_]+)\|>""")

    /** `<|name|>` becomes `<¦name¦>`, so caller text cannot emit a delimiter or marker token. */
    fun escape(text: String): String = special.replace(text) { "<¦${it.groupValues[1]}¦>" }

    /** Option texts in the model's order; a noul reads as [false, true]. Text requests only (no audio wording). */
    fun renderOptions(q: D1Question): List<String> = when (q.type) {
        "choice" -> q.criteria.map { (k, v) -> if (v.isEmpty()) k else "$k: $v" }
        "score" -> q.criteria.mapIndexed { i, (_, v) -> "level $i: $v" }
        else -> {
            val c = q.criteria.toMap()
            val no = c["false"] ?: c["no"]
            val yes = c["true"] ?: c["yes"]
            listOf("false: " + (no?.takeIf { it.isNotEmpty() } ?: "no, the statement does not hold"),
                "true: " + (yes?.takeIf { it.isNotEmpty() } ?: "yes, the statement holds"))
        }
    }

    /**
     * The ids of [q] over [state] and the marker positions. The option block gets max(96, min(24k + 32, maxLen / 2))
     * tokens shared out evenly; the state is cut on the right to the room that is left. Throws when the options
     * themselves do not fit [maxLen].
     */
    fun encode(tok: D1Tokens, state: String, q: D1Question, maxLen: Int = TEXT_MAX_LEN): D1Row {
        fun enc(s: String) = tok.encode(escape(s))
        val opts = renderOptions(q)
        val budget = maxOf(96, minOf(opts.size * PER_OPTION + 32, maxLen / 2))
        val per = maxOf(2, Math.floorDiv(budget - 3 * opts.size, opts.size))
        val question = ArrayList<Int>()
        question += tok.id(Q)
        enc(q.instructions).forEach { question += it }
        while (question.size > maxOf(16, budget)) question.removeAt(question.size - 1)
        val markers = ArrayList<Int>()
        for (text in opts) {
            markers += question.size + 1
            question += tok.id(OPT)
            question += tok.id(MARKER)
            enc(" $text").let { ids -> for (i in 0 until minOf(per, ids.size)) question += ids[i] }
            question += tok.id(OPT_END)
        }
        question += tok.id(DECIDE)
        val room = maxOf(0, maxLen - question.size - 2)
        val stateIds = enc(state)
        val head = 2 + minOf(room, stateIds.size) // bos + <state> + the state's ids that fit
        val n = minOf(maxLen, head + question.size)
        val ids = IntArray(n)
        var i = 0
        fun put(v: Int) { if (i < n) ids[i++] = v }
        put(tok.id(BOS)); put(tok.id(STATE))
        for (k in 0 until head - 2) put(stateIds[k])
        for (v in question) put(v)
        val m = IntArray(markers.size) { markers[it] + head }
        require(m.last() < maxLen) { "the options do not fit in the context" }
        return D1Row(ids, m)
    }

    /** contract.json temperature_rule: "<type>:2" / ":3-5" / ":6-10" / ":11+" by option count, else the type's. */
    fun temperature(q: D1Question, temperatures: Map<String, Double>): Double {
        val k = q.options
        val key = q.type + ":" + when { k <= 2 -> "2"; k <= 5 -> "3-5"; k <= 10 -> "6-10"; else -> "11+" }
        return temperatures[key] ?: temperatures[q.type] ?: 1.0
    }

    /**
     * The read-out: the scores at the markers (option order), divided by the temperature for a text request
     * ([calibrate]), softmax in double; a noul comes back as [yes, no].
     */
    fun readout(z: DoubleArray, q: D1Question, calibrate: Boolean, temperatures: Map<String, Double>): DoubleArray {
        val t = if (calibrate) temperature(q, temperatures) else 1.0
        val x = DoubleArray(q.options) { z[it] / t }
        val max = x.max()
        val e = DoubleArray(x.size) { exp(x[it] - max) }
        val sum = e.sum()
        val p = DoubleArray(x.size) { e[it] / sum }
        return if (q.type == "noul") p.reversedArray() else p
    }
}
