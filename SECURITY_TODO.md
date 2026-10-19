# Android security remediation checklist

Date: 2026-10-18. Baseline: `670578355c4e408c2468ba1564b0e1a366faaa0c`. Release: **HOLD**.

Specification: [SECURITY_REMEDIATION_PLAN.md](SECURITY_REMEDIATION_PLAN.md). Findings/evidence: [security analysis](reports/security-code-analysis-2026-10-18/SECURITY_CODE_ANALYSIS.md).

All implementation items start unchecked. The seven existing passing audit probes confirm vulnerable behavior; they are not passing security regressions. Mark an item complete only with its evidence reference. Keep source, JVM, emulator, physical-device and external verification distinct.

## 0. Preserve baseline and prepare execution

- [x] PRE-01 Reconcile local/remote candidate, branch/PR state and current CI without changing existing work. See execution receipt below.
- [x] PRE-02 Freeze baseline probes, synthetic PDFs, XML and logs with a hash manifest; preserve them separately from post-fix evidence.
- [x] PRE-03 Create an isolated implementation worktree and record its base SHA.
- [x] PRE-04 Establish a finding-to-commit/test/evidence matrix; retain all SC and RA aliases. Finding scopes are in the analysis/plan; execution matrix is in the receipt.

## 1. SC-01 — Editor forensic redaction

- [x] SC01-01 Reproduce retained sensitive Forms/images through the actual editor worker export. Three expected security assertion failures on the unchanged baseline APK, API 24; see execution receipt.
- [x] SC01-02 Construct sanitized redacted-page resources/object ownership; preserve unrelated content and fail-closed behavior. Form-tree cleanup, hidden widgets, shared-stream rasterization and document-carrier removal pass local worker/native checks; full CI remains a separate gate below.
- [x] SC01-03 Convert component probes into absence-of-sensitive-data regressions; cover scanned images, shared resources, retained form values, metadata/accessibility/embedded originals and outline-linked structure elements. All 22 helper regressions pass within the 63-test worker suite; the extended nine-method native selection passes on API24 and API36. See execution receipt and raw evidence.
- [x] SC01-04 Verify saved decoded objects/resources, extraction and pixels; run actual editor export on API 24/30/34/36. Final source `62d1b1e8` passes 221 methods per API (884 total), including all eight Editor security regressions and annotation parity; downloaded artifacts are validated in the execution receipt. Earlier checkpoints remain separate.
- [x] SC01-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Final application/test source `62d1b1e8` is committed and remote-verified; full CI run `39228725164` passes all nine jobs. The following closeout commit contains documentation/evidence only.

## 2. SC-02 — Search-redaction object graph

- [x] SC02-01 Exercise actual worker search, select returned targets and export the internal-link fixture. The unchanged app fails before publication with closed imported streams; the separate saved-PDF component probe establishes the retained-sensitive-stream leak. See SC02_EXECUTION.md; no successful leaking native export or UI automation is claimed.
- [x] SC02-02 Keep destinations attached to sanitized page identities and prevent imported original-sensitive references. One live document graph, transitive shared-stream replacement and final reachability checks pass local native tests.
- [x] SC02-03 Cover direct destinations, GoTo actions, annotations, cross-page links and shared resources. Eight security methods pass within ten-method local API24/36 selections, including crop/rotation pixels and multiline selection. See the SC-02 receipt.
- [x] SC02-04 Verify saved objects, extraction, pixels, crop/rotation/multiline cases and safe navigation on Android. Source `383f193a` passes 229 methods each on CI API24/30/34/36 (916 total), including all eight new security cases; downloaded artifacts are validated in the SC-02 receipt.
- [x] SC02-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source `383f193a` is committed/remote-verified; run `39236250552` passes all nine jobs after the unchanged secret-check retry. The closeout commit changes only documentation/evidence.

## 3. SC-03 — Published batch output survives cancellation

