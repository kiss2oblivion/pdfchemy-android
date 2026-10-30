# PDFchemy 2.1.0 — Reader Integration & UX Regression Acceptance Report

**Repository:** `kiss2oblivion/pdfchemy-android`  
**Author / Co-Architect:** Vulcan (Autonomous AI Co-Architect partnering with Andrei Ioan Cucoș / @kiss2oblivion)  
**Owned Scope:** Reader experience (`R01`–`R06`), Table Extractor & Play readiness (`G01`–`G08`), and cross-PR UX/instrumentation compatibility.  
**Integration Branch (Codex Owned):** `integration/2.1.0-readiness-2026-10-29`  
**Integrated HEAD SHA:** `de825ca14c6ca9c2273e91b5c3e0708f51509a25`  
**Integrated CI Workflow Run:** [Run 40970821297](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297) (✅ 100% Green, 9/9 jobs passed)  
**Reader PR #15 Standalone SHA:** `0d2711d1dfca989ecc19da05f266f93a11a1fbff`  
**Reader PR #15 Standalone CI:** [Run 40953658656](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40953658656) (✅ 100% Green, 9/9 jobs passed)  
**Play Acceptance PR #12 Standalone SHA:** `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`  

---

## 1. Remote Branch, PR & CI Triage (PR #12 & PR #15)

### A. PR #12 Triage (`gemini/2.1.0-play-acceptance`)
- **PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/12
- **Base:** `integration/2.1.0-2026-10-26` | **HEAD:** `31f744e0ccd7fa4b8eddf439a0c837e8d88eca36`
- **Failing CI Run:** [Run 40867756273](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40867756273)
  - `architecture`, `secrets`, `dependency-security`, `tests`: `SUCCESS`
  - `android-instrumented-security (24, 30, 34, 36)`: `FAILURE`
  - `build`: `SKIPPED`
- **Exact Failure Diagnosis from Downloaded Run Artifacts (`artifacts_40867756273`):**
  - **Failing Test (Identical on all 4 API levels):** `com.pdfchemy.app.logic.PdfLinearizeEngineTest.testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported`
  - **Failure Message:**
    ```
    java.lang.AssertionError: Expected failure explaining unsupported linearization, got: com.pdfchemy.app.logic.PdfGateway$WorkerFailure: Jail 400: /proc/self/fd/34 (Permission denied)
        at com.pdfchemy.app.logic.PdfLinearizeEngineTest$testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported$1.invokeSuspend(PdfLinearizeEngineTest.kt:60)
    ```
  - **Classification:** Actual regression in inherited unmerged core linearize code from base `integration/2.1.0-2026-10-26`. The test passed `Uri.fromFile(dest)` to `PdfGateway.request("LINEARIZE_OPTIMIZE")`, where the isolated worker failed to open the unshared file descriptor (`EACCES`).
  - **Resolution Ownership:** Owned and resolved by Codex in PR #13 / commit `8fc81091` / `4a16d56f`. In `PdfLinearizeEngine.optimizeFastWebView`, unsupported linearization now immediately fails closed with `UnsupportedOperationException` without worker dispatch. In the integrated tree (`integration/2.1.0-readiness-2026-10-29`), this test passes on all 4 emulator levels.
  - **G01–G08 Test Verification:** All 316 other tests in PR #12 (including all TableExtractor, CSV parser, and Billing entitlement tests) passed on all 4 matrix tiers (API 24, 30, 34, 36).

### B. PR #15 Triage (`gemini/next-reader-experience`)
- **PR:** https://github.com/kiss2oblivion/pdfchemy-android/pull/15
- **Base:** `main` | **HEAD:** `0d2711d1dfca989ecc19da05f266f93a11a1fbff`
- **Historical CI Remediation Path:**
  1. Commit `fd6bf9d1`: Fixed 14 release lint `MissingTranslation` errors across 20 languages.
  2. Commit `48a1f16f`: Fixed single-flight worker contention during Reader-to-Editor handoff by sequentializing startup (`ReaderStartupCoordinator.executeSequentialStartup`) and gating document actions (`ReaderControlsUiState.canExecuteDocumentAction`). Added `ReaderDocumentLifecycleTest.kt`.
  3. Commit `0d2711d1`: Resolved circular deadlock between `LazyListState.scrollToItem` and loading state gating by restructuring startup (`ReaderStartupCoordinator.publishReadyThenRestorePosition`) to publish ready state first, perform layout and scroll restoration, and only then enable position saving. Added unit regression test `positionRestoreRunsAfterReadyStateSoFirstLayoutCanComplete`.
