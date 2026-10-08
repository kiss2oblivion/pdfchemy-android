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
    @Test fun failedSetupAndMissingDetailsAreRetryableAndCancellationDoesNotGrantPremium() {
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
            manager.retry()
            val details = mockk<ProductDetails>()
            every { details.productId } returns BillingManager.PREMIUM_PRODUCT_ID
            val found = mockk<QueryProductDetailsResult>()
            every { found.productDetailsList } returns listOf(details)
            products.captured.onProductDetailsResponse(result(BillingClient.BillingResponseCode.OK), found)
            assertTrue(manager.billingUi.value.canPurchase)
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
    private fun result(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()
}
