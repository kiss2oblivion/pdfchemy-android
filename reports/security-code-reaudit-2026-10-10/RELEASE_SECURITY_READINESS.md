# Release Security Readiness — 2026-10-11

**Release Decision: HOLD.**
**2026-10-18 reconciliation:** the implementation tables below describe historical findings and revisions. The [independent analysis](../security-code-analysis-2026-10-18/SECURITY_CODE_ANALYSIS.md) identifies new SC-01 through SC-09 blockers and retained open criteria. Follow the [current remediation plan](../../SECURITY_REMEDIATION_PLAN.md) and [execution receipt](../security-remediation-2026-10-18/EXECUTION_STATUS.md); prior automated passes do not close these findings. Public release remains held for code remediation, final verification and external/device gates.

## Source and Scope

- **Repository:** `kiss2oblivion/pdfchemy-android`
- **Implementation Branch:** `fix/security-stabilization-antigravity`
- **Reviewed Head Commit:** `14022ba2bd9aa0daef7029916035fe73765e4466`
- **Follow-Up Stabilization Head:** `de0cf48`
- **Tracking PR:** [PR #2 (Draft)](https://github.com/kiss2oblivion/pdfchemy-android/pull/2)

The scope covers:
- Destination freshness and cross-operation provenance (SA-01 / RV-01).
- Window-scoped `FLAG_SECURE` reference-counted leases and reference cycle elimination (SA-02 / RV-02).
- Bounded staging lifecycle and session cleanup (SA-03).
- Startup initialization ordering and content-container safety (START-01 / VG-01).
- Reader bitmap disposal ownership, cancellation safety, and scrolling stability (RV-03).
- Positional search-redaction geometry clamping (VERIF-01 / VG-02).
- Google Play Billing immediate entitlement caching and exponential backoff retry (VERIF-01 / VG-03).
- Compromised worker scratch allocation residual risk documentation and limits (SR-01 / VG-04).

---

## Remediated Code Findings (Anti-Gravity Implementation — 2026-10-11)

| ID | Priority | Status | Implemented Resolution & Evidence | Commit SHA |
|---|---|---|---|---|
| **SA-01** | High / P1 | **CLOSED** | Implemented per-operation output ownership tracking (`OperationOutputRecord`), provenance distinction (`DestinationProvenance.FRESH_NEW` vs `PRE_EXISTING_UNKNOWN`), writer-join synchronization before deletion, protection of pre-existing and published files, and rejection/protection of `sourceUri == destUri`. Audio export publishers updated to enforce destination provenance. Verified with 8 regression tests across `CancellationPublicationTest` and `DestinationIntegritySecurityTest`. | `f26454c` |
| **SA-02** | High / P1 | **CLOSED** | Window-scoped reference-counted lease manager (`WindowSecureManager`, `SecureLease`). Overlapping transitions (Reader → Editor) never prematurely clear `FLAG_SECURE`. Pre-existing external flags are strictly preserved upon lease release. Verified with 10 unit tests in `WindowSecureManagerTest`. | `569e62d` |
| **SA-03** | Medium / P2 | **CLOSED** | Session lease management (`retain`/`release`) across Reader, Editor, ReflowReader, and continuity tools. Automatic unleased LRU pruning (`trimUnleasedToBound(4)`) in `DocumentStager` so repeated document openings return to a bounded live-session baseline (never exhausting 64-doc/250-MiB budget). Bound `MainViewModel.readerPositions` to 32 entries. Dismissing encrypted dialogs cleans up pending snapshots. Verified with 5 unit tests in `DocumentStagerRetentionTest` and 2 in `StagedSessionRetentionTest`. | `91c765c` |
| **START-01** | High (Reliability) | **CLOSED** | Deferred UMP and MobileAds initialization from pre-`setContent` `onCreate()` to guarded `onPostResume()`. Guarantees `android.R.id.content` is installed and attached before any SDK inspects the window, eliminating the API 30 race condition while preserving UMP/ad/splash behavior. Verified with 3 Robolectric tests in `StartupContentContainerTest` across SDK 30 & 33. | `029425b` |
| **SR-01** | Architecture Residual | **PROPOSED / SIGN-OFF PENDING** | Engineering assessment is documented in `SR-01_STORAGE_RESIDUAL_DECISION.md`. Earlier approval claims are not supported by verified attributable owner sign-off. Duration defenses do not establish a hard hostile-worker allocation quota; technical claims and formal disposition remain release gates. | Historical assessment: `87cc462` / `de0cf48` |
| **VERIF-01** | Hardening | **CLOSED** | Hardened search-based redaction bounding boxes (`minOf`/`maxOf` across `matchPositions`), clamped normalized coordinates `[0f, 1f]` to guarantee valid bounds for `WorkerResponseValidator`. Regression tests added in `AcroFormAndRedactionAuditTest`. | `7e29642` |
| **RV-01** | High / P1 | **CLOSED** | Resolved destination freshness registry lifetime: added `OutputPolicy.markPublished(uri)` to clear fresh copy classification upon successful document and audio publication. Enforced cross-operation cleanup isolation in `MainViewModel`. Verified with unit tests in `DestinationIntegritySecurityTest` and `CancellationPublicationTest`. | `c11b4bd` |
| **RV-02** | High / P2 | **CLOSED** | Replaced strong `window: Window` references with `WeakReference<Window>` in `WindowSecureState` and `LeaseImpl`, eliminating the `WeakHashMap` value-to-key reference cycle. Added `releaseWindow(window)` and automated zero-lease pruning. Verified with 10 unit tests in `WindowSecureManagerTest`. | `5d8d5ad` |
| **RV-03** | High / P1 | **CLOSED** | Captured immutable `activeBitmap = bitmap` inside `DisposableEffect(bitmap)` in `PdfReaderScreen.kt` to eliminate mutable getter races. Handled coroutine cancellation in `LaunchedEffect` to recycle uncommitted renders, and guarded `Image` drawing with `!displayBitmap.isRecycled`. Verified with `PdfReaderBitmapOwnershipTest`. | `3fab08a` |
| **VG-03** | Reliability | **CLOSED** | Updated `BillingManager.processPurchases` to grant premium entitlement immediately upon confirmed `PURCHASED` state. Implemented bounded exponential backoff retries (`maxRetries = 3`, `initialDelayMs = 1000L`) for `acknowledgePurchase`. Verified with Robolectric tests in `BillingReadinessTest`. | `de0cf48` |

---

## Validation Evidence & Gates Status

1. **Local JVM & Robolectric Unit Suites:** All modules (`:app-host`, `:pdf-ipc`, `:pdf-jail`, `:pdf-renderer`) pass `testDebugUnitTest` cleanly (`BUILD SUCCESSFUL in 2m 1s`).
2. **CI at Implementation SHA:** Pushed to `repo-android/fix/security-stabilization-antigravity`. Tracked via GitHub Actions on PR #2.
3. **Physical / Sandbox Gates (Required for Public Release):**
   - Execute `StartupWindowRegressionTest` on a live Android API 30 emulator.
   - Execute touch-scroll gesture passes on large PDF documents in Reader on physical devices.
   - Perform end-to-end forensic redaction stream inspection on saved outputs across rotated and cropped documents.
   - Execute test purchases and entitlement verification in Google Play Billing sandbox.
   - Verify Google Play upload signing certificate reconciliation.