- [x] SC03-01 Preserve terminal PUBLISHED state during cancellation/error/clearance cleanup. Atomic terminal publication passes the focused state regression; see SC03_EXECUTION.md.
- [x] SC03-02 Seed real provider bytes; cancel PDF/image batches during item 2 and retain completed item 1. Actual batch regressions fail on the unchanged APK, then pass on local API24/36 with byte preservation and incomplete-output cleanup assertions.
- [x] SC03-03 Verify incomplete-only deletion after writer termination, stale cancellation and newer-operation isolation. Four focused host unit tests and two real native batch methods per API24/36 pass; sentinel barriers establish cleanup ordering.
- [ ] SC03-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 4. SC-04 — Successful Warning output survives lifecycle cleanup

- [ ] SC04-01 Finalize publication after successful writing regardless of Warning presentation.
- [ ] SC04-02 Verify target-missed output survives dismissal, cancellation, ViewModel destruction and reuse.
- [ ] SC04-03 Verify freshness is retired while true failure still cleans only eligible incomplete output.
- [ ] SC04-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 5. SC-05 — Direct-screen provenance and completion

- [ ] SC05-01 Inventory all destination creators, writers and success/failure/cancel callbacks, including Redaction, Markdown and audio.
- [ ] SC05-02 Carry operation-scoped provenance and finalize every successful publication.
- [ ] SC05-03 Verify published direct-screen URI reuse preserves seeded existing bytes on later failure/cancellation.
- [ ] SC05-04 Verify fresh failed/abandoned destinations are cleaned after writer termination; preserve picker/folder behavior.
- [ ] SC05-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 6. Retained RA-05 — Redaction operation lifetime

- [ ] RA05-01 Give export retained ownership and an explicit Back/input/cancellation policy.
- [ ] RA05-02 Verify delayed export, Back, replacement input, recreation and provider failure through actual UI integration.
- [ ] RA05-03 Complete batch checks and a separate finding commit with remote/CI evidence.

## 7. SC-06 — Cancellation-safe lease and bitmap transfer

- [ ] SC06-01 Cover cancellation after resource acquisition but before caller dispatcher resumption.
- [ ] SC06-02 Fix staging transfer across Reader/Editor/Reflow without leaking leases or snapshots.
- [ ] SC06-03 Verify/fix the analogous native-rendered bitmap transfer and disposal boundary.
- [ ] SC06-04 Repeat cancellation/navigation/retry/recreation without quota drift; preserve active borrowers.
- [ ] SC06-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 8. SC-07 — Staging synchronization and stalled providers

- [ ] SC07-01 Add bounded deterministic providers stalled in query/open/read; establish the failing responsiveness contract.
- [ ] SC07-02 Move provider I/O outside the global monitor using short state transitions and quota reservations.
- [ ] SC07-03 Preserve atomic snapshot publication/initial lease, cancellation cleanup, aggregate quotas and concurrent eviction protection.
- [ ] SC07-04 Verify Main-thread retain/release and unrelated sessions stay responsive; document cancellation limits.
- [ ] SC07-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 9. SC-08 — Bounded Markdown admission

- [ ] SC08-01 Choose documented byte and decoded-character budgets appropriate to the editor.
- [ ] SC08-02 Enforce streaming limits, cancellation, input closure and visible rejection without replacing existing editor content.
- [ ] SC08-03 Test known/unknown-length boundaries, plus-one, multibyte and controlled endless streams safely.
- [ ] SC08-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 10. SC-09 — Source preservation

- [ ] SC09-01 Preserve original identity through staging and enforce known same-document rejection before destructive output opens.
- [ ] SC09-02 Define safe handling of supported aliases and unknown destination freshness.
- [ ] SC09-03 Seed source bytes and prove equality/known-alias rejection, no destructive open and unchanged bytes; keep unrelated/fresh outputs working.
- [ ] SC09-04 Replace the old rejection-named test with assertions that prove policy enforcement.
- [ ] SC09-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt.

## 11. Retained findings and risk disposition

