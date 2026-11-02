# P0 Interstitial Delivery Lifecycle — Verification Evidence & Attestation

**Date:** 2026-10-30  
**Canonical Branch:** `integration/2.1.0-readiness-2026-10-29`  
**Target Release:** PDFchemy 2.1.0 (versionCode 14)  
**Agent:** Vulcan (Autonomous AI Co-Architect, Master of the Forge)  
**Auditor / Repository Owner:** Andrei Ioan Cucoș (@kiss2oblivion)  

---

## 1. Executive Summary

In response to the historical AdMob telemetry failure (7 requests, 7 matched, 1 impression = 14.29% show rate, $0.00 revenue), the interstitial delivery lifecycle has been completely overhauled and verified.

The core root causes have been systematically addressed:
1. **AdMob 60-Minute Object Expiration:** Added proactive 55-minute TTL (`AD_EXPIRATION_MILLIS = 55 * 60 * 1000L`). Expired ads are invalidated and refreshed before presentation, eliminating silent `onAdFailedToShowFullScreenContent` drops.
2. **Missing Opportunity Routing:** Implemented centralized atomic opportunity tracking (`CompletedOperationOpportunity`) in `MainViewModel`. Terminal publication of `UiState.Success`, valid `UiState.Warning` (e.g. partial compression), and tool-return journeys on `Screen.Home` now evaluate ad display exactly once.
3. **Atomic Exactly-Once Consumption:** `viewModel.consumeAdOpportunity()` clears pending opportunities atomically, preventing duplicate presentations across Jetpack Compose recompositions.
4. **Impression & Failure Telemetry:** Added `onAdImpression` callback handling to track Google-billed impressions, and proactive auto-reloading upon ad dismissal or display failure.
5. **Inviolable Invariant Enforcement:** 60-second minimum cooldown strictly preserved, zero ad requests or displays for Premium users, zero requests without UMP consent, zero ads in Reader/Reflow/Secure screens (`isReaderOrReflowActive`), and zero withholding or disruption of user document output.

---

## 2. Automated Test Evidence

### Target Test Suites Executed:
- **`com.pdfchemy.app.billing.AdOpportunityDeliveryTest` (10 tests, 0 failures, 0 skips, 2.4s):**
  1. `opportunity_recordedOnSuccess_andConsumedExactlyOnce`: Proves terminal success generates exactly one ad opportunity, atomically cleared upon read.
  2. `opportunity_recordedOnWarningWithOutputs_andClearedOnError`: Proves warning with output publishes opportunity; error/cancellation safely purges pending opportunity.
  3. `cooldown_strictlyEnforcedAt60Seconds`: Proves show at T0 succeeds; show at T0 + 59s is blocked (`skippedCooldown++`); show at T0 + 60s is admitted.
  4. `adExpiration_55MinutesTTL_invalidatesAdAndTriggersReload`: Proves ad valid at 54m; expired at 55m 1ms (`expired++`); show attempt on expired ad triggers proactive reload (`reloadsTriggered++`) without displaying stale ad.
  5. `suppression_premiumUser_neverShowsAd`: Proves premium user suppresses show (`skippedPremium++`) and purges cached ad from memory.
  6. `suppression_noConsent_neverShowsAd`: Proves lack of UMP consent suppresses show (`skippedNoConsent++`) and purges cached ad.
  7. `suppression_readerOrReflowActive_strictlyEnforcesZeroInterruption`: Proves active Reader/Reflow screen suppresses ad (`skippedReaderActive++`), preserving uncorrupted user reading experience.
  8. `suppression_finishingOrDestroyedActivity_neverShowsAd`: Proves finishing/destroyed Activity gracefully skips (`skippedActivityUnavailable++`) without memory leaks or crashes.
  9. `callbacks_onAdImpression_recordedCorrectly`: Proves `onAdImpression` callback executes and increments debug counter.
  10. `proactiveReload_triggeredOnDismissalAndFailedToShow`: Proves dismissal and show failure immediately trigger fresh interstitial preload if user is eligible.

- **`com.pdfchemy.app.billing.AdCallbackLifecycleTest` (13 tests, 0 failures, 0 skips, 10.0s):**
  All 13 lifecycle edge-case tests (consent revocation in flight, concurrent load debouncing, stale failure isolation, re-entrant lock freedom) passed 100%.

- **`com.pdfchemy.app.billing.BillingReadinessTest` (13 tests, 0 failures, 0 skips, 5.3s):**
  Full billing readiness and entitlement cache suite passed 100%.

- **`com.pdfchemy.app.billing.PremiumAdWiringReviewTest` (7 tests, 0 failures, 0 skips, 4.2s):**
  Wiring review passed 100%.

- **`com.pdfchemy.app.ui.OperationConcurrencyTest` (7 tests, 0 failures, 0 skips, 14.0s):**
  All operation admission, mutual exclusion, warning publication, and byte preservation tests passed 100%.

**Total Suite Executed:** 50 targeted tests, 0 failures, 0 errors, 0 skips.

---

## 3. Production vs Test Ad Unit Configuration

- **Debug Builds:** Uses Google official test interstitial ad unit ID: `ca-app-pub-3940256099942544/1033173712`.
- **Release Builds:** Built with configured production unit IDs via `BuildConfig` and `manifestPlaceholders`:
  - Interstitial: `ca-app-pub-8945763551071628/9314012106` (`BuildConfig.AD_UNIT_ID`)
  - Banner: `ca-app-pub-8945763551071628/5697788038` (`BuildConfig.BANNER_AD_UNIT_ID`)
  - App ID: `ca-app-pub-8945763551071628~8206577473` (`manifestPlaceholders["adMobAppId"]`)
- **Telemetry Policy:** All `AdDebugCounters` reside strictly in local volatile memory. Zero user identifiers, device metadata, or document data are recorded or transmitted.

