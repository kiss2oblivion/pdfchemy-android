package com.pdfchemy.app.billing

import com.android.billingclient.api.BillingClient
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingPersistenceRecoveryTest {
    private lateinit var session: BillingTestSession
    @Before fun setUp() { session = BillingTestSession() }
    @After fun tearDown() { session.close() }

    @Test fun failedGrantCacheWriteIsVisibleAndDoesNotRevokePaidAccess() = with(session) {
        write = { false }
        connect(listOf(order()))
        assertTrue(manager.isPremium.value)
        assertFalse(stored)
        assertTrue("Failed offline cache needs a recovery action", manager.billingUi.value.canRetry)
        assertTrue(manager.billingUi.value.message.contains("offline", ignoreCase = true))
        verify { AdManager.clearAd() }
    }

    @Test fun storageExceptionCannotPreventGrantOrAcknowledgement() = with(session) {
        write = { throw IOException("storage unavailable") }
        connect(listOf(order(acknowledged = false)))
        assertTrue("Verified buyer must retain access despite storage failure", manager.isPremium.value)
        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        assertTrue(errors.isEmpty())
    }

    @Test fun retryRepairsCacheInsteadOfOnlyReloadingProductPrice() = with(session) {
        write = { false }
        connect(listOf(order()))
        advance(3_000L)
        write = { stored = it; true }
        val queriesBeforeRetry = inventories.size
        manager.retry()
        runCurrent()
        assertTrue("Retry must persist the verified entitlement", stored)
        assertEquals(queriesBeforeRetry, inventories.size)
    }

    @Test fun failedRevocationCacheWriteIsReportedWithoutRestoringOldAccess() = with(session) {
        connect(listOf(order()))
        assertTrue(stored)
        write = { false }
        manager.restorePurchases()
        inventories.last().onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), emptyList())
        runCurrent()
        assertFalse(manager.isPremium.value)
        assertTrue("Read-only storage still holds the previous record", stored)
        assertTrue(manager.billingUi.value.canRetry)
        assertTrue(manager.billingUi.value.message.contains("offline", ignoreCase = true))
    }

    @Test fun repairedCacheSurvivesNewManagerAndOfflineProcessStateReset() = with(session) {
        write = { false }
        connect(listOf(order()))
        advance(3_000L)
        manager.close()
        runCurrent()
        write = { stored = it; true }
        val replacement = BillingManager(app, scope, acknowledgementDispatcher = dispatcher, storageDispatcher = dispatcher)
        runCurrent()
        assertTrue(replacement.isPremium.value)
        assertTrue(stored)
        assertFalse(replacement.billingUi.value.entitlementStorageFailed)
        replacement.close()
        runCurrent()
        BillingManager.resetCacheForTesting()
        ready = false
        val offline = BillingManager(app, scope, acknowledgementDispatcher = dispatcher, storageDispatcher = dispatcher)
        assertTrue("Successfully repaired encrypted cache restores offline ownership", offline.isPremium.value)
    }

    @Test fun unreadableCacheDoesNotCrashAndRetryUsesPlayRestoration() {
        session.close()
        session = BillingTestSession(initialReadFailure = true)
        with(session) {
            runCurrent()
            assertFalse(manager.isPremium.value)
            assertTrue(manager.billingUi.value.entitlementStorageFailed)
            manager.retry()
            connect(listOf(order()))
            assertTrue(manager.isPremium.value)
            assertTrue(stored)
            assertFalse(manager.billingUi.value.entitlementStorageFailed)
            assertTrue(errors.isEmpty())
        }
    }
}
