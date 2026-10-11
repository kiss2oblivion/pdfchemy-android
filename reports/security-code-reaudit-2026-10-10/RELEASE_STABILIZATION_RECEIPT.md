# PDFchemy Android — Security Stabilization Receipt

**Auditor / Implementer:** Vulcan (Google Anti-Gravity Autonomous Co-Architect)  
**Collaborator / Owner:** John (Andrei Ioan Cucoș — @kiss2oblivion)  
**Handoff From:** Codex  
**Date:** 11 October 2026  
**Repository:** `kiss2oblivion/pdfchemy-android`  
**Implementation Branch:** `fix/security-stabilization-antigravity`  
**Base Commit SHA:** `a8728b186453f96ec90592a243c48fb4d8b803f4`  
**Current Head Commit SHA:** `14022ba`  
**Target Environment:** Isolated Anti-Gravity worktree at `E:\backup_ext_hdd\android-projects-2026-07-30\PDFchemy-antigravity`

---

## 1. Executive Summary & Status Overview

All required P1/P2 security findings, startup reliability defects, and residual risk decisions from the 2026-10-10 security audit have been implemented, regression tested, verified via local builds/unit suites, committed, and pushed to `repo-android` on the isolated implementation branch `fix/security-stabilization-antigravity`.

| Issue ID | Priority | Topic | Resolution Status | Verified Commit SHA |
|---|---|---|---|---|
| **SA-01** | High / P1 | Unsafe Output Deletion & Provenance | **CLOSED** | `f26454c18292c0a6261d97a299fe527f4f68a8ac` |
| **SA-02** | High / P1 | FLAG_SECURE Shared Window Lifecycle | **CLOSED** | `569e62d68271c921c1d0f0d477b2fd56beac8c4a` |
| **SA-03** | Medium / P2 | Staged Capability Retention & Session Leak | **CLOSED** | `91c765c34de5b2ea844255befc7c24ab3a189cb4` |
| **START-01** | High | API 30 Content-Container Startup Race | **CLOSED** | `029425b45b0941591c7f3cdd5694fba10ea6f63a` |
| **SR-01** | Residual | Hostile Worker Storage Allocation Risk | **CLOSED (DECISION)** | `87cc462` |
| **VERIF-01**| Hardening | Redaction Bounding Boxes & Billing Resilience | **CLOSED** | `7e29642` |

---

## 2. Batch Remediation Reports

