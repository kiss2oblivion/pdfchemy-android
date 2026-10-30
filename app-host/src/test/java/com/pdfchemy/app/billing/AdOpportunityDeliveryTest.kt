package com.pdfchemy.app.billing

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.UserMessagingPlatform
import com.pdfchemy.app.ui.MainViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdOpportunityDeliveryTest {

    private val testDispatcher = StandardTestDispatcher()
    private val consent = mockk<ConsentInformation>()
    private var consentGranted = true
    private val loadCallbacks = mutableListOf<InterstitialAdLoadCallback>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(UserMessagingPlatform::class)
        mockkStatic(InterstitialAd::class)
        every { UserMessagingPlatform.getConsentInformation(any()) } returns consent
        every { consent.canRequestAds() } answers { consentGranted }
        every { InterstitialAd.load(any(), any(), any(), capture(loadCallbacks)) } just Runs
        AdManager.resetForTesting()
    }

    @After
    fun cleanup() {
        Dispatchers.resetMain()
        AdManager.resetForTesting()
        unmockkAll()
    }

    // 1. Opportunity recorded on success and consumed exactly once (atomic consumption)
    @Test
    fun opportunity_recordedOnSuccess_andConsumedExactlyOnce() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        assertEquals(0L, AdManager.debugCounters.operationComplete)
        assertNull(vm.consumeAdOpportunity())

        val outputUris = listOf(Uri.parse("content://pdfchemy/test_output.pdf"))
        vm.setUiStateForTesting(MainViewModel.UiState.Success("Completed", "File saved", outputUris))

        assertEquals(1L, AdManager.debugCounters.operationComplete)

        // First consumption returns the opportunity
        val opp1 = vm.consumeAdOpportunity()
        assertNotNull(opp1)
        assertEquals("success", opp1?.toolName)
        assertEquals(outputUris, opp1?.outputUris)

        // Recomposition / duplicate check: second consumption must be null
        val opp2 = vm.consumeAdOpportunity()
        assertNull("Opportunity must be consumed exactly once to prevent duplicate ad shows", opp2)
    }

    // 2. Warning with outputs emits opportunity; Error clears pending opportunity
    @Test
    fun opportunity_recordedOnWarningWithOutputs_andClearedOnError() = runTest(testDispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = MainViewModel(app)

        val outputUris = listOf(Uri.parse("content://pdfchemy/partial.pdf"))
        vm.setUiStateForTesting(MainViewModel.UiState.Warning("Partial", "Some pages skipped", outputUris))

        assertEquals(1L, AdManager.debugCounters.operationComplete)
        assertNotNull(vm.pendingAdOpportunity.value)

        // Error state arrives (or user error dismiss) -> must clear opportunity
        vm.setUiStateForTesting(MainViewModel.UiState.Error("Operation failed"))
        assertNull("Error must clear any pending ad opportunity", vm.consumeAdOpportunity())
    }

    // 3. 60-second cooldown strictly enforced
    @Test
    fun cooldown_strictlyEnforcedAt60Seconds() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        var virtualTime = 100_000L
        AdManager.clock = { virtualTime }

        val mockAd1 = mockk<InterstitialAd>(relaxed = true)
        val mockAd2 = mockk<InterstitialAd>(relaxed = true)
        val callbackSlot1 = slot<FullScreenContentCallback>()
        val callbackSlot2 = slot<FullScreenContentCallback>()

        every { mockAd1.fullScreenContentCallback = capture(callbackSlot1) } just Runs
        every { mockAd2.fullScreenContentCallback = capture(callbackSlot2) } just Runs
        every { mockAd1.show(activity) } just Runs
        every { mockAd2.show(activity) } just Runs

        // First ad show at virtualTime = 100_000L
        AdManager.setLoadedAdForTesting(mockAd1, loadedAt = virtualTime)
        var dismissed1 = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed1 = true }

        assertEquals(1L, AdManager.debugCounters.showAttempted)
        assertTrue(callbackSlot1.isCaptured)
        callbackSlot1.captured.onAdShowedFullScreenContent()
        callbackSlot1.captured.onAdDismissedFullScreenContent()
        assertTrue(dismissed1)

        // Ready another ad immediately
        AdManager.setLoadedAdForTesting(mockAd2, loadedAt = virtualTime)

        // Attempt show at T + 59 seconds (59_000 ms) -> MUST BE BLOCKED BY COOLDOWN
        virtualTime += 59_000L
        var dismissedCooldown = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissedCooldown = true }

        assertTrue(dismissedCooldown)
        assertEquals(1L, AdManager.debugCounters.skippedCooldown)
        assertEquals(1L, AdManager.debugCounters.showAttempted) // Still 1, not incremented
        verify(exactly = 0) { mockAd2.show(activity) }

        // Advance to T + 60 seconds (60_000 ms total from last show) -> MUST BE ADMITTED
        virtualTime += 1_000L
        var dismissed2 = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed2 = true }

        assertEquals(2L, AdManager.debugCounters.showAttempted)
        verify(exactly = 1) { mockAd2.show(activity) }
        callbackSlot2.captured.onAdDismissedFullScreenContent()
        assertTrue(dismissed2)
    }

    // 4. 55-minute expiration invalidates ad and triggers reload
    @Test
    fun adExpiration_55MinutesTTL_invalidatesAdAndTriggersReload() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        var virtualTime = 1_000_000L
        AdManager.clock = { virtualTime }

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(mockAd, loadedAt = virtualTime)

        assertTrue("Ad must be valid immediately after load", AdManager.isAdLoaded())

        // Advance by 54 minutes (under 55 min TTL)
        virtualTime += 54 * 60 * 1000L
        assertTrue("Ad must remain valid at 54 minutes", AdManager.isAdLoaded())
        assertEquals(0L, AdManager.debugCounters.expired)

        // Advance by 1 minute and 1 millisecond (now 55m 1ms) -> Expired!
        virtualTime += (1 * 60 * 1000L + 1L)
        assertFalse("Ad must be marked expired and cleared after 55 minutes", AdManager.isAdLoaded())
        assertEquals(1L, AdManager.debugCounters.expired)

        // Now attempt to show on expired state
        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed = true }

        assertTrue("Expired show attempt must invoke completion immediately", dismissed)
        assertEquals(1L, AdManager.debugCounters.skippedNotReady)
        assertEquals(1L, AdManager.debugCounters.reloadsTriggered)
        verify(exactly = 0) { mockAd.show(activity) }
    }

    // 5. Suppression: Premium user never shown ads and clears memory
    @Test
    fun suppression_premiumUser_neverShowsAd() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(mockAd)

        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = true) { dismissed = true }

        assertTrue(dismissed)
        assertEquals(1L, AdManager.debugCounters.skippedPremium)
        assertFalse("Cached ad must be cleared when premium user encounters show check", AdManager.isAdLoaded())
        verify(exactly = 0) { mockAd.show(activity) }
    }

    // 6. Suppression: Consent not granted never shows ad and clears memory
    @Test
    fun suppression_noConsent_neverShowsAd() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(mockAd)
        consentGranted = false

        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed = true }

        assertTrue(dismissed)
        assertEquals(1L, AdManager.debugCounters.skippedNoConsent)
        assertFalse("Cached ad must be cleared when consent is missing", AdManager.isAdLoaded())
        verify(exactly = 0) { mockAd.show(activity) }
    }

    // 7. Suppression: Reader or Reflow active strictly suppresses ads
    @Test
    fun suppression_readerOrReflowActive_strictlyEnforcesZeroInterruption() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(mockAd)

        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false, isReaderOrReflowActive = true) {
            dismissed = true
        }

        assertTrue(dismissed)
        assertEquals(1L, AdManager.debugCounters.skippedReaderActive)
        verify(exactly = 0) { mockAd.show(activity) }
        assertTrue("Ad remains cached for legitimate later non-reader opportunity", AdManager.isAdLoaded())
    }

    // 8. Suppression: Finishing or Destroyed activity safely completes without crash
    @Test
    fun suppression_finishingOrDestroyedActivity_neverShowsAd() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        AdManager.setLoadedAdForTesting(mockAd)

        controller.pause().stop().destroy()
        assertTrue(activity.isDestroyed)

        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed = true }

        assertTrue(dismissed)
        assertEquals(1L, AdManager.debugCounters.skippedActivityUnavailable)
        verify(exactly = 0) { mockAd.show(activity) }
    }

    // 9. Callbacks: onAdImpression correctly increments debug counter
    @Test
    fun callbacks_onAdImpression_recordedCorrectly() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        val callbackSlot = slot<FullScreenContentCallback>()
        every { mockAd.fullScreenContentCallback = capture(callbackSlot) } just Runs
        every { mockAd.show(activity) } just Runs

        AdManager.setLoadedAdForTesting(mockAd)

        var dismissed = false
        AdManager.showInterstitialIfReady(activity, isPremium = false) { dismissed = true }

        assertTrue(callbackSlot.isCaptured)
        val callback = callbackSlot.captured

        assertEquals(0L, AdManager.debugCounters.onAdImpression)
        callback.onAdImpression()
        assertEquals(1L, AdManager.debugCounters.onAdImpression)

        callback.onAdDismissedFullScreenContent()
        assertTrue(dismissed)
    }

    // 10. Proactive reload on dismissal and failure to show
    @Test
    fun proactiveReload_triggeredOnDismissalAndFailedToShow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()

        val mockAd = mockk<InterstitialAd>(relaxed = true)
        val callbackSlot = slot<FullScreenContentCallback>()
        every { mockAd.fullScreenContentCallback = capture(callbackSlot) } just Runs
        every { mockAd.show(activity) } just Runs

        AdManager.setLoadedAdForTesting(mockAd)

        AdManager.showInterstitialIfReady(activity, isPremium = false) {}
        assertTrue(callbackSlot.isCaptured)

        // Dismissal triggers reload
        callbackSlot.captured.onAdDismissedFullScreenContent()
        assertEquals(1L, AdManager.debugCounters.reloadsTriggered)

        // Show failure also triggers reload
        val mockAd2 = mockk<InterstitialAd>(relaxed = true)
        val callbackSlot2 = slot<FullScreenContentCallback>()
        every { mockAd2.fullScreenContentCallback = capture(callbackSlot2) } just Runs
        every { mockAd2.show(activity) } just Runs
        AdManager.setLoadedAdForTesting(mockAd2)
        AdManager.setLastAdShownTimeForTesting(0L) // reset cooldown

        AdManager.showInterstitialIfReady(activity, isPremium = false) {}
        callbackSlot2.captured.onAdFailedToShowFullScreenContent(AdError(1, "Internal Error", "domain"))

        assertEquals(2L, AdManager.debugCounters.reloadsTriggered)
        assertEquals(1L, AdManager.debugCounters.failedToShow)
        assertEquals(1, AdManager.debugCounters.lastShowErrorCode)
    }
}

