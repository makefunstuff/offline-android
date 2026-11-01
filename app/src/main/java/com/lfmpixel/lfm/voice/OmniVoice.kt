package com.lfmpixel.lfm.voice

import com.lfmpixel.lfm.llm.BpeTokenizer
import com.lfmpixel.lfm.llm.GenStats
import com.lfmpixel.lfm.llm.LlmException
import com.lfmpixel.lfm.llm.SpeechStats
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * LFM3-Omni-2B's voice on LiteRT (litert-community/LFM3-Omni-2B-LiteRT): speech to text, text to speech and Talk's
 * spoken answers, with the semantics of LlamaSession's voice calls (talk.cpp). One decoder serves all three: the
 * PREFILL_DECODE, EMBEDDER and AUDIO_ENCODER_HW sections of the .litertlm, mapped from the file where they lie
 * (bundle_sections.json gives the byte ranges), plus the speech graphs: depth_frame2 (a frame's 12 codes, sampled in
 * the graph), frame_embed (a frame back into the decoder) and codec_decoder_f12 (12 frames to 0.96 s of 24 kHz audio).
 * The loop around them is runner/speech.py's, in [OmniCore]'s pieces.
 *
 * The decoder's KV cache is updated in place and indexed by position, so the conversation read in for Talk stays
 * valid while transcription and read-aloud run in the positions after it (attention with RoPE only sees relative
 * positions): a snapshot of the history is its tokens and the 13 short-convolution states, 640 KB. Every call runs on
 * one thread of its own, one at a time.
 */