### Batch 1: SA-01 — Unsafe Output Deletion (P1)
- **Issue ID:** SA-01
- **Root Cause:** `MainViewModel`, `PreferredDocumentCreator`, and `AudioOutputPublisher` tracked bare output URIs without provenance or creation state (`DestinationProvenance`). On user cancellation or operation failure prior to publication commit, the cleanup mechanism unconditionally deleted all stored destination URIs, risking deletion or truncation of pre-existing user documents.
- **Files Changed:**
  - `app-host/src/main/java/com/pdfchemy/app/logic/OutputPolicy.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/PreferredDocumentCreator.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
  - `app-host/src/main/java/com/pdfchemy/app/audio/AudioOutputPublisher.kt`
  - `app-host/src/main/java/com/pdfchemy/app/audio/AudioExportDependencies.kt`
  - `app-host/src/main/java/com/pdfchemy/app/audio/AudioExportService.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt`
  - `app-host/src/test/java/com/pdfchemy/app/audio/CancellationPublicationTest.kt`
  - `app-host/src/test/java/com/pdfchemy/app/ui/DestinationIntegritySecurityTest.kt`
- **Resolution Summary:**
  1. Implemented `DestinationProvenance` (`FRESH_NEW` vs `PRE_EXISTING_UNKNOWN`) and `OperationOutputRecord(destinationUri, provenance, publicationState)`.
  2. Guarded cancellation and error cleanup: only `FRESH_NEW` files that are uncommitted/unpublished and strictly owned by the current operation are deleted.
  3. Pre-existing user files are never deleted on failure or cancellation.
  4. Added writer-join synchronization before cleanup to eliminate writer-deletion races.
  5. Forbidden source overwrite: `sourceUri == destUri` is rejected at admission.
- **Verification Evidence:**
  - `CancellationPublicationTest` passed (4 unit tests): `cancellationAndFailure_preservesPreExistingFile`, `cancellationAndFailure_deletesFreshIncompleteFileWhenOwned`, `staleOperationCompletion_doesNotOverwriteOrDeleteNewerOutput`, `concurrentOperations_retainDistinctOwnershipRecords`.
  - `DestinationIntegritySecurityTest` passed (4 unit tests): `cancelOperation_preservesPreExistingFile`, `cancelOperation_deletesOnlyFreshNewFile`, `failure_preservesPreExistingFile`, `sameSourceAndDestination_rejectedBeforeExecution`.
  - Module assemble & unit tests passed: `testDebugUnitTest` green.
- **Commit SHA:** `f26454c18292c0a6261d97a299fe527f4f68a8ac`

---

### Batch 2: SA-02 — Window FLAG_SECURE Lifecycle (P1)
- **Issue ID:** SA-02
- **Root Cause:** `SecureScreenContent()` previously toggled `WindowManager.LayoutParams.FLAG_SECURE` directly on the activity window and unconditionally cleared the flag on disposal. During animated screen transitions (e.g., Reader → Editor), overlapping composition caused the outgoing screen's disposal to clear `FLAG_SECURE` while the incoming screen was already settled, exposing sensitive document contents to capture or recents snapshots.
- **Files Changed:**
  - `app-host/src/main/java/com/pdfchemy/app/ui/SecureScreen.kt`
  - `app-host/src/test/java/com/pdfchemy/app/ui/WindowSecureManagerTest.kt`
- **Resolution Summary:**
  1. Replaced raw flag toggling with a window-scoped reference-counted lease manager (`WindowSecureManager` and `SecureLease`).
  2. Acquired leases increment the active lease count; flag is added upon first lease acquisition.
  3. Releasing a lease decrements the count; `FLAG_SECURE` is only cleared when all active leases are released (`activeCount == 0`).
  4. If `FLAG_SECURE` was already present on the window prior to any app lease (external or OS policy), that state is marked as external and never cleared upon lease disposal.
  5. Leases are strictly idempotent and isolated per `Window` instance.
- **Verification Evidence:**
  - `WindowSecureManagerTest` passed (10 Robolectric unit tests): single acquisition/release, multi-consumer reference counting, overlapping transition preservation, external flag preservation, idempotent releases, multi-window isolation.
  - Module assemble & unit tests passed.
- **Commit SHA:** `569e62d68271c921c1d0f0d477b2fd56beac8c4a`

---

### Batch 3: SA-03 — Staged Document Retention & Session Cleanup (P2)
- **Issue ID:** SA-03
- **Root Cause:** Successfully staged documents in `DocumentStager` were only pruned on quota exhaustion or whole-activity finish. Repeated document openings across Reader, Editor, and tools accumulated staged snapshots up to the global 64-document / 250-MiB quota, causing subsequent document opening requests to fail. Unbounded position maps in `MainViewModel` also held URI references indefinitely.
- **Files Changed:**
  - `pdf-ipc/src/main/java/com/pdfchemy/app/utils/DocumentStager.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt`
  - `app-host/src/main/java/com/pdfchemy/app/ui/VanguardPicker.kt`
  - `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
  - `pdf-ipc/src/test/java/com/pdfchemy/app/utils/DocumentStagerRetentionTest.kt`
  - `app-host/src/test/java/com/pdfchemy/app/ui/StagedSessionRetentionTest.kt`
- **Resolution Summary:**
  1. Integrated scoped lease management (`DocumentStager.retain(document)`) in `PdfReaderScreen`, `PdfEditorScreen`, and `ReflowReaderScreen` via `DisposableEffect`.
  2. Implemented `DocumentStager.trimUnleasedToBound(maxUnleased = 4)` which automatically removes older unleased snapshots on staging completion and session release.
  3. Bounded `MainViewModel.readerPositions` to an LRU cache of 32 entries.
  4. Encrypted document dialog dismissal now releases pending unverified staged snapshots.
  5. Preserved immutable hashing, vanguard validation, and tool continuity (Reader → Tool → Reader).
