package com.pdfchemy.app.billing

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingAcknowledgementRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val client = mockk<BillingClient>(relaxed = true)
    private val setup = mutableListOf<BillingClientStateListener>()
    private val inventories = mutableListOf<PurchasesResponseListener>()
    private lateinit var manager: BillingManager

    @Before fun setUp() {
        BillingManager.resetCacheForTesting()
        app.getSystemService(JobScheduler::class.java).cancelAll()
        app.getSharedPreferences("billing_delivery_recovery", 0).edit().clear().commit()
        BillingAcknowledgementRecovery.setRunning(false)
        mockkStatic(BillingClient::class)
        mockkObject(EntitlementStores)
        val builder = mockk<BillingClient.Builder>()
        every { EntitlementStores.open(any()) } returns object : EntitlementStore {
            override fun read() = false
            override fun write(entitled: Boolean) = true
        }
        every { BillingClient.newBuilder(any()) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.build() } returns client
        every { client.isReady } returns true
        every { client.startConnection(capture(setup)) } just Runs
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(inventories)) } just Runs
        manager = BillingManager(app, scope)
    }

    @After fun tearDown() {
        scope.cancel()
        app.getSystemService(JobScheduler::class.java).cancelAll()
        BillingAcknowledgementRecovery.setRunning(false)
        unmockkAll()
        BillingManager.resetCacheForTesting()
    }

    @Test fun exhaustedTransientAttemptsLeaveDurableRecoveryScheduled() = runBlocking {
        every { client.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(
                BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.NETWORK_ERROR).build()
            )
        }
        val order = mockk<Purchase> { every { purchaseToken } returns "test-token" }
        manager.acknowledgePurchase(order, maxRetries = 3, initialDelayMs = 0)
        val jobs = app.getSystemService(JobScheduler::class.java).allPendingJobs
        assertTrue("Failed acknowledgement must survive process death", jobs.any {
            it.service.className == "com.pdfchemy.app.billing.BillingRecoveryJobService"
        })
        verify(exactly = 3) { client.acknowledgePurchase(any(), any()) }
    }

    @Test fun coroutineCancellationIsNotRetriedAsBillingFailure() = runBlocking {
        every { client.acknowledgePurchase(any(), any()) } throws CancellationException("owner closed")
        val order = mockk<Purchase> { every { purchaseToken } returns "test-token" }
        try {
            manager.acknowledgePurchase(order, maxRetries = 3, initialDelayMs = 0)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        }
    }

    private fun reply(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

    private fun order(state: Int = Purchase.PurchaseState.PURCHASED, acknowledged: Boolean = false) = mockk<Purchase> {
        every { purchaseToken } returns "test-token"
        every { purchaseState } returns state
        every { products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
        every { isAcknowledged } returns acknowledged
    }

    @Test fun duplicateAndOverlappingAcknowledgementsUseOneRequest() = runBlocking {
        val started = CompletableDeferred<AcknowledgePurchaseResponseListener>()
        every { client.acknowledgePurchase(any(), any()) } answers {
            started.complete(secondArg())
        }
        val purchase = order()
        val first = async { manager.acknowledgePurchase(purchase, initialDelayMs = 0) }
        val callback = started.await()
        val second = async { manager.acknowledgePurchase(purchase, initialDelayMs = 0) }
        yield()
        callback.onAcknowledgePurchaseResponse(reply(BillingClient.BillingResponseCode.OK))
        assertEquals(AcknowledgementOutcome.ACKNOWLEDGED, first.await())
        assertEquals(AcknowledgementOutcome.ACKNOWLEDGED, second.await())
        assertEquals(AcknowledgementOutcome.ACKNOWLEDGED, manager.acknowledgePurchase(purchase, initialDelayMs = 0))
        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
    }

    @Test fun permanentErrorPausesAutomaticRetriesUntilReconciliation() = runBlocking {
        every { client.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(reply(BillingClient.BillingResponseCode.DEVELOPER_ERROR))
        }
        assertEquals(AcknowledgementOutcome.RECONCILE, manager.acknowledgePurchase(order(), initialDelayMs = 0))
        assertTrue(BillingAcknowledgementRecovery.pending(app))
        assertFalse(BillingAcknowledgementRecovery.canRetryAutomatically(app))
        assertNull(app.getSystemService(JobScheduler::class.java).getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
    }

    @Test fun newOwnerReschedulesMarkerAndSuccessfulInventoryRetiresRecovery() = runBlocking {
        every { client.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(reply(BillingClient.BillingResponseCode.NETWORK_ERROR))
        }
        manager.acknowledgePurchase(order(), maxRetries = 1, initialDelayMs = 0)
        scope.cancel()
        app.getSystemService(JobScheduler::class.java).cancelAll()
        val replacementScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val replacement = BillingManager(app, replacementScope)
            assertNotNull(app.getSystemService(JobScheduler::class.java).getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
            setup.last().onBillingSetupFinished(reply(BillingClient.BillingResponseCode.OK))
            inventories.last().onQueryPurchasesResponse(reply(BillingClient.BillingResponseCode.OK), listOf(order(acknowledged = true)))
            assertTrue(replacement.isPremium.value)
            assertFalse(BillingAcknowledgementRecovery.pending(app))
            assertNull(app.getSystemService(JobScheduler::class.java).getPendingJob(BillingAcknowledgementRecovery.JOB_ID))
            verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        } finally { replacementScope.cancel() }
    }

    @Test fun pendingAndUnrelatedPurchasesAreNeverAcknowledged() {
        val unrelated = mockk<Purchase> {
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { products } returns listOf("not_premium")
        }
        setup.last().onBillingSetupFinished(reply(BillingClient.BillingResponseCode.OK))
        inventories.last().onQueryPurchasesResponse(reply(BillingClient.BillingResponseCode.OK), listOf(order(Purchase.PurchaseState.PENDING), unrelated))
        verify(exactly = 0) { client.acknowledgePurchase(any(), any()) }
        assertFalse(manager.isPremium.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun missingAcknowledgementCallbackTimesOutUsingVirtualTime() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val localScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val local = BillingManager(app, localScope, acknowledgementDispatcher = dispatcher)
            every { client.acknowledgePurchase(any(), any()) } just Runs
            val result = async { local.acknowledgePurchase(order(), initialDelayMs = 0) }
            runCurrent()
            advanceTimeBy(15_000L)
            runCurrent()
            assertEquals(AcknowledgementOutcome.RETRYABLE, result.await())
            assertTrue(BillingAcknowledgementRecovery.canRetryAutomatically(app))
            assertFalse(local.isPremium.value)
        } finally { localScope.cancel() }
    }

    @Test fun recoveryServiceIsPrivateAndSystemPermissionProtected() {
        val info = app.packageManager.getServiceInfo(android.content.ComponentName(app, BillingRecoveryJobService::class.java), 0)
        assertFalse(info.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", info.permission)
    }

    @Test fun existingUiOwnersRemainUsableAndRecoveryReusesASurvivingOwner() {
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val second = BillingManager(app, secondScope)
            manager.restorePurchases()
            second.restorePurchases()
            assertEquals(2, inventories.size)
            verify(exactly = 0) { client.endConnection() }
            secondScope.cancel()
            assertSame(manager, BillingManager.activeForRecovery())
            inventories.first().onQueryPurchasesResponse(reply(BillingClient.BillingResponseCode.OK), emptyList())
            manager.restorePurchases()
            assertEquals(3, inventories.size)
        } finally { secondScope.cancel() }
    }

    @Test fun foregroundRetiresOnlyTemporaryRecoveryConnectionBeforeCreatingItsClient() {
        manager.close()
        val temporaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val foregroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val temporary = BillingManager(app, temporaryScope, recoveryOnly = true)
            val foreground = BillingManager(app, foregroundScope)
            assertSame(foreground, BillingManager.activeForRecovery())
            val inventoryCount = inventories.size
            temporary.restorePurchases()
            assertEquals(inventoryCount, inventories.size)
            foreground.restorePurchases()
            assertEquals(inventoryCount + 1, inventories.size)
            verify(exactly = 2) { client.endConnection() }
        } finally {
            temporaryScope.cancel()
            foregroundScope.cancel()
        }
    }
}
