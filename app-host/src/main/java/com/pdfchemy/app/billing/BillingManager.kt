package com.pdfchemy.app.billing

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

data class BillingUiState(val canPurchase: Boolean = false, val loading: Boolean = true,
    val message: String = "Connecting to Google Play…", val canRetry: Boolean = false)

class BillingManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) : PurchasesUpdatedListener {

    private lateinit var billingClient: BillingClient
    
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _premiumPrice = MutableStateFlow("$4.99") // Default fallback
    val premiumPrice: StateFlow<String> = _premiumPrice.asStateFlow()

    private var premiumProductDetails: ProductDetails? = null
    private val _billingUi = MutableStateFlow(BillingUiState())
    val billingUi: StateFlow<BillingUiState> = _billingUi.asStateFlow()
    private var connecting = false
    private var connectionTimeout: Job? = null
    private val connectionRequests = com.pdfchemy.app.logic.LatestRequest()
    private val detailRequests = com.pdfchemy.app.logic.LatestRequest()
    private fun available(message: String) = BillingUiState(
        canPurchase = premiumProductDetails != null && billingClient.isReady, loading = false,
        message = message, canRetry = premiumProductDetails == null || !billingClient.isReady)

    fun retry() {
        if (connecting) return
        if (billingClient.isReady) queryProductDetails() else connectToPlayBilling()
    }

    companion object {
        const val PREMIUM_PRODUCT_ID = "premium_upgrade"
        private const val PREFS_NAME = "billing_prefs_secured"
        private const val KEY_IS_PREMIUM = "is_premium_entitled"
    }
    
    private val securePrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        // Fallback to secure cached value immediately for offline support
        _isPremium.value = securePrefs.getBoolean(KEY_IS_PREMIUM, false)
        initializeBillingClient()
    }

    private fun initializeBillingClient() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
            )
            .build()

        connectToPlayBilling()
    }

    private fun connectToPlayBilling() {
        if (connecting) return
        connecting = true
        val ticket = connectionRequests.begin()
        _billingUi.value = BillingUiState()
        connectionTimeout?.cancel()
        connectionTimeout = coroutineScope.launch {
            delay(15_000)
            if (connectionRequests.isCurrent(ticket) && connecting) {
                connecting = false
                _billingUi.value = BillingUiState(loading = false, message = "Google Play did not respond. Check your connection and retry.", canRetry = true)
            }
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (!connectionRequests.isCurrent(ticket)) return
                connecting = false
                connectionTimeout?.cancel()
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here.
                    queryPurchases()
                    queryProductDetails()
                } else {
                    _billingUi.value = BillingUiState(loading = false, message = "Google Play billing is unavailable. Check your connection and retry.", canRetry = true)
                }
            }

            override fun onBillingServiceDisconnected() {
                if (!connectionRequests.isCurrent(ticket)) return
                connecting = false
                premiumProductDetails = null
                detailRequests.invalidate()
                _billingUi.value = available("Google Play disconnected. Retry when your connection is available.")
                // Retry connection after a brief delay
                coroutineScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (!billingClient.isReady && connectionRequests.isCurrent(ticket)) {
                        connectToPlayBilling()
                    }
                }
            }
        })
    }

    private fun queryProductDetails() {
        val ticket = detailRequests.begin()
        premiumProductDetails = null
        _billingUi.value = BillingUiState(message = "Loading purchase details…")
        connectionTimeout?.cancel()
        connectionTimeout = coroutineScope.launch {
            delay(15_000)
            if (detailRequests.isCurrent(ticket) && premiumProductDetails == null)
                _billingUi.value = available("Google Play did not return purchase details. Retry when your connection is available.")
        }
        val queryProductDetailsParams =
            QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(PREMIUM_PRODUCT_ID)
                            .setProductType(BillingClient.ProductType.INAPP)
                            .build()
                    )
                )
                .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, productDetailsResult ->
            if (!detailRequests.isCurrent(ticket)) return@queryProductDetailsAsync
            connectionTimeout?.cancel()
            val list = productDetailsResult.productDetailsList
            val details = list.firstOrNull { it.productId == PREMIUM_PRODUCT_ID }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && details != null) {
                premiumProductDetails = details
                _billingUi.value = available("One-time purchase through Google Play.")
                // We intentionally don't update _premiumPrice.value here because the user wants it hardcoded to $4.99
            } else _billingUi.value = available("Purchase details are unavailable. Check Google Play and retry.")
        }
    }

    private fun queryPurchases() {
        if (!billingClient.isReady) {
            return
        }
        
        // Query for existing in-app purchases (non-consumables)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
            
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            _billingUi.value = if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING })
                BillingUiState(loading = false, message = "Payment is pending. Premium unlocks when Google Play confirms it.")
            else available("Purchase received. Google Play is confirming your entitlement.")
            processPurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            _billingUi.value = available("Purchase cancelled. You can continue using PDFchemy.")
        } else {
            _billingUi.value = available("Purchase could not complete. Check Google Play and try again.")
        }
    }

    private fun processPurchases(purchases: List<Purchase>) {
        coroutineScope.launch {
            var hasPremium = false
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PREMIUM_PRODUCT_ID)
                ) {
                    hasPremium = true
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }
                }
            }
            // Centralize entitlement logic: Update cache with source of truth from Play Store
            securePrefs.edit().putBoolean(KEY_IS_PREMIUM, hasPremium).apply()
            _isPremium.value = hasPremium
        }
    }

    private suspend fun acknowledgePurchase(purchase: Purchase) {
        withContext(Dispatchers.IO) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(acknowledgePurchaseParams)
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val productDetails = premiumProductDetails
        if (productDetails == null || !billingClient.isReady || !_billingUi.value.canPurchase) {
            _billingUi.value = available("Purchase is not ready. Check Google Play and retry.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        _billingUi.value = BillingUiState(loading = true, message = "Opening Google Play…")
        val result = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK)
            _billingUi.value = available("Google Play could not open checkout. Check your connection and try again.")
    }
}
