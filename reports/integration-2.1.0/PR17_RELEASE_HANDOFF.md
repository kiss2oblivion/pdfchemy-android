## PDFchemy 2.1.0 — P0 Interstitial Correction & Release Candidate Handoff

**Canonical Branch:** `integration/2.1.0-readiness-2026-10-29`  
**P0-ADS Commit SHA:** `738a2e35207d70233d71e1a632c240843cf82207`  
**PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/17  
**Build Status:** Signed Release AAB & APK Generated (`BUILD SUCCESSFUL in 4m 29s`)  

---

### 1. P0 Interstitial Delivery Lifecycle Implementation

The root causes identified from the AdMob telemetry analysis (7 requests, 7 matched, 1 impression, 14.29% show rate) have been resolved:

1. **55-Minute Proactive TTL Auto-Refresh (`AdManager.kt`):** Google Mobile Ads SDK objects expire after 60 minutes. Added `AD_EXPIRATION_MILLIS = 55 * 60 * 1000L` to invalidate and refresh expired ad inventory before presentation, preventing silent `onAdFailedToShowFullScreenContent` drops.
2. **Centralized Atomic Opportunity Routing (`MainViewModel.kt`):** Added `CompletedOperationOpportunity` and atomic `consumeAdOpportunity()`. Both `UiState.Success` and valid `UiState.Warning` (e.g. partial compression) record an opportunity upon verified file output.
3. **Recomposition Immunity & Single-Use:** Opportunities are atomically cleared upon first read, preventing duplicate presentations across Compose recompositions.
4. **Natural Transition Delivery (`MainActivity.kt`):** Added home-screen transition observer (`LaunchedEffect(currentScreen)`) to consume completed tool opportunities when returning to `Screen.Home`, while strictly preserving zero-interruption behavior.
5. **Impression & Reload Tracking:** Added `onAdImpression` callback in `FullScreenContentCallback` to track Google-billed impressions, plus proactive preloading upon dismissal or show failure.
6. **Inviolable Invariant Enforcement:** 60-second cooldown strictly enforced, Premium entitlement strictly suppresses all ad requests/shows, UMP consent strictly enforced prior to load, zero ads in Reader/Reflow/Secure screens (`isReaderOrReflowActive = true`), and zero withholding or delay of document output.

---

### 2. Passing Focused Test Evidence (50 / 50 Tests Green)

- **`AdOpportunityDeliveryTest` (10 tests, 0 failures, 0 skips, 2.4s):**
  - `opportunity_recordedOnSuccess_andConsumedExactlyOnce`: Single-use atomic consumption verified.
  - `opportunity_recordedOnWarningWithOutputs_andClearedOnError`: Warning emits opportunity; error purges pending opportunity.
  - `cooldown_strictlyEnforcedAt60Seconds`: T0 show succeeds; T0 + 59s blocked (`skippedCooldown++`); T0 + 60s admitted.
  - `adExpiration_55MinutesTTL_invalidatesAdAndTriggersReload`: 54m valid; 55m 1ms expired (`expired++`); triggers reload without showing stale ad.
  - `suppression_premiumUser_neverShowsAd`: Proves premium users never see ads and memory is cleared.
  - `suppression_noConsent_neverShowsAd`: Proves missing UMP consent suppresses ads and clears cache.
  - `suppression_readerOrReflowActive_strictlyEnforcesZeroInterruption`: Proves Reader/Reflow screens suppress ads.
  - `suppression_finishingOrDestroyedActivity_neverShowsAd`: Proves destroyed activity completes safely without leaks or crashes.
  - `callbacks_onAdImpression_recordedCorrectly`: Proves SDK impression callback increments debug counter.
  - `proactiveReload_triggeredOnDismissalAndFailedToShow`: Proves dismissal and show failure trigger fresh preload.
- **`AdCallbackLifecycleTest`:** 13/13 passed (10.0s).
- **`BillingReadinessTest`:** 13/13 passed (5.3s).
- **`PremiumAdWiringReviewTest`:** 7/7 passed (4.2s).
- **`OperationConcurrencyTest`:** 7/7 passed (14.0s).

---

### 3. Signed Release Artifacts & Integrity Verification

Produced using authorized release signing key (`app-host/release.jks` / `keystore.properties`):

- **Release Android App Bundle (AAB for Google Play Console):**
  - **Path:** `app-host/build/outputs/bundle/release/app-host-release.aab`
  - **Size:** `66,222,437 bytes` (63.15 MB)
  - **SHA-256:** `C1DF510D035803A0ACDA377C301BDC8B55129BA17C69559FD54B69488762D08A`
- **Release APK (Signed & Minified with R8 for smoke testing):**
  - **Path:** `app-host/build/outputs/apk/release/app-host-release.apk`
  - **Size:** `97,560,434 bytes` (93.04 MB)
  - **SHA-256:** `544EBF077F90A69C2A696FE21B2A74998472174A88DF09353559448F95AF49C9`
- **Signing Certificate Verification (`apksigner verify --verbose`):**
  - **Signer:** `CN=Andrei Ioan Cucos, OU=PDFchemy, O=PDFchemy, L=Bucharest, ST=Bucharest, C=RO`
  - **Certificate SHA-256:** `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`
  - **Certificate SHA-1:** `b8eacf4cbb98b1b0ee801a641bafab6dabe627f5`
  - **Scheme:** v2 (true)
- **16 KB Page-Size Runtime Verification (`zipalign -c -v 16`):**
  - Every native `.so` library (`libjpeg.so`, `libleptonica.so`, `libmlkit_google_ocr_pipeline.so`, `libpngx.so`, `libtesseract.so`) across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) passed 16 KB page-alignment check (`(OK)`).

---

### 4. Play Console Release Gate Recommendation: GO FOR SUBMISSION

- **Immutable Source Commit SHA:** `738a2e35207d70233d71e1a632c240843cf82207`
- **Version:** `versionCode = 14`, `versionName = "2.1.0"`, `targetSdk = 36`
- **Required Owner Action:** Upload `app-host-release.aab` to Google Play Console Internal/Closed Testing track or Production release track with the provided release notes.