- **Final Standalone CI:** [Run 40953658656](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40953658656) — **100% Green** across all 9 jobs (`architecture`, `secrets`, `dependency-security`, `tests`, `android-instrumented-security (24, 30, 34, 36)`, and `build`).

---

## 2. Integrated Capability & Regression Matrix (`R01`–`R06` & `G01`–`G08`)

| Track | Task | Integrated Implementation & Invariants | Automated Regression Tests | Status in Integrated HEAD (`de825ca1`) |
| :--- | :--- | :--- | :--- | :--- |
| **Reader** | **R01** | Consistent Reader-first navigation from Home, Recent Files, Tool Search, SAF picker, and external `ACTION_VIEW` / `ACTION_SEND` intents. Seamless tool continuity preserves document lease. | `ReaderNavigationTest`, `ScreenRestorationTest` | ✅ Verified Green |
| **Reader** | **R02** | Continuous native inertial scrolling with bounded page bitmap cache (`BoundedPageBitmapCache`) and double-tap / pinch transform handling (`ReaderZoomCalculator`). | `ReaderViewportAndCacheTest` | ✅ Verified Green |
| **Reader** | **R03** | Tap-to-reveal minimal controls overlay with orientation-stable viewport padding (`computeStableViewportPadding`) preventing scroll jumps on toggle. | `ReaderControlsStateTest` | ✅ Verified Green |
| **Reader** | **R04** | Bottom Command Centre sheet giving 1–2 tap direct access to core PDF tools with staged continuity. | `ReaderCommandCentreTest` | ✅ Verified Green |
| **Reader** | **R05** | Stable content-derived document identity, exact page + scroll offset restoration, and graceful revoked SAF error handling. | `ReaderHistoryAndPositionTest` | ✅ Verified Green |
| **Reader** | **R06** | WCAG 48dp touch targets, TalkBack semantics, small-screen landscape support, large font scaling, and 20-locale localization. | `ReaderAccessibilityAndResilienceTest` | ✅ Verified Green |
| **Table** | **G01** | Isolated extraction pipeline, cancellation of stale extractions, prevention of concurrent duplicate requests. | `TableExtractorScreenTest`, `LatestRequestTest` | ✅ Verified Green |
| **Table** | **G02** | RFC 4180 CSV parser with quotation, comma, and newline handling; quota enforcement; explicit failure reporting. | `CsvParserTest`, `TableExtractorSessionTest` | ✅ Verified Green |
| **Table** | **G03** | Table worker metadata row count verification; safe-mode CSV formula injection sanitization (`=`, `+`, `-`, `@`, `\t`, `\r`). | `TableExtractWorkerTest`, `HostResponseContractSecurityTest` | ✅ Verified Green |
| **Table** | **G04** | Input snapshotting before export, duplicate export lockout, validated worker result contracts. | `TableExportSecurityTest`, `DestinationIntegritySecurityTest` | ✅ Verified Green |
| **Table** | **G05** | Share CSV snapshot at invocation, partial cleanup on failure, ClipData read URI grants. | `TableShareSecurityTest`, `CancellationPublicationTest` | ✅ Verified Green |
| **Billing** | **G06** | Elimination of static preferences cache leak across test isolation, in-memory entitlement caching, reflection verification. | `BillingReadinessTest`, `AdConsentGateTest` | ✅ Verified Green |
| **Security** | **G07** | Android instrumented security tests for isolated worker extraction, file export, cancellation, and retained premium state. | `TableExtractAndroidTest`, `StagedSessionRetentionTest` | ✅ Verified Green |
| **I18n** | **G08** | Full localization of Table Extractor, CSV export, and error messages across 21 locales with XML percent escaping (`%%`). | `TableExtractorLocalizationTest` | ✅ Verified Green |

---

## 3. Cross-PR Collision & Architecture Review (#12, #13, #14, #15)

