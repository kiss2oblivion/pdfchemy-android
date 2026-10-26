package com.pdfchemy.app.billing

import android.app.Activity
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deterministic unit tests covering the 10 ad lifecycle & concurrency scenarios
 * specified in the Gemini Remediation Report. None of these tests rely on timing sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdCallbackLifecycleTest {

    @After
    fun cleanup() {
        AdManager.resetForTesting()
        unmockkAll()
    }

    private fun exercise(block: (Activity, MutableList<InterstitialAdLoadCallback>, (Boolean) -> Unit) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val consent = mockk<ConsentInformation>()
        var allowed = true
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } answers { allowed }
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs
        AdManager.resetForTesting()
        try {
            block(controller.get(), callbacks) { allowed = it }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    // Scenario 1: Consent revoked while ad load in flight -> late success discarded, no ad stored.
    @Test
    fun scenario1_consentRevokedWhileLoadInFlight_lateSuccessDiscarded() = exercise { activity, callbacks, setAllowed ->
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        // Consent is revoked while load request is in flight
        setAllowed(false)
        // Late arrival of onAdLoaded callback from earlier request
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertFalse("A callback after consent revocation must not store the ad", AdManager.isAdLoaded())
    }

    // Scenario 2: Premium purchased while ad load in flight -> late success discarded, no ad stored.
    @Test
    fun scenario2_premiumPurchasedWhileLoadInFlight_lateSuccessDiscarded() = exercise { activity, callbacks, _ ->
        var isPremiumUser = false
        AdManager.loadInterstitial(activity, isPremium = false, isPremiumProvider = { isPremiumUser })
        assertEquals(1, callbacks.size)
        // User purchases premium while request is in flight
        isPremiumUser = true
        AdManager.setPremiumForTesting(true)
        assertFalse(AdManager.isAdLoaded())
        // Late callback from previous non-premium load executes
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertFalse("Late ad callback must not store ad after Premium purchase", AdManager.isAdLoaded())
    }

    // Scenario 3: Request A cancelled, Request B initiated -> callback A failure cannot clear Request B in-flight state.
    @Test
    fun scenario3_requestACancelledRequestBInitiated_callbackAFailureCannotClearRequestBState() = exercise { activity, callbacks, setAllowed ->
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        // Cancel/clear Request A
        AdManager.clearAd()
        // Initiate Request B
        AdManager.loadInterstitial(activity)
        assertEquals(2, callbacks.size)
        // Late failure from Request A arrives
        callbacks[0].onAdFailedToLoad(mockk<LoadAdError>(relaxed = true))
        // Attempt another load: Request B is still in flight, so a 3rd request must NOT be dispatched
        AdManager.loadInterstitial(activity)
        assertEquals("Old failure callback must not release Request B's in-flight lock", 2, callbacks.size)
        // Completing Request B successfully stores the ad
        callbacks[1].onAdLoaded(mockk(relaxed = true))
        assertTrue("Request B must complete and store the ad", AdManager.isAdLoaded())
    }

    // Scenario 4: Concurrent load calls -> only one ad request dispatched, second call dropped or debounced.
    @Test
    fun scenario4_concurrentLoadCalls_onlyOneAdRequestDispatched() = exercise { activity, callbacks, _ ->
        AdManager.loadInterstitial(activity, isPremium = false)
        AdManager.loadInterstitial(activity, isPremium = false)
        assertEquals("Concurrent load call must be debounced; exactly 1 request dispatched", 1, callbacks.size)
    }

    // Scenario 5: In-flight failure arrives after subsequent load succeeds -> existing loaded ad retained.
    @Test
    fun scenario5_inFlightFailureArrivesAfterSubsequentLoadSucceeds_existingLoadedAdRetained() = exercise { activity, callbacks, _ ->
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        // Clear and start newer request B
        AdManager.clearAd()
        AdManager.loadInterstitial(activity)
        assertEquals(2, callbacks.size)
        // Newer request B succeeds
        val mockAdB = mockk<InterstitialAd>(relaxed = true)
        callbacks[1].onAdLoaded(mockAdB)
        assertTrue(AdManager.isAdLoaded())
        // Older failure from Request A arrives late
        callbacks[0].onAdFailedToLoad(mockk<LoadAdError>(relaxed = true))
        assertTrue("Stale failure from older request must not nullify loaded ad from newer request", AdManager.isAdLoaded())
    }

    // Scenario 6: Consent revoked after successful load -> cached ad cleared, show attempts fail closed.
    @Test
    fun scenario6_consentRevokedAfterSuccessfulLoad_cachedAdClearedAndShowFailsClosed() = exercise { activity, callbacks, setAllowed ->
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertTrue(AdManager.isAdLoaded())
        // User revokes consent
        setAllowed(false)
        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed = true }
        assertTrue("Show attempt must fail-closed and invoke completion immediately", completed)
        assertFalse("Cached ad must be cleared when consent is revoked", AdManager.isAdLoaded())
    }

    // Scenario 7: User becomes Premium after successful load -> cached ad cleared, show attempts fail closed.
    @Test
    fun scenario7_userBecomesPremiumAfterSuccessfulLoad_cachedAdClearedAndShowFailsClosed() = exercise { activity, callbacks, _ ->
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertTrue(AdManager.isAdLoaded())
        // User becomes Premium
        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = true) { completed = true }
        assertTrue("Show attempt must fail-closed and invoke completion immediately for Premium user", completed)
        assertFalse("Cached ad must be cleared when user becomes Premium", AdManager.isAdLoaded())
    }

    // Scenario 8: Consent re-granted after revocation -> subsequent load succeeds and caches ad normally.
    @Test
    fun scenario8_consentRegrantedAfterRevocation_subsequentLoadSucceedsAndCachesAd() = exercise { activity, callbacks, setAllowed ->
        setAllowed(false)
        AdManager.loadInterstitial(activity)
        assertEquals(0, callbacks.size)
        assertFalse(AdManager.isAdLoaded())
        // Re-grant consent
        setAllowed(true)
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(mockk(relaxed = true))
        assertTrue("Ad must be cached normally after consent is re-granted", AdManager.isAdLoaded())
    }

    // Scenario 9: Host Activity destroyed before ad loads -> callback handled safely without leaking Activity or crashing.
    @Test
    fun scenario9_hostActivityDestroyedBeforeAdLoads_callbackHandledSafelyWithoutCrashing() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val consent = mockk<ConsentInformation>()
        val callbacks = mutableListOf<InterstitialAdLoadCallback>()
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } returns true
        every { InterstitialAd.load(any(), any(), any(), capture(callbacks)) } just Runs
        AdManager.resetForTesting()

        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)

        // Activity is destroyed while load is in flight
        controller.pause().stop().destroy()
        assertTrue("Activity must be reported as destroyed", activity.isDestroyed)

        // Asynchronous callback arrives after activity destruction
        callbacks.single().onAdLoaded(mockk(relaxed = true))

        // Attempting to show on a destroyed Activity must fail closed without crashing
        var completed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed = true }
        assertTrue("Show attempt on destroyed Activity must complete safely without crashing", completed)
    }

    // Scenario 10: Rapid repeated show calls within cooldown window -> only first show succeeds, subsequent calls trigger callback without showing.
    @Test
    fun scenario10_rapidRepeatedShowCallsWithinCooldownWindow_onlyFirstShowSucceeds() = exercise { activity, callbacks, _ ->
        val mockAd = mockk<InterstitialAd>(relaxed = true)
        every { mockAd.show(activity) } just Runs
        AdManager.loadInterstitial(activity)
        assertEquals(1, callbacks.size)
        callbacks.single().onAdLoaded(mockAd)
        assertTrue(AdManager.isAdLoaded())

        var completed1 = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed1 = true }
        verify(exactly = 1) { mockAd.show(activity) }

        // Rapid repeated show call immediately after
        var completed2 = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { completed2 = true }
        assertTrue("Second show call within cooldown window must invoke completion callback", completed2)
        // Ad show must still only have been called once
        verify(exactly = 1) { mockAd.show(activity) }
    }
}