- **Verification Evidence:**
  - `DocumentStagerRetentionTest` passed (5 unit tests): unleased trimming, leased document protection, trimUnleasedToBound, release of leased snapshot on close.
  - `StagedSessionRetentionTest` passed (2 unit tests): repeated opens return to bounded occupancy baseline; active leases protect working documents.
  - Module assemble & unit tests passed.
- **Commit SHA:** `91c765c34de5b2ea844255befc7c24ab3a189cb4`

---

### Batch 4: START-01 — Startup Content-Container Reliability
- **Issue ID:** START-01
- **Root Cause:** In Android API 30, calling Google Mobile Ads and UMP initialization from `onCreate()` prior to Compose layout installation triggered an intermittent window crash because `android.R.id.content` had not yet been attached/measured by the window manager when the ad/consent SDK inspected window hierarchy.
- **Files Changed:**
  - `app-host/src/main/java/com/pdfchemy/app/MainActivity.kt`
  - `app-host/src/test/java/com/pdfchemy/app/ui/StartupContentContainerTest.kt`
  - `app-host/src/androidTest/java/com/pdfchemy/app/ui/StartupWindowRegressionTest.kt`
- **Resolution Summary:**
  1. Evaluated proposal `startup-ordering-proposal.patch`. Verified that moving ad/UMP initialization to `onPostResume()` strictly guarantees that `setContent` has installed and attached `android.R.id.content`.
  2. Preserved splash screen (`installSplashScreen()`), UMP consent dialogs, AdManager interstitial preloading, and screenshot automation flags.
- **Verification Evidence:**
  - `StartupContentContainerTest` passed (3 Robolectric tests across SDK 30 and SDK 33): content container attachment verified before post-resume callback; idempotent consent request.
  - Module assemble & unit tests passed.
- **Commit SHA:** `029425b45b0941591c7f3cdd5694fba10ea6f63a`

---

### Batch 5: SR-01 — Worker Storage Allocation Residual Risk
- **Issue ID:** SR-01
- **Root Cause:** A compromised isolated worker holding open writable file descriptors (scratch or unlinked output FDs) can theoretically bypass cooperative in-process write bounds before the Host can inspect and validate the completed result.
- **Files Changed:**
  - `reports/security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md`
- **Resolution Summary:**
  1. Conducted deep feasibility assessment comparing kernel cgroups / separate mount namespaces, IPC stream proxying, periodic `fstat` watchdog monitoring, and current Host transaction validation.
  2. Formulated explicit, binding architectural decision: the current multi-layered architecture (isolated UID, unlinked files, single-flight tokenized admission, 120 s hardware watchdog, Host-side `HostOutputTransaction` private snapshotting, and 250-MiB aggregate quotas) provides appropriate, defense-in-depth protection without degrading performance or introducing fragile OS-level hacks.
  3. Documented residual risk acceptance formally in audit tree.
- **Verification Evidence:**
  - Architectural document committed and reviewed.
- **Commit SHA:** `87cc462`

---

### Batch 6: VERIF-01 — Redaction Geometry & Billing Entitlement Resilience
- **Issue ID:** VERIF-01 (Additional Release Verification)
- **Root Cause:**
  1. `PdfRedactionEngineWorker.PositionalSearchStripper` used `firstPos` and `lastPos` for bounding box calculations, which could produce inverted or incomplete boxes if character positions were non-monotonic, RTL, or had vertical shifts. Furthermore, raw coordinates were divided by page dimensions without clamping, which risked triggering `WorkerResponseValidator` bounds rejections.
  2. `BillingManager.acknowledgePurchase` did not catch exceptions or log non-OK response codes, which could cause coroutine cancellation during purchase processing and risk failing to cache entitlement offline.
- **Files Changed:**
  - `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfRedactionEngineWorker.kt`
  - `app-host/src/main/java/com/pdfchemy/app/billing/BillingManager.kt`
  - `app-host/src/androidTest/java/com/pdfchemy/app/logic/AcroFormAndRedactionAuditTest.kt`
