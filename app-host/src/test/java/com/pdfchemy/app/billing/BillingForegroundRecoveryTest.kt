package com.pdfchemy.app.billing

import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingForegroundRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val client = mockk<BillingClient>(relaxed = true)
    private val setup = mutableListOf<BillingClientStateListener>()
    private val inventories = mutableListOf<PurchasesResponseListener>()
    private var ready = false
    private var stored = false
    private lateinit var manager: BillingManager

    @Before fun setUp() {
        BillingManager.resetCacheForTesting()
        mockkStatic(BillingClient::class)
        mockkObject(EntitlementStores)
        mockkObject(AdManager)
        every { AdManager.clearAd() } just Runs
        every { EntitlementStores.open(any()) } returns object : EntitlementStore {
            override fun read() = stored
            override fun write(entitled: Boolean): Boolean { stored = entitled; return true }
        }
        val builder = mockk<BillingClient.Builder>()
        every { BillingClient.newBuilder(any()) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.build() } returns client
        every { client.isReady } answers { ready }
        every { client.startConnection(capture(setup)) } just Runs
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(inventories)) } just Runs
        manager = BillingManager(application, scope, storageDispatcher = dispatcher)
    }

    @After fun tearDown() {
        scope.cancel()
        dispatcher.scheduler.runCurrent()
        unmockkAll()
        BillingManager.resetCacheForTesting()
    }

    private fun result(code: Int = BillingClient.BillingResponseCode.OK) =
        BillingResult.newBuilder().setResponseCode(code).build()

    private fun purchase(state: Int) = mockk<Purchase> {
        every { purchaseState } returns state
        every { products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
        every { isAcknowledged } returns true
    }

    private fun connect(purchases: List<Purchase> = emptyList()) {
        ready = true
        setup.last().onBillingSetupFinished(result())
        inventories.last().onQueryPurchasesResponse(result(), purchases)
        dispatcher.scheduler.runCurrent()
    }

    @Test fun returningToForegroundFindsPaymentCompletedWithoutCallback() {
        connect(listOf(purchase(Purchase.PurchaseState.PENDING)))
        val activity = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        dispatcher.scheduler.runCurrent()
        assertEquals("Foreground must refresh inventory", 2, inventories.size)
        inventories.last().onQueryPurchasesResponse(result(), listOf(purchase(Purchase.PurchaseState.PURCHASED)))
        dispatcher.scheduler.runCurrent()
        assertTrue(manager.isPremium.value)
        assertTrue(stored)
        activity.pause().stop().destroy()
    }

    @Test fun repeatedResumesCoalesceAndWaitForManualRestore() {
        connect()
        manager.restorePurchases()
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        first.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        dispatcher.scheduler.runCurrent()
        assertEquals("Foreground must not supersede manual restore", 2, inventories.size)
        inventories.last().onQueryPurchasesResponse(result(), emptyList())
        dispatcher.scheduler.runCurrent()
        assertEquals("Coalesced foreground refresh follows the completed restore", 3, inventories.size)
        replacement.pause().stop().destroy()
    }

    @Test fun foregroundReconnectsOnlyOnceAndFailedQueryPreservesPremium() {
        connect(listOf(purchase(Purchase.PurchaseState.PURCHASED)))
        ready = false
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        first.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        dispatcher.scheduler.runCurrent()
        assertEquals("Resume must reconnect without duplicate connections", 2, setup.size)
        ready = true
        setup.last().onBillingSetupFinished(result())
        inventories.last().onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.NETWORK_ERROR), emptyList())
        dispatcher.scheduler.runCurrent()
        assertTrue(manager.isPremium.value)
        replacement.pause().stop().destroy()
    }

    @Test fun cancelledOwnerUnregistersLifecycleAndRejectsLateCallbacks() {
        connect()
        val first = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        dispatcher.scheduler.runCurrent()
        assertEquals(2, inventories.size)
        scope.cancel()
        dispatcher.scheduler.runCurrent()
        inventories.last().onQueryPurchasesResponse(result(), listOf(purchase(Purchase.PurchaseState.PURCHASED)))
        first.pause().stop().destroy()
        val replacement = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        dispatcher.scheduler.runCurrent()
        assertFalse(manager.isPremium.value)
        verify(exactly = 1) { client.endConnection() }
        assertEquals(2, inventories.size)
        replacement.pause().stop().destroy()
    }
}
