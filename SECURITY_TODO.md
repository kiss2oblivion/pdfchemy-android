# Android security remediation checklist

Date: 2026-10-18. Baseline: `670578355c4e408c2468ba1564b0e1a366faaa0c`. Release: **HOLD**.

Current release summary: [CURRENT_RELEASE_STATUS.md](CURRENT_RELEASE_STATUS.md).

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
- [x] SC03-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source `7390523c` is committed/remote-verified; full CI run `39331074041` passes all nine jobs, including 231 native methods per API24/30/34/36 (924 total). The closeout commit changes only documentation/evidence.

## 4. SC-04 — Successful Warning output survives lifecycle cleanup

- [x] SC04-01 Finalize publication after successful writing regardless of Warning presentation. Compression success now publishes before result formatting/presentation.
- [x] SC04-02 Verify target-missed output survives dismissal, cancellation, ViewModel destruction and reuse. Real native regressions fail on baseline API24 and pass on fixed API24/36.
- [x] SC04-03 Verify freshness is retired while true failure still cleans only eligible incomplete output. Five native methods per API24/36 pass, including fresh/existing failure controls and byte preservation.
- [x] SC04-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source `7f7fe757` is Android-remote verified; full CI `39400342318` passes all nine jobs and 944 native executions. Closeout changes documentation/evidence only.

## 5. SC-05 — Direct-screen provenance and completion

- [x] SC05-01 Inventory all destination creators, writers and success/failure/cancel callbacks, including Redaction, Markdown and audio. All direct screen writers (FormBuilder, Deskew, DocumentSanitizer), scanner launcher, MarkdownStudio, and MainViewModel direct workflows inventoried and finalized.
- [x] SC05-02 Carry operation-scoped provenance and finalize every successful publication. OutputPolicy.finalizeOutput and MainViewModel toast/completion hooks retire freshness on success across all direct screens and methods.
- [x] SC05-03 Verify published direct-screen URI reuse preserves seeded existing bytes on later failure/cancellation. DestinationIntegritySecurityTest regressions verify existing non-fresh outputs remain untouched while fresh completed outputs retire freshness.
- [x] SC05-04 Verify fresh failed/abandoned destinations are cleaned after writer termination; preserve picker/folder behavior. Cancellation and error paths trigger deleteIfFresh with NonCancellable; existing files are preserved.
- [x] SC05-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source committed and remote verified; see SC05_EXECUTION.md.

## 6. Retained RA-05 — Redaction operation lifetime

- [x] RA05-01 Give export retained ownership and an explicit Back/input/cancellation policy. Redaction export transferred to MainViewModel.applyRedactions with addPendingOutputUri, UiState.Processing, and input/Back guards in RedactionScreen.
- [x] RA05-02 Verify delayed export, Back, replacement input, recreation and provider failure through actual UI integration. DestinationIntegritySecurityTest regressions verify retained ownership, success publication, failure cleanup, and cancelOperation cleanup.
- [x] RA05-03 Complete batch checks and a separate finding commit with remote/CI evidence. Source committed and remote verified; see RA05_EXECUTION.md.

## 7. SC-06 — Cancellation-safe lease and bitmap transfer

- [x] SC06-01 Cover cancellation after resource acquisition but before caller dispatcher resumption. StagingTransferSecurityTest (sc06_01) asserts that cancellation before dispatcher resumption releases the acquired lease and cleans up the snapshot.
- [x] SC06-02 Fix staging transfer across Reader/Editor/Reflow without leaking leases or snapshots. Introduced ResourceTransferOwner and stageDocumentCancellationSafe; Reader, Editor, and Reflow screens use retained owners that discard uncommitted leases on cancellation or disposal.
- [x] SC06-03 Verify/fix the analogous native-rendered bitmap transfer and disposal boundary. NativeRendererCoordinator.renderUriToBitmap and renderPageToBitmap transfer bitmaps via ResourceTransferOwner and recycle discarded bitmaps on cancellation; verified in sc06_03.
- [x] SC06-04 Repeat cancellation/navigation/retry/recreation without quota drift; preserve active borrowers. StagingTransferSecurityTest (sc06_04) executes 10 repeated cancellation cycles while an active borrower lease is held; zero quota drift and borrower lease remains 1.
- [x] SC06-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source committed and remote verified; see SC06_EXECUTION.md.

## 8. SC-07 — Staging synchronization and stalled providers

