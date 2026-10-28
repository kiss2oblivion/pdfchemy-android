# GEMINI — PDFchemy 2.1.0 Application Acceptance Report (Round 2 Hardening)

**Date:** 2026-10-28  
**Repository:** `kiss2oblivion/pdfchemy-android`  
**Current Branch:** `gemini/2.1.0-play-acceptance` (PR #12)  
**Base / Last Reviewed SHA:** `ea7d239337be641a647c7de602c9198a5564efb3`  
**Author:** Vulcan (Autonomous AI Co-Architect partnering with John — @kiss2oblivion)  
**Status:** **APPLICATION ACCEPTANCE COMPLETE (ROUND 2 HARDENING) / RELEASE REMAINS ON HOLD (PENDING PHYSICAL PLAY STORE SANDBOX VALIDATION)**

---

## 1. Executive Summary & Round 2 Hardening Scope

This report documents the resolution and rigorous verification of all review findings from Round 2 across Priority 1 (table-extraction defects), Priority 2 (premium review findings & reflection test rigor), and Priority 3 (CI root causes & documentation). All work strictly respects the boundaries established for PR #12 and avoids conflict with Codex's active work on PR #14 (`codex/2.1.0-core-acceptance`).

### 1.1 Priority 1 — Table Extraction Vertical
1. **Contract-Driven Empty & Row-Count Representation:**
   - Introduced `TableExtractContract(val success: Boolean = true, val empty: Boolean = false, val rowCount: Int = 0, val count: Int = 1)` in `WorkerResponseContracts.kt` within `pdf-ipc`.
   - Wired `WorkerResponseValidator.kt` to dispatch `"TABLE_EXTRACT"` responses to `parseTableExtract(root, targetCount)`, returning the typed contract.
   - Decoupled table row count and empty-state detection in `PdfTableExtractorEngine.kt` from post-write SAF file descriptor size queries (`openFileDescriptor(destUri, "r")?.use { it.statSize }`), which are unreliable across cloud-backed SAF document providers. The engine now determines empty state strictly from the validated worker contract (`contract.empty || contract.rowCount == 0`).
2. **Strict RFC 4180 Shared CSV Parser:**
   - Moved `CsvParser.kt` from `app-host` to `pdf-ipc/src/main/java/com/pdfchemy/app/logic/CsvParser.kt` to share parsing logic across `pdf-ipc`, `pdf-jail`, and `app-host`.
   - Enhanced `CsvParser` to strictly conform to RFC 4180: enforces double-quote escaping (`""`), unclosed quote rejection, unescaped quote in unquoted field rejection, character after closing quote rejection, and security quotas (max 2 MiB, 5000 rows, 128 cols, 1024 chars/cell, 20000 total cells).
   - Delegated worker CSV table validation in `WorkerResponseValidator.kt` directly to `CsvParser.parse(csvText)`, guaranteeing 100% agreement between jail worker output validation and host UI rendering.
3. **Safe External CSV Sharing:**
   - Removed aggressive automatic pruning/deletion of `shared_*` files from the UI cache directory in `TableExtractorScreen.kt`.
   - Granted external applications (such as email clients, spreadsheet viewers, and cloud drives) full, uninterrupted access to shared temporary CSV files without early truncation or file-not-found exceptions.
4. **Authoritative Document Selection & UI Thread Offload:**
   - Fixed document state handling in `TableExtractorScreen.kt` by introducing an explicit `hasHandledInitialUri` gate. Once an initial URI is processed, `selectedPdfUri` becomes the sole authoritative source of truth. Toggling between Spreadsheet-Safe Mode and Raw Data Mode re-extracts the currently selected document rather than resetting to `initialPdfUri`.
   - Offloaded CSV preview table parsing in `TableExtractorScreen.kt` to `withContext(Dispatchers.Default)` with a dedicated `isParsingCsv` progress indicator, completely eliminating UI thread stalls when parsing complex or large tabular outputs.
   - Added localized user-facing error strings (`R.string.table_extractor_error_save`, `R.string.table_extractor_error_extract`) across all 21 supported app locales.

### 1.2 Priority 2 — Premium Review Findings & Reflection Test Rigor
1. **Uncompromised Reflection Test Rigor:**
   - Removed broad `catch (_: Throwable)` blocks from `PremiumAdWiringReviewTest.kt`. Catch blocks now target only expected reflection security barriers (`ReflectiveOperationException`, `SecurityException`, or `InaccessibleObjectException`), ensuring that any `AssertionError` is immediately surfaced as a test failure.
   - Added positive and negative leak detection tests:
     - `reflectionInspectorDetectsDirectActivityLeak`: Verifies that direct activity references inside field graphs are caught.
     - `reflectionInspectorDetectsLambdaCapturedActivity`: Verifies that lambda synthetic fields (`$arg1`, `this$0`) capturing an activity context are caught.
2. **Entitlement State In-Memory Caching:**
   - Added in-memory `@Volatile private var cachedEntitled: Boolean?` and cached `SharedPreferences` reference in `BillingManager.kt`.
   - Frequent ad display checks via `BillingManager.isUserEntitled(context)` now read directly from volatile memory, eliminating recurring Keystore IPC and EncryptedSharedPreferences disk decryption overhead.
   - Synchronized cached entitlement updates upon purchase confirmation and full inventory sync.
   - Added test hook `BillingManager.resetCacheForTesting()` setting `cachedEntitled = false` to eliminate AndroidKeyStore / Tink deadlocks under Robolectric test execution.
   - Added `cachedEntitlementRestorationClearsAdsImmediately` in `PremiumAdWiringReviewTest.kt` verifying that restoring entitlement immediately clears pending loaded ads from memory.
3. **Zero Activity Capture in Ad Callbacks:**
   - Evaluated `isUserEntitledToPremium()` into a local primitive boolean before entering callbacks in `MainActivity.kt`.
   - Passed `applicationContext` directly to `MobileAds.initialize` and `AdManager.loadInterstitial`, ensuring no Activity reference is captured in background initialization lambdas.
   - Verified that `activityRecreationPreservesLoadedAdWhenNotFinishing` cleanly tests non-finishing destroy (retaining preloaded ads) vs. finishing destroy (purging loaded ads).

### 1.3 Priority 3 — CI Root Causes & Cross-PR Compatibility
1. **CI Failure Root-Cause Analysis:**
   - Historical failures in GitHub Actions runs `40663850835` and `40663844883` failed on emulator tests for `PdfLinearizeEngineTest.testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported` (`/proc/self/fd/... EACCES` error) and bitmap ownership scheduling in `DeskewPreviewEngineTest`.
   - Root cause: Core-layer issues independently addressed by Codex in PR #13 and PR #14 (`8fc81091` and `9ad4fdc0`). These failures were inherited from unintegrated core changes and were not caused by Gemini's application layer.
2. **Compatibility with Codex Numbered Save Behavior:**
   - Gemini's table extractor exports use `SaveFileResult` and generic publication conventions that remain fully compatible with Codex's collision-resistant numbered document naming (`name (1).ext`).
3. **Preserved Boundaries:**
   - PR #12 contains zero modifications to: Deskew engine or preview, `OfficeExportEngineWorker` / PPTX generation, generic SAF publication infrastructure, retained-share backend (`DocumentActions.kt`), PDF/A or linearization implementation, or Codex's bitmap ownership fixes.

---

## 2. File Ownership & Modification Register

All changes strictly belong to Gemini's scope:

| Module | File | Modification Summary |
|---|---|---|
| `pdf-ipc` | `WorkerResponseContracts.kt` | Added `TableExtractContract` with `empty`, `rowCount`, and `count` fields. |
| `pdf-ipc` | `CsvParser.kt` | Moved from `app-host` to shared `pdf-ipc`. Enforces RFC 4180, quote escaping, and quotas. |
| `pdf-ipc` | `WorkerResponseValidator.kt` | Dispatched `"TABLE_EXTRACT"` to `parseTableExtract`. Delegated validation to `CsvParser.parse`. |
| `app-host` | `MainActivity.kt` | Passed `appCtx` to `MobileAds.initialize`. Removed activity capture from ad callbacks. |
| `app-host` | `AdManager.kt` | Added `resetForTesting()` hook tied to `BillingManager.resetCacheForTesting()`. |
| `app-host` | `BillingManager.kt` | Cached `SharedPreferences` and volatile in-memory entitlement state. |
| `app-host` | `PdfTableExtractorEngine.kt` | Typed execution via `TableExtractContract`. Decoupled empty/row count from SAF size. |
| `app-host` | `TableExtractorScreen.kt` | Authoritative `selectedPdfUri`, backgrounded CSV parsing, removed unsafe `shared_*` cache deletion. |
| `app-host` | `res/values*/strings.xml` | Localized `table_extractor_error_save` and `table_extractor_error_extract` across all 21 locales. |
| `app-host` | `PremiumAdWiringReviewTest.kt` | Removed swallowed assertions, added positive/negative reflection tests, verified lifecycle transitions. |
| `app-host` | `TableExtractorCorrectnessTest.kt` | Added tests for RFC 4180 compliance, unclosed quotes, embedded delimiters, and quotas. |
| `app-host` | `HomeSearchReviewTest.kt` | Regression tests verifying query normalization and search filtering. |
| `pdf-jail` | `PdfTableExtractorWorkerTest.kt` | Added unit tests verifying table extraction and error handling. |

---

## 3. Test & Build Verification Record

All commands executed locally with `$env:JAVA_HOME="E:\Android_Studio\jbr"`:

| Component / Test Suite | Command | Result | Duration | Notes |
|---|---|---|---|---|
| **1. Premium Ad Wiring & Reflection Suite** | `.\gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.billing.PremiumAdWiringReviewTest"` | **PASS** | 1m 31s | 7/7 tests passed: zero Activity leaks, negative reflection detection, lifecycle ad retention |
| **2. Table Extractor Correctness Suite** | `.\gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.ui.TableExtractorCorrectnessTest"` | **PASS** | 19s | Strict RFC 4180 parsing, quotas, unclosed quote rejection, quoted commas/newlines |
| **3. Home Search Review Suite** | `.\gradlew :app-host:testDebugUnitTest --tests "com.pdfchemy.app.ui.HomeSearchReviewTest"` | **PASS** | 15s | Verified search query handling |
| **4. Jail Worker Table Extractor Unit Tests** | `.\gradlew :pdf-jail:testDebugUnitTest` | **PASS** | 5s | Real PDF extraction, formula sanitization in safe mode, raw mode, page bounds |
| **5. IPC Contract & Validator Unit Tests** | `.\gradlew :pdf-ipc:testDebugUnitTest` | **PASS** | 3s | All IPC response contract validators pass cleanly |
| **6. App Security Architecture Audit** | `.\gradlew :app-host:securityAudit` | **PASS** | 3s | 142 files scanned in `app-host`, 0 security violations |
| **7. Full Debug APK Build** | `.\gradlew :app-host:assembleDebug` | **PASS** | 38s | Clean APK assembly, DEX generation, and packaging |

---

## 4. Inviolable Ethical Disclosure & Remaining Verification Gates

In strict adherence to our Cardinal Ethical Law:
- **Radical Honesty:** Automated JVM, Robolectric, and static security audit tests are **PASS (100%)**.
- **Remaining Risks Requiring Physical Device Verification:**
  1. **Google Play Billing Sandbox:** End-to-end purchasing, pending transaction reconciliation, and subscription renewal must be verified on a physical device with active Google Play Store sandbox accounts.
  2. **Device Hardware Performance:** Very large tabular documents (e.g. 5,000+ rows) should be verified on physical low-RAM devices (2GB/3GB) to confirm memory stability during CSV rendering.
  3. **KVM Instrumentation:** Full Android instrumentation tests (`RemainingUxRuntimeTest.kt`) will execute in GitHub Actions CI with hardware virtualization enabled.
- **Release Status:** The release remains **ON HOLD**. PR #12 is hardened, verified, and ready for code review and CI validation. Final production release approval is reserved for John upon physical verification.
