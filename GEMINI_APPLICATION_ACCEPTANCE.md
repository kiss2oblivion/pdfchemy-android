# GEMINI — PDFchemy 2.1.0 Application Acceptance Report

**Date:** 2026-10-28  
**Repository:** `kiss2oblivion/pdfchemy-android`  
**Current Branch:** `gemini/2.1.0-play-acceptance` (PR #12)  
**Starting SHA:** `3d59d622972971aa2b5fd3899b6f2a70ed210d5f`  
**Author:** Vulcan (Autonomous AI Co-Architect partnering with John — @kiss2oblivion)  
**Status:** **APPLICATION ACCEPTANCE COMPLETE / RELEASE REMAINS ON HOLD (PENDING PHYSICAL PLAY STORE SANDBOX VALIDATION)**

---

## 1. Executive Summary

This report documents the resolution of all assigned P0 and P1 application-level defects, table extraction vertical ownership, CI reliability triggers, and acceptance gates for PDFchemy Android 2.1.0 under PR #12:

1. **P0 — Correct Table Extraction Vertical:**
   - **Reusable Production CSV Parser:** Created `CsvParser.kt` in `app-host/src/main/java/com/pdfchemy/app/logic/CsvParser.kt`. Implements RFC 4180 parsing with full preservation of quoted whitespace, escaped double quotes (`""`), embedded newlines (`\r`, `\n`, `\r\n`), empty cells (`a,,c`), and blank rows without collapsing or dropping lines.
   - **Direct Production Testing:** Refactored `TableExtractorCorrectnessTest.kt` to exercise production `CsvParser.parse` directly, removing the duplicate mirror parser. Added tests for quoted whitespace, empty cells, blank rows, and escaped quotes.
   - **Worker Outcomes & Strict Validation:** Updated `PdfTableExtractorWorker.kt` to:
     - Enforce strict page-index validation: throws `IllegalArgumentException` on invalid page indices (`pageIndex < 0 || pageIndex >= totalPages`) rather than silently clamping.
     - Report distinct outcomes: returns `{"success": true, "empty": false, "rowCount": N}` for valid tables and `{"success": true, "empty": true, "rowCount": 0}` for empty results, making empty documents completely distinguishable from failures.
     - Eliminate redundant disk copies: loads directly from `sourceFd` stream via `JailMemory.settings()`, removing temporary file collision risks.
     - Default `safeMode` to `true` (formula prefixing `=, +, -, @` with `'`), with explicit raw data export option.
   - **Isolated Worker Testing:** Created `PdfTableExtractorWorkerTest.kt` in `pdf-jail` running real PDF generation and extraction through `PdfTableExtractorWorker.extractText` under Robolectric. Verified formula sanitization in default `safeMode = true`, raw output in `safeMode = false`, strict page-index rejection, and blank document handling.
   - **Engine Sealed Outcomes & Temp File Safety:** Updated `PdfTableExtractorEngine.kt` with sealed hierarchy `TableExtractionResult` (`Success`, `Empty`, `Failure`, `Cancelled`), collision-resistant `UUID.randomUUID()` temp file with guaranteed cleanup in `finally`, and structured `CancellationException` propagation.

2. **P1 — CSV Sharing & UI Responsiveness:**
   - **Off-Main-Thread File Writes:** Moved large CSV exports and file provider writes in `TableExtractorScreen.kt` off the Compose main thread onto `Dispatchers.IO` (`withContext(Dispatchers.IO)`).
   - **Bounded Shared File Retention:** Uses collision-resistant `shared_table_${UUID.randomUUID()}.csv` in `cacheDir/exports` and prunes older `shared_*` copies (max 5 retained), matching Codex's retention policy in `DocumentActions.kt` without introducing a conflicting retention system.
   - **Stale Preview & Cancellation Safety:** Cancels active jobs on new document selection and uses `LatestRequest` ticket checks to ensure cancelled or stale requests never mutate the UI.
   - **UI Empty State & Toggle:** Added dedicated UI for empty results (`isEmptyResult`), error banner with retry button, and an explicit toggle between Spreadsheet-Safe Mode (default) and Raw Data Mode.

3. **P0 — Reliable CI Triggers:**
   - Updated `.github/workflows/android-release.yml` triggers so both `push` and `pull_request` target `integration/2.1.0-2026-10-26`, `integration/**`, `release/**`, `gemini/**`, `codex/**`, `fix/**`.
   - Preserved the existing 9-job validation structure (`preflight-security`, `unit-tests-jvm`, `instrumentation-api24`, `instrumentation-api30`, `instrumentation-api34`, `instrumentation-api36`, `release-lint`, `assemble-release`, `publish-release-artifacts`).

4. **P1 — Premium & Ads Lifecycle Leak Elimination:**
   - Eliminated Activity-capturing lambda retained by singleton `AdManager`.
   - Added lifecycle-safe entitlement check `BillingManager.isUserEntitled(applicationContext)` and static `setEntitlementSource`.
   - Preserved immediate ad invalidation upon purchase, consent enforcement, pending purchase restoration, localized Play prices, and ad-free Reader experience.
   - Added reflection regression test `adManagerRetainsZeroActivityReferencesAfterStartup` in `PremiumAdWiringReviewTest.kt`.

5. **P1 — Home Search Verification:**
   - Verified normal-font and enlarged-font search instrumentation tests compile cleanly in `RemainingUxRuntimeTest.kt`.
   - Marked runtime execution UNVERIFIED locally (no attached physical device or emulator). All execution will run in CI with KVM.

---

## 2. File Ownership & Changes Summary

All modifications strictly observe Gemini's ownership boundaries. Engine classes owned by Codex (`pdf-ipc/**`, generic publication, Deskew, Office conversion, and sharing backend `DocumentActions.kt`) were untouched. `PdfTableExtractorWorker.kt` was modified under explicit user authorization.

```
 .github/workflows/android-release.yml                               |  8 +++-
 app-host/src/main/java/com/pdfchemy/app/MainActivity.kt             | 10 ++---
 app-host/src/main/java/com/pdfchemy/app/billing/AdManager.kt         | 17 ++++---
 app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt    | 12 +++++
 app-host/src/main/java/com/pdfchemy/app/logic/CsvParser.kt          | 85 ++++++++++++++++++++++++++++++++++
 app-host/src/main/java/com/pdfchemy/app/logic/PdfTableExtractorEngine.kt | 95 ++++++++++++++++++++++++++++++------
 app-host/src/main/java/com/pdfchemy/app/ui/TableExtractorScreen.kt  | 112 ++++++++++++++++++++++++++++++-------------
 app-host/src/test/java/com/pdfchemy/app/billing/PremiumAdWiringReviewTest.kt | 53 ++++++++++++++++++++
 app-host/src/test/java/com/pdfchemy/app/ui/TableExtractorCorrectnessTest.kt | 59 ++++++++++++++---------
 pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfTableExtractorWorker.kt | 45 ++++++++++-------
 pdf-jail/src/test/java/com/pdfchemy/app/jail/engines/PdfTableExtractorWorkerTest.kt | 185 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
```

---

## 3. Test & Build Verification Record

All commands executed with `$env:JAVA_HOME="E:\Android_Studio\jbr"`:

| Component / Test Suite | Command | Result | Duration | Notes |
|---|---|---|---|---|
| 1. Premium Ad Reflection Leak Test | `.\gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.billing.PremiumAdWiringReviewTest"` | **PASS** | 21s | Zero Activity leaks in AdManager |
| 2. Production CSV Parser Unit Tests | `.\gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.ui.TableExtractorCorrectnessTest"` | **PASS** | 13s | RFC 4180 parsing, quoted whitespace, empty cells, blank rows |
| 3. Isolated Worker Real PDF Extraction | `.\gradlew :pdf-jail:testDebugUnitTest --tests "com.pdfchemy.app.jail.engines.PdfTableExtractorWorkerTest"` | **PASS** | 14s | 5/5 tests pass: safeMode, raw, negative page, OOB page, blank doc |
| 4. Full App Host Unit Test Suite | `.\gradlew :app-host:testDebugUnitTest` | **PASS** | 1m 47s | All host unit tests pass cleanly |
| 5. Android Test Compilation | `.\gradlew :app-host:compileDebugAndroidTestKotlin` | **PASS** | 7s | All instrumentation tests compile |
| 6. App Security Audit | `.\gradlew :app-host:securityAudit` | **PASS** | 2s | Scanned 143 files, 0 security violations |
| 7. Debug APK Build | `.\gradlew :app-host:assembleDebug` | **PASS** | 30s | Successful assembleDebug |

---

## 4. Play Acceptance & Inviolable Ethical Disclosure

In accordance with our Cardinal Ethical Law:
- **Absolute Transparency:** Automated JVM and Robolectric unit tests are **PASS**. However:
  - Runtime execution of `RemainingUxRuntimeTest.kt` on Android devices is **UNVERIFIED locally** due to absence of attached local emulators or physical test hardware. These will execute in GitHub Actions CI with hardware KVM.
  - End-to-end Google Play Billing sandbox purchases and pending purchase recovery remain **UNVERIFIED** until tested on a physical test device with Play Billing sandbox accounts.
- **No Release Prematurity:** The release remains **ON HOLD**. PR #12 is ready for review and CI validation, but production release approval is reserved for John upon physical verification.
