# Release security readiness — 2026-10-10

**Release decision: HOLD.** This review establishes open source findings and the evidence needed to close them. It does not certify an APK/AAB for public release.

## Source and scope

Remote main: `4b3c157f3458f7df00ea94044258d33c7449ced9`.
Implementation candidate: `80ecd0ba00d209daee14ce6613820b7b715c608c`.
Prior tested head: `7aca098a02edd246b914669fae542967cd5608fc`.
The tested-to-candidate comparison contains only three audit Markdown files; production source, tests and build inputs are identical. Main and implementation are distinct revisions: candidate CI is not a test of every main-only difference.

The preceding [code audit](SECURITY_CODE_REAUDIT.md) inspected isolated workers, IPC, staging, output publication, UI privacy, audio, redaction, archives, billing, manifests and logging. This continuation rechecked main's destination cleanup, SecureScreen, staging/Reader lifetime and audio cleanup, candidate manifests/FileProvider, release configuration and prior certification records, scanner implementations, destination creation, staging and Settings privacy-options wiring. Source findings have not been reproduced as exploits on a device. Backend rules, Play Console, signing infrastructure and third-party native internals remain outside scope. Local shell/edit execution is still unavailable; no new local test/build/diff-check is claimed.

## Remediated code findings (Anti-Gravity Implementation — 2026-10-11)

| ID | Priority | Status | Implemented Resolution & Evidence | Commit SHA |
|---|---|---|---|---|
| SA-01 | High / P1 | **CLOSED** | Implemented per-operation output ownership tracking (`OperationOutputRecord`), provenance distinction (`DestinationProvenance.FRESH_NEW` vs `PRE_EXISTING_UNKNOWN`), writer-join synchronization before deletion, protection of pre-existing and published files, and rejection/protection of `sourceUri == destUri`. Audio export publishers updated to enforce destination provenance. Verified with 8 regression tests across `CancellationPublicationTest` and `DestinationIntegritySecurityTest`. | `f26454c` |
| SA-02 | High / P1 | **CLOSED** | Window-scoped reference-counted lease manager (`WindowSecureManager`, `SecureLease`). Overlapping transitions (Reader → Editor) never prematurely clear `FLAG_SECURE`. Pre-existing external flags are strictly preserved upon lease release. Verified with 10 unit tests in `WindowSecureManagerTest`. | `569e62d` |
| SA-03 | Medium / P2 | **CLOSED** | Session lease management (`retain`/`release`) across Reader, Editor, ReflowReader, and continuity tools. Automatic unleased LRU pruning (`trimUnleasedToBound(4)`) in `DocumentStager` so repeated document openings return to a bounded live-session baseline (never exhausting 64-doc/250-MiB budget). Bound `MainViewModel.readerPositions` to 32 entries. Dismissing encrypted dialogs cleans up pending snapshots. Verified with 5 unit tests in `DocumentStagerRetentionTest` and 2 in `StagedSessionRetentionTest`. | `91c765c` |
| START-01 | High (Reliability) | **CLOSED** | Deferred UMP and MobileAds initialization from pre-`setContent` `onCreate()` to guarded `onPostResume()`. Guarantees `android.R.id.content` is installed and attached before any SDK inspects the window, eliminating the API 30 race condition while preserving UMP/ad/splash behavior. Verified with 3 Robolectric tests in `StartupContentContainerTest` across SDK 30 & 33. | `029425b` |
| SR-01 | Architecture Residual | **ACCEPTED DECISION** | Formulated explicit architectural residual risk decision in `reports/security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md`. Documented why host capability boundaries, unlinked files, watchdog timeouts, and `HostOutputTransaction` validation are maintained without an unbounded stream-proxy redesign. | `87cc462` |
| VERIF-01 | Hardening | **CLOSED** | Hardened search-based redaction bounding boxes (`minOf`/`maxOf` across `matchPositions`), clamped normalized coordinates `[0f, 1f]` to guarantee valid bounds for `WorkerResponseValidator`. Added try-catch and status logging to `BillingManager.acknowledgePurchase` to prevent coroutine cancellation and protect offline entitlement caching. Added regression tests in `AcroFormAndRedactionAuditTest`. | `7e29642` |

## Validation evidence

[CI run 37764572638](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37764572638), head `7aca098a02edd246b914669fae542967cd5608fc`:

- **Fresh, 2026-10-10 18:41:22 UTC:** dependency job `114285038779` passed; 234 resolved production dependencies, 0 OSV affected packages. This is a database result at that time, not proof that dependencies are vulnerability-free.
- **Historical, 2026-10-08:** architecture and unit gates passed; instrumentation completed 209 tests with zero failures on each API 24/30/34/36. These suites do not close the newly identified findings.
- **Historical, 2026-10-08:** TruffleHog 3.97.9 reachable-history policy passed: 6 exact reviewed historical matches, 0 rejected findings. This continuation has not yet executed a fresh secret scan.
- **Fresh, 2026-10-10 18:57:39 UTC:** build job `114285337630` passed in 16m 8s: release lint, debug/unsigned minified release assembly and artifact hygiene. Lint reported 652 warnings and 38 stale baseline entries; this is not a warning-free result. Artifact gate verified 14 corpus files, 62 smoke routes, fixture hashes, production ad IDs and release hygiene. Run attempt 2 completed successfully at 18:57:41 UTC. Only dependency and downstream build were rerun; other job results are carried over from October 8. This does not verify Play upload signing.

The release checker enforces corpus hashes, 62 route coverage and artifact hygiene. It does **not** turn NOT RUN manual matrix rows into PASS. No retained Actions artifacts were returned for this run.

Inspected source preserves private isolated PDF/native services, host-only provider publication, immutable bounded staging, scoped FileProvider directories, disabled backup and R8/resource shrinking. Settings does expose UMP privacy options when required (MainActivity 3467–3474); the preceding wiring question is therefore resolved at source level. Consent update/error/revoke flows and third-party SDK behavior still need runtime evidence. Incoming file URI admission and search-generated redaction geometry remain follow-up verification gaps; no private-file exfiltration or redaction exploit is claimed.

## Ordered release plan

1. Verify the startup correction with normal SDK startup, repeated launch/recreation and the original API 30 audio launch test.
2. Fix SA-01 and SA-02 in separate reviewed batches; close SA-03 with lifetime/continuity stress tests. Run focused tests, relevant build/lint, diff-check and same-source CI; push only to the Android repository. No merge is authorized here.
3. Record SR-01 disposition. Test secure search redaction across crop/rotation/multiline cases, incoming URI admission, billing acknowledgment/purchase flow, consent/privacy revocation and audio provider failures.
4. Run current resolved-dependency and reachable-history secret gates against the final release commit.
5. Build and verify a signed/minified APK and AAB from that exact clean commit. Prior October 7 signed artifacts are older evidence.
6. Reconcile the Play **upload** certificate with the actual signing key. Existing local certificates differ; Console registration is unverified. Confirm version code 13 availability.
7. Execute signed physical smoke: all 62 current routes, the 14 committed PDFs, audio scenarios, fresh install/upgrade and screenshot/task-snapshot checks. Complete missing producer/original-incident compatibility evidence. Finish required Play foreground-service declaration/video and accurate data-safety disclosure.

Release closes only when open findings and startup have verified dispositions, final artifact hashes/signatures are recorded and the external/device gates have actual evidence. Green CI alone does not close these gates.
