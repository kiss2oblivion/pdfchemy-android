package com.pdfchemy.app.billing

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BillingReadinessTest {

    @org.junit.Before
    fun setup() {
        BillingManager.resetCacheForTesting()
    }

    @org.junit.After
    fun tearDown() {
        BillingManager.resetCacheForTesting()
    }

    @Test
    fun initialPriceNeverExposesUnverifiedUsdFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-initial-price-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns false
            every { client.startConnection(any()) } just Runs

            val manager = BillingManager(context, scope)

            // Invariant GEM-01: Must never expose hardcoded USD fallback ($4.99)
            assertEquals("Initial price must be empty until Play returns local details", "", manager.premiumPrice.value)
            assertFalse("Cannot purchase until price and details are resolved", manager.billingUi.value.canPurchase)
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun failedSetupAndMissingDetailsAreRetryableAndCancellationDoesNotGrantPremium() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val products = slot<ProductDetailsResponseListener>()
        var ready = false
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } answers { ready }
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), capture(products)) } just Runs
            val manager = BillingManager(context, scope)
            assertFalse(manager.billingUi.value.canPurchase)
            assertEquals("", manager.premiumPrice.value)

            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
            assertTrue(manager.billingUi.value.canRetry)
            manager.retry()
            ready = true
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))
            assertTrue(manager.billingUi.value.loading)
            assertFalse(manager.billingUi.value.canPurchase)
            val absent = mockk<QueryProductDetailsResult>()
            every { absent.productDetailsList } returns emptyList()
            products.captured.onProductDetailsResponse(result(BillingClient.BillingResponseCode.OK), absent)
            assertTrue(manager.billingUi.value.canRetry)
            assertFalse(manager.billingUi.value.canPurchase)

            manager.retry()
            val details = mockk<ProductDetails>()
            every { details.productId } returns BillingManager.PREMIUM_PRODUCT_ID
            val offer = mockk<ProductDetails.OneTimePurchaseOfferDetails>()
            every { offer.formattedPrice } returns "€4.99"
            every { details.oneTimePurchaseOfferDetails } returns offer

            val found = mockk<QueryProductDetailsResult>()
            every { found.productDetailsList } returns listOf(details)
            products.captured.onProductDetailsResponse(result(BillingClient.BillingResponseCode.OK), found)
            assertTrue(manager.billingUi.value.canPurchase)
            assertEquals("€4.99", manager.premiumPrice.value)

            manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.USER_CANCELED), null)
            assertTrue(manager.billingUi.value.canPurchase)
            assertTrue(manager.billingUi.value.message.contains("cancelled"))
            assertFalse(manager.isPremium.value)
            verify(exactly = 0) { client.launchBillingFlow(any(), any()) }
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun pendingPurchasesNeverGrantPremium() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-pending-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs

            val manager = BillingManager(context, scope)

            val pendingPurchase = mockk<Purchase>()
            every { pendingPurchase.purchaseState } returns Purchase.PurchaseState.PENDING
            every { pendingPurchase.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)

            manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), mutableListOf(pendingPurchase))

            // Invariant: Pending purchase must NOT unlock premium!
            assertFalse("Pending purchase must not grant Premium!", manager.isPremium.value)
            assertFalse(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
            assertTrue(manager.billingUi.value.message.contains("Payment is pending"))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun validPurchasedStateGrantsPremiumAndAcknowledgesWithRetry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-success-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val ackListener = slot<AcknowledgePurchaseResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs

            every { client.acknowledgePurchase(any(), capture(ackListener)) } answers {
                ackListener.captured.onAcknowledgePurchaseResponse(result(BillingClient.BillingResponseCode.OK))
            }

            val manager = BillingManager(context, scope)

            val validPurchase = mockk<Purchase>()
            every { validPurchase.purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { validPurchase.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
            every { validPurchase.isAcknowledged } returns false
            every { validPurchase.purchaseToken } returns "mock-purchase-token-xyz"

            manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), mutableListOf(validPurchase))

            // Entitlement granted and persisted
            assertTrue(manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))

            // Acknowledge attempted with retry
            withTimeoutOrNull(3000) {
                while (!ackListener.isCaptured) {
                    delay(20)
                }
            }
            verify(atLeast = 1) { client.acknowledgePurchase(any(), any()) }
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun acknowledgePurchaseRetriesOnTransientErrors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-retry-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        var callCount = 0
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs

            val ackSlot = slot<AcknowledgePurchaseResponseListener>()
            every { client.acknowledgePurchase(any(), capture(ackSlot)) } answers {
                callCount++
                if (callCount < 3) {
                    ackSlot.captured.onAcknowledgePurchaseResponse(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))
                } else {
                    ackSlot.captured.onAcknowledgePurchaseResponse(result(BillingClient.BillingResponseCode.OK))
                }
            }

            val manager = BillingManager(context, scope)
            val purchase = mockk<Purchase>()
            every { purchase.purchaseToken } returns "token-retry-123"

            manager.acknowledgePurchase(purchase, maxRetries = 3, initialDelayMs = 0L)

            assertEquals("Acknowledge should have been retried until success", 3, callCount)
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun queryProductDetailsUpdatesLocalizedPriceFromOneTimePurchaseOffer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-price-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val products = slot<ProductDetailsResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), capture(products)) } just Runs

            val manager = BillingManager(context, scope)
            assertEquals("Initial price must be blank", "", manager.premiumPrice.value)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))

            val details = mockk<ProductDetails>()
            every { details.productId } returns BillingManager.PREMIUM_PRODUCT_ID
            val offer = mockk<ProductDetails.OneTimePurchaseOfferDetails>()
            every { offer.formattedPrice } returns "€4.99"
            every { details.oneTimePurchaseOfferDetails } returns offer

            val queryResult = mockk<QueryProductDetailsResult>()
            every { queryResult.productDetailsList } returns listOf(details)

            products.captured.onProductDetailsResponse(result(BillingClient.BillingResponseCode.OK), queryResult)

            assertEquals("Localized formatted price should update from ProductDetails offer", "€4.99", manager.premiumPrice.value)
            assertTrue(manager.billingUi.value.canPurchase)
            assertEquals("One-time purchase through Google Play.", manager.billingUi.value.message)
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun restorePurchasesRestoresActivePurchaseAndUpdatesUiState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-restore-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val purchasesListener = slot<PurchasesResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(purchasesListener)) } just Runs

            val manager = BillingManager(context, scope)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))

            assertFalse(manager.isPremium.value)

            // Trigger user-initiated restore
            manager.restorePurchases()

            val activePurchase = mockk<Purchase>()
            every { activePurchase.purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { activePurchase.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
            every { activePurchase.isAcknowledged } returns true

            purchasesListener.captured.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), listOf(activePurchase))

            assertTrue("Active purchase must be restored as Premium", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
            assertTrue(manager.billingUi.value.message.contains("restored successfully"))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun restorePurchasesReportsNoPurchasesWhenListIsEmpty() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-restore-empty-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val purchasesListener = slot<PurchasesResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(purchasesListener)) } just Runs

            val manager = BillingManager(context, scope)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))

            manager.restorePurchases()

            purchasesListener.captured.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), emptyList())

            assertFalse(manager.isPremium.value)
            assertTrue(manager.billingUi.value.message.contains("No previous purchases found"))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun pendingUpdateDoesNotRevokeAlreadyEntitledUser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-pending-entitled-test", 0)
        prefs.edit().putBoolean(BillingManager.KEY_IS_PREMIUM, true).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any<PurchasesResponseListener>()) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs

            val manager = BillingManager(context, scope)
            assertTrue("Initial entitlement from securePrefs should be active", manager.isPremium.value)

            val pendingPurchase = mockk<Purchase>()
            every { pendingPurchase.purchaseState } returns Purchase.PurchaseState.PENDING
            every { pendingPurchase.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)

            // Delta update receives pending purchase
            manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), mutableListOf(pendingPurchase))

            // Invariant: Delta pending purchase must NOT revoke existing entitlement!
            assertTrue("Pending delta update must not revoke existing Premium entitlement!", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun delayedFullInventoryQueryCannotRevokeNewerVerifiedPurchase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-stale-inventory-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val purchasesListener = slot<PurchasesResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(purchasesListener)) } just Runs

            val manager = BillingManager(context, scope)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))

            // Background queryPurchasesAsync (Request A) is now captured in purchasesListener
            assertTrue("Request A queryPurchasesAsync captured", purchasesListener.isCaptured)

            // Before Request A completes, user purchases Premium via onPurchasesUpdated (newer verified purchase)
            val purchased = mockk<Purchase>()
            every { purchased.purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { purchased.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
            every { purchased.isAcknowledged } returns true
            manager.onPurchasesUpdated(result(BillingClient.BillingResponseCode.OK), mutableListOf(purchased))

            assertTrue("Premium must be granted by purchase update", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))

            // Now delayed background Request A returns with empty inventory
            purchasesListener.captured.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), emptyList())

            // Invariant GEM-01: A delayed full-inventory query must NOT revoke a newer verified entitlement!
            assertTrue("Delayed empty inventory must NOT revoke newer verified entitlement!", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun outOfOrderQueryABCompletionsDropStaleQueryA() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-out-of-order-test", 0)
        prefs.edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val capturedListeners = mutableListOf<PurchasesResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(capturedListeners)) } just Runs

            val manager = BillingManager(context, scope)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))
            assertEquals(1, capturedListeners.size) // Request A (ticket 1)

            // Trigger Request B (ticket 2)
            manager.restorePurchases()
            assertEquals(2, capturedListeners.size)

            val listenerA = capturedListeners[0]
            val listenerB = capturedListeners[1]

            // Request B completes first with active purchase
            val purchaseB = mockk<Purchase>()
            every { purchaseB.purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { purchaseB.products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
            every { purchaseB.isAcknowledged } returns true
            listenerB.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), listOf(purchaseB))

            assertTrue("Request B establishes premium entitlement", manager.isPremium.value)

            // Out-of-order: Request A completes afterwards with empty list
            listenerA.onQueryPurchasesResponse(result(BillingClient.BillingResponseCode.OK), emptyList())

            // Invariant: Stale Request A cannot revoke newer Request B entitlement
            assertTrue("Stale Request A cannot revoke newer entitlement", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun offlinePremiumCachePreservedWhenPlayUnavailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-offline-test", 0)
        // User already has entitlement saved in securePrefs from prior purchase
        prefs.edit().putBoolean(BillingManager.KEY_IS_PREMIUM, true).commit()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns false
            every { client.startConnection(capture(setup)) } just Runs

            val manager = BillingManager(context, scope)

            // Invariant: Offline launch must immediately reflect cached entitlement
            assertTrue("Offline premium cache must be active immediately", manager.isPremium.value)

            // Play connection fails
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE))

            // Entitlement must remain intact!
            assertTrue("Offline entitlement must survive Play service unavailability", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
            assertTrue(manager.billingUi.value.canRetry)

            // Restore attempt when offline fails safely without revoking
            manager.restorePurchases()
            assertTrue("Failed restore attempt must not revoke offline cached entitlement", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    @Test
    fun storeErrorDuringRestorePreservesEntitlementAndShowsUnavailable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("billing-store-error-test", 0)
        prefs.edit().putBoolean(BillingManager.KEY_IS_PREMIUM, true).commit()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val builder = mockk<BillingClient.Builder>()
        val client = mockk<BillingClient>()
        val setup = slot<BillingClientStateListener>()
        val purchasesListener = slot<PurchasesResponseListener>()
        mockkStatic(BillingClient::class)
        mockkStatic(EncryptedSharedPreferences::class)
        mockkConstructor(MasterKey.Builder::class)
        try {
            every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
            every { anyConstructed<MasterKey.Builder>().build() } returns mockk()
            every { EncryptedSharedPreferences.create(any<Context>(), any<String>(), any<MasterKey>(), any(), any()) } returns prefs
            every { BillingClient.newBuilder(any()) } returns builder
            every { builder.setListener(any()) } returns builder
            every { builder.enablePendingPurchases(any()) } returns builder
            every { builder.build() } returns client
            every { client.isReady } returns true
            every { client.startConnection(capture(setup)) } just Runs
            every { client.queryProductDetailsAsync(any(), any()) } just Runs
            every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(purchasesListener)) } just Runs

            val manager = BillingManager(context, scope)
            setup.captured.onBillingSetupFinished(result(BillingClient.BillingResponseCode.OK))

            manager.restorePurchases()

            // Play returns BILLING_UNAVAILABLE error on restore query
            purchasesListener.captured.onQueryPurchasesResponse(
                result(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE),
                emptyList()
            )

            // Must NOT revoke existing entitlement on error!
            assertTrue("Billing error must not revoke existing entitlement", manager.isPremium.value)
            assertTrue(prefs.getBoolean(BillingManager.KEY_IS_PREMIUM, false))
            assertTrue(manager.billingUi.value.message.contains("unavailable on this device"))
        } finally {
            scope.cancel()
            unmockkConstructor(MasterKey.Builder::class)
            unmockkStatic(EncryptedSharedPreferences::class)
            unmockkStatic(BillingClient::class)
        }
    }

    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()
}
