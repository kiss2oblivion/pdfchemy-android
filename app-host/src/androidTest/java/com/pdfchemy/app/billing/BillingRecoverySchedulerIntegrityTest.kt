package com.pdfchemy.app.billing

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual platform scheduling/manifest contract; does not simulate a Play purchase. */
@RunWith(AndroidJUnit4::class)
class BillingRecoverySchedulerIntegrityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scheduler = context.getSystemService(JobScheduler::class.java)

    @After fun cleanup() {
        BillingAcknowledgementRecovery.setRunning(false)
        BillingAcknowledgementRecovery.complete(context)
    }

    @Test fun platformAcceptsPrivatePermissionProtectedRecoveryJobWithoutPurchasePayload() {
        val info = context.packageManager.getServiceInfo(ComponentName(context, BillingRecoveryJobService::class.java), 0)
        assertFalse(info.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
        BillingAcknowledgementRecovery.markPending(context)
        val job = requireNotNull(scheduler.getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
        assertEquals(JobInfo.NETWORK_TYPE_ANY, job.networkType)
        assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
        assertTrue(job.minLatencyMillis >= 60_000L)
        assertTrue(job.extras.isEmpty)
    }

    @Test fun savedHintReschedulesAnAbsentJobAndSuccessfulReconciliationRetiresIt() {
        BillingAcknowledgementRecovery.markPending(context)
        scheduler.cancel(BillingAcknowledgementRecovery.JOB_ID)
        assertTrue(BillingAcknowledgementRecovery.pending(context))
        BillingAcknowledgementRecovery.ensureScheduled(context)
        assertNotNull(scheduler.getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
        BillingAcknowledgementRecovery.complete(context)
        assertFalse(BillingAcknowledgementRecovery.pending(context))
        assertNull(scheduler.getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
    }

    @Test fun permanentFailureDoesNotScheduleAnAutomaticRetryLoop() {
        BillingAcknowledgementRecovery.markPending(context)
        BillingAcknowledgementRecovery.pauseAutomaticRetry(context)
        BillingAcknowledgementRecovery.ensureScheduled(context)
        assertTrue(BillingAcknowledgementRecovery.pending(context))
        assertNull(scheduler.getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
    }
}
