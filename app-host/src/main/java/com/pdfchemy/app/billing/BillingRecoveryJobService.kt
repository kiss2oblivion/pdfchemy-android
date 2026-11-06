package com.pdfchemy.app.billing

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

/** Queries Play again after process death; accepts no external purchase data. */
class BillingRecoveryJobService : JobService() {
    private var work: Job? = null
    private var owner: CoroutineScope? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BillingAcknowledgementRecovery.canRetryAutomatically(this)) return false
        BillingAcknowledgementRecovery.setRunning(true)
        val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        owner = jobScope
        work = jobScope.launch {
            var temporary: BillingManager? = null
            val outcome = try {
                withTimeout(45_000L) {
                    val manager = BillingManager.activeForRecovery() ?: BillingManager(
                        applicationContext, jobScope, recoveryOnly = true
                    ).also { temporary = it }
                    manager.reconcileDelivery()
                }
            } catch (cancel: CancellationException) {
                if (!isActive) throw cancel
                AcknowledgementOutcome.RETRYABLE
            } catch (_: Exception) {
                AcknowledgementOutcome.RETRYABLE
            } finally {
                temporary?.close()
            }
            BillingAcknowledgementRecovery.setRunning(false)
            jobFinished(params, outcome == AcknowledgementOutcome.RETRYABLE)
            owner = null
            jobScope.cancel()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stopWork()
        return BillingAcknowledgementRecovery.canRetryAutomatically(this)
    }

    private fun stopWork() {
        work?.cancel()
        work = null
        owner?.cancel()
        owner = null
        BillingAcknowledgementRecovery.setRunning(false)
    }

    override fun onDestroy() {
        stopWork()
        super.onDestroy()
    }
}
