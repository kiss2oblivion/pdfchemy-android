package com.pdfchemy.app.billing

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

/** A private recovery hint, never a purchase token or proof of ownership. */
internal object BillingAcknowledgementRecovery {
    internal const val JOB_ID = 0x504442
    private const val PREFS = "billing_delivery_recovery"
    private const val PENDING = "pending"
    private const val AUTOMATIC = "automatic"
    private var running = false

    @Synchronized fun markPending(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, true).putBoolean(AUTOMATIC, true).commit()
        if (!saved) com.pdfchemy.app.utils.AppLogger.w("Billing delivery recovery marker could not be saved")
        ensureScheduled(context)
    }

    @Synchronized fun ensureScheduled(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (running || !prefs.getBoolean(PENDING, false) || !prefs.getBoolean(AUTOMATIC, true)) return
        val scheduled = runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@runCatching false
            if (scheduler.getPendingJob(JOB_ID) != null) return@runCatching true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, BillingRecoveryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(60_000L)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
        if (!scheduled) {
            com.pdfchemy.app.utils.AppLogger.w("Billing delivery recovery scheduling was refused")
        }
    }

    @Synchronized fun complete(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PENDING, false).commit()
        // A running service finishes its own job, without cancelling its coroutine midway.
        if (!running) context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    @Synchronized fun pauseAutomaticRetry(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(AUTOMATIC, false).commit()
        if (!running) context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }

    @Synchronized fun pending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false)

    @Synchronized fun canRetryAutomatically(context: Context): Boolean =
        pending(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTOMATIC, true)

    @Synchronized fun setRunning(value: Boolean) { running = value }
}
