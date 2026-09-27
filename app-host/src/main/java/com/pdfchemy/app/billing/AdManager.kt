package com.pdfchemy.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.BuildConfig

object AdManager {
    private var interstitialAd: InterstitialAd? = null
    private var lastAdShownTime: Long = 0
    private const val COOLDOWN_MILLIS = 60_000L // 60 seconds cooldown
    fun loadInterstitial(context: Context) {
        if (interstitialAd != null) return // Already loaded

        val adRequest = AdRequest.Builder().build()
        InterstitialAd.load(
            context,
            BuildConfig.AD_UNIT_ID,
            adRequest,
            object : InterstitialAdLoadCallback() {
                override fun onAdFailedToLoad(adError: LoadAdError) {
                    AppLogger.d("Failed to load interstitial ad: ${adError.message}")
                    interstitialAd = null
                }

                override fun onAdLoaded(ad: InterstitialAd) {
                    AppLogger.d("Interstitial ad loaded successfully.")
                    interstitialAd = ad
                }
            }
        )
    }

    fun showInterstitialIfReady(
        activity: Activity,
        isPremium: Boolean,
        onAdDismissed: () -> Unit
    ) {
        if (isPremium) {
            onAdDismissed()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAdShownTime < COOLDOWN_MILLIS) {
            AppLogger.d("Ad cooldown active. Not showing ad.")
            onAdDismissed()
            return
        }

        if (interstitialAd != null) {
            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    AppLogger.d("Ad was dismissed.")
                    interstitialAd = null
                    lastAdShownTime = System.currentTimeMillis()
                    // Pre-load the next ad
                    loadInterstitial(activity)
                    onAdDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    AppLogger.d("Ad failed to show: ${adError.message}")
                    interstitialAd = null
                    onAdDismissed()
                }

                override fun onAdShowedFullScreenContent() {
                    AppLogger.d("Ad showed fullscreen content.")
                    lastAdShownTime = System.currentTimeMillis()
                    interstitialAd = null
                }
            }
            interstitialAd?.show(activity)
        } else {
            AppLogger.d("Ad was not ready. Starting load and skipping show.")
            loadInterstitial(activity)
            onAdDismissed()
        }
    }
}
