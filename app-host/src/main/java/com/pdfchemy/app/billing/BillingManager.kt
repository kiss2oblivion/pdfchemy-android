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

    private val _premiumPrice = MutableStateFlow("") // Empty until verified from Google Play
    val premiumPrice: StateFlow<String> = _premiumPrice.asStateFlow()

    private var premiumProductDetails: ProductDetails? = null
    private val _billingUi = MutableStateFlow(BillingUiState())
    val billingUi: StateFlow<BillingUiState> = _billingUi.asStateFlow()
    private var connecting = false
    private var connectionTimeout: Job? = null
    private val connectionRequests = com.pdfchemy.app.logic.LatestRequest()
    private val detailRequests = com.pdfchemy.app.logic.LatestRequest()
    private val purchaseRequests = com.pdfchemy.app.logic.LatestRequest()
    private fun available(message: String) = BillingUiState(
        canPurchase = premiumProductDetails != null && billingClient.isReady && _premiumPrice.value.isNotBlank(),
        loading = false,
        message = message,
        canRetry = premiumProductDetails == null || !billingClient.isReady || _premiumPrice.value.isBlank()
    )

    fun retry() {
        if (connecting) return
        if (billingClient.isReady) queryProductDetails() else connectToPlayBilling()
    }

    companion object {
        const val PREMIUM_PRODUCT_ID = "premium_upgrade"
        internal const val PREFS_NAME = "billing_prefs_secured"
        internal const val KEY_IS_PREMIUM = "is_premium_entitled"

        @Volatile
        private var cachedEntitled: Boolean? = null

        internal fun getSecurePrefs(context: Context): SharedPreferences {
            val appContext = context.applicationContext ?: context
            return runCatching {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    appContext,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }

        fun isUserEntitled(context: Context): Boolean {
            cachedEntitled?.let { return it }
            return try {
                val entitled = getSecurePrefs(context).getBoolean(KEY_IS_PREMIUM, false)
                cachedEntitled = entitled
                entitled
            } catch (_: Exception) {
                false
            }
        }

        internal fun updateCachedEntitlement(entitled: Boolean) {
            cachedEntitled = entitled
        }

        @androidx.annotation.VisibleForTesting
        internal fun resetCacheForTesting() {
            cachedEntitled = null
        }
    }
    
    private val securePrefs: SharedPreferences by lazy {
        getSecurePrefs(context)
    }

    init {
        // Fallback to secure cached value immediately for offline support
        val initialEntitled = securePrefs.getBoolean(KEY_IS_PREMIUM, false)
        _isPremium.value = initialEntitled
        updateCachedEntitlement(initialEntitled)
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
                _premiumPrice.value = ""
                detailRequests.invalidate()
                purchaseRequests.invalidate()
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
        _premiumPrice.value = ""
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
                val offer = details.oneTimePurchaseOfferDetails
                val formattedPrice = offer?.formattedPrice
                if (!formattedPrice.isNullOrBlank()) {
                    premiumProductDetails = details
                    _premiumPrice.value = formattedPrice
                    _billingUi.value = available("One-time purchase through Google Play.")
                } else {
                    premiumProductDetails = null
                    _premiumPrice.value = ""
                    _billingUi.value = available("Purchase price is unavailable from Google Play. Retry when your connection is available.")
                }
            } else {
                premiumProductDetails = null
                _premiumPrice.value = ""
                _billingUi.value = available("Purchase details are unavailable. Check Google Play and retry.")
            }
        }
    }

    fun restorePurchases() {
        if (!billingClient.isReady) {
            _billingUi.value = BillingUiState(
                loading = false,
                message = "Google Play is connecting. Tap retry if it does not connect.",
                canRetry = true
            )
            connectToPlayBilling()
            return
        }
        _billingUi.value = BillingUiState(loading = true, message = "Checking previous purchases…")
        queryPurchases(isUserInitiated = true)
    }

    private fun queryPurchases(isUserInitiated: Boolean = false) {
        if (!billingClient.isReady) {
            return
        }
        val ticket = purchaseRequests.begin()
        
        // Query for existing in-app purchases (non-consumables)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
            
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (!purchaseRequests.isCurrent(ticket)) {
                com.pdfchemy.app.utils.AppLogger.d("Ignoring stale onQueryPurchasesResponse for ticket $ticket")
                return@queryPurchasesAsync
            }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList, isFullInventory = true, requestTicket = ticket)
                if (isUserInitiated) {
                    val hasActive = purchasesList.any {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                            it.products.contains(PREMIUM_PRODUCT_ID)
                    }
                    _billingUi.value = if (hasActive) {
                        available("Premium entitlement restored successfully.")
                    } else {
                        available("No previous purchases found for this Google account.")
                    }
                }
            } else if (isUserInitiated) {
                val msg = when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.NETWORK_ERROR,
                    BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                        "Network error connecting to Google Play. Check connection and retry."
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                    BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                        "Google Play billing is unavailable on this device."
                    else ->
                        "Could not restore purchases. Check Google Play and retry."
                }
                _billingUi.value = available(msg)
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        // Monotonic invalidation: Invalidate any pending background full-inventory queries
        // so that a delayed empty inventory cannot revoke a newer purchase or update.
        purchaseRequests.invalidate()

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    _billingUi.value = if (purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING })
                        BillingUiState(loading = false, message = "Payment is pending. Premium unlocks when Google Play confirms it.")
                    else available("Purchase received. Google Play is confirming your entitlement.")
                    processPurchases(purchases, isFullInventory = false, requestTicket = null)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingUi.value = available("Purchase cancelled. You can continue using PDFchemy.")
            }
            BillingClient.BillingResponseCode.NETWORK_ERROR,
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
                _billingUi.value = available("Network error communicating with Google Play. Check your connection and try again.")
            }
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> {
                _billingUi.value = available("Google Play billing is unavailable on this device.")
            }
            else -> {
                _billingUi.value = available("Purchase could not complete. Check Google Play and try again.")
            }
        }
    }

    private fun processPurchases(
        purchases: List<Purchase>,
        isFullInventory: Boolean = false,
        requestTicket: Long? = null
    ) {
        coroutineScope.launch {
            if (requestTicket != null && !purchaseRequests.isCurrent(requestTicket)) {
                com.pdfchemy.app.utils.AppLogger.d("Ignoring stale processPurchases for ticket $requestTicket")
                return@launch
            }
            var hasPremiumInBatch = false
            val unacknowledged = mutableListOf<Purchase>()
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PREMIUM_PRODUCT_ID)
                ) {
                    hasPremiumInBatch = true
                    if (!purchase.isAcknowledged) {
                        unacknowledged.add(purchase)
                    }
                }
            }
            if (hasPremiumInBatch) {
                securePrefs.edit().putBoolean(KEY_IS_PREMIUM, true).apply()
                updateCachedEntitlement(true)
                _isPremium.value = true
                AdManager.clearAd()
            } else if (isFullInventory) {
                if (requestTicket == null || purchaseRequests.isCurrent(requestTicket)) {
                    securePrefs.edit().putBoolean(KEY_IS_PREMIUM, false).apply()
                    updateCachedEntitlement(false)
                    _isPremium.value = false
                }
            }

            for (purchase in unacknowledged) {
                acknowledgePurchase(purchase)
            }
        }
    }

    internal suspend fun acknowledgePurchase(purchase: Purchase, maxRetries: Int = 3, initialDelayMs: Long = 1000L) {
        withContext(Dispatchers.IO) {
            val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            var attempt = 0
            var delayMs = initialDelayMs
            while (attempt < maxRetries) {
                attempt++
                try {
                    val result = billingClient.acknowledgePurchase(acknowledgePurchaseParams)
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        return@withContext
                    }
                    com.pdfchemy.app.utils.AppLogger.w("BillingManager: Purchase acknowledgement attempt $attempt failed with code: ${result.responseCode}")
                } catch (e: Exception) {
                    com.pdfchemy.app.utils.AppLogger.e("BillingManager: Error on purchase acknowledgement attempt $attempt", e)
                }
                if (attempt < maxRetries && delayMs > 0) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
    }

    fun launchPurchaseFlow(activity: Activity) {
        val productDetails = premiumProductDetails
        if (productDetails == null || !billingClient.isReady || !_billingUi.value.canPurchase || _premiumPrice.value.isBlank()) {
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
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            val msg = when (result.responseCode) {
                BillingClient.BillingResponseCode.USER_CANCELED ->
                    "Purchase cancelled. You can continue using PDFchemy."
                BillingClient.BillingResponseCode.NETWORK_ERROR,
                BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                    "Network error opening checkout. Check your connection and try again."
                else ->
                    "Google Play could not open checkout. Check your connection and try again."
            }
            _billingUi.value = available(msg)
        }
    }
}
