package com.lfmpixel.lfm.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * When speech plays: the jitter buffer's rules, apart from the speaker so they are tested and replayed in the voice
 * benchmark. Audio comes from the model at its own pace; the player asks [needMs] how much must be waiting before it
 * starts (or carries on after running low), and [quiet] whether a slice is a pause in the voice.
 *
 * - Faster than real time: start once [BASE_MS] is waiting.
 * - Slower (real-time factor r over 1): of the speech still to come, a share of 1 - 1/r is waited for up front, so the
 *   rest plays without a gap. How much is to come is a guess from the answer's text so far ([expect]), and at least
 *   the usual length of an answer, learnt as it goes.
 * - Running low while slower: stop at the next pause in the voice (between words or phrases), not mid-word.
 * - Ran dry anyway: that is an underrun; counted, logged, and r is raised so the next wait is longer.
 */
class Jitter(val rate: Int = 24_000, private val shared: Prior = prior) {
    /** Generation time per second of speech; learned across stretches, and shared with the app's other players. */
    var rtf = shared.rtf
        private set
    /** How long a stretch usually is: the least still to come while its text is not all in. */
    private var usualMs = shared.usualMs
    private var arrivedSamples = 0L
    private var firstArrivalMs = -1.0
    private var firstChunk = 0
    private var expectedMs = -1.0
    private var loud = 0.03

    /** What this stretch did, for the log and the benchmark. */
    var underruns = 0
        private set
    var pauses = 0
        private set
    var waitedMs = 0.0
        private set

    fun begin() {
        // The latest of this phone's pace, from any player or from the last run (Settings).
        rtf = shared.rtf; usualMs = shared.usualMs
        arrivedSamples = 0; firstArrivalMs = -1.0; firstChunk = 0; expectedMs = -1.0
        underruns = 0; pauses = 0; waitedMs = 0.0
    }

    /** [n] samples came from the model at [nowMs] (any clock, as long as it is the same one). */
    fun arrived(n: Int, nowMs: Double) {
        if (firstArrivalMs < 0) { firstArrivalMs = nowMs; firstChunk = n }
        arrivedSamples += n
        // Measured from the second chunk on, once a second of speech has come: the first comes after the prompt.
        val made = (arrivedSamples - firstChunk) * 1000.0 / rate
        if (made >= 1000) setRtf(0.5 * rtf + 0.5 * ((nowMs - firstArrivalMs) / made).coerceIn(0.05, 10.0))
    }

    private fun setRtf(v: Double) { rtf = v; shared.rtf = v }

    /** The stretch was [totalMs] long in all: learnt as the usual length. */
    fun ended(totalMs: Double) {
        if (totalMs < 500) return
        usualMs = 0.7 * usualMs + 0.3 * totalMs
        shared.usualMs = usualMs
    }

    /** The whole stretch should last about [totalMs] (from its text); updated as more text comes. */
    fun expect(totalMs: Double) { expectedMs = totalMs }

    /** Milliseconds of audio that should be waiting before playing on, with [playedMs] played so far. */
    fun needMs(playedMs: Double): Double {
        if (rtf <= 0.95) return BASE_MS
        // The text runs only a little ahead of the voice, so until it is all in, assume at least the usual length.
        val remaining = max(max(expectedMs, usualMs) - playedMs, 0.0)
        return (remaining * (1 - 1 / rtf) * 1.1 + BASE_MS).coerceIn(BASE_MS, MAX_WAIT_MS)
    }

    /** True if [pcm] from [from] for [n] samples is quiet next to the voice so far: a safe place to wait. */
    fun quiet(pcm: FloatArray, from: Int, n: Int): Boolean {
        var sum = 0.0
        for (k in from until from + n) sum += pcm[k] * pcm[k]
        val rms = sqrt(sum / max(n, 1))
        if (rms > loud) loud = 0.9 * loud + 0.1 * rms else if (rms > loud * 0.5) loud = 0.99 * loud + 0.01 * rms
        return rms < max(loud * 0.25, 0.002)
    }

