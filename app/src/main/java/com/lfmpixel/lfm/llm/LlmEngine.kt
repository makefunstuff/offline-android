package com.lfmpixel.lfm.llm

import java.io.File

/** The phone's cores, read from sysfs: LiteRT's CPU threads go only on the ones that are not little. */
object Cores {
    /**
     * Cores that are not little (Tensor G5: 6 of 8). Little cores are the slowest group only, and only when well under
     * half the big core's capacity (G5: about a quarter), or, without capacity numbers, a clearly lower top clock.
     */
    val fast: Int by lazy {
        val dirs = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
        fun read(f: File) = runCatching { f.readText().trim().toLong() }.getOrDefault(0L)
        val cap = dirs.map { read(File(it, "cpu_capacity")) }
        val khz = dirs.map { read(File(it, "cpufreq/cpuinfo_max_freq")) }
        val maxCap = cap.maxOrNull() ?: 0; val minCap = cap.filter { it > 0 }.minOrNull() ?: 0
        val maxKhz = khz.maxOrNull() ?: 0; val minKhz = khz.filter { it > 0 }.minOrNull() ?: 0
        val little = dirs.indices.count { i ->
            if (maxCap > 0) cap[i] == minCap && minCap * 2 < maxCap else maxKhz > 0 && khz[i] == minKhz && minKhz * 10 < maxKhz * 7
        }
        (dirs.size - little).coerceAtLeast(1).takeIf { dirs.isNotEmpty() } ?: Runtime.getRuntime().availableProcessors()
    }
}

/** Thread counts for one LiteRT CPU model: [gen] while writing (bound by memory), [batch] while reading a prompt in. */
data class Threads(val gen: Int, val batch: Int) {
    companion object {
        /**
         * [setting] 0 is Auto: one thread fewer than the cores that are not little while writing, so the screen and
         * the system keep a core. [cap] (the heat's, 0 for none) holds every count down while the phone is hot.
         */
        fun pick(setting: Int, fastCores: Int, cap: Int = 0): Threads {
            val fast = fastCores.coerceAtLeast(1)
            val t = if (setting > 0) Threads(setting, maxOf(setting, fast)) else Threads((fast - 1).coerceIn(2, 6).coerceAtMost(fast), fast)
            return if (cap > 0) Threads(minOf(t.gen, cap), minOf(t.batch, cap)) else t
        }
    }
}

/** One benchmark run, from [TextSession.bench]. */
data class BenchRun(val promptMs: Double, val promptTokens: Int, val firstTokenMs: Double, val genMs: Double, val genTokens: Int,
                    val samplerMs: Double, val threads: Int, val batchThreads: Int) {
    val promptTps get() = if (promptMs > 0) promptTokens * 1000.0 / promptMs else 0.0
    val genTps get() = if (genMs > 0) genTokens * 1000.0 / genMs else 0.0
}

data class GenStats(val prefillMs: Double, val prefillTokens: Int, val decodeMs: Double, val decodeTokens: Int, val reusedTokens: Int,
                    val firstTokenMs: Double = 0.0) {
    val decodeTps get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else 0.0
    val prefillTps get() = if (prefillMs > 0) prefillTokens * 1000.0 / prefillMs else 0.0
}

/** A picture as the app prepares it: packed RGB, 3 bytes a pixel, rows top to bottom. */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray)

open class LlmException(message: String) : Exception(message)

/** One speak or Talk turn from Omni, stage by stage (talk::result in talk.h). Times in ms. */
data class SpeechStats(
    val rc: Int = 0, val promptTokens: Int = 0, val reusedTokens: Int = 0, val promptMs: Double = 0.0,
    val firstTextMs: Double = 0.0, val firstAudioMs: Double = 0.0, val textTokens: Int = 0, val frames: Int = 0,
    val genMs: Double = 0.0, val textMs: Double = 0.0, val frameMs: Double = 0.0, val depthMs: Double = 0.0,
    val codecMs: Double = 0.0, val samples: Int = 0, val sampleRate: Int = 24_000,
) {
    val audioMs get() = if (sampleRate > 0) samples * 1000.0 / sampleRate else 0.0
    /** Generation time per second of speech: over 1, speech is made slower than it plays. */
    val rtf get() = if (audioMs > 0) genMs / audioMs else 0.0
    /** Backbone steps a second (text tokens and audio frames alike). */
    val stepsPerSecond get() = if (textMs + frameMs > 0) (textTokens + frames) * 1000.0 / (textMs + frameMs) else 0.0
    val textTps get() = if (textMs > 0) textTokens * 1000.0 / textMs else 0.0

    companion object {
        fun of(v: DoubleArray) = if (v.size < 15) SpeechStats() else SpeechStats(v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3],
            v[4], v[5], v[6].toInt(), v[7].toInt(), v[8], v[9], v[10], v[11], v[12], v[13].toInt(), v[14].toInt())
    }
}
