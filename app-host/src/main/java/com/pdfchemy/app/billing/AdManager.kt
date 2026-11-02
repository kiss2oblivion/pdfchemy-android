package com.pdfchemy.app.billing

import android.app.Activity
import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.pdfchemy.app.BuildConfig
import com.pdfchemy.app.utils.AppLogger

object AdManager {
    private val lock = Any()
    private var interstitialAd: InterstitialAd? = null
    private var isLoading = false
    private var currentGeneration: Long = 0L
    private var lastAdShownTime: Long = 0L
    private var adLoadedTimestamp: Long = 0L

    internal const val COOLDOWN_MILLIS = 60_000L // 60 seconds minimum cooldown
    internal const val AD_EXPIRATION_MILLIS = 55 * 60 * 1000L // 55 minutes TTL (Google Mobile Ads SDK limit is 60m)

    @Volatile
    private var premiumOverride: Boolean? = null
    @Volatile
    private var entitlementSource: (() -> Boolean)? = null

    @androidx.annotation.VisibleForTesting
    internal var clock: () -> Long = { System.currentTimeMillis() }

    data class AdDebugCounters(
        var operationComplete: Long = 0L,
        var eligibleOpportunity: Long = 0L,
        var skippedCooldown: Long = 0L,
        var skippedPremium: Long = 0L,
        var skippedNoConsent: Long = 0L,
        var skippedReaderActive: Long = 0L,
        var skippedActivityUnavailable: Long = 0L,
        var skippedNotReady: Long = 0L,
        var loadRequested: Long = 0L,
        var loaded: Long = 0L,
        var loadFailed: Long = 0L,
        var lastLoadErrorCode: Int? = null,
        var showAttempted: Long = 0L,
        var showStarted: Long = 0L,
        var onAdImpression: Long = 0L,
        var dismissed: Long = 0L,
        var failedToShow: Long = 0L,
        var lastShowErrorCode: Int? = null,
        var expired: Long = 0L,
        var reloadsTriggered: Long = 0L
    )

    private val _debugCounters = AdDebugCounters()
    val debugCounters: AdDebugCounters get() = _debugCounters

    fun resetDebugCounters() {
        synchronized(lock) {
            _debugCounters.operationComplete = 0L
            _debugCounters.eligibleOpportunity = 0L
            _debugCounters.skippedCooldown = 0L
            _debugCounters.skippedPremium = 0L
            _debugCounters.skippedNoConsent = 0L
            _debugCounters.skippedReaderActive = 0L
            _debugCounters.skippedActivityUnavailable = 0L
            _debugCounters.skippedNotReady = 0L
            _debugCounters.loadRequested = 0L
            _debugCounters.loaded = 0L
            _debugCounters.loadFailed = 0L
            _debugCounters.lastLoadErrorCode = null
            _debugCounters.showAttempted = 0L
            _debugCounters.showStarted = 0L
            _debugCounters.onAdImpression = 0L
            _debugCounters.dismissed = 0L
            _debugCounters.failedToShow = 0L
            _debugCounters.lastShowErrorCode = null
            _debugCounters.expired = 0L
            _debugCounters.reloadsTriggered = 0L
        }
    }

    fun setEntitlementSource(source: (() -> Boolean)?) {
        entitlementSource = source
    }

    fun clearAd() {
        synchronized(lock) {
            clearAdLocked()
        }
    }

    private fun clearAdLocked() {
        currentGeneration++
        interstitialAd = null
        adLoadedTimestamp = 0L
        isLoading = false
    }

    internal fun isAdExpired(): Boolean {
        return adLoadedTimestamp != 0L && (clock() - adLoadedTimestamp >= AD_EXPIRATION_MILLIS)
    }