class OmniVoice private constructor(
    private val tok: BpeTokenizer,
    private val env: RtEnv,
    private val lm: Lm,
    private val embedder: RtGraph,
    private val encoder: RtGraph,
    private val depth: RtGraph,
    private val frameEmbed: RtGraph,
    private val codecGraph: RtGraph,
    private val window: FloatArray,
    /** Load times and sizes, for the log and the debug bench. */
    val loadInfo: JSONObject,
) {
    companion object {
        const val LITERTLM = "LFM3-Omni-2B_int8.litertlm"
        const val DEPTH = "LFM3-Omni-2B_depth_frame2_f32.tflite"
        const val FRAME = "LFM3-Omni-2B_frame_embed_f32.tflite"
        const val CODEC = "LFM3-Omni-2B_codec_decoder_f12_f32.tflite"
        const val WINDOW = "codec_window_f32.npy"
        const val CONTRACT = "LFM3-Omni-2B_contract.json"
        const val SECTIONS = "LFM3-Omni-2B_bundle_sections.json"
        private const val CONTRACT_ID = "lfm3-omni-2b-litert-speech-v1"
        /** The default folder (in speechDir) for XNNPACK's packed weights, one file per graph, written on the first open. */
        const val CACHE_DIR = "omni-xnnpack"

        /** Files OmniVoice needs inside speechDir (for the downloader/catalog), besides the .litertlm. */
        val SPEECH_FILES: List<String> = listOf(DEPTH, FRAME, CODEC, WINDOW, CONTRACT, SECTIONS)

        private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "omni").apply { priority = Thread.MAX_PRIORITY } }
            .asCoroutineDispatcher()

        /**
         * [litertlm]: the .litertlm; [speechDir]: folder with the speech graphs (SPEECH_FILES); [threads]: CPU threads;
         * [cacheDir]: where XNNPACK's packed weights are kept (needed: without them each of the decoder's seven
         * signatures packs its own copy in RAM). The first open writes them (about 2.7 GB, a minute or more); later
         * opens map them. Files of graphs that changed are deleted.
         */
        suspend fun open(litertlm: File, speechDir: File, tokenizer: BpeTokenizer, threads: Int,
                         cacheDir: File = File(speechDir, CACHE_DIR)): OmniVoice = withContext(dispatcher) {
            val t0 = System.nanoTime()
            val info = JSONObject()
            val contract = JSONObject(File(speechDir, CONTRACT).readText())
            if (contract.optString("contract") != CONTRACT_ID) throw LlmException("$CONTRACT is not $CONTRACT_ID")
            val table = JSONObject(File(speechDir, SECTIONS).readText())
            if (litertlm.length() != table.getLong("bytes")) {
                throw LlmException("${litertlm.name} is ${litertlm.length()} bytes, not ${table.getLong("bytes")}: download it again")
            }
            fun section(key: String): Pair<Long, Long> {
                val sha = table.getJSONObject("inputs").getJSONObject(key).getString("sha256")
                val rows = table.getJSONArray("sections")
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    if (r.optString("sha256") == sha) return r.getLong("begin") to r.getLong("end") - r.getLong("begin")
                }
                throw LlmException("$SECTIONS lists no $key section")
            }
            cacheDir.mkdirs()
            val keep = HashSet<String>()
            fun cache(tag: String, file: File, size: Long): File? {
                if (!cacheDir.isDirectory) return null
                val name = "$tag-$size-${file.lastModified()}.xnnpack"
                keep += name
                return File(cacheDir, name)
            }
            val opened = ArrayList<AutoCloseable>()
            val env = RtEnv().also { opened += it }
            try {
                fun graph(tag: String, file: File, range: Pair<Long, Long>?): RtGraph {
                    val ts = System.nanoTime()
                    val (off, size) = range ?: (0L to file.length())
                    if (!file.isFile) throw LlmException("${file.name} is missing")
                    return RtGraph(env, file, off, size, threads, cache(tag, file, size)).also {
                        opened += it
                        info.put("${tag}_ms", (System.nanoTime() - ts) / 1e6)
                    }
                }
                val decoder = graph("decoder", litertlm, section("PREFILL_DECODE"))
                val embedder = graph("embedder", litertlm, section("EMBEDDER"))
                val encoder = graph("audio_encoder", litertlm, section("AUDIO_ENCODER_HW"))
                val depth = graph("depth", File(speechDir, DEPTH), null)
                val frame = graph("frame_embed", File(speechDir, FRAME), null)
                val codec = graph("codec", File(speechDir, CODEC), null)
                // caches of files that changed since
                cacheDir.listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
                val window = readNpy(File(speechDir, WINDOW))
                val lm = Lm(decoder)
                info.put("load_ms", (System.nanoTime() - t0) / 1e6)
                info.put("threads", threads)
                OmniVoice(tokenizer, env, lm, embedder, encoder, depth, frame, codec, window, info)
            } catch (t: Throwable) {
                for (o in opened.reversed()) runCatching { o.close() }
                throw t
            }
        }

        /** A float32 .npy array (version 1 or 2 header, little-endian, C order). */
        fun readNpy(file: File): FloatArray {
            val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val major = b.get(6).toInt()
            val headerLen = if (major == 1) b.getShort(8).toInt() and 0xffff else b.getInt(8)
            val start = (if (major == 1) 10 else 12) + headerLen
            val header = String(b.array(), if (major == 1) 10 else 12, headerLen, Charsets.US_ASCII)
            require("'<f4'" in header && "True" !in header) { "${file.name}: not little-endian float32 in C order" }
            b.position(start)
            return FloatArray(b.remaining() / 4) { b.getFloat() }
        }
    }

    private val mutex = Mutex()
    private var closed = false
    private val cancelGen = AtomicInteger()
    private var lastStats = GenStats(0.0, 0, 0.0, 0, 0)
    private var speechStats = SpeechStats()

    /** The conversation read in so far: the latest snapshot and the one it grew from (talk::history_cache). */
    private val snaps = ArrayList<Lm.Snap>()

    /** The last speak or Talk turn's stages, readable after the session is closed. */
    @Volatile var lastSpeech: SpeechStats = SpeechStats()

    private suspend fun <T> onThread(block: () -> T): T = mutex.withLock {
        withContext(dispatcher) {
            if (closed) throw LlmException("The model was unloaded; try again")
            block()
        }
    }

    // --- the small graphs -------------------------------------------------------------------------------------

    private val embSig = embedder.Sig(embedder.signatures.single())
    private val embIn = embSig.buffer("token_ids").also { embSig.bind("token_ids", it) }
    private val embOut = embSig.buffer("embeddings", true).also { embSig.bind("embeddings", it, true) }

    private fun embed(id: Int): FloatArray {
        embIn.putInts(intArrayOf(id))
        embSig.run()
        return embOut.get(FloatArray(OmniTokens.HIDDEN))
    }

    private val depthSig = depth.Sig("depth_frame")
    private val depthIn = listOf("hidden", "presence", "reveal0", "reveal1", "reveal2", "u").associateWith { n ->
        depthSig.buffer(n).also { depthSig.bind(n, it) }
    }
    private val depthOut = depthSig.buffer("codes", true).also { depthSig.bind("codes", it, true) }
    private val presence = FloatArray(OmniTokens.CODEBOOKS * OmniTokens.CODEBOOK_SIZE)

    /** One frame's 12 codes from the decoder's hidden state, in a random maskgit order. */
    private fun sampleFrame(hidden: FloatArray, window: CodeWindow, rng: OmniRng): IntArray {
        val order = rng.permutation(OmniTokens.CODEBOOKS)
        val reveal = Maskgit.reveal(order)
        depthIn.getValue("hidden").put(hidden)
        depthIn.getValue("presence").put(window.presence(presence))
        for (p in 0 until 3) depthIn.getValue("reveal$p").put(reveal[p])
        depthIn.getValue("u").put(Maskgit.uniforms(order, rng))
        depthSig.run()
        return depthOut.getInts(IntArray(OmniTokens.CODEBOOKS))
    }

    private val frameSig = frameEmbed.Sig(frameEmbed.signatures.single())
    private val frameIn = frameSig.buffer("codes").also { frameSig.bind("codes", it) }
    private val frameOut = frameSig.buffer("embeds", true).also { frameSig.bind("embeds", it, true) }

    private fun embedFrame(codes: IntArray): FloatArray {
        frameIn.putInts(codes)
        frameSig.run()
        return frameOut.get(FloatArray(OmniTokens.HIDDEN))
    }

    private val encSig = encoder.Sig("encode")
    private val encAudio = encSig.buffer("audio").also { encSig.bind("audio", it) }
    private val encFeatures = encSig.buffer("features", true).also { encSig.bind("features", it, true) }
    private val encMask = encSig.buffer("mask", true).also { encSig.bind("mask", it, true) }

    /** The audio encoder's rows for a 16 kHz clip: one call per 30 s window, the valid rows joined (as LiteRT-LM does). */
    private fun audioRows(pcm: FloatArray): List<FloatArray> {
        val win = encAudio.size
        val rows = ArrayList<FloatArray>()
        var from = 0
        do {
            val n = minOf(win, pcm.size - from)
            encAudio.zero()
            encAudio.put(pcm, from, n)
            encSig.run()
            var valid = 0
            for (i in 0 until encMask.size) if (encMask.byte(i) != 0) valid++
            val feats = encFeatures.get()
            for (r in 0 until valid) rows += feats.copyOfRange(r * OmniTokens.HIDDEN, (r + 1) * OmniTokens.HIDDEN)
            from += win
        } while (from < pcm.size)
        return rows
    }

    /** The codec in chunks of the graph's 12 frames, its state carried from call to call, the samples overlap-added. */
    private inner class Codec {
        private val sig = codecGraph.Sig("decode")
        private val per = sig.shape("codes")[2]
        private val stateNames = sig.inputs.filter { it != "codes" && it != "start" }
        private val sets = Array(2) { stateNames.associateWith { n -> sig.buffer(n) } }
        private val codes = sig.buffer("codes").also { sig.bind("codes", it) }
        private val start = sig.buffer("start").also { sig.bind("start", it) }
        private val frames = sig.buffer("frames", true).also { sig.bind("frames", it, true) }
        private val stream = CodecStream(window)
        private val pending = ArrayList<IntArray>()
        private var cur = 0
        private var done = 0
        var ms = 0.0

        fun reset() {
            for (s in sets) for (b in s.values) b.zero()
            cur = 0; done = 0
            pending.clear()
            stream.reset()
        }

        /** Adds a frame; returns the samples now final (a full chunk's), or none. */
        fun push(c: IntArray): FloatArray {
            pending += c.copyOf()
            return if (pending.size < per) FloatArray(0) else call(false)
        }

        /** The rest, at the end of a speech segment; the codec starts over after it. */
        fun finish(): FloatArray = (if (pending.isNotEmpty()) call(true) else stream.finish()).also { reset() }

        private fun call(finalize: Boolean): FloatArray {
            val t0 = System.nanoTime()
            val valid = pending.size
            val flat = IntArray(OmniTokens.CODEBOOKS * per)
            for (j in 0 until valid) for (k in 0 until OmniTokens.CODEBOOKS) flat[k * per + j] = pending[j][k]
            codes.putInts(flat)
            start.putInts(intArrayOf(done * 6))
            for (n in stateNames) {
                sig.bind(n, sets[cur].getValue(n))
                sig.bind(n + "_out", sets[1 - cur].getValue(n), true)
            }
            sig.run()
            cur = 1 - cur
            done += valid
            pending.clear()
            val out = stream.push(frames.get(), valid * 6, finalize)
            ms += (System.nanoTime() - t0) / 1e6
            return out
        }
    }

    private val codec = Codec()

    // --- the generation loop ----------------------------------------------------------------------------------

    private class Turn(val call: Long = System.nanoTime()) {
        var rc = 0
        var promptTokens = 0; var reused = 0; var promptMs = 0.0
        var firstTextMs = 0.0; var firstAudioMs = 0.0
        var text = 0; var frames = 0; var samples = 0
        var genMs = 0.0; var textMs = 0.0; var frameMs = 0.0; var depthMs = 0.0
        fun since(t: Long) = (System.nanoTime() - t) / 1e6
        fun stats(codecMs: Double) = SpeechStats(rc, promptTokens, reused, promptMs, firstTextMs, firstAudioMs, text, frames,
            genMs, textMs, frameMs, depthMs, codecMs, samples, OmniTokens.SAMPLE_RATE)
    }

    /**
     * From the prompt's last hidden state [h0], at most [maxSteps] slots of [plan]: text tokens from [pick] (null:
     * speech only) streamed to [onText], frames decoded to audio for [onAudio]. Sets [turn]'s rc: 0 done, -6 stopped,
     * -2 out of context.
     */
    private fun generate(plan: SlotPlan, h0: FloatArray, maxSteps: Int, pick: ((FloatArray) -> Int)?, onText: (String) -> Boolean,
                         onAudio: (FloatArray) -> Boolean, turn: Turn, gen: Int) {
        val t0 = System.nanoTime()
        val rng = OmniRng(42)
        val codeWindow = CodeWindow()
        val utf8 = Utf8Stream()
        codec.reset()
        codec.ms = 0.0
        var h = h0
        fun send(pcm: FloatArray): Boolean {
            if (pcm.isEmpty()) return true
            if (turn.samples == 0) turn.firstAudioMs = turn.since(turn.call)
            turn.samples += pcm.size
            return onAudio(pcm)
        }
        var speechOpen = false
        loop@ for (step in 0 until maxSteps) {
            if (cancelGen.get() != gen) { turn.rc = -6; break }
            if (lm.pos >= lm.cacheLength) { turn.rc = -2; break }
            val next: FloatArray
            if (plan.nextIsSpeech) {
                val td = System.nanoTime()
                var codes = sampleFrame(h, codeWindow, rng)
                val eos = codes.any { it == OmniTokens.AUDIO_EOS_CODE }
                turn.depthMs += turn.since(td)
                if (eos) {
                    codes = IntArray(OmniTokens.CODEBOOKS) { OmniTokens.AUDIO_EOS_CODE }
                    speechOpen = false
                    if (!send(codec.finish())) { turn.rc = -6; break }
                } else {
                    codeWindow.add(codes)
                    turn.frames++
                    speechOpen = true
                    if (!send(codec.push(codes))) { turn.rc = -6; break }
                }
                if (plan.onFrame(eos) || step == maxSteps - 1) break
                val te = System.nanoTime()
                next = embedFrame(codes)
                turn.depthMs += turn.since(te)
            } else {
                val ts = System.nanoTime()
                val p = pick ?: run { turn.rc = -3; return }
                val token = p(lm.logits())
                turn.text++
                val piece = if (tok.decode(intArrayOf(token), true).isEmpty()) "" else utf8.feed(tok.bytesOf(token))
                turn.textMs += turn.since(ts)
                if (piece.isNotEmpty()) {
                    if (turn.firstTextMs == 0.0) turn.firstTextMs = turn.since(turn.call)
                    if (!onText(piece)) { turn.rc = -6; break }
                }
                if (plan.onText(token) || step == maxSteps - 1) break
                next = embed(token)
            }
            val ts = System.nanoTime()
            val speechNext = plan.nextIsSpeech
            h = lm.step(next, logits = !speechNext)
            if (speechNext) turn.frameMs += turn.since(ts) else turn.textMs += turn.since(ts)
        }
        if (turn.rc == 0) {
            utf8.flush().takeIf { it.isNotEmpty() }?.let { onText(it) }
            if (speechOpen && !send(codec.finish())) turn.rc = -6
        }
        turn.genMs = turn.since(t0)
    }

    /** Where a call that keeps no state reads its prompt: after the conversation kept for Talk, or from 0 if it does not fit. */
    private fun scratchStart(need: Int): Int {
        val end = snaps.maxOfOrNull { it.tokens.size } ?: 0
        if (end + need <= lm.cacheLength) return end
        snaps.clear()
        return 0
    }

    private fun ids(text: String) = OmniPrompt.withBos(tok.encode(text))

    // --- the calls --------------------------------------------------------------------------------------------

    /** Speech to text. [stop] is asked between tokens (an early hearing that turned out not to be needed). */
    suspend fun transcribe(pcm16k: FloatArray, systemPrompt: String, maxTokens: Int = 512, stop: () -> Boolean = { false }): String = onThread {
        val t0 = System.nanoTime()
        val rows = audioRows(pcm16k)
        val (ids, at) = OmniPrompt.withAudio(tok.encode(OmniPrompt.asrText(systemPrompt)), rows.size)
        val start = scratchStart(ids.size + maxTokens + 1)
        if (start + ids.size + 1 >= lm.cacheLength) throw LlmException("The recording is too long to transcribe")
        lm.begin(start)
        var h = lm.prefill(ids.size, true) { i -> if (i >= at && i < at + rows.size) rows[i - at] else embed(ids[i]) }
        val prefillMs = (System.nanoTime() - t0) / 1e6
        val out = ArrayList<Int>()
        val td = System.nanoTime()
        var first = 0.0
        while (out.size < maxTokens && lm.pos < lm.cacheLength && !stop()) {
            val t = OmniSampler.sampleText(lm.logits(), OmniTokens.TEXT_ONLY_SUPPRESS, 0f, 1, 1f, 0.0)
            if (first == 0.0) first = (System.nanoTime() - t0) / 1e6
            if (t == OmniTokens.IM_END || t == OmniTokens.TEXT_END) break
            out += t
            if (out.size == maxTokens) break
            h = lm.step(embed(t), logits = true)
        }
        lastStats = GenStats(prefillMs, ids.size, (System.nanoTime() - td) / 1e6, out.size, 0, first)
        tok.decode(out.toIntArray(), skipSpecial = true).replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()
    }

    /**
     * Speaks [prompt] (Omni's TTS template, SpeechFormat.ttsPrompt), sending audio to [onAudio] as it is made. Returns
     * false if stopped ([cancel], or [onAudio] returning false).
     */
    suspend fun speak(prompt: String, maxFrames: Int, onAudio: (FloatArray) -> Boolean): Boolean = onThread {
        val gen = cancelGen.get()
        val turn = Turn()
        val ids = ids(prompt)
        if (ids.last() != OmniTokens.AUDIO_START) throw LlmException("Not a speech prompt")
        if (ids.size + maxFrames + 8 >= lm.cacheLength) throw LlmException("Too much to say at once")
        lm.begin(scratchStart(ids.size + maxFrames + 8))
        val h = lm.prefill(ids.size, true) { embed(ids[it]) }
        turn.promptTokens = ids.size
        turn.promptMs = turn.since(turn.call)
        generate(SlotPlan(SlotPlan.Mode.AUDIO, continuing = false), h, maxFrames, null, { true }, onAudio, turn, gen)
        finishTurn(turn)
    }

    /** One spoken answer for Talk: its text pieces and its audio, interleaved, as Omni makes them; [spoken] false: text only. */
    suspend fun talkReply(history: String, prefix: String, maxSteps: Int, temp: Float, spoken: Boolean,
                          onText: (String) -> Boolean, onAudio: (FloatArray) -> Boolean): Boolean = onThread {
        val gen = cancelGen.get()
        val turn = Turn()
        val hist = ids(history)
        val pre = tok.encode(prefix).let { if (spoken) it + OmniTokens.MIXED_START else it }
        if (hist.size + pre.size + maxSteps + 64 >= lm.cacheLength) throw LlmException("The conversation is too long; start Talk again")
        readHistory(hist, turn)
        val h = lm.prefill(pre.size, true) { embed(pre[it]) }
        turn.promptTokens += pre.size
        turn.promptMs = turn.since(turn.call)
        val rng = OmniRng(42)
        val suppress = if (spoken) OmniTokens.NEVER_SAMPLED else OmniTokens.TEXT_ONLY_SUPPRESS
        val plan = SlotPlan(if (spoken) SlotPlan.Mode.INTERLEAVED else SlotPlan.Mode.TEXT, continuing = true)
        generate(plan, h, maxSteps, { l -> OmniSampler.sampleText(l, suppress, temp, 50, 0.9f, rng.nextDouble()) }, onText, onAudio, turn, gen)
        finishTurn(turn)
    }

    /** Reads [history] (the start of Talk's next turn) in ahead and keeps it, so the next turn starts sooner. Returns the tokens read. */
    suspend fun talkPrewarm(history: String): Int = onThread {
        val tokens = ids(history)
        if (snaps.any { it.tokens.contentEquals(tokens) }) return@onThread 0
        val turn = Turn()
        readHistory(tokens, turn)
        turn.promptTokens
    }

    private fun finishTurn(turn: Turn): Boolean {
        speechStats = turn.stats(codec.ms)
        lastSpeech = speechStats
        if (turn.rc == -2) throw LlmException("The conversation is too long; start Talk again")
        if (turn.rc == -3) throw LlmException("Could not answer (-3)")
        return turn.rc == 0
    }

    /**
     * [tokens] read in from position 0, from the longest kept snapshot they start with; the state after them is kept
     * beside that snapshot (talk.cpp's read_history). Leaves the decoder at their end.
     */
    private fun readHistory(tokens: IntArray, turn: Turn) {
        val base = snaps.filter { it.tokens.size <= tokens.size && tokens.startsWith(it.tokens) }.maxByOrNull { it.tokens.size }
        if (base != null) lm.restore(base) else lm.begin(0)
        val from = base?.tokens?.size ?: 0
        turn.reused = from
        turn.promptTokens = tokens.size - from
        if (tokens.size > from) lm.prefill(tokens.size - from, false) { embed(tokens[from + it]) }
        val now = lm.snapshot(tokens)
        snaps.clear()
        if (base != null && base.tokens.size < tokens.size) snaps += base
        snaps += now
    }

    private fun IntArray.startsWith(p: IntArray): Boolean {
        if (p.size > size) return false
        for (i in p.indices) if (this[i] != p[i]) return false
        return true
    }

    suspend fun speechStats(): SpeechStats = onThread { speechStats }

    /** Numbers from the last transcribe call. */
    fun stats(): GenStats = lastStats

    /** Stops speech being made, between frames (the call in progress returns false). */
    fun cancel() { cancelGen.incrementAndGet() }

    suspend fun close() = mutex.withLock {
        withContext(dispatcher) {
            if (!closed) {
                closed = true
                lm.close()
                for (g in listOf(embedder, encoder, depth, frameEmbed, codecGraph)) runCatching { g.close() }
                env.close()
            }
        }
    }

    /**
     * The decoder section (signatures prefill_1024 ... prefill_1 and decode; inputs embeddings, input_pos, mask and the
     * kv_cache_* state; outputs hidden, decode also logits), driven the way LiteRT-LM drives it: every state tensor is
     * one buffer, the input and the output of every call; a prompt goes in exact-cover chunks of the prefill
     * signatures, largest first, and its last row through decode (whose logits a text slot reads); a speech slot's step
     * runs prefill_1, which skips the text head. The mask opens row i on [seqStart, pos + i].
     */
    private class Lm(private val g: RtGraph) : AutoCloseable {
        private class Call(val sig: RtGraph.Sig, val emb: RtBuffer, val pos: RtBuffer, val mask: RtBuffer, val hidden: RtBuffer, val rows: Int)

        private val decodeSig = g.Sig("decode")
        val cacheLength = decodeSig.shape("mask").last()
        private val stateNames = decodeSig.inputs.filter { it.startsWith("kv_cache_") }
        private val state = stateNames.associateWith { decodeSig.buffer(it) }
        private val convNames = stateNames.filter { it.startsWith("kv_cache_c_") }
        val prefillSizes = g.signatures.filter { it.startsWith("prefill_") }.map { it.removePrefix("prefill_").toInt() }.sortedDescending()
        private val calls = HashMap<String, Call>()
        private val logitsBuf = decodeSig.buffer("logits", true).also { decodeSig.bind("logits", it, true) }
        private val maskFill = -0.7f * Float.MAX_VALUE
        var pos = 0
            private set
        private var seqStart = 0
        private var logitsValid = false

        init {
            check(prefillSizes.lastOrNull() == 1) { "decoder: prefill signatures $prefillSizes" }
        }

        private fun call(name: String): Call = calls.getOrPut(name) {
            val s = if (name == "decode") decodeSig else g.Sig(name)
            for (n in stateNames) { s.bind(n, state.getValue(n)); s.bind(n, state.getValue(n), true) }
            val c = Call(s, s.buffer("embeddings"), s.buffer("input_pos"), s.buffer("mask"), s.buffer("hidden", true), s.shape("embeddings")[1])
            s.bind("embeddings", c.emb); s.bind("input_pos", c.pos); s.bind("mask", c.mask); s.bind("hidden", c.hidden, true)
            c
        }

        /** A new sequence at [start]: the conv state zeroed (the KV entries before [start] stay, masked out). */
        fun begin(start: Int) {
            for (n in convNames) state.getValue(n).zero()
            pos = start
            seqStart = start
            logitsValid = false
        }

        class Snap(val tokens: IntArray, val conv: Map<String, FloatArray>)

        /** The conversation so far: its tokens and conv state (the KV entries are kept in place, by position). */
        fun snapshot(tokens: IntArray): Snap {
            check(seqStart == 0 && pos == tokens.size)
            return Snap(tokens, convNames.associateWith { state.getValue(it).get() })
        }

        fun restore(s: Snap) {
            for ((n, v) in s.conv) state.getValue(n).put(v)
            pos = s.tokens.size
            seqStart = 0
            logitsValid = false
        }

        /**
         * [n] rows from [row] at [pos]; [decodeLast]: the last through decode, returning its hidden state (its logits are
         * then [logits]); otherwise all through the prefill signatures, returning an empty array.
         */
        fun prefill(n: Int, decodeLast: Boolean, row: (Int) -> FloatArray): FloatArray {
            check(pos + n <= cacheLength) { "context full" }
            val chunked = if (decodeLast) n - 1 else n
            var i = 0
            while (i < chunked) {
                val size = prefillSizes.first { it <= chunked - i }
                val c = call("prefill_$size")
                for (r in 0 until size) c.emb.put(row(i + r), at = r * OmniTokens.HIDDEN)
                run(c, size)
                i += size
            }
            logitsValid = false
            return if (decodeLast) step(row(n - 1), logits = true) else FloatArray(0)
        }

        private val maskRow = FloatArray(cacheLength)

        private fun run(c: Call, rows: Int) {
            c.pos.putInts(IntArray(rows) { pos + it })
            for (r in 0 until rows) {
                java.util.Arrays.fill(maskRow, maskFill)
                java.util.Arrays.fill(maskRow, seqStart, pos + r + 1, 0f)
                c.mask.put(maskRow, at = r * cacheLength)
            }
            c.sig.run()
            pos += rows
        }

        /** One slot at [pos]; returns its hidden state. [logits] false (the next slot is speech): prefill_1, no text head. */
        fun step(vec: FloatArray, logits: Boolean): FloatArray {
            check(pos < cacheLength) { "context full" }
            val c = call(if (logits) "decode" else "prefill_1")
            c.emb.put(vec)
            run(c, 1)
            logitsValid = logits
            return c.hidden.get(FloatArray(OmniTokens.HIDDEN))
        }

        /** The text logits of the last step (a copy, the sampler changes it). */
        fun logits(): FloatArray {
            check(logitsValid) { "the last step had no text head" }
            return logitsBuf.get()
        }

        override fun close() {
            for (c in calls.values) for (b in listOf(c.emb, c.pos, c.mask, c.hidden)) b.close()
            for (b in state.values) b.close()
            logitsBuf.close()
            g.close()
        }
    }
}
