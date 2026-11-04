package com.pdfchemy.app.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingRestoreRecoveryTest {
    private lateinit var session: BillingTestSession
    @Before fun setUp() { session = BillingTestSession() }
    @After fun tearDown() { session.close() }

    @Test fun missingCallbackStopsLoadingAndRetryRepeatsInventoryQuery() = with(session) {
        connect()
        manager.restorePurchases()
        advance(15_000L)
        assertFalse("Restore must not spin indefinitely", manager.billingUi.value.loading)
        assertTrue(manager.billingUi.value.canRetry)
        manager.retry()
        assertEquals("Retry must repeat the failed restore", 3, inventories.size)
    }

    @Test fun lateTimedOutInventoryCannotRevokeCachedPremium() = with(session) {
        connect(listOf(order()))
        manager.restorePurchases()
        val expired = inventories.last()
        advance(15_000L)
        expired.onQueryPurchasesResponse(result(), emptyList())
        runCurrent()
        assertTrue("Expired callback must not alter entitlement", manager.isPremium.value)
    }

    @Test fun networkFailureOffersRestoreRetryEvenWhenPriceIsReady() = with(session) {
        connect()
        manager.restorePurchases()
        inventories.last().onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
        runCurrent()
        assertTrue("A loaded price must not hide restore retry", manager.billingUi.value.canRetry)
        manager.retry()
        assertEquals(3, inventories.size)
    }

    @Test fun restoreIntentSurvivesInitialConnection() = with(session) {
        manager.restorePurchases()
        connect(listOf(order()))
        assertTrue(manager.isPremium.value)
        assertTrue(manager.billingUi.value.message.contains("restored successfully"))
    }

    @Test fun restoreReportsPendingPaymentInsteadOfNoPurchases() = with(session) {
        connect()
        manager.restorePurchases()
        inventories.last().onQueryPurchasesResponse(result(), listOf(order(Purchase.PurchaseState.PENDING)))
        runCurrent()
        assertFalse(manager.isPremium.value)
        assertTrue(manager.billingUi.value.message.contains("pending", ignoreCase = true))
        assertFalse(manager.billingUi.value.message.contains("No previous purchases"))
    }

    @Test fun priceCallbackCannotReplaceAnActiveRestoreWithSuccess() = with(session) {
        connect(withDetails = false)
        manager.restorePurchases()
        completeDetails()
        assertTrue("Price loading must not complete an outstanding restore", manager.billingUi.value.loading)
        assertFalse(manager.billingUi.value.canPurchase)
    }

    @Test fun duplicateRestoreTapsDoNotStartOverlappingManualQueries() = with(session) {
        connect()
        repeat(16) { manager.restorePurchases() }
        assertEquals(2, inventories.size)
        inventories.last().onQueryPurchasesResponse(result(), emptyList())
        runCurrent()
        assertFalse(manager.billingUi.value.loading)
    }

    @Test fun restoredSuccessFollowsActualEntitlementProcessing() = with(session) {
        connect()
        manager.restorePurchases()
        inventories.last().onQueryPurchasesResponse(result(), listOf(order()))
        assertFalse(manager.isPremium.value)
        assertTrue(manager.billingUi.value.loading)
        runCurrent()
        assertTrue(manager.isPremium.value)
        assertTrue(manager.billingUi.value.message.contains("restored successfully"))
    }
}