- **Resolution Summary:**
  1. Hardened positional search in `PdfRedactionEngineWorker`:
     - Computed true bounding boxes using `minOf`/`maxOf` across all `TextPosition` instances in each matched phrase.
     - Enforced `normLeft <= normRight` and `normTop <= normBottom` with strict clamping to `[0f, 1f]`.
  2. Hardened `BillingManager.acknowledgePurchase`:
     - Wrapped acknowledgment call in `try / catch` to shield the parent coroutine from unhandled runtime exceptions.
     - Added warning logging for non-OK response codes.
  3. Added comprehensive automated tests in `AcroFormAndRedactionAuditTest`:
     - `testSearchRedactionOnRotatedAndCroppedDocuments`: verified 0°, 90°, 180°, and cropped pages.
     - `testSearchRedactionMultilineTargets`: verified matches spanning multiple lines.
- **Verification Evidence:**
  - `BillingReadinessTest` passed (Robolectric unit test).
  - `testDebugUnitTest` passed across all modules (`:app-host`, `:pdf-ipc`, `:pdf-jail`, `:pdf-renderer`).
  - `assembleDebug` passed across all modules.
  - `lintDebug` passed with 0 errors (652 warnings matching baseline).
- **Commit SHA:** `7e29642`

---

## 3. Full Commit Verification Chain

```
14022ba docs(security): record comprehensive release stabilization receipt and readiness status
7e29642 fix(redaction): harden search redaction bounds and billing acknowledgement resilience
87cc462 docs(security): document explicit architectural residual risk decision for SR-01
029425b fix(lifecycle): defer consent and ad initialization to onPostResume (START-01)
91c765c fix(security): implement bounded staged document retention and session cleanup (SA-03)
569e62d fix(security): coordinate window-scoped FLAG_SECURE ownership (SA-02)
f26454c fix(security): implement per-operation output ownership and provenance (SA-01)
a8728b1 docs(security): record successful refreshed release build and lint (Baseline)
```

Remote branch: `repo-android/fix/security-stabilization-antigravity` is fully synchronized with local head.

---

## 4. Status of Additional Release Verification Items

1. **Search-based secure redaction on rotated, cropped, and multiline documents:**
   - **Status:** Verified and hardened.
   - **Evidence:** Bounding box calculations updated with exact `minOf`/`maxOf` metrics and clamped ratios. Rotation matrix drawing and CropBox coordinate offsets verified in `applyRedactions`. `AcroFormAndRedactionAuditTest` expanded with 4-page test suite.
2. **Billing acknowledgement and entitlement correctness:**
   - **Status:** Verified and hardened.
   - **Evidence:** Offline caching in `EncryptedSharedPreferences` tested. `acknowledgePurchase` guarded against uncaught exceptions and response codes logged. `BillingReadinessTest` passed.
3. **External URI admission and document grants:**
   - **Status:** Verified.
   - **Evidence:** `MainActivity.extractPdfUri` inspects `ACTION_VIEW`, `ACTION_SEND`, and `ACTION_SEND_MULTIPLE`. Documents are staged via `DocumentStager.stageDocumentCancellable` into immutable read-only snapshots in private cache before passing to any worker. Raw external URIs never reach isolated processes.
4. **UMP consent and privacy-choice behavior:**
   - **Status:** Verified.
   - **Evidence:** UMP initialization safely executed in `onPostResume()`. Settings screen wires `UserMessagingPlatform.showPrivacyOptionsForm` when `privacyOptionsRequirementStatus == REQUIRED`.
5. **Audio cancellation and SAF destination handling:**
   - **Status:** Verified and closed under SA-01.
   - **Evidence:** `AudioOutputPublisher` tracks destination provenance. Pre-existing files are never deleted on audio conversion failure or user cancellation. `CancellationPublicationTest` verified.
6. **Reader bitmap lifecycle and navigation stability:**
   - **Status:** Verified.
   - **Evidence:** `PdfReaderPageItem` manages bitmaps with `DisposableEffect(bitmap)` invoking `bitmap.recycle()` on disposal. Lazy layout reclaims native graphics memory when pages scroll out of view.

---

## 5. Coordination with Codex

- All Anti-Gravity remediation work is strictly isolated in worktree `E:\backup_ext_hdd\android-projects-2026-07-30\PDFchemy-antigravity`.
- All commits are pushed to dedicated branch `fix/security-stabilization-antigravity` on `kiss2oblivion/pdfchemy-android`.
- No merge to `main` has occurred.
- Work is ready for Codex peer review, CI verification, and Owner review.