- [ ] FOLLOW-01 RA-02: invalidate Reader search on Clear; test delayed completion and scrolling.
- [ ] FOLLOW-02 RA-03: preserve cancellation and accurate provider failure diagnosis for single/multi pickers with Vanguard on/off.
- [ ] FOLLOW-03 Define external VIEW/SEND URI admission separately from trusted internal staged capabilities.
- [ ] FOLLOW-04 Run separate-sender/provider Android tests for scheme/grant/private-path policy and record actual impact.
- [ ] FOLLOW-05 Reconcile existing window/startup/billing/consent/audio evidence without assuming closure or rewriting correct fixes. Investigate the recorded local API24/WebView 51 host SIGILL from the full suite; clean-state comparison and attribution are in the execution receipt.
- [x] FOLLOW-06 Reconcile SR-01 technical guarantees and contradictory approval claims. See updated SR-01 assessment and execution receipt; stronger enforcement and owner disposition remain open.
- [ ] FOLLOW-07 Record attributable owner risk disposition or implement and verify a stronger boundary.
- [ ] FOLLOW-08 Reconcile redaction API count semantics: the worker reports selected-box count, but the shared host standard-output contract currently returns one output. The SC-02 multiline regression verifies every selected line through saved-data absence; no matched-box count correction is claimed.

## 12. Integrated release verification

- [ ] FINAL-01 Architecture/host-boundary gates and required module unit suites pass on the resulting candidate.
- [ ] FINAL-02 Debug/Android-test assembly and release lint/minified build pass; record remaining warnings/baselines.
- [ ] FINAL-03 Complete same-candidate API 24/30/34/36 instrumentation passes, including real secure exports and lifecycle/provider/concurrency cases.
- [ ] FINAL-04 Current resolved-dependency and complete reachable-history secret gates pass or have explicit finding dispositions.
- [ ] FINAL-05 Corpus provenance/hashes, current route coverage, manifests, production IDs and artifact hygiene pass.
- [ ] FINAL-06 Signed APK/AAB source/version/hash/signature identities are recorded; Play version-code and upload-key checks are complete.
- [ ] FINAL-07 Signed physical workflow, corpus, 15 audio scenarios and screenshot/task-snapshot checks have actual results.
- [ ] FINAL-08 Forensic inspection of final real export outputs confirms absence of original sensitive content/resources/references.
- [ ] FINAL-09 Play Billing/UMP sandbox and consent/privacy-options/revoke checks are complete.
- [ ] FINAL-10 Accessibility and UX-23 performance evidence have results or explicit owner-reviewed dispositions.
- [ ] FINAL-11 Required Play declarations/video and Data Safety/privacy disclosures match the actual candidate.
- [ ] FINAL-12 Final receipt maps all findings, accepted risks, deferrals and gates to the exact candidate/artifacts; owner release decision is recorded.

## Batch ledger

Retain failed attempts alongside successful verification. Use NOT RUN, FAIL, BLOCKED or DEFERRED explicitly. A source-only result cannot complete a required device gate.

| Batch / finding | Status | Commit / remote SHA | Evidence level and location | Remaining gate |
|---|---|---|---|---|
| Baseline review | COMPLETE — findings demonstrated | `670578355c4e408c2468ba1564b0e1a366faaa0c` | Analysis, seven component probes and 34 existing focused tests in reports/security-code-analysis-2026-10-18 | All remediation remains open |
| SC-01 | COMPLETE: implementation and batch gates verified | Final source `62d1b1e8`, Android remote verified | 63 worker tests and nine focused local native methods pass; CI run `39228725164` passes 221 methods on each API24/30/34/36 (884 total), all eight Editor regressions included | None for this batch; legacy WebView observation remains separately under FOLLOW-05 and overall release stays HOLD |
| SC-02 | COMPLETE: implementation and batch gates verified | Source `383f193a`, Android remote verified | Local worker/native/build/lint/artifact gates and full same-source CI pass; 229 native methods per API24/30/34/36, 916 total | None for this batch; overall release remains HOLD |
| SC-03 | Local implementation/build/native gates complete; full CI pending | Batch after SC-02 closeout `ce58c234`; exact commit/ref recorded in the SC-03 receipt | Four host unit tests, actual PDF/image batch tests on API24/36, assembly/static/lint, unsigned minified release and artifact checks pass | Commit/push/ref verification and complete same-source CI |

Current evidence/status: [execution receipt](reports/security-remediation-2026-10-18/EXECUTION_STATUS.md).

No implementation, merge, upload or rollout is marked complete by preparation of this checklist.
