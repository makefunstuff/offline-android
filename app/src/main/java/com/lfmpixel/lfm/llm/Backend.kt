package com.lfmpixel.lfm.llm

import java.util.Locale

/**
 * Where a model runs (docs/model-pool.md): LiteRT-LM on the GPU (OpenCL) or the CPU (XNNPACK). llama.cpp and its
 * GGUF files left the phone in LFMP-8; Omni's voice graphs and d1 run on LiteRT's CPU beside these.
 */
enum class Backend(val label: String, val short: String, val liteRt: Boolean = true) {
    LiteRtGpu("LiteRT-LM on the GPU", "GPU"),
    LiteRtCpu("LiteRT-LM on the CPU", "CPU"),
}

/** The owner's pick in Settings → Models. Auto takes the GPU where the phone has one, unless the benchmark says otherwise. */
enum class BackendChoice(val label: String, val backend: Backend?) {
    Auto("Auto", null), LiteRtGpu("GPU", Backend.LiteRtGpu), LiteRtCpu("CPU", Backend.LiteRtCpu),
}

/**
 * One model's measured speed on one backend. [ok] is false when it answered the check questions wrong (a driver
 * that computes garbage) or would not load: Auto never picks it then. [restartSeconds] is what a new conversation
 * costs before it starts: LiteRT-LM starts a new engine for one (see LiteRtSession), llama.cpp just clears its cache.
 */
data class BackendSpeed(val genTps: Double, val promptTps: Double, val ok: Boolean, val restartSeconds: Double = 0.0) {
    /**
     * Seconds for a typical chat turn: [TURN_PROMPT] new prompt tokens read in, [TURN_GEN] written, and a restart in
     * [FRESH_SHARE] of turns (a new chat, a trimmed history, a document, an answer that hit its length). Lower is better.
     */
    val turnSeconds get() = turnSeconds(FRESH_SHARE)

    /** The same with a restart in [freshShare] of turns: with thinking on, most answers use up the thinking budget. */
    fun turnSeconds(freshShare: Double) = if (!ok || genTps <= 0 || promptTps <= 0) Double.MAX_VALUE
        else TURN_PROMPT / promptTps + TURN_GEN / genTps + freshShare * restartSeconds

    companion object {
        const val TURN_PROMPT = 400.0
        const val TURN_GEN = 150.0
        const val FRESH_SHARE = 0.5
        /** With thinking on, an answer that uses up the thinking budget is continued, and on LiteRT-LM that restarts. */
        const val FRESH_SHARE_THINKING = 1.0
    }
}

object Backends {
    /**
     * The backend for one model. [available] are the backends this phone allows (the CPU always). A manual pick holds
     * while it is available, else the CPU. Auto takes the GPU when it is there and has not failed its check or its
     * load: it is what LiteRT is for, quicker and lighter on the battery than the CPU (docs/model-pool.md). Only a
     * measured CPU at least [MARGIN] quicker than a measured GPU keeps a model on the CPU.
     */
    fun pick(choice: BackendChoice, available: Set<Backend>, speeds: Map<Backend, BackendSpeed>,
             freshShare: Double = BackendSpeed.FRESH_SHARE): Backend {
        choice.backend?.let { return if (it in available) it else Backend.LiteRtCpu }
        if (Backend.LiteRtGpu !in available) return Backend.LiteRtCpu
        val gpu = speeds[Backend.LiteRtGpu]
        if (gpu != null && !gpu.ok) return Backend.LiteRtCpu
        val cpu = speeds[Backend.LiteRtCpu]?.takeIf { it.ok } ?: return Backend.LiteRtGpu
        return if (gpu != null && cpu.turnSeconds(freshShare) * MARGIN <= gpu.turnSeconds(freshShare)) Backend.LiteRtCpu else Backend.LiteRtGpu
    }

    /** The CPU must be this much quicker for Auto to keep a model off the GPU. */
    const val MARGIN = 1.1

    /** Short questions with one right answer, asked greedily with thinking off: a broken backend gets them wrong. */
    val checks = listOf(
        "What is the capital of Japan? Answer with one word." to "tokyo",
        "What is 17 + 25? Answer with the number only." to "42",
        "How many days are in a week? Answer with the number only." to "7",
    )

    /** Whether a backend's answers to [checks] (in order) are good enough to trust it: at least two of three right. */
    fun passes(answers: List<String>): Boolean =
        checks.zip(answers).count { (q, a) -> Regex("\\b${q.second}\\b").containsMatchIn(a.lowercase(Locale.ROOT)) } >= 2

    // Stored in Settings as "file|LiteRtGpu=12.30,140.50,1,3.20;file|LiteRtCpu=…": per model file. Entries for the old
    // llama.cpp backends (Cpu, Vulkan) no longer parse and drop out.
    fun encode(map: Map<String, Map<Backend, BackendSpeed>>): String = map.flatMap { (file, speeds) ->
        speeds.map { (b, s) -> "$file|${b.name}=${"%.2f".format(Locale.ENGLISH, s.genTps)},${"%.2f".format(Locale.ENGLISH, s.promptTps)},${if (s.ok) 1 else 0}," +
            "%.2f".format(Locale.ENGLISH, s.restartSeconds) }
    }.joinToString(";")

    fun decode(text: String): Map<String, Map<Backend, BackendSpeed>> = text.split(';').mapNotNull { entry ->
        val (key, value) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val file = key.substringBeforeLast('|', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val backend = runCatching { Backend.valueOf(key.substringAfterLast('|')) }.getOrNull() ?: return@mapNotNull null
        val v = value.split(',').takeIf { it.size == 3 || it.size == 4 } ?: return@mapNotNull null
        val gen = v[0].toDoubleOrNull() ?: return@mapNotNull null
        val prompt = v[1].toDoubleOrNull() ?: return@mapNotNull null
        Triple(file, backend, BackendSpeed(gen, prompt, v[2] == "1", v.getOrNull(3)?.toDoubleOrNull() ?: 0.0))
    }.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, l) -> l.toMap() }
}