1. **Resource & Localization Integrity:**
   - PR #13 (Core Acceptance) introduced `core_linearize.xml` and revised English definitions.
   - PR #15 (Reader) introduced 14 user-facing strings across all 20 `values-*` directories.
   - **Verification:** Integrated branch cleanly contains both resource sets without key collisions, duplicate identifiers, or missing translations in release lint (`0 errors`).
2. **Document Staging & Transfer Ownership:**
   - PR #14 introduced explicit transfer ownership and published copy preservation.
   - PR #15 introduced `ResourceTransferOwner` wrappers for Reader page bitmaps and staging leases (`PdfReaderPageItem`, `PdfReaderDocument`).
   - **Verification:** Both mechanisms share the unified `ResourceTransferOwner<T>` pattern in `app-host/src/main/java/com/pdfchemy/app/utils/ResourceTransferOwner.kt`.
3. **Search Invalidation & Cancellation:**
   - Codex added `ReaderSearchCoordinator` to formalize search generation tokens and cancellation (FOLLOW-01).
   - PR #15's in-document search seamlessly delegates to `ReaderSearchCoordinator`, ensuring cancelled searches do not deliver delayed scroll events.
4. **Release Shrinking (R8 / ProGuard):**
   - Commit `de825ca1` added ProGuard rules to retain navigation route IDs (`Screen` sealed hierarchy), ensuring Compose route restoration does not break under minification.

---

## 4. Verification Commands & Reproducible Evidence

All verification commands executed cleanly on `integration/2.1.0-readiness-2026-10-29` at commit `de825ca14c6ca9c2273e91b5c3e0708f51509a25`:

| Gate | Execution Command | Result |
| :--- | :--- | :--- |
| **Unit Tests** | `./gradlew :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :pdf-renderer:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| **Architecture Audit** | `./gradlew :app-host:securityAudit securityArchitecture dependencyInventory` | `BUILD SUCCESSFUL` (0 violations) |
| **Secrets Audit** | `python3 -m unittest discover -s tools -p test_check_secrets.py && python3 tools/check_secrets.py` | `PASS` (0 unreviewed secrets) |
| **Dependencies Audit** | `python3 tools/check_dependencies.py` | `PASS` (0 affected packages) |
| **Release Lint & Assemble** | `./gradlew :app-host:lintRelease :app-host:assembleDebug :app-host:assembleRelease -PunsignedReleaseVerification=true` | `BUILD SUCCESSFUL` (0 errors) |
| **Release Gate Verification** | `python3 scripts/check_release_gate.py --artifact` | `PASS` (All hygiene and smoke gates valid) |
| **4-Tier Instrumented Matrix** | `CI Workflow: android-instrumented-security (24, 30, 34, 36)` | `SUCCESS` across all 4 emulator levels |

---

## 5. Unresolved Issues & Manual-Device Verification Requirements

While automated unit, integration, and Android emulator instrumented tests are 100% green, the following tests require manual physical-device validation prior to Play Store release tagging:

1. **Hardware Window Protection (`FLAG_SECURE`):**
   - Verify on physical Android 14/15 hardware that document contents are blanked out in the Recent Apps switcher and that screenshots are blocked when viewing documents in Reader and Editor.
2. **Physical Cloud & Storage Access Framework (SAF) Providers:**
   - Open and save documents via Google Drive, Microsoft OneDrive, and external SD cards to verify URI permission persistence and recovery from network timeouts.
3. **Google Play Billing Sandbox:**
   - Execute purchase and restore flows with registered Google Play Console license tester accounts to verify dynamic localized currency formatting and real-time ad removal.
4. **Print Spooler Integration:**
   - Test `PrintManager` integration with physical Wi-Fi / Mopria printers to verify print preview generation and spooling.

---

## 6. Handoff & Release Readiness Sign-Off

- **PR #15 Status:** Cleanly verified standalone on SHA `0d2711d1dfca989ecc19da05f266f93a11a1fbff` (Run 40953658656, 100% Green).
- **Integrated Readiness Status:** Fully integrated into `integration/2.1.0-readiness-2026-10-29` at HEAD SHA `de825ca14c6ca9c2273e91b5c3e0708f51509a25` (Run 40970821297, 100% Green across all 9 jobs).
- **Next Step:** Codex to publish the final combined integration PR into `main`. Once published, Gemini will post this acceptance report and sign-off on the combined integration PR.
