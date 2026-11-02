### P0 / RC1 Blocker Acknowledgment & Interstitial Delivery Lifecycle Remediation (Gemini / Vulcan)

**Role:** Sole active implementation agent on canonical branch `integration/2.1.0-readiness-2026-10-29` (PR #17).  
**Starting HEAD:** [`6f44b49ca96ca87ceac87cdc71c701ce6d2e0e53`](https://github.com/kiss2oblivion/pdfchemy-android/commit/6f44b49ca96ca87ceac87cdc71c701ce6d2e0e53) (contains `RC-01`, `RC-02`, and `RC-03` lifecycle/quota fixes).  
**Status:** **IN PROGRESS (P0-ADS-D1 through D5 executing).** RC1 freeze is held until interstitial lifecycle defects are verified and resolved.

---

#### 1. Confirmed Production & Code Defects Diagnosed
1. **1-Hour Expiration & Stale Request Deadlock:**
   - In `AdManager.kt`, loaded interstitials are held in `interstitialAd` without a load timestamp.
   - Per Google Mobile Ads guidelines, loaded interstitial ad objects expire after **1 hour**.
   - Because `loadInterstitial()` immediately aborts if `interstitialAd != null`, an expired ad sitting in cache permanently suppresses all fresh load requests for the remainder of a long session, while failing with `onAdFailedToShowFullScreenContent` when eventually invoked.
2. **Missing Completion Opportunities on Eligible Non-`Success` Workflows:**
   - In `MainActivity.kt:1095-1170`, `showInterstitialIfReady` is only invoked upon manual dismissal / button press ("Another Operation" / "Home") of the `UiState.Success` dialog.
   - Operations that complete via `UiState.Warning` (e.g. compress with missed target ratio but valid saved output) and operations completing via callback / `UiState.Idle` (e.g. `deskewPdf`, `redactPdf`, `sanitizePdf`, `extractImagesFromPdf`, `batchCompressImages`, `replaceImageInPdf`) bypass the success dialog entirely and **never** trigger an interstitial opportunity.
3. **Missing `onAdImpression` Metric Callback:**
   - `adToShow.fullScreenContentCallback` handles `onAdShowedFullScreenContent`, `onAdDismissedFullScreenContent`, and `onAdFailedToShowFullScreenContent`, but omits `onAdImpression`. A show event does not reliably distinguish a client attempt from a Google-billed impression.
4. **No Bounded Retry / Cold Preload:**
   - If an ad is not ready at the moment of completion, the request is kicked off, but the opportunity is immediately discarded. If `onAdFailedToLoad` occurs, no bounded exponential retry is scheduled, leaving the ad cache empty until a future user operation.

---

#### 2. Remediation Architecture & Invariants
- **Central Operation Completion Bus / Opportunity Flow:** Introduce a dedicated, exactly-once-per-operation event stream emitted only upon verified, published output completion (across `Success`, valid `Warning`, and callback completions).
- **Ad Lifespan & Automatic Refresh:** Enforce a strict 55-minute TTL (`AD_EXPIRATION_MILLIS = 55 * 60 * 1000L`) that invalidates and proactively refreshes expired ad inventory before presentation.
- **Inviolable Policy Invariants:**
  - 60-second minimum cooldown strictly preserved.
  - Premium entitlement strictly suppresses all ad requests and shows.
  - UMP consent strictly enforced prior to any load request.
  - Zero interstitials or banners inside Reader / Reflow / Secure mode.
  - Zero disruption or delay to user document access or output publication.
- **Local Debug Counters:** Implement zero-telemetry in-memory counters for: `operationComplete`, `eligible`, `skipped(reason)`, `loadRequested`, `loaded`, `loadFailed(code)`, `showAttempted`, `showStarted`, `onAdImpression`, `dismissed`, `failedToShow(code)`, `expired`, and `reload`.

Proceeding directly through P0-ADS-D1 → D5.

