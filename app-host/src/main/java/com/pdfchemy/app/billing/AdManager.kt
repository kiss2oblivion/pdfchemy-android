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
    private const val COOLDOWN_MILLIS = 60_000L // 60 seconds cooldown

    @Volatile
    private var premiumOverride: Boolean? = null
    @Volatile
    private var isPremiumProvider: (() -> Boolean)? = null

    fun clearAd() {
        synchronized(lock) {
            clearAdLocked()
        }
    }

    private fun clearAdLocked() {
        currentGeneration++
        interstitialAd = null
        isLoading = false
    }

    @androidx.annotation.VisibleForTesting
    internal fun isAdLoaded(): Boolean = synchronized(lock) { interstitialAd != null }

    @androidx.annotation.VisibleForTesting
    internal fun setLastAdShownTimeForTesting(time: Long) {
        synchronized(lock) {
            lastAdShownTime = time
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setPremiumForTesting(isPremium: Boolean?) {
        synchronized(lock) {
            premiumOverride = isPremium
            if (isPremium == true) {
                clearAdLocked()
            }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun resetForTesting() {
        synchronized(lock) {
            clearAdLocked()
            lastAdShownTime = 0L
            premiumOverride = null
            isPremiumProvider = null
        }
    }

    fun loadInterstitial(
        context: Context,
        isPremium: Boolean = false,
        isPremiumProvider: (() -> Boolean)? = null
    ) {
        val appContext = context.applicationContext ?: context
        if (isPremiumProvider != null) {
            synchronized(lock) {
                this.isPremiumProvider = isPremiumProvider
            }
        }

        val effectivePremium = isPremium || (premiumOverride == true) || (this.isPremiumProvider?.invoke() == true)
        if (effectivePremium) {
            clearAd()
            return
        }

        val consentGranted = runCatching {
            com.google.android.ump.UserMessagingPlatform.getConsentInformation(appContext).canRequestAds()
        }.getOrDefault(false)

        if (!consentGranted) {
            clearAd()
            return
        }

        val requestGeneration: Long
        synchronized(lock) {
            if (interstitialAd != null || isLoading) return
            isLoading = true
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
                        AppLogger.d("Failed to load interstitial ad: ${adError.message}")
                        interstitialAd = null
                        isLoading = false
                    }
                }

                override fun onAdLoaded(ad: InterstitialAd) {
                    synchronized(lock) {
                        if (requestGeneration != currentGeneration) {
                            AppLogger.d("Ignoring stale onAdLoaded from generation $requestGeneration (current: $currentGeneration)")
                            return
                        }
                        isLoading = false
                        val currentConsent = runCatching {
                            com.google.android.ump.UserMessagingPlatform.getConsentInformation(appContext).canRequestAds()
                        }.getOrDefault(false)

                        val latePremium = isPremium || (premiumOverride == true) || (this@AdManager.isPremiumProvider?.invoke() == true)

                        if (!currentConsent || latePremium) {
                            AppLogger.d("Ad loaded but consent was revoked or user is premium; dropping ad.")
                            interstitialAd = null
                        } else {
                            AppLogger.d("Interstitial ad loaded successfully.")
                            interstitialAd = ad
                        }
                    }
                }
            }
        )
    }

    fun showInterstitialIfReady(
        activity: Activity,
        isPremium: Boolean,
        onAdDismissed: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) {
            AppLogger.d("Activity is finishing or destroyed. Skipping show.")
            onAdDismissed()
            return
        }

        val consentGranted = runCatching {
            com.google.android.ump.UserMessagingPlatform.getConsentInformation(activity).canRequestAds()
        }.getOrDefault(false)

        val effectivePremium = isPremium || (premiumOverride == true) || (isPremiumProvider?.invoke() == true)

        if (effectivePremium || !consentGranted) {
            clearAd()
            onAdDismissed()
            return
        }

        val currentTime = System.currentTimeMillis()
        val adToShow: InterstitialAd?
        synchronized(lock) {
            if (currentTime - lastAdShownTime < COOLDOWN_MILLIS) {
                AppLogger.d("Ad cooldown active. Not showing ad.")
                onAdDismissed()
                return
            }
            adToShow = interstitialAd
            if (adToShow != null) {
                interstitialAd = null
                lastAdShownTime = currentTime
            }
        }

        if (adToShow != null) {
            adToShow.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    AppLogger.d("Ad was dismissed.")
                    clearAd()
                    synchronized(lock) {
                        lastAdShownTime = System.currentTimeMillis()
                    }
                    // Pre-load the next ad safely
                    loadInterstitial(activity, isPremium)
                    onAdDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    AppLogger.d("Ad failed to show: ${adError.message}")
                    clearAd()
                    onAdDismissed()
                }

                override fun onAdShowedFullScreenContent() {
                    AppLogger.d("Ad showed fullscreen content.")
                    synchronized(lock) {
                        lastAdShownTime = System.currentTimeMillis()
                    }
                    clearAd()
                }
            }
            adToShow.show(activity)
        } else {
            AppLogger.d("Ad was not ready. Starting load and skipping show.")
            loadInterstitial(activity, isPremium)
            onAdDismissed()
        }
    }
}
