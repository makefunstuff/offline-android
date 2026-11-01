package com.lfmpixel.lfm.voice

import java.util.SplittableRandom
import kotlin.math.exp

/**
 * OmniVoice's plain-Kotlin parts (no LiteRT, unit-tested on the JVM): LFM3-Omni-2B's token ids and speech constants,
 * the sampler, the maskgit order, the interleaving of text and speech, the codec's overlap-add and the prompt ids.
 * Sources: runner/contract.json, runner/speech.py and runner/prompt.py of litert-community/LFM3-Omni-2B-LiteRT.
 */
object OmniTokens {
    const val BOS = 124894 // <|startoftext|>
    const val IM_START = 124899
    const val IM_END = 124900
    const val VISION_START = 125009
    const val VISION_END = 125010
    const val IMAGE = 125030
    const val VIDEO = 125029
    const val AUDIO = 125031
    const val AUDIO_OUT = 125032
    const val AUDIO_START = 125012
    const val TEXT_END = 125014
    const val MIXED_START = 125015

    /** Placeholders never sampled in a text slot (the model's _placeholder_token_ids). */
    val NEVER_SAMPLED = intArrayOf(AUDIO, AUDIO_OUT, IMAGE, VIDEO)
    /** Text only: the switches into speech ruled out too. */
    val TEXT_ONLY_SUPPRESS = NEVER_SAMPLED + intArrayOf(MIXED_START, AUDIO_START)

    const val CODEBOOKS = 12
    const val CODEBOOK_SIZE = 2017
    const val AUDIO_EOS_CODE = 2016
    val MASKGIT_SCHEDULE = intArrayOf(4, 4, 4)
    const val INTERLEAVED_TEXT = 6
    const val INTERLEAVED_AUDIO = 12
    const val HIDDEN = 2048
    const val SAMPLE_RATE = 24_000

    /** Speech sampling (generation_config.json): temperature 0.4, repetition penalty 1.2 over the last 64 frames. */
    const val AUDIO_TEMPERATURE = 0.4f
    const val AUDIO_PENALTY = 1.2f
    const val AUDIO_WINDOW = 64
}

/** The draws of one answer: the sampler's and the maskgit order's, from its seed. */
class OmniRng(seed: Long) {
    private val r = SplittableRandom(seed)
    fun nextDouble(): Double = r.nextDouble()

    /** A random order of 0 until [n] (Fisher-Yates). */
    fun permutation(n: Int): IntArray {
        val p = IntArray(n) { it }
        for (i in n - 1 downTo 1) {
            val j = r.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        return p
    }
}

object OmniSampler {
    /** transformers' repetition penalty on the ids in [seen]: a positive logit divided by [penalty], a negative one multiplied. */
    fun penalize(logits: FloatArray, seen: IntArray, penalty: Float) {
        if (penalty == 1f) return
        for (id in seen.distinct()) if (id in logits.indices) logits[id] = if (logits[id] < 0f) logits[id] * penalty else logits[id] / penalty
    }

    fun argmax(l: FloatArray): Int {
        var best = 0
        for (i in 1 until l.size) if (l[i] > l[best]) best = i
        return best
    }

    /**
     * A text token from [logits] (changed in place): [suppress] ids never; [temp] 0 = greedy; otherwise as Talk's
     * llama.cpp chain did: top-k [topK], then top-p [topP] on the softmax, then [temp], then one draw of [u01].
     */
    fun sampleText(logits: FloatArray, suppress: IntArray, temp: Float, topK: Int, topP: Float, u01: Double): Int {
        for (id in suppress) if (id in logits.indices) logits[id] = Float.NEGATIVE_INFINITY
        if (temp <= 0f) return argmax(logits)
        // the top-k candidates, best first
        val k = topK.coerceIn(1, logits.size)
        val idx = topIndices(logits, k)
        val max = logits[idx[0]]
        val p = DoubleArray(k) { exp((logits[idx[it]] - max).toDouble()) }
        var sum = p.sum()
        // top-p: the smallest prefix whose mass reaches topP
        var keep = k
        var acc = 0.0
        for (i in 0 until k) {
            acc += p[i] / sum
            if (acc >= topP) { keep = i + 1; break }
        }
        val q = DoubleArray(keep) { exp((logits[idx[it]] - max).toDouble() / temp) }
        sum = q.sum()
        var x = u01 * sum
        for (i in 0 until keep) {
            x -= q[i]
            if (x <= 0.0) return idx[i]
        }
        return idx[keep - 1]
    }

