package com.lfmpixel.lfm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.lfmpixel.lfm.llm.BpeTokenizer
import com.lfmpixel.lfm.voice.OmniVoice
import com.lfmpixel.lfm.voice.SpeechFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb shell am broadcast -a com.lfmpixel.lfm.DEBUG_OMNI [--ei threads 4] [--ez reload true]: OmniVoice (LiteRT) on the
 * files in files/omni/ (the .litertlm, OmniVoice.SPEECH_FILES, tokenizer.json, A1.wav): ASR of A1, TTS of the S1 and S2
 * fixtures, Talk's interleaved answer to S3 and a second turn on top of it, each TTS wav transcribed back with
 * OmniVoice's own ASR. Writes files/omni/out/{*.wav, omni_bench.json}; logs under lfm-omni. The run outlives the
 * broadcast: keep the app from being frozen meanwhile (in front, or `settings put global cached_apps_freezer disabled`).
 */
class DebugOmniReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dir = File(context.filesDir, "omni")
        val out = File(dir, "out").apply { mkdirs() }
        val threads = intent.getIntExtra("threads", 4)
        val reload = intent.getBooleanExtra("reload", true)
        fun log(s: String) = Log.i("lfm-omni", s)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val res = JSONObject()
            try {
                val tok = BpeTokenizer.load(File(dir, "tokenizer.json"))
                var t0 = System.nanoTime()
                var v = OmniVoice.open(File(dir, OmniVoice.LITERTLM), dir, tok, threads)
                res.put("load_s", (System.nanoTime() - t0) / 1e9).put("load_info", v.loadInfo).put("rss_after_load_mb", rss("VmRSS"))
                log("loaded in ${res.getDouble("load_s")} s: ${v.loadInfo}")
                if (reload) {
                    v.close()
                    t0 = System.nanoTime()
                    v = OmniVoice.open(File(dir, OmniVoice.LITERTLM), dir, tok, threads)
                    res.put("reload_s", (System.nanoTime() - t0) / 1e9).put("rss_after_reload_mb", rss("VmRSS"))
                    log("reloaded in ${res.getDouble("reload_s")} s")
                }
                // ASR
                val a1 = readWav16k(File(dir, "A1.wav"))
                t0 = System.nanoTime()
                val text = v.transcribe(a1, "Perform ASR in English.")
                res.put("asr", JSONObject().put("text", text).put("s", (System.nanoTime() - t0) / 1e9)
                    .put("audio_s", a1.size / 16000.0).put("stats", v.stats().toString()).put("decode_tps", v.stats().decodeTps))
                log("ASR ${res.getJSONObject("asr")}")
                // TTS
                val tts = JSONArray()
                for ((id, voice, sentence) in listOf(
                    Triple("S1", "female", "The quick brown fox jumps over the lazy dog."),
                    Triple("S2", "male", "The library opens at nine in the morning and closes at six in the evening."),
                )) {
                    val prompt = "<|im_start|>system\nPerform TTS in American English using a $voice voice.<|im_end|>\n" +
                        "<|im_start|>user\n$sentence<|im_end|>\n<|im_start|>assistant\n<think></think><|audio_start|>"
                    val pcm = ArrayList<FloatArray>()
                    t0 = System.nanoTime()
                    val done = v.speak(prompt, 256) { pcm += it; true }
                    val s = (System.nanoTime() - t0) / 1e9
                    val all = join(pcm)
                    writeWav(File(out, "${id}.wav"), all, 24000)
                    val st = v.lastSpeech
                    val back = v.transcribe(resample24to16(all), "Perform ASR in English.")
                    tts.put(JSONObject().put("id", id).put("done", done).put("s", s).put("audio_s", all.size / 24000.0)
                        .put("frames", st.frames).put("rtf", st.rtf).put("first_audio_ms", st.firstAudioMs).put("prompt_ms", st.promptMs)
                        .put("depth_ms", st.depthMs).put("frame_ms", st.frameMs).put("codec_ms", st.codecMs)
                        .put("reference", sentence).put("asr_back", back))
                    log("TTS $id ${tts.getJSONObject(tts.length() - 1)}")
                }
                res.put("tts", tts)
                // Talk: S3, then a second turn on top (the history read in once)
                val system = "Respond with interleaved text and audio."
                val talk = JSONArray()
                val turns = ArrayList<Pair<String, String>>()
                val prewarm = v.talkPrewarm(SpeechFormat.talkPrefix(system, emptyList()))
                res.put("prewarm_tokens", prewarm)
                for (q in listOf("What is the capital of France?", "And what is it famous for?")) {
                    val history = SpeechFormat.talkHistory(system, turns, q)
                    val sb = StringBuilder()
                    val pcm = ArrayList<FloatArray>()
                    t0 = System.nanoTime()
                    val done = v.talkReply(history, SpeechFormat.REPLY_PREFIX, 1200, 0f, true, { sb.append(it); true }, { pcm += it; true })
                    val s = (System.nanoTime() - t0) / 1e9
                    val all = join(pcm)
                    val n = talk.length() + 1
                    writeWav(File(out, "S3_turn$n.wav"), all, 24000)
                    val st = v.lastSpeech
                    val back = v.transcribe(resample24to16(all), "Perform ASR in English.")
                    talk.put(JSONObject().put("q", q).put("done", done).put("text", sb.toString()).put("s", s)
                        .put("audio_s", all.size / 24000.0).put("frames", st.frames).put("text_tokens", st.textTokens)
                        .put("prompt_tokens", st.promptTokens).put("reused_tokens", st.reusedTokens).put("prompt_ms", st.promptMs)
                        .put("first_text_ms", st.firstTextMs).put("first_audio_ms", st.firstAudioMs).put("rtf", st.rtf)
                        .put("steps_per_s", st.stepsPerSecond).put("text_tps", st.textTps).put("asr_back", back))
                    log("Talk ${talk.getJSONObject(talk.length() - 1)}")
                    turns += q to sb.toString()
                }
                // text only
                val sb = StringBuilder()
                t0 = System.nanoTime()
                v.talkReply(SpeechFormat.talkHistory(system, emptyList(), "Name three colours of the rainbow."), SpeechFormat.REPLY_PREFIX,
                    200, 0f, false, { sb.append(it); true }, { true })
                val st = v.lastSpeech
                res.put("text_only", JSONObject().put("text", sb.toString()).put("s", (System.nanoTime() - t0) / 1e9)
                    .put("text_tps", st.textTps).put("tokens", st.textTokens).put("first_text_ms", st.firstTextMs))
                res.put("talk", talk)
                v.close()
            } catch (e: Throwable) {
                Log.e("lfm-omni", "failed", e)
                res.put("error", e.toString())
            } finally {
                res.put("vm_hwm_mb", rss("VmHWM")).put("vm_rss_mb", rss("VmRSS"))
                File(out, "omni_bench.json").writeText(res.toString(1))
                log("done: ${res.toString().take(3000)}")
            }
        }
    }

    private fun rss(key: String): Long =
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.filter { it.isDigit() }.toLong() / 1024

    private fun join(parts: List<FloatArray>): FloatArray {
        val all = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
        return all
    }

    /** 24 kHz to 16 kHz: a 3-tap average against aliasing, then linear interpolation (enough for the ASR check). */
    private fun resample24to16(x: FloatArray): FloatArray {
        val s = FloatArray(x.size) { i -> (x[maxOf(0, i - 1)] + x[i] + x[minOf(x.size - 1, i + 1)]) / 3f }
        return FloatArray(x.size * 2 / 3) { j ->
            val t = j * 1.5
            val i = t.toInt()
            val f = (t - i).toFloat()
            s[i] * (1 - f) + s[minOf(i + 1, s.size - 1)] * f
        }
    }

    private fun readWav16k(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = String(b.array(), p, 4, Charsets.US_ASCII)
            val len = b.getInt(p + 4)
            if (id == "data") return FloatArray(len / 2) { b.getShort(p + 8 + it * 2) / 32768f }
            p += 8 + len + (len and 1)
        }
        error("no data chunk in ${f.name}")
    }

    private fun writeWav(f: File, pcm: FloatArray, rate: Int) {
        val b = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size * 2)
        for (v in pcm) b.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        RandomAccessFile(f, "rw").use { it.setLength(0); it.write(b.array()) }
    }
}
