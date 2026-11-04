package com.pdfchemy.app.billing

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Exercises the production manager with controllable Play callbacks and virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class BillingTestSession : AutoCloseable {
    val dispatcher = StandardTestDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val app = ApplicationProvider.getApplicationContext<Application>()
    val client = mockk<BillingClient>(relaxed = true)
    val connections = mutableListOf<BillingClientStateListener>()
    val inventories = mutableListOf<PurchasesResponseListener>()
    val details = mutableListOf<ProductDetailsResponseListener>()
    var ready = false
    var stored = false
    var write: (Boolean) -> Boolean = { stored = it; true }
    val manager: BillingManager

    init {
        BillingManager.resetCacheForTesting()
        app.getSharedPreferences("billing_delivery_recovery", 0).edit().clear().commit()
        app.getSystemService(JobScheduler::class.java).cancel(BillingAcknowledgementRecovery.JOB_ID)
        mockkStatic(BillingClient::class)
        mockkObject(EntitlementStores)
        mockkObject(AdManager)
        every { AdManager.clearAd() } just Runs
        every { EntitlementStores.open(any()) } returns object : EntitlementStore {
            override fun read() = stored
            override fun write(entitled: Boolean) = write.invoke(entitled)
        }
        val builder = mockk<BillingClient.Builder>()
        every { BillingClient.newBuilder(any()) } returns builder
        every { builder.setListener(any()) } returns builder
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.build() } returns client
        every { client.isReady } answers { ready }
        every { client.startConnection(capture(connections)) } just Runs
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), capture(inventories)) } just Runs
        every { client.queryProductDetailsAsync(any(), capture(details)) } just Runs
        every { client.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(result())
        }
        manager = BillingManager(app, scope, acknowledgementDispatcher = dispatcher)
    }

    fun result(code: Int = BillingClient.BillingResponseCode.OK) =
        BillingResult.newBuilder().setResponseCode(code).build()

    fun order(state: Int = Purchase.PurchaseState.PURCHASED, acknowledged: Boolean = true) = mockk<Purchase> {
        every { purchaseState } returns state
        every { products } returns listOf(BillingManager.PREMIUM_PRODUCT_ID)
        every { purchaseToken } returns "test-premium-token"
        every { isAcknowledged } returns acknowledged
    }

    fun connect(orders: List<Purchase> = emptyList(), withDetails: Boolean = true) {
        ready = true
        connections.last().onBillingSetupFinished(result())
        inventories.last().onQueryPurchasesResponse(result(), orders)
        if (withDetails) completeDetails()
        runCurrent()
    }

    fun completeDetails() {
        val product = mockk<ProductDetails>(relaxed = true)
        val offer = mockk<ProductDetails.OneTimePurchaseOfferDetails>()
        every { product.productId } returns BillingManager.PREMIUM_PRODUCT_ID
        every { product.productType } returns BillingClient.ProductType.INAPP
        every { product.oneTimePurchaseOfferDetails } returns offer
        every { offer.formattedPrice } returns "EUR 4.99"
        val result = mockk<QueryProductDetailsResult>()
        every { result.productDetailsList } returns listOf(product)
        details.last().onProductDetailsResponse(result(), result)
    }

    fun runCurrent() = dispatcher.scheduler.runCurrent()
    fun advance(millis: Long) { dispatcher.scheduler.advanceTimeBy(millis); runCurrent() }

    override fun close() {
        scope.cancel()
        runCurrent()
        app.getSystemService(JobScheduler::class.java).cancel(BillingAcknowledgementRecovery.JOB_ID)
        unmockkAll()
        BillingManager.resetCacheForTesting()
    }
}
