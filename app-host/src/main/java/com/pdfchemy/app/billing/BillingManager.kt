package com.pdfchemy.app.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

internal enum class AcknowledgementOutcome { ACKNOWLEDGED, RETRYABLE, RECONCILE }

data class BillingUiState(val canPurchase: Boolean = false, val loading: Boolean = true,
    val message: String = "Connecting to Google Play…", val canRetry: Boolean = false,
    val entitlementStorageFailed: Boolean = false)

class BillingManager(
    private val context: Context,
    coroutineScope: CoroutineScope,
    private val acknowledgementDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recoveryOnly: Boolean = false,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO
) : PurchasesUpdatedListener {
    private val managerJob = SupervisorJob(coroutineScope.coroutineContext[Job])
    private val coroutineScope = CoroutineScope(coroutineScope.coroutineContext + managerJob)
    private val entitlementStore: EntitlementStore by lazy { EntitlementStores.open(context) }
    private val persistence = EntitlementPersistenceCoordinator(entitlementStore, this.coroutineScope, storageDispatcher)
    private lateinit var billingClient: BillingClient
    
    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _premiumPrice = MutableStateFlow("") // Empty until verified from Google Play
    val premiumPrice: StateFlow<String> = _premiumPrice.asStateFlow()

    private var premiumProductDetails: ProductDetails? = null
    private val _billingUi = MutableStateFlow(BillingUiState())
    val billingUi: StateFlow<BillingUiState> = combine(_billingUi, persistence.state, _isPremium) { ui, storage, premium ->
        if (!storage.failed) ui else ui.copy(
            entitlementStorageFailed = true,
            loading = storage.saving,
            canPurchase = false,
            canRetry = !storage.saving,
            message = when {
                storage.readFailed -> "Saved purchase status could not be read. Connect to Google Play and restore purchases."
                premium -> "Premium is active, but offline access could not be saved. Retry saving purchase status."
                else -> "Purchase status changed, but the offline cache could not be updated. Retry saving purchase status."
            }
        )
    }.stateIn(this.coroutineScope, SharingStarted.Eagerly, _billingUi.value)
    private var connecting = false
    private var connectionTimeout: Job? = null
    private var detailsTimeout: Job? = null
    private var inventoryTimeout: Job? = null
    private var restorePending = false
    private var restoreActive = false
    private var retryRestore = false
    private var restoreMessage: String? = null
    private val connectionRequests = com.pdfchemy.app.logic.LatestRequest()
    private val detailRequests = com.pdfchemy.app.logic.LatestRequest()
    private val purchaseRequests = com.pdfchemy.app.logic.LatestRequest()
    private var closed = false
    private var inventoryInFlight: Long? = null
    private var refreshAfterInventory = false
    private var foregroundRefresh: Job? = null
    private val acknowledgementTasks = ConcurrentHashMap<String, CompletableDeferred<AcknowledgementOutcome>>()
    private val acknowledgedTokens = LinkedHashSet<String>()
    private val recoveryWaiters = mutableSetOf<CompletableDeferred<AcknowledgementOutcome>>()
    private val application = context.applicationContext as? Application
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = refreshOnForeground()
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun refreshOnForeground() {
        if (closed || foregroundRefresh?.isActive == true) return
        foregroundRefresh = coroutineScope.launch {
            // Coalesce recreation/resume callbacks before touching the one BillingClient.
            yield()
            if (closed || connecting) return@launch
            when {
                !billingClient.isReady -> connectToPlayBilling()
                inventoryInFlight != null -> refreshAfterInventory = true
                else -> queryPurchases()
            }
        }
    }

    internal fun close() {
        if (closed) return
        closed = true
        application?.unregisterActivityLifecycleCallbacks(activityCallbacks)
        connectionRequests.invalidate()
        detailRequests.invalidate()
        purchaseRequests.invalidate()
        foregroundRefresh?.cancel()
        connectionTimeout?.cancel()
        detailsTimeout?.cancel()
        inventoryTimeout?.cancel()
        recoveryWaiters.toList().forEach { it.complete(AcknowledgementOutcome.RETRYABLE) }
        recoveryWaiters.clear()
        synchronized(sessionLock) { sessions.removeAll { it.get() == null || it.get() === this } }
        managerJob.cancel()
        // Closing a disconnected Play service must not prevent owner cleanup.
        runCatching { billingClient.endConnection() }
    }
    private fun available(message: String) = BillingUiState(
        canPurchase = premiumProductDetails != null && billingClient.isReady && _premiumPrice.value.isNotBlank(),
        loading = false,
        message = message,
        canRetry = premiumProductDetails == null || !billingClient.isReady || _premiumPrice.value.isBlank()
    )

    private fun publishDetailsStatus(message: String) {
        if (restorePending || restoreActive) return
        _billingUi.value = available(restoreMessage ?: message).let {
            if (retryRestore) it.copy(canRetry = true) else it
        }
    }

    private fun finishRecoveryWaiters(outcome: AcknowledgementOutcome) {
        recoveryWaiters.toList().forEach { it.complete(outcome) }
    }

    fun retry() {
        if (closed) return
        if (persistence.state.value.failed && persistence.hasPendingWrite()) {
            persistence.retry()
            return
        }
        if (persistence.state.value.readFailed) {
            restorePurchases()
            return
        }
        if (connecting) return
        if (retryRestore || restorePending) {
            restorePurchases()
            return
        }
        if (billingClient.isReady) queryProductDetails() else connectToPlayBilling()
    }

    companion object {
        const val PREMIUM_PRODUCT_ID = "premium_upgrade"
        internal const val PREFS_NAME = "billing_prefs_secured"
        internal const val KEY_IS_PREMIUM = "is_premium_entitled"

        @Volatile
        private var cachedEntitled: Boolean? = null
        private val sessionLock = Any()
        private val sessions = mutableListOf<WeakReference<BillingManager>>()

        internal fun activeForRecovery(): BillingManager? = synchronized(sessionLock) {
            sessions.removeAll { it.get()?.closed != false }
            sessions.mapNotNull { it.get() }.firstOrNull { !it.recoveryOnly }
                ?: sessions.firstOrNull()?.get()
        }

        fun isUserEntitled(context: Context): Boolean {
            cachedEntitled?.let { return it }
            return try {
                val entitled = EntitlementStores.open(context).read()
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
            EntitlementPersistenceCoordinator.resetForTesting()
        }
    }
    
    init {
        // Fallback to secure cached value immediately for offline support
        val initialEntitled = cachedEntitled ?: try { entitlementStore.read() } catch (_: Exception) {
            persistence.readFailed()
            false
        }
        _isPremium.value = initialEntitled
        updateCachedEntitlement(initialEntitled)
        // Main/UI and JobService construction are serialized on Android Main. Retire a
        // temporary recovery session before another owner creates a BillingClient.
        synchronized(sessionLock) {
            sessions.mapNotNull { it.get() }.filter { it.recoveryOnly }.forEach { it.close() }
            sessions.add(WeakReference(this))
        }
        initializeBillingClient()
        if (!recoveryOnly) application?.registerActivityLifecycleCallbacks(activityCallbacks)
        coroutineScope.coroutineContext[Job]?.invokeOnCompletion { close() }
        BillingAcknowledgementRecovery.ensureScheduled(context)
        persistence.retry()
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
        if (closed || connecting) return
        connecting = true
        val ticket = connectionRequests.begin()
        _billingUi.value = BillingUiState()
        connectionTimeout?.cancel()
        connectionTimeout = coroutineScope.launch {
            delay(15_000)
            if (connectionRequests.isCurrent(ticket) && connecting) {
                connecting = false
                _billingUi.value = BillingUiState(loading = false, message = "Google Play did not respond. Check your connection and retry.", canRetry = true)
                finishRecoveryWaiters(AcknowledgementOutcome.RETRYABLE)
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
                    if (!recoveryOnly) queryProductDetails()
                } else {
                    _billingUi.value = BillingUiState(loading = false, message = "Google Play billing is unavailable. Check your connection and retry.", canRetry = true)
                    finishRecoveryWaiters(AcknowledgementOutcome.RETRYABLE)
                }
            }

            override fun onBillingServiceDisconnected() {
                if (!connectionRequests.isCurrent(ticket)) return
                connecting = false
                premiumProductDetails = null
                _premiumPrice.value = ""
                detailRequests.invalidate()
                purchaseRequests.invalidate()
                inventoryTimeout?.cancel()
                detailsTimeout?.cancel()
                restorePending = restorePending || restoreActive
                restoreActive = false
                inventoryInFlight = null
                refreshAfterInventory = false
                _billingUi.value = available("Google Play disconnected. Retry when your connection is available.")
                finishRecoveryWaiters(AcknowledgementOutcome.RETRYABLE)
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
        if (closed) return
        val ticket = detailRequests.begin()
        premiumProductDetails = null
        _premiumPrice.value = ""
        if (!restorePending && !restoreActive && restoreMessage == null)
            _billingUi.value = BillingUiState(message = "Loading purchase details…")
        detailsTimeout?.cancel()
        detailsTimeout = coroutineScope.launch {
            delay(15_000)
            if (detailRequests.isCurrent(ticket) && premiumProductDetails == null)
                publishDetailsStatus("Google Play did not return purchase details. Retry when your connection is available.")
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
            detailsTimeout?.cancel()
            val list = productDetailsResult.productDetailsList
            val details = list.firstOrNull { it.productId == PREMIUM_PRODUCT_ID }
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && details != null) {
                val offer = details.oneTimePurchaseOfferDetails
                val formattedPrice = offer?.formattedPrice
                if (!formattedPrice.isNullOrBlank()) {
                    premiumProductDetails = details
                    _premiumPrice.value = formattedPrice
                    publishDetailsStatus("One-time purchase through Google Play.")
                } else {
                    premiumProductDetails = null
                    _premiumPrice.value = ""
                    publishDetailsStatus("Purchase price is unavailable from Google Play. Retry when your connection is available.")
                }
            } else {
                premiumProductDetails = null
                _premiumPrice.value = ""
                publishDetailsStatus("Purchase details are unavailable. Check Google Play and retry.")
            }
        }
    }

    fun restorePurchases() {
        if (closed || restoreActive) return
        restorePending = true
        retryRestore = false
        restoreMessage = null
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
        if (closed || !billingClient.isReady) {
            return
        }
        val ticket = purchaseRequests.begin()
        inventoryInFlight = ticket
        val manualRestore = isUserInitiated || restorePending || restoreActive
        if (manualRestore) {
            restorePending = false
            restoreActive = true
            _billingUi.value = BillingUiState(message = "Checking previous purchases…")
        }
        inventoryTimeout?.cancel()
        inventoryTimeout = coroutineScope.launch {
            delay(15_000L)
            if (!purchaseRequests.isCurrent(ticket) || inventoryInFlight != ticket) return@launch
            purchaseRequests.invalidate()
            inventoryInFlight = null
            restoreActive = false
            if (manualRestore) {
                retryRestore = true
                restoreMessage = "Google Play did not return your purchases. Check your connection and retry."
                _billingUi.value = available(restoreMessage!!).copy(canRetry = true)
            }
            finishRecoveryWaiters(AcknowledgementOutcome.RETRYABLE)
            if (refreshAfterInventory) {
                refreshAfterInventory = false
                refreshOnForeground()
            }
        }
        
        // Query for existing in-app purchases (non-consumables)
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
            
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (!purchaseRequests.isCurrent(ticket)) {
                com.pdfchemy.app.utils.AppLogger.d("Ignoring stale onQueryPurchasesResponse for ticket $ticket")
                return@queryPurchasesAsync
            }
            inventoryTimeout?.cancel()
            inventoryInFlight = null
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                processPurchases(purchasesList, isFullInventory = true, requestTicket = ticket) {
                    if (manualRestore && purchaseRequests.isCurrent(ticket)) {
                        restoreActive = false
                        retryRestore = false
                        val premium = purchasesList.filter { it.products.contains(PREMIUM_PRODUCT_ID) }
                        restoreMessage = when {
                            premium.any { it.purchaseState == Purchase.PurchaseState.PURCHASED } ->
                                "Premium entitlement restored successfully."
                            premium.any { it.purchaseState == Purchase.PurchaseState.PENDING } ->
                                "Payment is pending. Premium unlocks when Google Play confirms it."
                            else -> "No previous purchases found for this Google account."
                        }
                        _billingUi.value = available(restoreMessage!!)
                    }
                }
            } else {
                finishRecoveryWaiters(AcknowledgementOutcome.RETRYABLE)
                if (manualRestore) {
                    restoreActive = false
                    retryRestore = true
                    val msg = when (billingResult.responseCode) {
                        BillingClient.BillingResponseCode.NETWORK_ERROR,
                        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                            "Network error connecting to Google Play. Check connection and retry."
                        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED ->
                            "Google Play billing is unavailable on this device."
                        else ->
                            "Could not restore purchases. Check Google Play and retry."
                    }
                    restoreMessage = msg
                    _billingUi.value = available(msg).copy(canRetry = true)
                }
            }
            if (refreshAfterInventory) {
                refreshAfterInventory = false
                refreshOnForeground()
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (closed) return
        // Monotonic invalidation: Invalidate any pending background full-inventory queries
        // so that a delayed empty inventory cannot revoke a newer purchase or update.
        purchaseRequests.invalidate()
        inventoryTimeout?.cancel()
        restoreActive = false
        restorePending = false
        retryRestore = false
        restoreMessage = null
        inventoryInFlight = null
        refreshAfterInventory = false

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
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
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

    internal suspend fun reconcileDelivery(): AcknowledgementOutcome {
        if (closed) return AcknowledgementOutcome.RETRYABLE
        val waiter = CompletableDeferred<AcknowledgementOutcome>()
        recoveryWaiters.add(waiter)
        try {
            when {
                connecting -> Unit // Setup will query the inventory.
                !billingClient.isReady -> connectToPlayBilling()
                inventoryInFlight == null -> queryPurchases()
            }
            return waiter.await()
        } finally {
            recoveryWaiters.remove(waiter)
        }
    }

    private fun processPurchases(
        purchases: List<Purchase>,
        isFullInventory: Boolean = false,
        requestTicket: Long? = null,
        onEntitlementProcessed: (() -> Unit)? = null
    ) {
        coroutineScope.launch {
            if (closed || (requestTicket != null && !purchaseRequests.isCurrent(requestTicket))) {
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
                updateCachedEntitlement(true)
                _isPremium.value = true
                AdManager.clearAd()
                persistence.save(true)
            } else if (isFullInventory) {
                if (requestTicket == null || purchaseRequests.isCurrent(requestTicket)) {
                    updateCachedEntitlement(false)
                    _isPremium.value = false
                    persistence.save(false)
                }
            }

            onEntitlementProcessed?.invoke()

            var outcome = AcknowledgementOutcome.ACKNOWLEDGED
            for (purchase in unacknowledged) {
                if (closed) return@launch
                val acknowledgement = acknowledgePurchase(purchase)
                if (acknowledgement == AcknowledgementOutcome.RETRYABLE) outcome = acknowledgement
                else if (acknowledgement == AcknowledgementOutcome.RECONCILE && outcome != AcknowledgementOutcome.RETRYABLE)
                    outcome = acknowledgement
            }
            if (isFullInventory && (requestTicket == null || purchaseRequests.isCurrent(requestTicket))) {
                when (outcome) {
                    AcknowledgementOutcome.ACKNOWLEDGED -> BillingAcknowledgementRecovery.complete(context)
                    AcknowledgementOutcome.RECONCILE -> BillingAcknowledgementRecovery.pauseAutomaticRetry(context)
                    AcknowledgementOutcome.RETRYABLE -> Unit
                }
                recoveryWaiters.toList().forEach { it.complete(outcome) }
            } else if (outcome == AcknowledgementOutcome.RECONCILE) {
                BillingAcknowledgementRecovery.pauseAutomaticRetry(context)
            }
        }
    }

    internal suspend fun acknowledgePurchase(
        purchase: Purchase, maxRetries: Int = 3, initialDelayMs: Long = 1000L
    ): AcknowledgementOutcome {
        val token = purchase.purchaseToken
        synchronized(acknowledgedTokens) {
            if (token in acknowledgedTokens) return AcknowledgementOutcome.ACKNOWLEDGED
        }
        val task = CompletableDeferred<AcknowledgementOutcome>()
        val existing = acknowledgementTasks.putIfAbsent(token, task)
        if (existing != null) return existing.await()
        try {
            val outcome = withContext(acknowledgementDispatcher) {
                // Schedule before the attempt so process death cannot lose an unacknowledged order.
                BillingAcknowledgementRecovery.markPending(context)
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(token)
                    .build()
                var attempt = 0
                var delayMs = initialDelayMs
                while (attempt < maxRetries) {
                    attempt++
                    try {
                        val result = withTimeoutOrNull(15_000L) {
                            billingClient.acknowledgePurchase(acknowledgePurchaseParams)
                        }
                        if (result == null) return@withContext AcknowledgementOutcome.RETRYABLE
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            return@withContext AcknowledgementOutcome.ACKNOWLEDGED
                        }
                        com.pdfchemy.app.utils.AppLogger.w("BillingManager: Purchase acknowledgement attempt $attempt failed with code: ${result.responseCode}")
                        if (result.responseCode !in setOf(
                                BillingClient.BillingResponseCode.NETWORK_ERROR,
                                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                                BillingClient.BillingResponseCode.ERROR
                            )) return@withContext AcknowledgementOutcome.RECONCILE
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // No purchase/token or provider exception messages enter application logs.
                        com.pdfchemy.app.utils.AppLogger.w("BillingManager: Purchase acknowledgement transport failed")
                    }
                    if (attempt < maxRetries && delayMs > 0) {
                        delay(delayMs)
                        delayMs *= 2
                    }
                }
                AcknowledgementOutcome.RETRYABLE
            }
            if (outcome == AcknowledgementOutcome.ACKNOWLEDGED) synchronized(acknowledgedTokens) {
                acknowledgedTokens.add(token)
                if (acknowledgedTokens.size > 64) acknowledgedTokens.remove(acknowledgedTokens.first())
            }
            if (outcome == AcknowledgementOutcome.RECONCILE)
                BillingAcknowledgementRecovery.pauseAutomaticRetry(context)
            task.complete(outcome)
            return outcome
        } catch (failure: Throwable) {
            task.completeExceptionally(failure)
            throw failure
        } finally {
            acknowledgementTasks.remove(token, task)
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
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
                    "Network error opening checkout. Check your connection and try again."
                else ->
                    "Google Play could not open checkout. Check your connection and try again."
            }
            _billingUi.value = available(msg)
        }
    }
}
