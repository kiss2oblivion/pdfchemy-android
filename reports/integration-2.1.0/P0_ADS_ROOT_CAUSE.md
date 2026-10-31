# P0 / RC1 Blocker — Interstitial Ad Lifecycle Root-Cause Analysis (P0-ADS-D1)

**Date:** 2026-10-30  
**Canonical Branch:** `integration/2.1.0-readiness-2026-10-29`  
**Starting HEAD:** `6f44b49ca96ca87ceac87cdc71c701ce6d2e0e53`  
**Author:** Gemini / Vulcan (Autonomous AI Co-Architect partnering with Andrei Ioan Cucoș)  
**Status:** COMPLETE (P0-ADS-D1)

---

## 1. Executive Summary & Production Data Correlation

AdMob production telemetry for `Interstitial_Ad` (October 2–29, 2026):
- **Requests:** 7
- **Matched Requests:** 7
- **Match Rate:** 100.00%
- **Impressions:** 1
- **Show Rate:** 14.29%
- **Revenue:** $0.00 rounded

### Why were there only 7 requests in an entire month?
1. **Cache Blocking Lockout:** In `AdManager.kt:110`, `loadInterstitial()` immediately aborts if `interstitialAd != null || isLoading`. When an ad is preloaded at application startup, it occupies `interstitialAd`. For the entire duration of that user's session, **no new load requests are ever initiated** unless that cached ad is successfully displayed.
2. **Missing Opportunities on Non-`UiState.Success` Flows:** Only 9 out of 31 user operations in `MainViewModel` route through `UiState.Success`. Workflows completing through callbacks, toasts, or `UiState.Idle` (e.g. image extraction, image batch compression, deskew, redaction, sanitization) never emit an opportunity.
3. **No Periodic Refresh / Inactivity Expiration:** The app did not track ad age or refresh inventory when users spent long sessions in the Reader or Editor.

### Why did 6 out of 7 matched ads fail to record an impression? (14.29% show rate)
1. **1-Hour SDK Expiration Deadlock:** Under official Google Mobile Ads SDK rules, loaded interstitial ad objects expire after **1 hour**. If a user reads a document, performs edits, or keeps the app open across an hour, the cached ad becomes invalid in memory. When `show()` is eventually called, Google Play Services rejects the ad with `onAdFailedToShowFullScreenContent`. Because `AdManager` lacked expiration tracking, the expired ad remained in memory blocking fresh loads.
2. **Dialog Navigation Abandonment:** In `MainActivity.kt:1095-1145`, `AdManager.showInterstitialIfReady` is only invoked when the user dismisses the dialog via "Another Operation", "Home", or outside tap. If the user taps "Open File", "Share", or "Browse files" and leaves the app to view the output, the dialog is never dismissed, and the ad is never presented.
3. **Cooldown Drop Without Deferred Queue:** If an operation finishes within the 60-second cooldown of a previous ad (e.g. compressing two files in 45 seconds), `showInterstitialIfReady` sees `inCooldown == true` and permanently drops the opportunity without queuing it for the next natural pause.
4. **Target-Missed Warning Bypass:** When PDF compression succeeds in generating and saving a document but misses the requested target file size, it emits `UiState.Warning`. `MainActivity.kt:1150-1170` wires `UiState.Warning` to `resetState()` directly, completely bypassing `showInterstitialIfReady`.

---

## 2. Operation Completion Route Mapping