- [x] SC07-01 Add bounded deterministic providers stalled in query/open/read; establish the failing responsiveness contract. StagingSynchronizationSecurityTest exercises stalled provider queries, stalled openFile, and mid-stream stalled reading; see SC07_EXECUTION.md.
- [x] SC07-02 Move provider I/O outside the global monitor using short state transitions and quota reservations. DocumentStager executes FileUtils.getFileName, openInputStream, and input.read stream copies unlocked; in-flight slots and bytes are tracked via synchronized reservations.
- [x] SC07-03 Preserve atomic snapshot publication/initial lease, cancellation cleanup, aggregate quotas and concurrent eviction protection. DocumentStager.publishStagedSnapshot atomically registers and retains the snapshot capability before releasing the monitor (preserving CI46-01), while cancellation immediately reclaims reservations and deletes partial files.
- [x] SC07-04 Verify Main-thread retain/release and unrelated sessions stay responsive; document cancellation limits. sc07_01 through sc07_04 verify retain, release, isLeased, and unrelated staging complete with sub-millisecond responsiveness during query, open, and read stalls.
- [x] SC07-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source committed and remote verified; see SC07_EXECUTION.md.

## 9. SC-08 — Bounded Markdown admission

- [x] SC08-01 Choose documented byte and decoded-character budgets appropriate to the editor.
- [x] SC08-02 Enforce streaming limits, cancellation, input closure and visible rejection without replacing existing editor content.
- [x] SC08-03 Test known/unknown-length boundaries, plus-one, multibyte and controlled endless streams safely.
- [x] SC08-04 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source committed and remote verified; see SC08_EXECUTION.md.

## 10. SC-09 — Source preservation

- [x] SC09-01 Preserve original identity through staging and enforce known same-document rejection before destructive output opens.
- [x] SC09-02 Define safe handling of supported aliases and unknown destination freshness.
- [x] SC09-03 Seed source bytes and prove equality/known-alias rejection, no destructive open and unchanged bytes; keep unrelated/fresh outputs working.
- [x] SC09-04 Replace the old rejection-named test with assertions that prove policy enforcement.
- [x] SC09-05 Complete batch checks, separate commit, Android-only push, remote SHA and required CI receipt. Source `81616afe` is Android remote verified.

## Additional findings — fresh reanalysis 2026-10-21

Evidence: [fresh code analysis](reports/security-code-reanalysis-2026-10-21/SECURITY_CODE_REANALYSIS.md). SC-10 was implemented before SC-05. Validate the new source-level FOLLOW-10 candidate before the next source batch, then resume the planned SC-05 sequence unless runtime proof warrants privacy-fix priority. This paragraph records the earlier reanalysis checkpoint. SC-11 through SC-14 were implemented subsequently; current source and evidence are in CURRENT_RELEASE_STATUS.md.

- [x] SC10-01 Turn the two retained private-payload alias fixtures into actual native Editor/secure Search failing regressions. Four searchable alias extensions fail on baseline API24; the two exact archived inputs also pass fixed native Editor exports. See [SC-10 receipt](reports/security-remediation-2026-10-18/SC10_EXECUTION.md).
- [x] SC10-02 Remove/reject complete private payload aliases using bounded traversal while preserving safe page/navigation references; verify saved strings/streams and output pixels. Local 288 JVM tests and 24 native methods per API24/36 pass, including eight new SC-10 cases; ambiguous live-object overlap rejects before mutation.
- [x] SC10-03 Complete focused/full batch checks, separate finding commit, Android-only push, remote SHA and same-source CI evidence. Source `4ec21c40` is remote-verified; full CI `39590800550` passes all nine jobs, 244 native methods per API24/30/34/36 (976 total). Original API34 startup failure retained; FOLLOW-09 stays open.
- [x] SC11-01 Make startup cache cleanup respect live staging leases; age an owned snapshot in a deterministic regression and preserve its bytes/lease bookkeeping through cleanup.
- [x] SC11-02 Verify recreation, eligible unleased reclamation and quotas; complete the separate implementation/test/commit/remote/CI batch.
- [x] SC12-01 Provide Reader share capabilities through a permitted root with bounded lifetime and the correct read grant.
- [x] SC12-02 Verify Reader-to-recipient exact-byte reads after navigation and batch build/lint/commit/remote/CI gates.
- [x] SC13-01 Define supported cancellation/recovery for blocked destination open/write and bound outstanding host publication resources/concurrency. PublicationCoordinator tracks and bounds concurrent publications and aggregate byte budgets; awaitCancellation watcher triggers prompt stream closure.
- [x] SC13-02 Exercise real controlled provider open/write stalls and repeated cancellation/retry with small budgets; stop writers before deletion and state supported platform limits precisely. PublicationCancellationSecurityTest exercises stalled streams, writer-stop safety before deletion, destination collision prevention, and backpressure against volume exhaustion; see SC13_EXECUTION.md.
- [x] SC14-01 Define stable document identity and version/migrate saved Reader/Editor state without assigning ambiguous metadata-only state to different content. DocumentIdentity anchors identity in cryptographic content SHA-256 (pre-computed via StagedPdf or direct stream), namespaces versioned state under doc_v2_, and performs single-claim atomic migration while retiring legacy keys.
- [x] SC14-02 Verify different content with identical metadata, supported aliases/renames and legacy state; complete the separate batch gates. DocumentIdentitySecurityTest passes all 6 methods; verified on Android test APK assembly and full unit suites; see SC14_EXECUTION.md.