    @androidx.annotation.VisibleForTesting
    internal fun isAdLoaded(): Boolean = synchronized(lock) {
        val ad = interstitialAd
        if (ad != null && isAdExpired()) {
            _debugCounters.expired++
            clearAdLocked()
            false
        } else {
            ad != null
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setLastAdShownTimeForTesting(time: Long) {
        synchronized(lock) {
            lastAdShownTime = time
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAdLoadedTimestampForTesting(time: Long) {
        synchronized(lock) {
            adLoadedTimestamp = time
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setLoadedAdForTesting(ad: InterstitialAd?, loadedAt: Long = clock()) {
        synchronized(lock) {
            interstitialAd = ad
            adLoadedTimestamp = if (ad != null) loadedAt else 0L
            isLoading = false
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setPremiumForTesting(isPremium: Boolean?) {
        premiumOverride = isPremium
        if (isPremium == true) {
            clearAd()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        synchronized(lock) {
            clearAdLocked()
            lastAdShownTime = 0L
            adLoadedTimestamp = 0L
            premiumOverride = null
            entitlementSource = null
            clock = { System.currentTimeMillis() }
            resetDebugCounters()
        }
        BillingManager.resetCacheForTesting()
    }

    private fun isUserPremium(isPremium: Boolean, context: Context? = null): Boolean {
        if (isPremium || (premiumOverride == true) || (entitlementSource?.invoke() == true)) {
            return true
        }
        if (context != null) {
            return BillingManager.isUserEntitled(context)
        }
        return false
    }

    private fun canRequestAds(context: Context): Boolean {
        return runCatching {
            com.google.android.ump.UserMessagingPlatform.getConsentInformation(context).canRequestAds()
        }.getOrDefault(false)
    }

    fun loadInterstitial(
        context: Context,
        isPremium: Boolean = false,
        isPremiumProvider: (() -> Boolean)? = null
    ) {
        val appContext = context.applicationContext ?: context
        if (isPremiumProvider != null) {
            this.entitlementSource = isPremiumProvider
        }

        if (isUserPremium(isPremium, appContext)) {
            clearAd()
            return
        }

        if (!canRequestAds(appContext)) {
            clearAd()
            return
        }

        val requestGeneration: Long
        synchronized(lock) {
            val ad = interstitialAd
            if (ad != null) {
                if (isAdExpired()) {
                    _debugCounters.expired++
                    clearAdLocked()
                } else {
                    return
                }
            }
            if (isLoading) return
            isLoading = true
            _debugCounters.loadRequested++
            requestGeneration = ++currentGeneration
        }

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            appContext,
            BuildConfig.AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    synchronized(lock) {
                        if (requestGeneration != currentGeneration) {
                            AppLogger.d("Ignoring stale onAdFailedToLoad from generation $requestGeneration (current: $currentGeneration)")
                            return
                        }
                        _debugCounters.loadFailed++
                        _debugCounters.lastLoadErrorCode = adError.code
                        AppLogger.d("Failed to load interstitial ad: ${adError.message}")
                        interstitialAd = null
                        adLoadedTimestamp = 0L
                        isLoading = false
                    }
                }

                override fun onAdLoaded(ad: InterstitialAd) {
                    val isStale = synchronized(lock) { requestGeneration != currentGeneration }
                    if (isStale) {
                        AppLogger.d("Ignoring stale onAdLoaded from generation $requestGeneration (current: $currentGeneration)")
                        return
                    }

                    val currentConsent = canRequestAds(appContext)
                    val latePremium = isUserPremium(isPremium, appContext)

                    synchronized(lock) {
                        if (requestGeneration != currentGeneration) {
                            AppLogger.d("Ignoring stale onAdLoaded after re-checks from generation $requestGeneration (current: $currentGeneration)")
                            return
                        }
                        isLoading = false
                        if (!currentConsent || latePremium) {
                            AppLogger.d("Ad loaded but consent was revoked or user is premium; dropping ad.")
                            interstitialAd = null
                            adLoadedTimestamp = 0L
                        } else {
                            _debugCounters.loaded++
                            AppLogger.d("Interstitial ad loaded successfully.")
                            interstitialAd = ad
                            adLoadedTimestamp = clock()
                        }
                    }
                }
            }
        )
    }

    fun showInterstitialIfReady(
        activity: Activity,
        isPremium: Boolean,
        isReaderOrReflowActive: Boolean = false,
        onAdDismissed: () -> Unit
    ) {
        _debugCounters.eligibleOpportunity++

        if (activity.isFinishing || activity.isDestroyed) {
            _debugCounters.skippedActivityUnavailable++
            AppLogger.d("Activity is finishing or destroyed. Skipping show.")
            onAdDismissed()
            return
        }

        if (isReaderOrReflowActive) {
            _debugCounters.skippedReaderActive++
            AppLogger.d("Reader/Reflow active. Strict zero-interruption policy enforced.")
            onAdDismissed()
            return
        }

        val consentGranted = canRequestAds(activity)
        val effectivePremium = isUserPremium(isPremium, activity)

        if (effectivePremium) {
            _debugCounters.skippedPremium++
            clearAd()
            onAdDismissed()
            return
        }

        if (!consentGranted) {
            _debugCounters.skippedNoConsent++
            clearAd()
            onAdDismissed()
            return
        }

        val currentTime = clock()
        val adToShow: InterstitialAd?
        val inCooldown: Boolean
        synchronized(lock) {
            inCooldown = (currentTime - lastAdShownTime < COOLDOWN_MILLIS)
            if (inCooldown) {
                _debugCounters.skippedCooldown++
                adToShow = null
            } else {
                if (interstitialAd != null && isAdExpired()) {
                    _debugCounters.expired++
                    clearAdLocked()
                }
                adToShow = interstitialAd
                if (adToShow != null) {
                    interstitialAd = null
                    adLoadedTimestamp = 0L
                    lastAdShownTime = currentTime
                    _debugCounters.showAttempted++
                }
            }
        }

        if (inCooldown) {
            AppLogger.d("Ad cooldown active. Not showing ad.")
            onAdDismissed()
            return
        }

        if (adToShow != null) {
            adToShow.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdShowedFullScreenContent() {
                    _debugCounters.showStarted++
                    AppLogger.d("Ad showed fullscreen content.")
                    synchronized(lock) {
                        lastAdShownTime = clock()
                    }
                    clearAd()
                }

                override fun onAdImpression() {
                    _debugCounters.onAdImpression++
                    AppLogger.d("Ad impression recorded by SDK.")
                }

                override fun onAdDismissedFullScreenContent() {
                    _debugCounters.dismissed++
                    AppLogger.d("Ad was dismissed.")
                    clearAd()
                    synchronized(lock) {
                        lastAdShownTime = clock()
                    }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val currentPrem = isUserPremium(isPremium, activity)
                        if (!currentPrem && canRequestAds(activity)) {
                            _debugCounters.reloadsTriggered++
                            loadInterstitial(activity, isPremium = currentPrem)
                        }
                    }
                    onAdDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    _debugCounters.failedToShow++
                    _debugCounters.lastShowErrorCode = adError.code
                    AppLogger.d("Ad failed to show: ${adError.message}")
                    clearAd()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val currentPrem = isUserPremium(isPremium, activity)
                        if (!currentPrem && canRequestAds(activity)) {
                            _debugCounters.reloadsTriggered++
                            loadInterstitial(activity, isPremium = currentPrem)
                        }
                    }
                    onAdDismissed()
                }
            }
            adToShow.show(activity)
        } else {
            _debugCounters.skippedNotReady++
            AppLogger.d("Ad was not ready or expired. Starting load and skipping show.")
            if (!activity.isFinishing && !activity.isDestroyed) {
                _debugCounters.reloadsTriggered++
                loadInterstitial(activity, effectivePremium)
            }
            onAdDismissed()
        }
    }
}