| Operation | Output Saved? | UI / Result Route | Ad Opportunity in 2.0.3/2.0.6 | Ad Opportunity in 2.1.0 Baseline | Defect Classification | Root Cause & Failure Reason |
|---|---|---|---|---|---|---|
| **Compress PDF (Ratio Met)** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Ad only offered if user dismisses dialog via "Another Operation" / "Home". Bypassed if user taps "Open File" / "Share" or leaves app. Dropped if within 60s cooldown. |
| **Compress PDF (Target Missed)** | Yes | `UiState.Warning` | **None** | **None** | **CONFIRMED** | Valid output saved to disk, but `UiState.Warning` dialog calls `resetState()` directly. Never invokes `showInterstitialIfReady`. |
| **Create Text PDF** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Images to PDF** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Merge PDFs** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Split PDF** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Rotate PDF** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Extract Text** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Same dialog abandonment / cooldown vulnerability. |
| **Extract Images** | Yes | `onComplete(Int, Int)` -> `UiState.Idle` | **None** | **None** | **CONFIRMED** | Bypasses `UiState.Success`. Never invokes `AdManager`. |
| **Batch Image Compress** | Yes | `onComplete(Result)` -> `UiState.Idle` | **None** | **None** | **CONFIRMED** | Bypasses `UiState.Success`. Never invokes `AdManager`. |
| **Deskew PDF** | Yes | `onComplete(Boolean)` -> `UiState.Idle` | **None** | **None** | **CONFIRMED** | Bypasses `UiState.Success`. Never invokes `AdManager`. |
| **Redact PDF** | Yes | `onComplete(Boolean)` -> `UiState.Idle` | **None** | **None** | **CONFIRMED** | Bypasses `UiState.Success`. Never invokes `AdManager`. |
| **Sanitize PDF** | Yes | `onComplete(Boolean)` -> `UiState.Idle` | **None** | **None** | **CONFIRMED** | Bypasses `UiState.Success`. Never invokes `AdManager`. |
| **Table Extractor (CSV)** | Yes | Staged Share / Local File | **None** | **None** | **CONFIRMED** | No ad trigger exists on table/CSV completion. |
| **Office Export (Word/PPTX/Excel)**| Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Bypassed if user opens file immediately. |
| **Repair PDF** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Bypassed if user opens file immediately. |
| **Flatten / Grayscale / Crop** | Yes | `UiState.Success` | Dialog Dismissal | Dialog Dismissal | **CONFIRMED** | Bypassed if user opens file immediately. |

---

## 3. Comparative Architecture: Published (2.0.3/2.0.6) vs 2.1.0

1. **Published App (2.0.3 / 2.0.6):**
   - Had no background publication coordinator.
   - Had cold interstitial load at `MainActivity.onCreate` after consent.
   - Tied ad showing exclusively to `UiState.Success` dialog dismissal.
   - No tracking of ad expiration (1-hour SDK limit).
   - No retry upon `onAdFailedToLoad`.
   - AdMob metrics (7 requests, 1 impression, 14.29% show rate) accurately reflect this exact code architecture.
2. **Current 2.1.0 Integration Baseline (`6f44b49c`):**
   - Inherited the exact same `AdManager.kt` and dialog dismissal wiring.
   - Added `UiState.Warning` handling for output publication, but did not wire `UiState.Warning` to ad presentation.
   - The root cause is identical between published 2.0.3/2.0.6 and 2.1.0 baseline.

---

## 4. Required Corrective Design (P0-ADS-D2)

1. **Central Operation Completion Event Bus (`OperationCompletionCoordinator`):**
   - Emits a structured completion event: `OperationCompletedEvent(operationId, toolName, outputUris, isSuccess)`.
   - Emitted strictly once per operation upon terminal publication (covering `UiState.Success`, valid `UiState.Warning`, and callback/`Idle` tool completions).
   - Only user-initiated, successfully saved documents emit an ad opportunity. Failed or cancelled operations emit no ad event.
2. **AdManager Lifecycle & Inventory Hardening:**
   - **Ad Age & Expiration Tracking:** Record `adLoadedTimestamp`. Set `AD_EXPIRATION_MILLIS = 55 * 60 * 1000L` (55 minutes, safely below Google's 60-minute cutoff).
   - **Automatic Inventory Refresh:** If `isAdExpired()` is true, invalidate the stale ad and initiate a fresh load.
   - **Bounded Exponential Retry:** On `onAdFailedToLoad`, schedule a single backoff reload (e.g. 30s) if the app is active and has consent, preventing empty inventory.
   - **Impression Event Binding:** Implement `onAdImpression` in `FullScreenContentCallback` to log verified impressions.
   - **Cooldown & Preload Invariants:** Strictly retain the **60-second minimum cooldown** and **zero ads for Premium or within Reader/Reflow/Secure screens**.
3. **Local Debug Counters (Zero-Telemetry):**
   - In-memory thread-safe atomic counters for: `operationComplete`, `eligible`, `skippedCooldown`, `skippedPremium`, `skippedConsent`, `skippedReaderActive`, `loadRequested`, `loaded`, `loadFailed`, `showAttempted`, `showStarted`, `onAdImpression`, `dismissed`, `failedToShow`, `expired`, and `reloaded`.

