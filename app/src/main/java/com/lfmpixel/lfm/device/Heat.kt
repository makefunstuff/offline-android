package com.lfmpixel.lfm.device

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How hot the phone says it is, from Android's thermal status, in the steps that change what Wren does. */
enum class Heat(val label: String) {
    Normal(""),
    /** Moderate: one thread fewer. */
    Warm("Phone is warm · answers a little slower"),
    /** Severe: half the threads; long jobs wait. */
    Hot("Phone is hot · answers slower, long jobs wait"),
    /** Critical and above: two threads; nothing new starts until it cools. */
    TooHot("Phone is too hot · waiting for it to cool");

    /** Queued messages and long jobs wait before they start (the one running carries on, slower). */
    val holdsLongJobs get() = this >= Hot
    val holdsEverything get() = this == TooHot

    companion object {
        fun of(status: Int): Heat = when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> TooHot
            status >= PowerManager.THERMAL_STATUS_SEVERE -> Hot
            status >= PowerManager.THERMAL_STATUS_MODERATE -> Warm
            else -> Normal
        }

        /** Most CPU threads LiteRT may use at [heat] on a phone with [fastCores] big cores; 0 is no cap. */
        fun threadCap(heat: Heat, fastCores: Int): Int {
            val fast = fastCores.coerceAtLeast(1)
            return when (heat) {
                Normal -> 0
                Warm -> (fast - 2).coerceAtLeast(2).coerceAtMost(fast)
                Hot -> ((fast + 1) / 2).coerceAtLeast(2).coerceAtMost(fast)
                TooHot -> 2.coerceAtMost(fast)
            }
        }
    }
}

/**
 * Listens to Android's thermal status (spec section 7, Battery and heat). Models loaded on the CPU from then on take
 * its thread cap (ModelManager); the assistant's queue and long jobs read [heat] before they start.
 */
class HeatWatch(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val _heat = MutableStateFlow(Heat.of(power?.currentThermalStatus ?: 0))
    val heat: StateFlow<Heat> = _heat
    /** Called with each new step, after [heat] changed. */
    var onChange: (Heat) -> Unit = {}

    init {
        // Not every build has a thermal service (Robolectric, some emulators): then the phone is taken as cool.
        runCatching {
            power?.addThermalStatusListener(context.mainExecutor) { status ->
                val h = Heat.of(status)
                if (h != _heat.value) { _heat.value = h; onChange(h) }
            }
        }
    }
}