    /** Running low ([bufferedMs] waiting) before a [quietSlice]: wait here rather than run dry mid-word. */
    fun pauseHere(bufferedMs: Double, quietSlice: Boolean, ended: Boolean) =
        !ended && quietSlice && rtf > 1.0 && bufferedMs < LOW_MS

    fun paused(waitMs: Double) { pauses++; waitedMs += waitMs }

    /** Ran dry mid-speech. */
    fun underrun(waitMs: Double) {
        underruns++
        waitedMs += waitMs
        setRtf(max(rtf, 1.05) * 1.1)
    }

    /** What every new [Jitter] starts from: this phone's speed and answer length, as last seen. */
    class Prior(@Volatile var rtf: Double = 1.0, @Volatile var usualMs: Double = 6000.0)

    companion object {
        const val BASE_MS = 240.0
        const val LOW_MS = 600.0
        const val MAX_WAIT_MS = 8000.0
        const val SLICE_MS = 40
        val prior = Prior()
    }
}

/** How a stretch of speech would play, replayed from when each piece of audio came (the voice benchmark). */
data class PlaybackSim(val startMs: Double, val underruns: Int, val pauses: Int, val waitedMs: Double, val endMs: Double)

/**
 * Plays [chunks] (arrival time in ms from the start, PCM) through [jitter]'s rules in virtual time, as AudioOut
 * would. [expectAt] gives the expected total length known at a time (from the text so far), or -1.
 */
fun simulatePlayback(jitter: Jitter, chunks: List<Pair<Double, FloatArray>>, expectAt: (Double) -> Double = { -1.0 }): PlaybackSim {
    jitter.begin()
    val rate = jitter.rate
    val slice = rate * Jitter.SLICE_MS / 1000
    val total = chunks.sumOf { it.second.size }
    val all = FloatArray(total)
    val arriveAt = DoubleArray(chunks.size)
    var o = 0
    chunks.forEachIndexed { i, (t, pcm) -> System.arraycopy(pcm, 0, all, o, pcm.size); o += pcm.size; arriveAt[i] = t }
    val ends = IntArray(chunks.size)
    var acc = 0
    chunks.forEachIndexed { i, c -> acc += c.second.size; ends[i] = acc }
    var next = 0 // chunks arrived
    var now = 0.0
    var avail = 0
    fun arriveUntil(t: Double) {
        while (next < chunks.size && arriveAt[next] <= t) {
            jitter.arrived(chunks[next].second.size, arriveAt[next]); avail = ends[next]; next++
            expectAt(arriveAt[next - 1]).takeIf { it > 0 }?.let { jitter.expect(it) }
        }
    }
    var played = 0
    var start = -1.0
    var waiting = true
    var lastQuiet = true
    while (played < total) {
        arriveUntil(now)
        val ended = next == chunks.size
        val bufferedMs = (avail - played) * 1000.0 / rate
        if (waiting) {
            if (bufferedMs >= jitter.needMs(played * 1000.0 / rate) || (ended && avail > played)) {
                waiting = false
                if (start < 0) start = now
            } else {
                now = if (next < chunks.size) arriveAt[next] else now
                continue
            }
        }
        // Waits from now until enough is waiting again (or everything has come); returns when that is.
        fun refill(): Double {
            var w = now
            while (true) {
                arriveUntil(w)
                val b = (avail - played) * 1000.0 / rate
                if (b >= jitter.needMs(played * 1000.0 / rate) || next == chunks.size) return w
                w = max(w, arriveAt[next])
            }
        }
        if (avail - played <= 0) {
            // Ran dry: a pause if the voice was quiet there, an underrun mid-word.
            val w = refill()
            if (lastQuiet) jitter.paused(w - now) else jitter.underrun(w - now)
            now = w
            continue
        }
        val n = min(slice, avail - played)
        val q = jitter.quiet(all, played, n)
        lastQuiet = q
        if (jitter.pauseHere(bufferedMs, q, ended)) {
            val w = refill()
            jitter.paused(w - now)
            now = w
        }
        played += n
        now += n * 1000.0 / rate
    }
    jitter.ended(total * 1000.0 / rate)
    return PlaybackSim(max(start, 0.0), jitter.underruns, jitter.pauses, jitter.waitedMs, now)
}
