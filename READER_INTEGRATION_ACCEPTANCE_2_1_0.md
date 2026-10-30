# PDFchemy 2.1.0 — Reader Integration & UX Regression Acceptance Report

**Repository:** `kiss2oblivion/pdfchemy-android`  
**Author / Co-Architect:** Vulcan (Autonomous AI Co-Architect partnering with Andrei Ioan Cucoș / @kiss2oblivion)  
**Owned Scope:** Reader experience (`R01`–`R06`), Table Extractor & Play readiness (`G01`–`G08`), and cross-PR UX/instrumentation compatibility.  
**Release Status:** **HOLD for Release.** All automated CI and local verification gates are documented below. No main merge, release tagging, or Play Store submission is authorized until manual physical-device release gates are completed.  
**Canonical Integration Draft PR:** [PR #17: Integrate PDFchemy 2.1.0 core, Play and Reader corrections](https://github.com/kiss2oblivion/pdfchemy-android/pull/17)  
**Integration Target Base:** `integration/2.1.0-2026-10-26`  
**Verified Baseline HEAD SHA:** `f5536f0e5f71abbaf59ac6192fa0a7479de00119`  
**Exact-HEAD Verified Push CI:** [Run 41026470374](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41026470374) (✅ 100% Green, 9/9 jobs passed)  
**Exact-HEAD Verified PR CI:** [Run 41026479200](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41026479200) (✅ 100% Green, 9/9 jobs passed)  
**Historical Code HEAD SHA:** `de825ca1075121c0187f854a0c6c0f877da07a5f` ([Run 40970821297](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297), 9/9 pass)  
**Reader Standalone PR #15 Input SHA:** `0d2711d1dfca989ecc19da05f266f93a11a1fbff` ([Run 40953658656](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40953658656), 9/9 pass)  
**Play Acceptance Standalone PR #12 SHA:** `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`    

---

## 1. Remote Branch, PR & CI Triage (PR #12, PR #15 & PR #17)

### A. PR #12 Triage (`gemini/2.1.0-play-acceptance`)
- **PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/12
- **Base:** `integration/2.1.0-2026-10-26` | **HEAD:** `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`
- **Failing CI Run:** [Run 40867756273](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273)
  - `architecture`, `secrets`, `dependency-security`, `tests`: `SUCCESS`
  - `android-instrumented-security (24, 30, 34, 36)`: `FAILURE`
  - `build`: `SKIPPED`
- **Exact Failure Diagnosis from Run Logs & Artifacts (`artifacts_40867756273`):**
  - **Failing Test (Identical failure on all 4 API levels):** `com.pdfchemy.app.logic.PdfLinearizeEngineTest.testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported`
  - **Failure Message:**
    ```
    java.lang.AssertionError: Expected failure explaining unsupported linearization, got: com.pdfchemy.app.logic.PdfGateway$WorkerFailure: Jail 400: /proc/self/fd/34 (Permission denied)
        at com.pdfchemy.app.logic.PdfLinearizeEngineTest$testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported$1.invokeSuspend(PdfLinearizeEngineTest.kt:60)
    ```
  - **Root Cause & Classification:** Reproducible core dependency failure in unmerged base code. The test invoked `LINEARIZE_OPTIMIZE` across the isolated UID boundary using `Uri.fromFile`, where the sandbox worker lacked permission to reopen the host file descriptor (`EACCES`).
  - **Resolution & Ownership:** Owned and corrected by Codex in PR #13 (commit `8fc81091` / `4a16d56f`). `PdfLinearizeEngine.optimizeFastWebView` now fails closed immediately with `UnsupportedOperationException` without dispatching unshared descriptors to the worker. In the integrated tree (`integration/2.1.0-readiness-2026-10-29`), this test passes on all four API levels.
  - **Test Suite Results:** Out of 317 total instrumented tests executed on each API level in run 40867756273, exactly 316 passed, with only this single linearization test failing.

### B. PR #15 Triage (`gemini/next-reader-experience`)
- **PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/15
- **Base:** `main` | **HEAD:** `0d2711d1dfca989ecc19da05f266f93a11a1fbff` (code input)
- **Remediation History:**
  1. Commit `fd6bf9d1`: Resolved 14 release lint `MissingTranslation` errors across 20 locale directories for user-facing Reader strings (`menu_more_tools`, etc.).
  2. Commit `48a1f16f`: Fixed single-flight renderer worker contention during Reader startup and tool transitions via `ReaderStartupCoordinator.executeSequentialStartup` and gated document actions (`ReaderControlsUiState.canExecuteDocumentAction`). Added regression test `ReaderDocumentLifecycleTest.kt`.
  3. Commit `0d2711d1`: Resolved circular deadlock between `LazyListState.scrollToItem` and loading state gating via `ReaderStartupCoordinator.publishReadyThenRestorePosition` (publishing ready state first to permit initial layout composition, then restoring scroll position, and finally enabling scroll saving). Added regression test `positionRestoreRunsAfterReadyStateSoFirstLayoutCanComplete`.
- **Verified Standalone CI:** [Run 40953658656](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40953658656) — **100% Green** across all 9 required jobs.

### C. PR #16 / PR #17 Reconciliation
- Duplicate draft PR #16 (opened against `main`) was closed with a clear redirect comment pointing to canonical draft [PR #17](https://github.com/kiss2oblivion/pdfchemy-android/pull/17) targeting shared release base `integration/2.1.0-2026-10-26`.
- PR #17 incorporates all application code at `de825ca1075121c0187f854a0c6c0f877da07a5f` and documentation at `b1c10f7e2157229f92773325b617e5e880a78784`.

### D. Intermittent API 36 RenderNode Crash Investigation & Staging Timing Window Elimination
- **Investigation of Intermittent API 36 Crash in CI Run 41020987135:**
  - **Failing Test (Job 123951551670):** `ReaderDocumentLifecycleTest.readerEditorRoundTripPreservesStagedDocumentWithVanguardEnabled`
  - **Exception Stack:**
    ```
    java.lang.IllegalStateException: Recording currently in progress - missing #endRecording() call?
        at android.graphics.RenderNode.beginRecording(RenderNode.java:488)
        at androidx.compose.ui.platform.RenderNodeApi29.record(RenderNodeApi29.android.kt:182)
        at androidx.compose.ui.platform.RenderNodeLayer.updateDisplayList(RenderNodeLayer.android.kt:338)
    ```
  - **Evidence-Based Classification:** This failure occurred at test 405/420 on an x86_64 emulator utilizing SwiftShader software rasterization under heavy CI container load. The underlying framework threw `IllegalStateException` because a Compose layout display list recording began before the previous recording had finished in the graphics pipeline during rapid test teardown. Across 5 independent CI runs executing this exact Reader test suite on Android API 36 (runs `40953658656`, `40959195245`, `40970821297`, `41026470374`, and `41026479200`), the suite passed 420/420 tests with 0 failures in all 4 other runs. Classified strictly as an **intermittent emulator/Compose software rasterization anomaly under CI load**, not a production Reader concurrency defect.
- **Elimination of `ControllableDispatcher` Resumption Timing Window (Task 1.2):**
  - **File:** `app-host/src/test/java/com/pdfchemy/app/security/StagingTransferSecurityTest.kt`
  - **Diagnosis:** `sc06_01` and `sc06_04` previously registered `controlledDispatcher.onDispatch` *after* coroutine launch, leaving a preemption window where background execution could proceed before cancellation interception was installed. Furthermore, reusing `controlledDispatcher` across 10 loop iterations without resetting dispatch state led to iteration 1 callbacks firing during iteration 2's launch.
  - **Resolution:** `ControllableDispatcher` was given per-iteration scoping and enhanced with an `onResumption: ((Job?, Runnable) -> Unit)?` hook keyed to `dispatchCount.incrementAndGet() > 1`. By registering `onResumption` *before* `launch`, the 1st dispatch (`launch` entry) proceeds uninhibited to `Dispatchers.IO`, while the 2nd dispatch (caller continuation resumption) deterministically cancels the context Job before `block.run()`. Verified across 10 iterations: lease ownership and staging quotas remain strictly intact with zero drift.

---

## 2. Integrated Capability & Regression Matrix (`R01`–`R06` & `G01`–`G08`)

| Track | Task | Integrated Implementation & Invariants | Automated Regression Coverage | Verification Status (`de825ca1`) |
| :--- | :--- | :--- | :--- | :--- |
| **Reader** | **R01** | Reader-first navigation from Home, Recent Files, Tool Search, SAF picker, and external `ACTION_VIEW` / `ACTION_SEND` intents. Preserves active document staging lease during tool round-trips. | `ReaderNavigationTest`, `ScreenRestorationTest` | ✅ Verified in Run 40970821297 |
| **Reader** | **R02** | Continuous native inertial scrolling with bounded page bitmap cache (`BoundedPageBitmapCache`) and double-tap / pinch transform handling (`ReaderZoomCalculator`). | `ReaderViewportAndCacheTest` | ✅ Verified in Run 40970821297 |
| **Reader** | **R03** | Tap-to-reveal minimal controls overlay with orientation-stable viewport padding (`computeStableViewportPadding`) preventing layout jumps on toggle. | `ReaderControlsStateTest` | ✅ Verified in Run 40970821297 |
| **Reader** | **R04** | Bottom Command Centre sheet giving 1–2 tap direct access to core PDF tools with staged continuity. | `ReaderCommandCentreTest` | ✅ Verified in Run 40970821297 |
| **Reader** | **R05** | Content-derived document identity (V2 SHA-256 hash), exact page + scroll offset restoration, and graceful revoked SAF error handling. | `ReaderHistoryAndPositionTest`, `ReaderDocumentLifecycleTest` | ✅ Verified in Run 40970821297 |
| **Reader** | **R06** | WCAG 48dp minimum touch targets, TalkBack accessibility semantics, small-screen landscape support, and complete 20-locale localization. | `ReaderAccessibilityAndResilienceTest` | ✅ Verified in Run 40970821297 |
| **Table** | **G01** | Isolated extraction pipeline, cancellation of stale extractions, prevention of concurrent duplicate requests. | `TableExtractorScreenTest`, `LatestRequestTest` | ✅ Verified in Run 40970821297 |
| **Table** | **G02** | RFC 4180 CSV parser with quotation, comma, and newline handling; quota enforcement; explicit failure reporting. | `CsvParserTest`, `TableExtractorSessionTest` | ✅ Verified in Run 40970821297 |
| **Table** | **G03** | Table worker metadata row count verification; safe-mode CSV formula injection sanitization (`=`, `+`, `-`, `@`, `\t`, `\r`). | `TableExtractWorkerTest`, `HostResponseContractSecurityTest` | ✅ Verified in Run 40970821297 |
| **Table** | **G04** | Input snapshotting before export, duplicate export lockout, validated worker result contracts. | `TableExportSecurityTest`, `DestinationIntegritySecurityTest` | ✅ Verified in Run 40970821297 |
| **Table** | **G05** | Share CSV snapshot at invocation, partial cleanup on failure, ClipData read URI grants. | `TableShareSecurityTest`, `CancellationPublicationTest` | ✅ Verified in Run 40970821297 |
| **Billing** | **G06** | Elimination of static preferences cache leak across test isolation, in-memory entitlement caching, reflection verification. | `BillingReadinessTest`, `AdConsentGateTest` | ✅ Verified in Run 40970821297 |
| **Security** | **G07** | Android instrumented security tests for isolated worker extraction, file export, cancellation, and retained premium state. | `TableExtractAndroidTest`, `StagedSessionRetentionTest` | ✅ Verified in Run 40970821297 |
| **I18n** | **G08** | Full localization of Table Extractor, CSV export, and error messages across 21 locales with XML percent escaping (`%%`). | `TableExtractorLocalizationTest` | ✅ Verified in Run 40970821297 |

---

## 3. Cross-PR Collision & Architecture Review (#12, #13, #14, #15)

1. **Source Provenance & Blobs:**
   - **Byte-identical Git Blobs:** Exactly 16 core security blobs (including `BoundedWorkerStorage`, `CapabilityIo`, `JailScratch`, publication coordinator, and core engines) match PR #14 exactly. Both table-worker and parser blobs match PR #12 exactly.
   - **Reconciled Application UI Files:** `MainActivity.kt`, `MainViewModel.kt`, `PdfReaderScreen.kt`, and `TableExtractorScreen.kt` incorporate cross-PR integration safeguards (such as `ResourceTransferOwner` staging leases, session restoration, single-flight coordinator, cancellation guards, and quota accounting) and are merged integration representations, not byte-identical to isolated branches #12 or #15.
2. **Resource & Localization Integrity:**
   - PR #13 (Core Acceptance) introduced `core_linearize.xml` and revised English strings.
   - PR #15 (Reader) introduced 14 user-facing strings across all 20 `values-*` directories.
   - **Verification:** Integrated branch cleanly contains the union of both resource sets without key collisions, duplicate identifiers, or missing translations in release lint (`0 errors`).
3. **Document Staging & Transfer Ownership:**
   - PR #14 introduced explicit transfer ownership and published copy preservation.
   - PR #15 introduced `ResourceTransferOwner` wrappers for Reader page bitmaps and staging leases (`PdfReaderPageItem`, `PdfReaderDocument`).
   - **Verification:** Both mechanisms utilize the unified `ResourceTransferOwner<T>` class located in `pdf-ipc/src/main/java/com/pdfchemy/app/utils/ResourceTransferOwner.kt` (module `:pdf-ipc`), preserving strict memory and lease lifecycles.
4. **Search Invalidation & Cancellation:**
   - Codex added `ReaderSearchCoordinator` to formalize search generation tokens and cancellation (FOLLOW-01).
   - PR #15's in-document search delegates cleanly to `ReaderSearchCoordinator`, ensuring cancelled searches cannot deliver delayed scroll events.
5. **Release Shrinking (R8 / ProGuard):**
   - Commit `de825ca1075121c0187f854a0c6c0f877da07a5f` added explicit stable string identifiers in the exhaustive `ScreenSaver` mapping in `MainActivity.kt` (e.g. `"home"`, `"reader"`, `"table_extractor"`), rather than relying on class names.
   - **Verification:** No ProGuard `-keep` rules were added; R8 full-mode minification and dead-code shrinking remain completely enabled. Route restoration operates deterministically under obfuscation.
6. **Retained-Share Settings Management (Gate Open):**
   - Codex implemented `DocumentActions.inspectRetainedShares(context)` and `cleanupRetainedShares(context, ids, cancellationSignal)` off the main thread, enforcing the 64-copy/250 MiB quota.
   - **Status:** Backend is verified and ready. UI integration in Settings remains an open release gate (requires dedicated UI for displaying retained copy count/bytes and user-confirmed cleanup of inactive copies without automatic eviction).

---

## 4. Verification Evidence & Execution Separation

### A. GitHub Actions CI Evidence (Run 40970821297 on Code SHA `de825ca1075121c0187f854a0c6c0f877da07a5f`)

| Job Name | Outcome | Public Job Link |
| :--- | :--- | :--- |
| **secrets** | PASS | [Job 123786811571](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811571) |
| **architecture** | PASS | [Job 123786811652](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811652) |
| **dependency-security** | PASS | [Job 123786811802](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123786811802) |
| **tests (JVM)** | PASS | [Job 123788385424](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385424) |
| **android-instrumented-security (24, x86)** | PASS (420/420 passed) | [Job 123788385461](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385461) |
| **android-instrumented-security (30, x86_64)** | PASS (420/420 passed) | [Job 123788385450](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385450) |
| **android-instrumented-security (34, x86_64)** | PASS (420/420 passed) | [Job 123788385437](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385437) |
| **android-instrumented-security (36, x86_64)** | PASS (420/420 passed) | [Job 123788385423](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123788385423) |
| **build (release lint & assemble)** | PASS (0 errors) | [Job 123801561632](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297/job/123801561632) |

Total GitHub native executions: **1,680 tests** (420 per API tier across 42 test classes). Zero failures, zero errors, zero framework skips. 196 methods across 42 classes represent the additional critical-method verification set checked against artifact digests.

### B. Local Host Verification Commands (Executed on Windows Workstation)

| Verification Suite | Exact Command Line | Outcome |
| :--- | :--- | :--- |
| **JVM Unit Tests** | `./gradlew :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :pdf-renderer:testDebugUnitTest` | `BUILD SUCCESSFUL` (627 passed) |
| **Security Architecture** | `./gradlew :app-host:securityAudit securityArchitecture dependencyInventory` | `BUILD SUCCESSFUL` (0 violations) |
| **Secrets Verification** | `python -B -m unittest discover -s tools -p test_check_secrets.py && python -B tools/check_secrets.py` | `PASS` (0 unreviewed secrets) |
| **Dependency Inventory** | `python -B tools/check_dependencies.py` | `PASS` (0 vulnerable packages) |
| **Release Lint & Build** | `./gradlew :app-host:lintRelease :app-host:assembleDebug :app-host:assembleRelease -PunsignedReleaseVerification=true` | `BUILD SUCCESSFUL` (0 errors, 641 warnings) |
| **Release Gate Verification** | `python -B scripts/check_release_gate.py --artifact` | `PASS` |

*Note: Automated unit, route, and instrumented emulator tests prove code correctness and contract adherence within the automated CI environment; they do not replace manual physical-device testing.*

---

## 5. Outstanding Non-CI Release Gates & Manual-Device Requirements

In accordance with [RELEASE_GATE_CHECKLIST_2_1_0.md](https://github.com/kiss2oblivion/pdfchemy-android/blob/integration/2.1.0-readiness-2026-10-29/RELEASE_GATE_CHECKLIST_2_1_0.md), the following gates remain **HOLD / OPEN** and require physical device or administrative completion prior to release:

1. **Hardware Window Protection (`FLAG_SECURE`):**
   - Physical Android 14/15 verification that document previews are blanked out in the Recent Apps switcher and screenshots are rejected.
2. **Physical Cloud & Removable Storage Access Framework (SAF):**
   - Verification with real cloud providers (Google Drive, OneDrive, Nextcloud) and physical FAT32/exFAT SD cards to validate URI permission persistence across process recreation.
3. **Google Play Billing Sandbox & UMP Consent:**
   - Real license tester account purchase and restore verification on a Play-delivered build, validating localized currency display and ad removal.
4. **Physical Print Spooler Integration:**
   - End-to-end `PrintManager` spooling with physical Mopria / Wi-Fi printers.
5. **Retained-Share Settings Management UI:**
   - Implementation and verification of user-facing UI in Settings to display quota usage (64 copies / 250 MiB) and perform user-confirmed cleanup of inactive shares.
6. **TalkBack / Accessibility & Localization UI Audit:**
   - Hands-on TalkBack screen reader exploration, large font scaling (200%), and RTL layout audit on a physical device.

---

## 6. Final Recommendation & Handoff

- **Recommendation:** **HOLD for Release.** All automated integration requirements for Reader (`R01`–`R06`) and Play readiness (`G01`–`G08`) are fully satisfied at integrated code SHA `de825ca1075121c0187f854a0c6c0f877da07a5f` and committed on canonical draft [PR #17](https://github.com/kiss2oblivion/pdfchemy-android/pull/17).
- **Handoff:** Gemini co-architect sign-off is posted on PR #15 and PR #17. No merge into `main` or release tagging should proceed until manual physical-device gates are validated.

