package com.lfmpixel.lfm.memory

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.lfmpixel.lfm.LfmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The memory's nightly pass ([MemoryStore.nightly]): once a day, while the phone is charging and idle, through
 * Android's own JobScheduler (no Play Services, no network constraint: it needs none). Stopped if the phone is
 * unplugged or used; what it did so far is kept.
 */
class LearnJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val g = (application as LfmApp).graph
        job = scope.launch {
            try {
                if (g.heat.heat.value < com.lfmpixel.lfm.device.Heat.Hot)
                    g.memory.nightly { if (g.engine.canChat()) g.engine.textSession() else null }
            } catch (e: Exception) {
                android.util.Log.w(MemoryStore.TAG, "nightly failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val ID = 4711

        /** Schedules the nightly pass if it isn't already; off, cancels it. */
        fun schedule(context: Context, on: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!on) { js.cancel(ID); return }
            if (js.getPendingJob(ID) != null) return
            js.schedule(JobInfo.Builder(ID, ComponentName(context, LearnJob::class.java))
                .setRequiresCharging(true).setRequiresDeviceIdle(true)
                .setPeriodic(24 * 3_600_000L, 6 * 3_600_000L).build())
        }
    }
}
