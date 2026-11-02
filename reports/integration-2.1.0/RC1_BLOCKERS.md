# PDFchemy 2.1.0 RC1 — Blocker Triage Matrix

**Branch:** `integration/2.1.0-readiness-2026-10-29`  
**Current Baseline SHA:** `289c175273438f8f1c77febf11f61c22b17070db`  
**Candidate Fixes Applied:** `MainViewModel.kt`, `OutputPolicy.kt`, `ShareUtil.kt`, `RetainedShareStorage.kt`, `OperationConcurrencyTest.kt`  
**Date:** 2026-10-30  
**Status:** RC-01, RC-02, and RC-03 COMPLETE. Transitioning to RC-04 (Release Build & Freeze).

---

## 1. Classification Methodology

In accordance with owner directive (PR #17 comment 6520074055), issues are strictly classified into four operational bins:
1. **BLOCKER:** Direct threats to document integrity, cross-operation corruption, privacy leaks (redaction/sanitization bypass), crashes on normal user flows, release build/signing failures, or unusable core journeys. Must be resolved before freezing RC1.
2. **NONBLOCKING:** Known limitations, edge-case fidelity warnings, or ergonomic improvements that do not cause data loss, crashes, or security boundaries to fail. Explicitly deferred past RC1.
3. **OWNER-DEPENDENT:** Critical release gates that require physical hardware, external credentials, or owner-managed environments (e.g. physical ARM64 16 KB runtime, Play Billing sandbox, TalkBack hands-on audit). Gated for owner internal testing.
4. **UNVERIFIED:** Intermittent, test-runner-only, or theoretical issues lacking reproducible production defect evidence.

---

## 2. Triaged Blocker Matrix & Resolution Status

| ID | Category | Classification | Source Location | Description & Reproduction | Resolution & Verification | Status |
|---|---|---|---|---|---|---|
| **BLK-01** | Lifecycle | **BLOCKER** | `MainViewModel.kt:260-285` | `UiState.Warning` lacked terminal state publication. When `compressPdf` emitted `UiState.Warning` (e.g. target missed), outputs were never marked `PUBLISHED`, `activeJob` remained assigned, and `currentOperationId` was not reset. | **RESOLVED**: Updated `_uiState` setter to handle `UiState.Warning` identically to `UiState.Success`, publishing outputs in `OutputPolicy`, removing active operation records, clearing `activeJob`, resetting `currentOperationId = 0L`, and invalidating OCR requests. Verified in `OperationConcurrencyTest.warningResultMarksOutputPublishedAndClearsActiveJob`. | **RESOLVED** |
| **BLK-02** | Lifecycle | **BLOCKER** | `MainViewModel.kt:1044-1065` | `dismissError()` and `resetState()` lacked active-processing safeguards. If invoked while background worker was running, `dismissError()` set `_uiState = Idle` and `resetState()` cleared selected files and analysis without cancelling `activeJob`. | **RESOLVED**: Synchronized `dismissError()` under `operationAdmissionLock` to only transition to `Idle` if current state is `UiState.Error`. Synchronized `resetState()` to coordinate with active workers by invoking `cancelOperation()` before resetting analysis and file states. Also guarded `_uiState.set(UiState.Idle)` to cancel any active background coroutine job. Verified in `dismissErrorDoesNotDisruptActiveProcessing` and `resetStateCoordinatesCleanlyWithActiveOperation`. | **RESOLVED** |
| **BLK-03** | Document Integrity | **BLOCKER** | `MainViewModel.kt:205-255`, `OutputPolicy.kt:53-58` | Existing destination file byte preservation on cancellation or failure. Guarantee that cancellation or error cleanup only removes uncommitted staging/fresh copies, and NEVER truncates or deletes user's pre-existing destination files or source files. | **RESOLVED**: Verified differential cleanup in `OutputPolicy` and `MainViewModel`. Added path handling for both `file://` and `content://` URIs ensuring raw file destinations only delete if registered in `FreshOutputRegistry`. Added test `cancellationAndFailurePreservePreExistingDestinationBytes` with real on-disk files, confirming pre-existing bytes are 100% preserved while uncommitted fresh copies are deleted upon cancellation. | **RESOLVED** |
| **BLK-04** | Document Integrity | **NONBLOCKING** | `RetainedShareStorage.kt:3-5`, `ShareUtil.kt:10-25`, `MainViewModel.kt:507-518` | Retained-share quota (64-copy / 250 MiB cap) throws `RetainedShareQuotaException`. When cap was reached, user received no explanatory recovery path. | **RESOLVED**: Updated `RetainedShareQuotaException` with clear explanation: `"Share storage limit reached (64 items / 250 MB). Clear history in Settings to reclaim space."`. Updated `ShareUtil.shareFiles` to catch `RetainedShareQuotaException` and show long-duration Toast. Updated `MainViewModel.clearHistory()` to clean up inactive completed retained shares off the main thread via `DocumentActions.cleanupRetainedShares`, safely preserving active readers. | **RESOLVED** |
| **BLK-05** | Fidelity | **NONBLOCKING** | `RepairPdfScreen.kt`, `PdfEditorScreen.kt` | Repair and Find & Replace fidelity on corrupt cross-references, non-standard embedded Type3 fonts, or complex CJK streams. | Fail-closed error handling with user warnings. Source files remain uncorrupted. Deferred to post-2.1.0 engine update. | **NONBLOCKING** |
| **BLK-06** | Privacy | **NONBLOCKING** | `RedactionScreen.kt`, `DocumentSanitizerScreen.kt` | Redaction and sanitization visual vs text-stream verification. Ensure black boxes flatten streams and strip metadata, scripts, attachments, and annotations. | Verified in unit tests and instrumentation. Solid fill flattening prevents text extraction beneath redaction masks. | **VERIFIED** |
| **BLK-07** | Platform Gate | **OWNER-DEPENDENT** | Native JNI libraries (`libpdfium.so`, etc.) | Physical ARM64 16 KB page-size runtime verification. Static 16 KB ELF alignment verified via `zipalign -c -v 16`. | Requires physical Android 15+ ARM64 device with 16 KB page size enabled. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-08** | Platform Gate | **OWNER-DEPENDENT** | `MainActivity.kt` | Window `FLAG_SECURE` hardware blanking in Recent Apps overview and screenshot prevention. | Requires physical device interaction. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-09** | Platform Gate | **OWNER-DEPENDENT** | `FileUtil.kt`, SAF Subsystem | Physical removable storage / cloud SAF URI persistence. | Requires physical USB-OTG drive and Google Drive / Nextcloud cloud SAF providers. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-10** | Commerce Gate | **OWNER-DEPENDENT** | `BillingManager.kt`, `ConsentManager.kt` | Google Play Billing sandbox purchase/restore and UMP regional consent testing with registered license tester accounts. | Requires registered Google Play Console test accounts and Google Play services. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-11** | Platform Gate | **OWNER-DEPENDENT** | `PdfPrintAdapter.kt` | Physical Wi-Fi / Mopria PrintManager spooling. | Requires physical printer hardware on local network. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-12** | Accessibility | **OWNER-DEPENDENT** | Compose UI Layer | Physical TalkBack screen reader gestures, auditory feedback, and WCAG large-font scaling. | Requires hands-on manual verification with TalkBack enabled. Gated for owner internal testing. | **OWNER-DEPENDENT** |
| **BLK-13** | CI Artifact | **UNVERIFIED** | CI Runner (API 36) | `RenderNode.beginRecording` `IllegalStateException` during rapid test activity teardown on SwiftShader emulator. | SwiftShader emulator frame overlap under high host CPU load during test teardown. Not reproducible in production Reader code or in repeat local/CI runs. | **UNVERIFIED** |
| **BLK-14** | Monetization | **BLOCKER** | `AdManager.kt`, `MainActivity.kt`, `MainViewModel.kt` | Interstitial delivery lifecycle failure: stale ads retained past 60m Google Mobile Ads SDK TTL; missing ad opportunities on `UiState.Warning` and tool returns; missing impression tracking. | **RESOLVED**: Enforced 55m TTL auto-refresh, centralized atomic opportunity tracking, wired warning and tool-return opportunity consumption, added `onAdImpression` callback and proactive auto-reload. 10/10 new tests in `AdOpportunityDeliveryTest.kt` passing. | **RESOLVED** |

---

## 3. Verification Evidence Summary

- Target Unit Suite: `OperationConcurrencyTest` — 7 tests executed, 0 failures, 0 errors (BUILD SUCCESSFUL in 24s).
- Host Unit Suite: `:app-host:testDebugUnitTest` — 409 tests executed, 0 failures, 0 errors (BUILD SUCCESSFUL in 2m 3s).
- All confirmed P0/P1 blockers resolved with minimal surgical diffs.
- Ready for RC-04 (Freeze candidate source SHA, generate signed and minified APK + AAB).