    /** The [k] largest entries' indices, largest first (ties: lower index first). */
    fun topIndices(l: FloatArray, k: Int): IntArray {
        val out = IntArray(k) { -1 }
        for (i in l.indices) {
            val v = l[i]
            if (out[k - 1] >= 0 && v <= l[out[k - 1]]) continue
            var j = k - 1
            while (j > 0 && (out[j - 1] < 0 || v > l[out[j - 1]])) { out[j] = out[j - 1]; j-- }
            out[j] = i
        }
        return out.filter { it >= 0 }.toIntArray()
    }
}

/**
 * The depth decoder's inputs for one frame (depth_frame2 samples inside its graph): which codebooks each maskgit pass
 * reveals, from the frame's random [order] and the schedule [4, 4, 4], and one uniform draw per codebook, the j-th draw
 * going to codebook order[j], capped at [U_MAX].
 */
object Maskgit {
    /**
     * The largest draw given to the graph, 1 - 2^-11: its float32 cdf can total a little under 1, and a draw above
     * that would pick the last code, the end of audio, mid-sentence (the LiteRT sample's DepthDecoder.FRAME_U_MAX).
     */
    const val U_MAX = 1f - 1f / 2048

    fun reveal(order: IntArray, schedule: IntArray = OmniTokens.MASKGIT_SCHEDULE): Array<FloatArray> {
        val rows = Array(schedule.size) { FloatArray(order.size) }
        var start = 0
        for ((p, count) in schedule.withIndex()) {
            for (j in start until start + count) rows[p][order[j]] = 1f
            start += count
        }
        return rows
    }

    fun uniforms(order: IntArray, rng: OmniRng): FloatArray {
        val u = FloatArray(order.size)
        for (j in order.indices) u[order[j]] = minOf(rng.nextDouble().toFloat(), U_MAX)
        return u
    }
}

/** The repetition penalty's window: which codes each codebook used in the last [window] frames, as the graph's presence input. */
class CodeWindow(private val window: Int = OmniTokens.AUDIO_WINDOW) {
    private val counts = Array(OmniTokens.CODEBOOKS) { IntArray(OmniTokens.CODEBOOK_SIZE) }
    private val frames = ArrayDeque<IntArray>()

    fun add(codes: IntArray) {
        frames.addLast(codes.copyOf())
        for (k in codes.indices) counts[k][codes[k]]++
        if (frames.size > window) {
            val old = frames.removeFirst()
            for (k in old.indices) counts[k][old[k]]--
        }
    }

    /** [12 x 2017]: 1 where that code is in its codebook's window. */
    fun presence(out: FloatArray = FloatArray(OmniTokens.CODEBOOKS * OmniTokens.CODEBOOK_SIZE)): FloatArray {
        for (k in 0 until OmniTokens.CODEBOOKS) {
            val c = counts[k]
            val o = k * OmniTokens.CODEBOOK_SIZE
            for (i in c.indices) out[o + i] = if (c[i] > 0) 1f else 0f
        }
        return out
    }
}

/**
 * Which kind of slot comes next in one answer (the source model's _generate): TEXT, one token a slot; AUDIO, one
 * speech frame a slot until the end-of-audio frame; INTERLEAVED, blocks of 6 text slots and 12 frames until
 * `<|text_end|>`, then speech until its end. [continuing] (Talk): after a speech segment ends the answer goes on in
 * text; otherwise (read-aloud) the end of audio ends it.
 */
class SlotPlan(var mode: Mode, private val continuing: Boolean) {
    enum class Mode { TEXT, AUDIO, INTERLEAVED }

    private var slotsLeft = OmniTokens.INTERLEAVED_TEXT
    private var speaking = false
    private var textDone = false

    val nextIsSpeech get() = mode == Mode.AUDIO || (mode == Mode.INTERLEAVED && speaking)

    /** A text token was sampled; returns true when it ends the answer (it is then not fed back). */
    fun onText(token: Int): Boolean {
        val interleaving = mode == Mode.INTERLEAVED
        if (token == OmniTokens.IM_END || (!interleaving && token == OmniTokens.TEXT_END)) return true
        if (!interleaving) {
            if (token == OmniTokens.AUDIO_START) mode = Mode.AUDIO
            else if (token == OmniTokens.MIXED_START) { mode = Mode.INTERLEAVED; slotsLeft = OmniTokens.INTERLEAVED_TEXT; speaking = false; textDone = false }
        } else if (token == OmniTokens.TEXT_END) {
            textDone = true
            speaking = true
        }
        count(interleaving)
        return false
    }

    /** A frame was sampled; returns true when it ends the answer (the end-of-audio frame, not continuing). */
    fun onFrame(eos: Boolean): Boolean {
        val interleaving = mode == Mode.INTERLEAVED
        if (eos) {
            if (!continuing) return true
            mode = Mode.TEXT
        }
        count(interleaving)
        return false
    }

