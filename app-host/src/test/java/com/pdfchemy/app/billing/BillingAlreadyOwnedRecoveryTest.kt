package com.pdfchemy.app.billing

import android.app.Activity
import com.android.billingclient.api.*
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingAlreadyOwnedRecoveryTest {
    private lateinit var session: BillingTestSession
    @Before fun setup() { session = BillingTestSession() }
    @After fun cleanup() { session.close() }

    private fun BillingTestSession.ownedCallback() {
        manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), null)
    }

    @Test fun ownedCallbackQueriesBeforeGrantAndAcknowledgesConfirmedOrder() = with(session) {
        connect()
        ownedCallback()
        assertEquals(2, inventories.size)
        assertFalse(manager.isPremium.value)
        inventories.last().onQueryPurchasesResponse(result(), listOf(order(acknowledged = false)))
        runCurrent()
        assertTrue(manager.isPremium.value)
        assertTrue(stored)
        verify(exactly = 1) { client.acknowledgePurchase(any(), any()) }
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test fun immediateCheckoutOwnedResultReconcilesWithoutAnotherCheckout() = with(session) {
        connect(withDetails = false)
        completeDetails(actualProduct = true)
        runCurrent()
        every { client.launchBillingFlow(any(), any()) } returns result(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            manager.launchPurchaseFlow(activity.get())
            assertEquals(2, inventories.size)
            inventories.last().onQueryPurchasesResponse(result(), listOf(order()))
            runCurrent()
            assertTrue(manager.isPremium.value)
            verify(exactly = 1) { client.launchBillingFlow(any(), any()) }
        } finally { activity.destroy() }
    }

    @Test fun duplicateOwnedCallbacksCoalesceIntoOneInventoryRequest() = with(session) {
        connect()
        repeat(16) { ownedCallback() }
        assertEquals(2, inventories.size)
        inventories.last().onQueryPurchasesResponse(result(), emptyList())
        runCurrent()
        assertFalse(manager.isPremium.value)
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test fun ownedPendingOrderNeverGrantsOrAcknowledgesAndCannotRepurchase() = with(session) {
        connect()
        ownedCallback()
        inventories.last().onQueryPurchasesResponse(result(), listOf(order(Purchase.PurchaseState.PENDING)))
        runCurrent()
        assertFalse(manager.isPremium.value)
        assertTrue(manager.billingUi.value.message.contains("pending", ignoreCase = true))
        assertFalse(manager.billingUi.value.canPurchase)
        verify(exactly = 0) { client.acknowledgePurchase(any(), any()) }
    }

    @Test fun ownedWithNoMatchingPurchaseReportsAbsenceWithoutGrant() = with(session) {
        connect()
        ownedCallback()
        inventories.last().onQueryPurchasesResponse(result(), emptyList())
        runCurrent()
        assertFalse(manager.isPremium.value)
        assertTrue(manager.billingUi.value.message.contains("No previous purchases"))
        verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
    }

    @Test fun ownedQueryErrorPreservesCachedPremiumAndOffersInventoryRetry() = with(session) {
        connect(listOf(order()))
        ownedCallback()
        inventories.last().onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
        runCurrent()
        assertTrue(manager.isPremium.value)
        assertTrue(manager.billingUi.value.canRetry)
        manager.retry()
        assertEquals(3, inventories.size)
    }

    @Test fun unrelatedPurchasedProductCannotUnlockPremium() = with(session) {
        connect()
        ownedCallback()
        assertEquals(2, inventories.size)
        val unrelated = mockk<Purchase> {
            every { products } returns listOf("another_product")
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        }
        inventories.last().onQueryPurchasesResponse(result(), listOf(unrelated))
        runCurrent()
        assertFalse(manager.isPremium.value)
        verify(exactly = 0) { client.acknowledgePurchase(any(), any()) }
    }

    @Test fun alreadyEntitledUserCannotOpenAnotherCheckout() = with(session) {
        connect(listOf(order()), withDetails = false)
        completeDetails(actualProduct = true)
        runCurrent()
        every { client.launchBillingFlow(any(), any()) } returns result()
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            manager.launchPurchaseFlow(activity.get())
            verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
        } finally { activity.destroy() }
    }

    @Test fun knownPendingPaymentCannotOpenAnotherCheckout() = with(session) {
        connect(listOf(order(Purchase.PurchaseState.PENDING)), withDetails = false)
        completeDetails(actualProduct = true)
        runCurrent()
        every { client.launchBillingFlow(any(), any()) } returns result()
        val activity = Robolectric.buildActivity(Activity::class.java).create()
        try {
            manager.launchPurchaseFlow(activity.get())
            verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
        } finally { activity.destroy() }
    }
}
