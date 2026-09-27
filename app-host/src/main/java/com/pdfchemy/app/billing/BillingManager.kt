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
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    // The BillingClient is ready. You can query purchases here.
                    queryPurchases()
                    queryProductDetails()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Retry connection after a brief delay
                coroutineScope.launch {
                    kotlinx.coroutines.delay(3000)
                    if (!billingClient.isReady) {
                        connectToPlayBilling()
                    }
                }
            }
        })
    }

    private fun queryProductDetails() {
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
            val list = productDetailsResult.productDetailsList
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !list.isNullOrEmpty()) {
                val details = list[0]
                premiumProductDetails = details
                // We intentionally don't update _premiumPrice.value here because the user wants it hardcoded to $4.99
            }
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
            processPurchases(purchases)
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            // Handle an error caused by a user cancelling the purchase flow.
        } else {
            // Handle any other error codes.
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
        val productDetails = premiumProductDetails ?: return

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        billingClient.launchBillingFlow(activity, billingFlowParams)
    }
}