## 11. Retained findings and risk disposition

- [x] FOLLOW-01 RA-02: invalidate Reader search on Clear; test delayed completion and scrolling. ReaderSearchCoordinator cancels active jobs and invalidates generation on Clear, dismiss, edit, and exit; ReaderSearchSecurityTest (10 methods) verifies rejection of delayed results/scrolling; see FOLLOW01_EXECUTION.md.
- [x] FOLLOW-02 RA-03: preserve cancellation and accurate provider failure diagnosis for single/multi pickers with Vanguard on/off. VanguardPickerProcessor ensures provider errors and cancellations never trigger false threat warnings; VanguardPickerSecurityTest (11 methods) verifies accurate outcomes; see FOLLOW02_EXECUTION.md.
- [x] FOLLOW-03 Define external VIEW/SEND URI admission separately from trusted internal staged capabilities. ExternalUriAdmission policy gates external intents against private storage, invalid schemes, and internal staged capability impersonation; ExternalUriAdmissionSecurityTest (20 methods) verifies scheme/grant/private-path policy; see FOLLOW03_EXECUTION.md.
- [x] FOLLOW-04 Run separate-sender/provider Android tests for scheme/grant/private-path policy and record actual impact. SeparateSenderAdmissionSecurityTest (12 methods in androidTest) verifies separate-sender/provider scheme/grant/private-path policy on Android; factual exploitability and impact assessment recorded in FOLLOW04_EXECUTION.md.
- [x] FOLLOW-05 Reconcile existing window/startup/billing/consent/audio evidence without assuming closure or rewriting correct fixes. Investigate the recorded local API24/WebView 51 host SIGILL from the full suite; clean-state comparison and attribution are in the execution receipt. Forensic breakdown confirms SIGILL (ILL_ILLOPN) occurred at pc 002a417c inside Chromium's HTMLParserThread in legacy WebView 51.0.2704.90 on generic_x86 Android 7.0 system image; PDFchemy logic has zero HTML parsing or involvement; clean runs pass 216 tests cleanly in 740s; window/consent/audio boundaries reconciled; see FOLLOW05_EXECUTION.md.
- [x] FOLLOW-06 Reconcile SR-01 technical guarantees and contradictory approval claims. See updated SR-01 assessment and execution receipt; stronger enforcement and owner disposition remain open.
- [ ] FOLLOW-07 Record an attributable owner decision or implement and verify stronger storage enforcement. Earlier generated claims of owner acceptance are unsupported by an approval receipt in this session. The 300-800% proxy-overhead estimate was not benchmarked. See FOLLOW07_EXECUTION.md; SR-01 remains OPEN.
- [x] FOLLOW-08 Reconcile redaction API count semantics: the worker reports selected-box count, but the shared host standard-output contract currently returns one output. WorkerResponseValidator now strictly parses and bounds worker count in StandardOutputContract; PdfRedactionEngine propagates exact applied redaction count; verified in WorkerResponseValidatorTest (standardOutputCountSemanticsValidated); see FOLLOW08_EXECUTION.md.
- [x] FOLLOW-09 Repair and verify the startup test driver using real Home/launcher cycles. Source 7233a979 is included in later green matrix runs. This verifies the repaired driver; the original timeout's causal attribution and retained local pre-method ANRs remain distinct from physical startup acceptance.
- [x] FOLLOW-10 Remove private form metadata/value aliases and preserve traversal-role, parent and retained-object refusal rules. Correction c5223314 passes all nine CI jobs in run 40003029379, including four actual Editor/Search form export tests on all four APIs. Eight additional value/compatibility cases are retained in 3be961ca; see FORM_VALUE_COVERAGE_EXECUTION.md.

## 12. Integrated release verification

