# Android security remediation — 2026-10-01

Work order: the 2026-09-30 audit of `repo-android` at `cbafb998666219862b64e8d3d8dd1c172217b4e6`.
Implementation and verification were performed with Codex assistance. This record describes observed results; it is not a security certification.

**Release remains blocked: API 36 OCR crashes inside the platform NNAPI runtime when its hardware-service query runs under an isolated UID.** The original isolated boundary remains enforced. The first CI run passed architecture, dependency security, unit tests and the complete API 24 suite; API 30 / 36 failed only OCR and the build job was consequently skipped. Subsequent fixes require a fresh final-commit CI run. The GitHub connector and Git credential manager work; the separate GitHub CLI token is invalid. CI results are authoritative for the final pushed commit.

## Implementation and evidence

| Finding | Change | Evidence / remaining verification |
| --- | --- | --- |
| SEC-001 / SEC-002 | Host engines became IPC facades; parsers, generators, image decoders and crypto live in worker modules. Shared DTOs, limits, staging and Binder contracts live in `pdf-ipc`. Host compile dependencies exclude parser libraries. | Original `securityAudit` retained; module DAG check and strict compiled-host bytecode tests added. All passed locally on the subsequent `51ad0d8` implementation. |
| SEC-003 | Every batch input requires exact hash/size arrays and full verification before dispatch. | Real Binder tests reject null, short, long, non-hex, zero, wrong-size and wrong-hash metadata, including either bad member of a two-input batch. |
| SEC-004 / SEC-005 | Intent, editor, reader and shared pickers stage immutable bytes, including when Vanguard is disabled. One stager enforces copy-time limits and registry provenance, preserves original display names, and reclaims partial/expired snapshots. Operation leases defer deletion until active users close. Coroutine cancellation closes the active input and removes partial snapshots. | Changing unknown-length provider fixture; staged-filename spoof; immutable-source mutation; exact 100 MiB and plus-one rejection; failed-batch/comparison cleanup; operation leases; cancellation of an input that ignores interrupts. All staging regressions passed on API 24. |
| SEC-006 / SEC-007 / SEC-018 | Both workers admit one job and reject overlap. Dedicated watchdog threads terminate their own processes at 120 s / 30 s. Redaction/Office pipelines use operation scopes and owned resources. | Jail UID, 12 BUSY responses, abort/rebind and real 120 s watchdog passed on API 24 and 36. Native isolated UID, actual 30 s process death, host survival and new-process recovery passed on API 24. |
| SEC-008 | Repair uses bounded prefix/suffix reads and FD scratch; OCR/editor renders use bounded sizes; image/CBZ decoding samples before allocation; signature bytes use FD transport and bounds checks. ML Kit is initialized explicitly with its known background state and ephemeral process-local preferences, avoiding inaccessible host-private SDK bookkeeping. | 8192-square image/CBZ fixtures remain within the raster budget; oversized signature rejected before decode. API 24 OCR output is searchable and the original Binder remains usable after delayed GMS callbacks, including the new preferences wrapper. API 36 reaches native initialization but crashes in `libneuralnetworks.so` / `getAllHalInstanceNames`; OCR compatibility and product feature preservation remain open release blockers. |
| SEC-009 | Worker quotas remain enforced. All host writer routes now transfer only unlinked temporary output FDs; real destinations stay host-owned. The host validates the successful result, target count and 250 MiB aggregate output budget, snapshots every output into private FDs, then performs one bounded commit per destination. | Chunk 1 replaces best-effort provider truncation with no provider open before validation. Device regressions cover actual writes followed by death/cancellation/deadline, normal and compromised overflow, late retained-FD mutation, duplicate callbacks, split/image batches and a nontruncatable pipe provider. Focused final-source results are recorded below. |
| SEC-010 | Shared scrubber walks bounded object/action graphs, including outlines, associated files, attachments, XFA and rich media, and re-audits serialized output against the requested purge scope. Default purges require no remaining carriers or metadata. Custom options preserve unselected features and report only selected removals. | Carrier, cycle/depth, automatic action, benign web-link and destination regressions; device sanitizer/redaction and custom/default purge tests passed on API 24. |
| SEC-011 | Strict jsoup `1.23.2` constraint retained; resolved artifact checked for the namespace implementation and adversarial XML behavior. | Audit wording says “through 1.23.2,” but the release contains upstream fix `862ba2f`. See [upstream PR](https://github.com/jhy/jsoup/pull/2556), [release notes](https://jsoup.org/news/release-1.23.2), and [release source](https://github.com/jhy/jsoup/blob/jsoup-1.23.2/src/main/java/org/jsoup/parser/XmlTreeBuilder.java). |
| SEC-012 | Structured X.500 name builder; signing scratch uses FD capabilities; Bouncy Castle belongs to jail. CMS certificate storage uses the correct certificate-holder store. | Literal hostile-name and ephemeral-key unit regression passed. Actual PDF CMS integrity verification is a device regression. Self-signed signing does not establish a trusted identity chain. |
| SEC-013 | History defaults off; opting out clears previous entries; release logging omits document paths. | Six history unit tests, including previously stored history removal. Opted-in history still contains private local metadata. |
| SEC-014 | Wrapper checksum; immutable action SHAs; independent secret and dependency jobs; fatal release lint. TruffleHog `3.97.9` release archives are SHA-256 pinned, with exact historical fingerprint review rather than broad detector/file exclusions. Bouncy Castle `1.86`, Jackson `2.18.11`, Guava `33.7.2-android`. | Ten secret-policy regressions passed. The full local reachable-history scan on `e78d145` completed with five exact reviewed unverified matches and zero rejected findings. These are public bundle signing digests, an ICU language-resource decoder false positive, and the bundled Firebase client identifier. Verified findings, verification errors, changed identity and incomplete scans fail. Cloud API allowlists were not inspected. CI must verify the updated gate. OSV matched no affected packages among 248 resolved production dependencies. |
| SEC-015 | Functional parser tests moved into the application instrumentation APK; malformed-input testing renamed accurately; real isolation/death/hang/provider/quota tests added. | Results below distinguish local JVM tests from actual Android runtime tests. |
| SEC-016 | 256 KiB parameter limit plus field/item/node/depth budgets; nesting checked before recursive JSON parsing; object and array contracts supported. | Bookmark array regression and hostile deep/trailing/string payload unit cases. |
| SEC-017 / RISK-001 | Unique operation files; explicit stream/FD cleanup; host-created, immediately unlinked scratch FD capabilities replace worker app-private paths. | Isolated scratch write/read/fsync and real parse/render passed on API 24 and 36. PDFBox uses a bounded 32 MiB main-memory buffer, not inaccessible disk-backed scratch. |

## Review Chunk 1 — host-owned output publication

Frozen review base: `f6b6f7df581d2b4fccf46c9def1ac7c5e076fb30`. This chunk changes only worker output authority and its regressions. Native renderer binding and the OCR backend remain unchanged.

- `PdfGateway` and the two legacy `PdfJailClient` writer methods use the same `HostOutputTransaction`. Worker callbacks are accepted once; numeric byte counts are computed again by the host. At the Chunk 1 review head, all worker-reported failures were exempted from abort along with BUSY; Chunk 2 below narrows that exemption to BUSY. Existing worker isolation, deadlines and quotas remain enforced.
- Temporary regular files are unlinked immediately. The host checks individual and aggregate lengths before copying, uses explicit-offset reads to ignore worker-controlled FD positions, and validates all private snapshots before opening the first destination. The worker never receives a private snapshot or real provider FD. A retained worker FD therefore cannot change the bytes being committed.
- The host opens destinations through the content resolver only for the validated commit. Seek/truncate syscalls are confined to temporary files; a SAF provider may return a pipe. Host ownership of temporary and snapshot FDs is closed on every exit.
- Worker failure/death while awaiting a successful result, cancellation or timeout before publication, and failed host validation leave existing destination bytes untouched. Generic SAF providers do not offer a transaction across files: provider I/O failure or cancellation after host publication starts can leave a partial file/batch. This chunk guarantees validation of the entire batch before publication, not rollback after publication begins.
- A hostile worker can retain an unlinked temporary FD until its process releases it; closing the host's copy cannot revoke a transferred FD. It has no destination authority, and its retained temporary bytes cannot mutate private commit snapshots. Arbitrary hostile-worker disk writes cannot be bounded by cooperative worker wrappers; this chunk adds independent host publication checks, not kernel storage quotas.

An additional legacy-export regression exposes an existing API 24 compatibility failure: the unchanged Jackson initializer throws `NoClassDefFoundError` for `java.lang.BootstrapMethodError`. The numeric callback never arrives, so the host times out after 125 seconds. The strengthened test also asserts the real provider remains unopened with its original bytes; those assertions pass before the original timeout is rethrown. This test remains failing rather than skipping or concealing the backend problem. The same Jackson path exists at the frozen review base; it is not changed in this chunk.

Observed Chunk 1 verification:

- Architecture gates and all 47 JVM tests passed after the final worker-failure/BUSY cleanup guard (23 host, 5 IPC, 19 jail; zero failures/errors/skips).
- The focused API 24 run passed 17/18 tests: all seven worker-isolation tests and ten publication tests passed; legacy export failed in the unchanged Jackson initializer described above. A separate rerun confirmed the real-provider preservation assertions pass before rethrowing that same legacy timeout.
- Release lint passed before the final cleanup guard: zero unfiltered errors, 571 warnings and the existing 38 narrowly filtered translation errors. The subsequent final-source run ended during host lint analysis without a result, before reaching the five selected device guard tests. Those final guard device tests remain unexecuted.
- API 36 publication verification and the complete API 24/30/36 matrix were not run for this chunk. The user requested pushing the current updates; no green-final-lint or green-device-matrix claim is made. The existing OCR release blocker remains unresolved.

## Review Chunk 2 — worker failure classification and recycling

Review base: `9b6dd9cd2ff4b057a56b6cc4d17e42be71fd986f`. This chunk changes worker failure lifecycle only. Chunk 1's output transaction remains intact; universal response contracts, renderer binding, legacy deletion and OCR remain later chunks.

- Gateway and legacy writer callbacks retain their numeric error code. Only 429 BUSY preserves the worker; other callback failures and host-side exceptions after request submission abort a live worker. Binding/staging failures before submission do not abort an unrelated operation, and Binder death only requires host cleanup when the Binder is already dead. The legacy query path follows the same failure/cancellation distinction.
- A failed admitted operation no longer releases its gate for reuse. The independent 120-second watchdog stays armed while the host requests recycling, including when an uncaught fatal error exits the operation. Successful completion still disarms the watchdog and releases admission.
- Batch metadata validation now runs after gate acquisition. Invalid overlap therefore returns BUSY rather than a pre-admission 400 that could cause the host to kill the currently admitted operation.
- Six new device regressions exercise failure callbacks, actual Binder death, changed worker PID, host survival and successful benign metadata parsing; legacy writer/query failures; host output-validation rejection; failed-process non-readmission; and BUSY preservation across Gateway, both legacy writers, legacy query and malformed batch calls. The existing batch identity regression retains every malformed-input assertion but recycles/rebinds between failures because reuse of a failed admitted process is no longer permitted.

Observed Chunk 2 verification on October 2, 2026:

- `securityArchitecture`, the existing `:app-host:securityAudit`, all 47 JVM tests (23 host / 5 IPC / 19 jail; zero failures/errors/skips), `:app-host:assembleDebug` and `:app-host:assembleDebugAndroidTest` passed. The first build attempt crashed during Kotlin compilation with a JVM native-memory allocation failure. The retry passed with no emulator running, one Gradle worker, a 768 MiB heap, `-XX:HeapBaseMinAddress=32G`, `-XX:ActiveProcessorCount=2` and in-process Kotlin compilation. No production build configuration was changed. Logs: `.security-chunk2-build.log`, `.security-chunk2-build-retry.log`.
- Focused API 24 instrumentation: **20/20 passed**, 126.582 seconds. Focused API 36 instrumentation: **20/20 passed**, 179.477 seconds. Both runs used the same newly built APKs, sequential headless emulators and real Binder/isolated-process execution. Logs: `.security-chunk2-api24.log`, `.security-chunk2-api36.log`.
- Each focused run includes all six `WorkerFailureLifecycleSecurityTest` tests, all seven `WorkerIsolationSecurityTest` tests and seven unchanged `HostOutputCommitSecurityTest` methods: `busyRequestsLeaveTheRunningWorkerAndBothDestinationsUntouched`, `rawCompromisedWorkerOverflowCannotTouchNonTruncatableProvider`, `workerDeathAndCancellationNeverOpenNonTruncatableProvider`, `retainedWorkerFdCannotChangeValidatedBytesDuringProviderCommit`, `duplicateSuccessCallbacksCommitExactlyOnce`, `realSplitAndPdfToImagesPreserveMultiOutputCommit`, `secondBatchOutputFailureDoesNotCommitFirstWorkerOutput`.
- The existing legacy-export success regression and OCR tests were not part of these focused runs; neither was changed or disabled. Their known release blockers remain unresolved. API 30, a complete final API 24/30/36 matrix, release lint/build and final-candidate CI revalidation remain pending. This is evidence for Chunk 2's lifecycle behavior, not release clearance.

## Review Chunk 2.5 — tokenized worker ownership and host acceptance handshake

Review base: `1bf2af87ec15485a83ac8076d580c9b6edf84366`. Corrective review commit: `4950f4850631a88865c774822b39a39d6d7d4388`. This chunk introduces tokenized worker ownership and Host acceptance lifecycle, resolving asynchronous admission, stale cancellation, and post-execution ownership races before Chunk 3's Host-side response validation.

- Removed interface-level `oneway` from `IPdfJailService.aidl`. Heavy operations remain individually `oneway`, preceded by a synchronous admission handshake: `beginOperation(ownerBinder): Long`, `completeOperation(token): Boolean`, and `abortOperation(token): void`. Execution methods take the admitted `operationId` token.
- `WorkerGate` refactored into a full tokenized state machine: `IDLE` -> `RESERVED(token)` -> `RUNNING(token)` -> `AWAITING_HOST_ACCEPT(token)` -> `completeOperation()` -> `IDLE` (or `FAILED_POISONED(token)` -> PID death). Atomic stale-token isolation guarantees that stale `complete` or `abort` calls cannot release or terminate a different operation's PID.
- `WorkerGate.complete(token)` strictly enforces that completion is valid ONLY when `token == currentToken && currentState == State.AWAITING_HOST_ACCEPT`. Attempting to complete while `RESERVED` or `RUNNING` strictly returns `false`.
- `WorkerGate.acquire()` compatibility helper utilizes private internal release semantics, avoiding any dilution or bypass of public `complete()` state requirements.
- Host acceptance checks are mandatory and fail-closed: every `completeOperation(operationToken)` call across `PdfGateway` (output and non-output) and `PdfJailClient` (publish and analyzePdf) checks the returned boolean (`check(jail.completeOperation(operationToken))`). Failed acceptance aborts the worker token, suppresses result/output commit, and fails closed.
- `PdfJailClient.analyzePdf` captures response via `CompletableDeferred` without releasing ownership in the Binder callback. Validation and budget checks execute while the token is owned, and `completeOperation(token)` is checked before returning the result.
- Watchdog phase and generation identity: dual watchdogs (`WatchdogPhase.EXECUTION` at 120 s and `WatchdogPhase.HOST_ACCEPT` at 30 s) advance `watchdogGeneration++`. Cancelled execution watchdog executions are ignored and cannot kill a worker that transitioned to `AWAITING_HOST_ACCEPT`.
- Output publication and serialization: `exportModifiedPdf` uses Gson deserialization, and duplicate success callback instrumentation fires only after transitioning to `AWAITING_HOST_ACCEPT`.
- Removed ambiguous boolean `requestSubmitted` from Host logic (`PdfGateway`, `PdfJailClient`). Cancellation only aborts when an active token exists (`token > 0L`). Cancelled BUSY requests cannot kill leaseholders.
- Production Host classes are statically forbidden from invoking raw PID-wide `abortWorker()` (enforced by ArchUnit in `ArchitectureBoundaryTest`).
- Fatal `Throwable`s (non-`Exception` runtime/linkage errors) in worker execution poison the gate and immediately kill the worker process, guaranteeing a failed worker never returns to the pool.

Observed Chunk 2.5 verification on October 2, 2026:

- Local JVM & Static Gates: `securityArchitecture` passed (2/2 rules, including `noProductionHostCallsRawAbortWorker`); all 14 `pdf-ipc` unit tests (`WorkerGateTest` 14/14, including `completeStrictlyRequiresAwaitingHostAcceptState`, `cancelledExecutionWatchdogDoesNotTerminateAwaitingHostAcceptWorker`, and `acquireHelperUsesPrivateReleaseWithoutViolatingStateSemantics`, `SecurityLimitsTest` 2/2) passed cleanly.
- Focused API 24 instrumentation (**37/37 passed**):
  - `OperationOwnershipSecurityTest`: **12/12 passed** (Scenarios A through K: BUSY tokenless, cancelled BUSY leaseholder preservation, stale abort isolation, stale completion isolation, success exclusivity until Host ACK, Host rejection exact PID kill, no cross-request kill after success, real complete sequence in Scenario H, fatal worker Throwable containment, owner Binder death recovery, and adversarial early success rejection in Scenario K).
  - `WorkerFailureLifecycleSecurityTest`: **6/6 passed** (failure recycling, BUSY preservation, pending recycle readmission lock).
  - `WorkerIsolationSecurityTest`: **7/7 passed** (isolated UID/PID, scratch capability, 120 s watchdog deadline death/recovery, batch metadata pre-rejection, output quota bounds).
  - `HostOutputCommitSecurityTest`: **12/12 passed** (all 12 passed, including `legacyWritersCommitThroughHostSnapshotsAndLeaveFailuresUntouched` and `duplicateSuccessCallbacksCommitExactlyOnce`).
- Focused API 36 instrumentation (**37/37 passed**):
  - `OperationOwnershipSecurityTest`: **12/12 passed**.
  - `WorkerFailureLifecycleSecurityTest`: **6/6 passed**.
  - `WorkerIsolationSecurityTest`: **7/7 passed**.
  - `HostOutputCommitSecurityTest`: **12/12 passed** (all 12 passed, including `realWorkerDeadlineLeavesProviderUntouched` live 120 s watchdog).
  - `OcrIsolationCompatibilityTest`: observed existing known blocker (`API 36 isolated OCR / NNAPI failure`).

## Review Chunk 3 — universal worker response validation

Review base: `f36a92c` (Chunk 2.5 hardened). Corrective commit head: `6b7dcd5` -> current corrective remediation commit. This chunk addresses host-side worker response parsing, result file ingestion (F01 / F02), and strict canonical wire contract enforcement across all 82 operations.

- All worker output is treated as hostile. Eliminated all 12 ad-hoc `.fromJson(` call sites in Host engines and removed raw JSONObject/JSONArray parsing across Host callers.
- Centralized wire parsing in `WorkerResponseValidator.kt` within `pdf-ipc`. All known operations (all 82 operations) return strongly typed `OperationContract` instances. No raw validated-JSON escape hatch exists.
- Operation validator is exhaustive and fails closed on unknown or unsupported operations (`SecurityException`).
- Enforces transport framing, maximum response depth (`MAX_RESPONSE_DEPTH = 8`), node count budgets (<= 5,000 nodes), UTF-8 response limits (`MAX_JSON_RESPONSE_SIZE = 1 MiB`), and rejects any trailing non-whitespace after the single JSON root value (`nextClean() == '\u0000'`).
- Ingestion of result files (`parseOutlineFile` and `parseCsvTableFile`) strictly bounds file size to 2 MiB, outline depth to 8, outline nodes to 2,000, and CSV tables to 5,000 rows, 128 columns, and 1,024 characters per cell.
- Standard Output validation: Removed permissive catch-all success branch. Unexpected String, Array, null, or unsupported root shapes fail immediately with `IllegalArgumentException`. Exact integral checks (`assertIntegral`) ensure integer and long fields (`renderedCount`, `size`) are strictly integral before conversion.
- `REPLACE_ALL` Wire Validation:
  - Eliminated raw `root.toInt()` truncation.
  - Raw root number path routes through `assertIntegral(root, "replacementsCount")` and strictly validates `value in 0L..Int.MAX_VALUE.toLong()`.
  - Rejects fractional numbers (`1.9`), negative numbers (`-1`), and numbers exceeding `Int.MAX_VALUE`.
- Exact Numeric Domain & Boundary Validation (`assertIntegral`):
  - Strictly validates that floating-point numbers (`Double`, `Float`) are finite, mathematically integral (`v == Math.floor(v)`), and reside strictly within the representable 64-bit signed integer range (`v >= -9223372036854775808.0 && v < 9223372036854775808.0`).
  - Values $\ge 2^{63}$ (`9223372036854775808.0`) or $< -2^{63}$ are rejected fail-closed, preventing double-to-long clamping bypasses.
  - Exact round-trip representation check (`l.toDouble() == v` / `l.toFloat() == v`) ensures no precision loss.
  - Dedicated handling for `BigInteger` and `BigDecimal` enforces exact integer conversion and bounds within `Long.MIN_VALUE..Long.MAX_VALUE`.
- Canonical `IMAGE_ANALYZE` Wire Contract & Semantic Matrix:
  - Enforces single canonical enum-string representation matching Gson serialization (`qualityLoss`: `"MINIMAL"`, etc.).
  - Strictly encodes the exact producer semantic state matrix:
    - `ALLOWED` / `WARNING_ALREADY_COMPRESSED` -> `isSupported = true` + `width > 0 && height > 0`
    - `DENIED_UNSUPPORTED_FORMAT` / `DENIED_CORRUPT` -> `isSupported = false` + `width == 0 && height == 0`
    - `DENIED_TOO_SMALL` -> `isSupported = false` + `width > 0 && height > 0`
  - Contradictory states (e.g. `isSupported = false + ALLOWED` or `isSupported = true + DENIED_CORRUPT`) are rejected fail-closed.
  - Strict integer validation: `width`, `height`, `originalSizeBytes`, `estimatedCompressedBytes`, and `estimatedSavingsPercent` reject fractional values.
- Canonical `SANITIZE_AUDIT` Schema:
  - Worker `PdfSanitizerEngineWorker.audit` emits complete canonical encrypted-response schema on encryption or parse exceptions (`isEncrypted = true, parseFailed = false, isClean = false, threatsFound = 1`) with all detailed fields explicitly populated with safe zero/false values (`jsCount: 0, launchActionsCount: 0, attachmentCount: 0, uriCount: 0, hasMetadata: false`).
  - Strict fail-closed invariant enforced in validator: `(isEncrypted || parseFailed) => !isClean` and `isClean => (!isEncrypted && !parseFailed && threatsFound == 0)`.
  - Rejects fractional numbers for `threatsFound`, `jsCount`, and other count fields.
  - Added safe null-handling for `document.documentInformation` in `PdfSanitizerEngineWorker`.
- `CapabilityIo.input(fd)` Fail-Closed Semantics:
  - Restored strict fail-closed `ParcelFileDescriptor.dup()` semantics without `catch (Throwable)` or `FileInputStream` fallback.
  - Removed host JVM/Robolectric testing fallback from production code; on-device Linux kernel descriptor duplication is validated via `WorkerResponseValidationSecurityTest` on device instrumentation.
- Chunk 1 (host publication & private snapshots) and Chunk 2 / 2.5 (tokenized ownership, BUSY preservation, host acceptance handshake, and suspect process abort lifecycle) semantics remain strictly preserved.
- Local JVM & Static Gates:
  - `securityArchitecture`: **PASSED** (all rules satisfied).
  - `:app-host:securityAudit`: **PASSED** (100 files scanned, 0 violations).
  - `:pdf-ipc:testDebugUnitTest`: **40/40 passed** (`WorkerResponseValidatorTest` 21/21, `WorkerGateTest` 14/14, `SecurityLimitsTest` 5/5).
  - `:pdf-jail:testDebugUnitTest`: **21/21 passed** (including `WorkerProducerContractTest` 2/2 producer -> real serialization -> validator tests across all 5 validation states, `ActiveContentScrubberTest` 7/7, `RequestValidatorTest` 7/7, `TextFormatConverterTest` 5/5).
  - `:app-host:testDebugUnitTest`: **32/32 passed** (including `HostResponseContractSecurityTest` 8/8).
- Device Instrumentation Evidence:
  - **Local Device Instrumentation Matrix (AVDs SecurityApi24 & SecurityApi36):**
    - API 24 Local: **169/169 passed**, 0 failures, 0 errors (Time: 691.897s), including `WorkerResponseValidationSecurityTest` 4/4 verifying real worker audit producer contract.
    - API 36 Local: **168/169 passed**, 1 failure (Time: 1,011.145s). The single failure is `scannedTextRemainsSearchableThroughTheIsolatedOcrWorker` (the known isolated OCR NNAPI platform crash on API 36, separately documented as a release blocker). All 168 other tests passed.
  - **Remote GitHub Actions CI Matrix (Workflow Run 37147182386 / Commit bde10e2fb594dd9e22940c8eb9036ab107f567b9):**
    - API 24 CI: **169/169 PASS** (100% green).
    - API 30 CI: **169/169 PASS** (100% green).
    - API 36 CI: **168/169 PASS** (only known isolated OCR worker crash).
    - Secrets, Dependency Security, Architecture, JVM tests: **ALL PASS**.
    - Final build: **SKIPPED** (due to API 36 isolated OCR failure).

## Limits and product behavior

- 100 MiB per staged input; 250 MiB aggregate staged input; 64 staged documents.
- 32 batch input/output FDs; 64 scratch allocations; 250 MiB output budget; 500 MiB cumulative operation writes; 500 output files/pages.
- Raster size at most 2048 per dimension and 4,194,304 pixels; compressed signature at most 2 MiB.
- PDFBox scratch buffers at most 32 MiB. Some inputs accepted by the staging cap can exceed this parser budget and fail safely.
- Active UI selections remain available through the activity session. Finishing the activity releases them; leases protect admitted operations; process-start cleanup removes expired snapshots. Original documents remain separate from snapshots.
- Overlapping jobs receive `BUSY`; user batch flows run sequentially where needed.
- Thirty-eight inherited translation omissions remain in `app-host/lint-localization-baseline.xml`. Only those exact localization findings are filtered; security/correctness lint errors remain fatal. Other lint warnings remain technical debt.

## Validation

At the time this review record was prepared:

- 47 JVM tests passed on the subsequent `51ad0d8` implementation: 23 host, 5 IPC and 19 jail tests, with zero failures/errors/skips. The original architecture tests remain intact, with additional DAG and compiled-host bytecode checks.
- Architecture gates and fatal release lint passed.
- The initial full local API 24 suite completed with 132 / 134 passing. Two fixture failures were corrected without reducing test limits: PDF signature `/Contents` zero padding is parsed correctly; the bitmap churn fixture releases its Canvas before its unchanged 10 MiB heap-growth assertion. Both corrected fixtures passed focused verification. The complete remote API 24 suite then passed all 134 tests on `d37fca6`.
- Focused API 36 runs passed 17 compression/resource stress tests and 16 capability/staging/raster/worker tests. An earlier full API 36 run stalled with an unresponsive emulator after 121 tests; it is not counted as a successful full run.
- The full local API 36 suite completed with 133 / 134 passing; the failure was isolated OCR. Fixing ML Kit's inaccessible SharedPreferences removed that Java crash, after which the focused OCR regression exposed a platform NNAPI native null dereference. This is not counted as resolved or as a successful full run.
- The process-local OCR preferences regression passed two jail unit tests and the focused API 24 OCR device test. API 30 needs rerunning after this fix; the first run's crash was an inaccessible `UserManager` during SharedPreferences initialization.
- OSV matched no affected Maven packages among 248 resolved production dependencies. The updated pinned TruffleHog gate passed locally as described above; the original remote secret job failed on the reviewed historical detections.

Implementation commit: `9931c93` (the coupled module/engine migration and its regressions are atomic). CI commit: `4d095bd`. The final PR and its check results record subsequent validation; this file is a review-time snapshot, not a claim that pending checks passed.

Follow-up implementation commits: `e78d145` pins the scanner and exact historical review policy (ten policy tests and a successful complete local scan); `499843c` makes OCR SDK preferences ephemeral (two unit regressions and searchable OCR on API 24); `51ad0d8` adds host cleanup after worker death/cancellation (actual API 24 Binder regression plus all 47 JVM tests and architecture gates).

Reproduce the gates with:

```text
./gradlew :app-host:securityAudit securityArchitecture dependencyInventory :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest :app-host:testDebugUnitTest :app-host:lintRelease :app-host:connectedDebugAndroidTest --max-workers=2
python3 tools/check_dependencies.py
python3 -m unittest discover -s tools -p test_check_secrets.py
python3 tools/check_secrets.py
```

CI runs the complete instrumentation suite on API 24 / 30 / 36. API 30 is not installed locally. OSV Maven matching is advisory coverage, not proof that no vulnerability exists; the jsoup Git advisory also required the separate source/artifact check above.

## Release follow-through

The remediation is on `security/audit-remediation-2026-10-01` in [draft PR #1](https://github.com/kiss2oblivion/pdfchemy-android/pull/1), targeting `repo-android`. Require green architecture, secrets, dependency-security, unit, instrumentation matrix and build jobs. A decision on replacing the incompatible bundled OCR backend is pending; no replacement has been implemented or approved. Re-audit the resulting commit before release. No release was created.
