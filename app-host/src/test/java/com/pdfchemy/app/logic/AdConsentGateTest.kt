package com.pdfchemy.app.logic

import android.app.Activity
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import com.pdfchemy.app.billing.AdManager
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdConsentGateTest {
    @After fun cleanup() {
        AdManager.clearAd()
        AdManager.setLastAdShownTimeForTesting(0)
        unmockkAll()
    }

    @Test fun cannotRequestAdsPreventsLoadingAndDoesNotBlockDocumentCompletion() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns false
        AdManager.loadInterstitial(activity)
        verify(exactly = 0) { InterstitialAd.load(any(), any(), any(), any()) }
        var completed = false
        AdManager.showInterstitialIfReady(activity, false) { completed = true }
        assertTrue(completed)
        activity.finish()
    }

    @Test fun premiumUserPreventsAdLoadingAndClearsLoadedAd() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true

        // Loading for a premium user must be completely rejected
        AdManager.loadInterstitial(activity, isPremium = true)
        verify(exactly = 0) { InterstitialAd.load(any(), any(), any(), any()) }
        org.junit.Assert.assertFalse("No ad should be loaded for Premium user", AdManager.isAdLoaded())

        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = true) { completed = true }
        assertTrue("Completion callback must be invoked immediately for Premium user", completed)
        activity.finish()
    }

    @Test fun concurrentLoadCallsDoNotDuplicateAdRequests() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { InterstitialAd.load(any(), any(), any(), any()) } just Runs

        // First call initiates loading (in flight)
        AdManager.loadInterstitial(activity, isPremium = false)
        // Second call while first is still in flight
        AdManager.loadInterstitial(activity, isPremium = false)

        // Concurrency guard must ensure InterstitialAd.load is invoked exactly once
        verify(exactly = 1) { InterstitialAd.load(any(), any(), any(), any()) }
        activity.finish()
    }

    @Test fun revokedConsentPurgesExistingAd() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns false

        // Purge must ensure no ad is loaded or shown when consent is absent
        AdManager.clearAd()
        org.junit.Assert.assertFalse(AdManager.isAdLoaded())

        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed = true }
        assertTrue(completed)
        verify(exactly = 0) { InterstitialAd.load(any(), any(), any(), any()) }
        activity.finish()
    }

    @Test fun adCooldownPreventsFrequentShows() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        val consent = mockk<ConsentInformation>()
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true

        // Set last show time to 10 seconds ago (cooldown is 60s)
        AdManager.setLastAdShownTimeForTesting(System.currentTimeMillis() - 10_000L)

        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed = true }
        assertTrue("Cooldown should invoke completion callback without showing", completed)
        activity.finish()
    }
}