    // a slot emitted while interleaving counts down the block; the token that switched into it does not
    private fun count(interleaving: Boolean) {
        if (interleaving && !textDone && --slotsLeft == 0) {
            speaking = !speaking
            slotsLeft = if (speaking) OmniTokens.INTERLEAVED_AUDIO else OmniTokens.INTERLEAVED_TEXT
        }
    }
}

/**
 * The codec's host side: its windowed time frames (1280 samples, hop 320) overlap-added and divided by the
 * overlap-added window power; the stream's first 480 samples dropped, the last 960 held for the next call, and on the
 * last call the final 480 dropped instead (LiquidCodecModel.decode, streaming branch).
 */
class CodecStream(window: FloatArray, private val hop: Int = 320) {
    private val nFft = window.size
    private val trim = (nFft - hop) / 2
    private val tailLen = 2 * trim
    private val w2 = FloatArray(nFft) { window[it] * window[it] }
    private var tailAudio: FloatArray? = null
    private var tailEnv: FloatArray? = null
    private var started = false

    fun reset() { tailAudio = null; tailEnv = null; started = false }

    /** [frames] holds [count] frames of 1280 samples from [offset]; returns the samples now final. */
    fun push(frames: FloatArray, count: Int, finalize: Boolean, offset: Int = 0): FloatArray {
        if (count == 0) return if (finalize) finish() else FloatArray(0)
        val size = (count - 1) * hop + nFft
        val audio = FloatArray(size)
        val env = FloatArray(size)
        for (i in 0 until count) {
            val o = i * hop
            val src = offset + i * nFft
            for (s in 0 until nFft) {
                audio[o + s] += frames[src + s]
                env[o + s] += w2[s]
            }
        }
        tailAudio?.let { t -> for (s in t.indices) audio[s] += t[s] }
        tailEnv?.let { t -> for (s in t.indices) env[s] += t[s] }
        val from = if (started) 0 else trim
        started = true
        val to: Int
        if (finalize) {
            to = size - trim
            tailAudio = null; tailEnv = null
        } else {
            to = size - tailLen
            tailAudio = audio.copyOfRange(size - tailLen, size)
            tailEnv = env.copyOfRange(size - tailLen, size)
        }
        return FloatArray(maxOf(0, to - from)) { audio[from + it] / env[from + it] }
    }

    /** The held tail, at the end of the speech. */
    fun finish(): FloatArray {
        val a = tailAudio ?: return FloatArray(0)
        val e = tailEnv!!
        reset()
        return FloatArray(a.size - trim) { a[it] / e[it] }
    }
}

/** Prompt ids as the model reads them (runner/prompt.py). */
object OmniPrompt {
    /** The start token in front, as the reference prompt has it (the app's chat strings leave it out). */
    fun withBos(ids: IntArray): IntArray = if (ids.isNotEmpty() && ids[0] == OmniTokens.BOS) ids else intArrayOf(OmniTokens.BOS) + ids

    /** Placeholders of a 16 kHz clip of [samples] (the processor's count: mel frames of the padded clip / 8, rounded up). */
    fun audioTokens(samples: Int): Int {
        val padded = if (samples < 400) 400 else samples + (-(samples - 400)).mod(160)
        val frames = 1 + (padded - 400) / 160
        return (frames + 7) / 8
    }

    /** ASR: [system] then one user turn holding the clip; the clip's placeholders are added by [withAudio]. */
    fun asrText(system: String) =
        "<|startoftext|><|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n<|vision_start|><|audio|><|vision_end|><|im_end|>\n" +
            "<|im_start|>assistant\n<think></think>"

    /** [ids] with its run of `<|audio|>` replaced by [rows] of them; returns the ids and where the run starts. */
    fun withAudio(ids: IntArray, rows: Int): Pair<IntArray, Int> {
        val first = ids.indexOf(OmniTokens.AUDIO)
        require(first >= 0) { "no audio placeholder" }
        var last = first
        while (last + 1 < ids.size && ids[last + 1] == OmniTokens.AUDIO) last++
        return (ids.copyOfRange(0, first) + IntArray(rows) { OmniTokens.AUDIO } + ids.copyOfRange(last + 1, ids.size)) to first
    }
}

/** UTF-8 bytes of streamed tokens to whole characters: holds back a character split across tokens. */
class Utf8Stream {
    private val pending = java.io.ByteArrayOutputStream()

    fun feed(bytes: ByteArray): String {
        pending.write(bytes)
        val all = pending.toByteArray()
        val tail = incompleteTail(all)
        pending.reset()
        pending.write(all, all.size - tail, tail)
        return String(all, 0, all.size - tail, Charsets.UTF_8)
    }

    fun flush(): String = String(pending.toByteArray(), Charsets.UTF_8).also { pending.reset() }

    companion object {
        /** How many bytes at the end of [s] start a character that is not complete yet. */
        fun incompleteTail(s: ByteArray): Int {
            val n = s.size
            for (back in 1..minOf(4, n)) {
                val c = s[n - back].toInt() and 0xff
                if (c and 0xC0 == 0x80) continue
                val need = when {
                    c and 0x80 == 0 -> 1
                    c and 0xE0 == 0xC0 -> 2
                    c and 0xF0 == 0xE0 -> 3
                    c and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                return if (need > back) back else 0
            }
            return 0
        }
    }
}
