## PDFchemy 2.1.0 — Corrected Release Candidate Handoff

**Canonical Branch:** `integration/2.1.0-readiness-2026-10-29`  
**PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/17  
**Build Status:** Fresh Signed Release AAB & APK Generated  

---

### 1. Audit Remediation Summary (Addressing Review & CI Stabilization)

1. **Callback / Idle Workflow Opportunity Wiring (`MainViewModel.kt`):**
   - Added verified post-publication opportunity recording across all 5 callback/Idle operations:
     - `extractImagesFromPdf`: `recordCompletedOpportunity("extract_images", createdOutputs.map { it.uri })` (invoked only if `extractedCount > 0`).
     - `compressImage` (single): `recordCompletedOpportunity("compress_image", listOf(destUri))` (invoked only if `result.success`).
     - `compressImageToTargetSize`: `recordCompletedOpportunity("compress_image", listOf(destUri))` (invoked only if `result.success`).
     - `compressImagesBatch`: `recordCompletedOpportunity("compress_images_batch", ArrayList(outputUris))` (invoked only if `outputUris.isNotEmpty()`).
     - `exportEditedPdf`: `recordCompletedOpportunity("export_edited_pdf", listOf(destUri))` (invoked only if `result.isSuccess`).
   - Zero opportunities created on failure, cancellation, or empty output.
2. **True Atomic Opportunity Consumption (`MainViewModel.kt`):**
   - Replaced non-atomic read-then-null pattern with true atomic CAS exchange:
     `fun consumeAdOpportunity(): CompletedOperationOpportunity? = _pendingAdOpportunity.getAndUpdate { null }`.
   - Verified thread-safe atomic single-use behavior across 16 concurrent threads.
3. **Corrected Report Discrepancy (`P0_ADS_DELIVERY_EVIDENCE.md`):**
   - Corrected report to match the actual compiled release IDs from `app-host/build.gradle.kts`:
     - Interstitial: `ca-app-pub-8945763551071628/9314012106` (`BuildConfig.AD_UNIT_ID`)
     - Banner: `ca-app-pub-8945763551071628/5697788038` (`BuildConfig.BANNER_AD_UNIT_ID`)
     - App ID: `ca-app-pub-8945763551071628~8206577473` (`manifestPlaceholders["adMobAppId"]`)
4. **Test Coroutine Exception Isolation (`PublicationCancellationSecurityTest.kt`):**
   - In `sc13_01_coroutineCancellation_closesActiveStreamAndUnblocksWriterPromptly`, writer coroutine on `Dispatchers.IO` now catches expected `IOException("Stream closed asynchronously during write")` upon stream closure.
   - Eliminates unhandled coroutine exception leakage to `Thread.defaultUncaughtExceptionHandler`, fully resolving the `UncaughtExceptionsBeforeTest` flakiness on downstream tests.

---

### 2. Passing Focused Test Evidence (46 / 46 Billing & Lifecycle Tests Green)

- **`AdOpportunityDeliveryTest` (13 tests, 0 failures, 0 skips, 10.4s):**
  - Added `callbackWorkflow_generatesOpportunity_consumedAtomicallyOnHome`: Verified image extraction and compression callback opportunities are retrievable on Home navigation.
  - Added `consumeAdOpportunity_isStrictlyAtomicUnderConcurrentContention`: Verified across 16 concurrent threads that exactly one thread captures the opportunity while all 15 others receive null.
  - Added `emptyOrFailedCallbackOutputs_neverRecordOpportunity`: Verified empty or failed callback outputs never create opportunities.
  - Retained all 10 baseline tests (55m expiration, 60s cooldown, suppression for Premium / no consent / Reader active / destroyed activity, impression callbacks, auto-reload).
- **`AdCallbackLifecycleTest`:** 13/13 passed (10.0s).
- **`BillingReadinessTest`:** 13/13 passed (5.3s).
- **`PremiumAdWiringReviewTest`:** 7/7 passed (4.2s).
- **`OperationConcurrencyTest`:** 7/7 passed (14.0s).
- **Full Unit Test Suite:** All 425 unit tests across `:pdf-ipc`, `:pdf-jail`, and `:app-host` passed locally (`BUILD SUCCESSFUL in 3m 55s`).

---

### 3. Fresh Signed Release Artifacts

Built with authorized release signing key (`app-host/release.jks` / `keystore.properties`):

- **Release Android App Bundle (AAB for Google Play Console):**
  - **Path:** `app-host/build/outputs/bundle/release/app-host-release.aab`
  - **Size:** `66,223,500 bytes` (63.16 MB)
  - **SHA-256:** `ED2B9C16C43E660FFB5AFEE19A7FE2BC2716F9CD82A508BEAD1A861C5BE13894`
- **Release APK (Signed & Minified with R8):**
  - **Path:** `app-host/build/outputs/apk/release/app-host-release.apk`
  - **Size:** `97,560,434 bytes` (93.04 MB)
  - **SHA-256:** `294384AECDE59A7800D36ABDFC8527B2427986CFC64CA38B3E2D817037756E91`
- **Signing Certificate Verification (`apksigner verify --verbose`):**
  - **Signer:** `CN=Andrei Ioan Cucos, OU=PDFchemy, O=PDFchemy, L=Bucharest, ST=Bucharest, C=RO`
  - **Certificate SHA-256:** `f7b12a179e08ebc24b0bc2afae5438e90834ec92896f67d9da8294aea653b41b`
  - **Scheme:** v2 (Verified: true)
- **16 KB Page-Size Runtime Verification (`zipalign -c -v 16`):**
  - All native `.so` libraries across ARM64, ARMv7, x86, and x86_64 pass 16 KB page-alignment (`(OK)`).

---

### 4. Play Console Release Recommendation: READY FOR CONSOLE UPLOAD

- **Version:** `versionCode = 14`, `versionName = "2.1.0"`, `targetSdk = 36`
- **Candidate Bundle for Upload:** `app-host/build/outputs/bundle/release/app-host-release.aab` (SHA-256: `ED2B9C16C43E660FFB5AFEE19A7FE2BC2716F9CD82A508BEAD1A861C5BE13894`)
