package com.aioapp.mdmlite

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Background fallback check-in. The in-process loop reports every minute while the host
 * app runs; once the app goes to the background, Android freezes or kills its process and
 * the loop stops. This periodic job (every 15 minutes, the platform minimum; in Doze only
 * during maintenance windows) wakes the process for one check-in, so vitals and queued
 * crash events keep arriving while the app is backgrounded or the device sleeps.
 *
 * JobScheduler is part of the platform: no dependency for the host to carry. Running the
 * job starts the host's process (Application.onCreate) without showing any UI.
 */
class CheckinJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        // The host's Application.onCreate has called AioMdm.init by the time any
        // component of the process runs; if it did not (MDM off in this build), skip.
        return AioMdm.checkinNow { jobFinished(params, false) }
    }

    override fun onStopJob(params: JobParameters): Boolean = true // retry per the schedule

    internal companion object {
        private const val JOB_ID = 0x4D444D4C // "MDML"

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, CheckinJob::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true) // survives reboots (RECEIVE_BOOT_COMPLETED)
                .build()
            if (js.schedule(job) != JobScheduler.RESULT_SUCCESS) Log.w("AioMdm", "background check-in not scheduled")
        }
    }
}