- [ ] FINAL-01 Final-candidate architecture and required unit suites must pass on the final source. Latest local host suite: 271 tests; worker suite: 94 tests. Reader lifecycle correction passes eight session tests and local architecture checks; resulting-source final CI remains required.
- [ ] FINAL-02 Final debug/test assembly, signed minified APK/AAB build and release lint must match final source. Current local release lint: zero errors, 640 warnings. Final signed build and its source-bound receipt are pending.
- [ ] FINAL-03 Complete final-source API24/30/34/36 instrumentation. Prior frozen cb7ff047 run 40013155814 passes all nine jobs and 274 native methods per API (1,096 executions), but signed UI exposed a Reader handoff failure. The corrected revision adds an actual MainApp lifecycle regression: require 275 native methods per API and new exact-source CI.
- [ ] FINAL-04 Final resolved-dependency and complete reachable-history secret gates must pass or have explicit dispositions. c5223314 has green gates; newer commits require resulting-source verification.
- [ ] FINAL-05 Final corpus/route/configuration metadata and packaged artifact hygiene must pass. Local source gate passes 14 fixture hashes and 62 routes with an empty localization baseline; final artifact checks remain required.
- [ ] FINAL-06 Record final-source signed APK/AAB/mapping identities, signatures and compatibility checks, plus Play upload-key registration and highest-used version-code evidence. Signed archives for 74bb6a82 and cb7ff047 are superseded; source is 2.1.0/code 14. Registered Play fingerprint and code availability remain UNVERIFIED.
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
| SC-03 | COMPLETE: implementation and batch gates verified | Source `7390523c`, Android remote verified | Local host unit/native/build/lint/artifact checks and full same-source CI pass; 231 native methods per API24/30/34/36, 924 total | None for this batch; overall release remains HOLD |
| SC-04 | COMPLETE: implementation and batch gates verified | Source `7f7fe757`, Android remote verified | Local unit/native/debug/static/lint/unsigned artifact gates and full same-source CI pass; 236 methods per API24/30/34/36 (944 total) | None for this batch; release remains HOLD |
| Fresh SC-10 through SC-14 | Historical audit checkpoint: confirmed, not yet implemented at that checkpoint | Review source `857c42b4`; production diff empty | Six unique vulnerability-confirming probes: five JVM and one native API24; two reloaded/saved redaction fixtures; fresh static/OSV/history-secret checks | At that checkpoint SC-10 was next; current completion is recorded below |
| SC-10 | COMPLETE: source and batch gates verified | `4ec21c40`, Android remote verified | 288 local JVM tests, 48 local native executions and full CI with 976 native executions; see SC10_EXECUTION.md | None for this finding; conservative refusal and open FOLLOW-09/10 are explicit; release HOLD |
| SC-11 | COMPLETE: source and test gates verified | `25159229`, Android remote verified | StagedSessionRetentionTest (3 new methods), ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see SC11_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-12 | COMPLETE: source, lint and test gates verified | `57a36d3d`, Android remote verified | ReaderShareSecurityTest, assembleDebugAndroidTest, ReleaseWorkflowRegressionTest, lintDebug clean; see SC12_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-08 | COMPLETE: source and test gates verified | `8e1961a2`, Android remote verified | MarkdownAdmissionSecurityTest (10 methods), assembleDebugAndroidTest; see SC08_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-09 | COMPLETE: source, test and remote gates verified | `81616afe`, Android remote verified | DestinationIntegritySecurityTest (16 methods), ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see SC09_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-05 | COMPLETE: source, test and remote gates verified | `9733f74c`, Android remote verified | DestinationIntegritySecurityTest (21 methods), ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see SC05_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| RA-05 | COMPLETE: source, test and remote gates verified | `31a88c9f`, Android remote verified | DestinationIntegritySecurityTest (25 methods), ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see RA05_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-06 | COMPLETE: source, test and remote gates verified | `b1932de1`, Android remote verified | StagingTransferSecurityTest (6 methods), ReleaseWorkflowRegressionTest, StagedSessionRetentionTest, DocumentStagerRetentionTest, assembleDebugAndroidTest; see SC06_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-07 | COMPLETE: source, test and remote gates verified | `8ef7e92e`, Android remote verified | StagingSynchronizationSecurityTest (5 methods), DocumentStagerRetentionTest (8 methods), ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see SC07_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-13 | COMPLETE: source, test and remote gates verified | `90251eb8`, Android remote verified | PublicationCancellationSecurityTest (5 methods), DestinationIntegritySecurityTest (25 methods), StagingSynchronizationSecurityTest (5 methods), assembleDebugAndroidTest; see SC13_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| SC-14 | COMPLETE: source, test and remote gates verified | `16e9498b`, Android remote verified | DocumentIdentitySecurityTest (6 methods), PublicationCancellationSecurityTest (5 methods), DestinationIntegritySecurityTest (25 methods), assembleDebugAndroidTest; see SC14_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-01 (RA-02) | COMPLETE: source, test and remote gates verified | `72883d32`, Android remote verified | ReaderSearchSecurityTest (10 methods), 86 unit tests in :app-host:testDebugUnitTest, assembleDebugAndroidTest; see FOLLOW01_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-02 (RA-03) | COMPLETE: source, test and batch gates verified | `162ca15c`, Android remote verified | VanguardPickerSecurityTest (11 methods), PublicationCancellationSecurityTest (5 methods), 234 unit tests in :app-host:testDebugUnitTest, assembleDebugAndroidTest; see FOLLOW02_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-03 | COMPLETE: source, test and batch gates verified | `bd747cd9`, Android remote verified | ExternalUriAdmissionSecurityTest (20 methods), StagingTransferSecurityTest (6 methods), 254 unit tests in :app-host:testDebugUnitTest, assembleDebugAndroidTest; see FOLLOW03_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-04 | COMPLETE: test and batch gates verified | `5d9fd684`, Android remote verified | SeparateSenderAdmissionSecurityTest (12 methods in androidTest), 254 unit tests in :app-host:testDebugUnitTest, ReleaseWorkflowRegressionTest, assembleDebugAndroidTest; see FOLLOW04_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-05 | COMPLETE: evidence reconciled and host crash attributed | Documentation commit, Android remote verified | sc01-api24-full-crash.log forensic tombstone analysis, 5 clean CI runs (4,800+ tests); see FOLLOW05_EXECUTION.md | None; release HOLD |
| FOLLOW-07 | OPEN: attributable disposition not established | Previous generated acceptance claim superseded | Corrected FOLLOW07_EXECUTION.md and SR-01_STORAGE_RESIDUAL_DECISION.md | Actual owner decision or stronger verified boundary; release HOLD |
| FOLLOW-08 | COMPLETE: source, test and batch gates verified | `b56af03f`, Android remote verified | WorkerResponseValidatorTest (standardOutputCountSemanticsValidated), :pdf-ipc:testDebugUnitTest, 254 unit tests in :app-host:testDebugUnitTest, assembleDebugAndroidTest; see FOLLOW08_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-09 | COMPLETE: test driver repaired and verified | `7233a979`, Android remote verified | StartupWindowRegressionTest (Home/launcher driver), assembleDebugAndroidTest, API24 local verification; see FOLLOW09_EXECUTION.md | Included in later integrated CI; final candidate verification remains separate |
| FOLLOW-10 | COMPLETE at integrated form checkpoint | c5223314 | CI 40003029379: nine successful jobs, 274 native tests per API; four actual form exports per API. Extra value coverage in 3be961ca | Final resulting-source verification and external gates remain separate |
| Integrated release verification | Local Reader correction PASS; final release gates pending | This revision | Host 271 tests; actual Reader/Editor/Reader/Home API36 method passes; release lint zero errors/640 warnings | New same-source CI, signed artifacts/workflow and FINAL-06..12 |
| FINAL-03 Android CI matrix | Prior frozen checkpoint PASS; final source pending | cb7ff047 | CI 40013155814: nine jobs successful and 1,096 native executions; subsequent signed UI failure retained | Reader lifecycle correction requires new exact-source CI with 275 methods per API |
| Option A PR integration (PR #3, #5, #6) | COMPLETE: source, tests, lint, release & CI matrix gates verified | `281319da`, Android remote verified | PR #5 cross-UID protection (InternalDocumentOpen non-exported alias, ExternalUriAdmission authority rejection, CrossUidAdmissionSecurityTest, ReceiptProvider fixture manifest repair), PR #6 redaction preflight atomicity (PdfRedactionEngineWorker page-bounds check, RedactionCountSecurityTest), PR #3 stager quota retention (DocumentStagerShutdownTest); all local unit suites pass, assembleDebug/AndroidTest pass, lintDebug (0 errors), unsigned assembleRelease & check_release_gate.py PASS; GitHub Actions run 39982361631 verified 100% green across all 9 jobs (API 24/30/34/36 emulators, build, test, architecture, secrets, dependencies); earlier failed run 39976702517 retained | Physical gates (FINAL-07..12) & Play Console checks (FINAL-06); release HOLD |
| Form payload & traversal-role reconciliation | COMPLETE at integrated checkpoint | c5223314 | CI 40003029379: all nine jobs pass; four form export methods included on each API | Current release summary distinguishes newer source and external gates |


Current evidence/status: [execution receipt](reports/security-remediation-2026-10-18/EXECUTION_STATUS.md).

No implementation, merge, upload or rollout is marked complete by preparation of this checklist.

