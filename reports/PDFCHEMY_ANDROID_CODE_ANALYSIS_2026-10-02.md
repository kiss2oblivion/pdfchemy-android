# PDFchemy Android — Code Analysis Report

**Verdict: RELEASE BLOCKED.** The accepted Host-owned output publication design remains intact. The main remaining risks are incomplete Host validation of worker responses, unenforced package/class ownership, independently unbounded worker file capabilities, native-renderer failure handling, isolated OCR compatibility, and release enforcement.

| Review identity | Value |
|---|---|
| Reviewed source | [`1bf2af87ec15485a83ac8076d580c9b6edf84366`](https://github.com/kiss2oblivion/pdfchemy-android/tree/1bf2af87ec15485a83ac8076d580c9b6edf84366) |
| Branch | `security/audit-remediation-2026-10-01` |
| Review date | 2026-10-02 |
| Previous reconciled review | `9b6dd9cd2ff4b057a56b6cc4d17e42be71fd986f` |
| Frozen pre-Chunk-1 base | `f6b6f7df581d2b4fccf46c9def1ac7c5e076fb30` |
| Pull request | [Draft PR #1 → repo-android](https://github.com/kiss2oblivion/pdfchemy-android/pull/1) |
| Changes made by this analysis | This report only; no production code, test, dependency, workflow, or repository-policy changes |

This is a consolidated security, correctness, lifecycle, resource, compatibility, and build-enforcement analysis of the current Android implementation. It follows the reconciled review scope. It does not reopen accepted Chunk 1, redesign unrelated features, select an OCR replacement, implement a quota architecture, merge, or authorize release.

## 1. Scope, method, and evidence standard

The inventory covers the four Android trust-domain modules and their production sources, manifests, dependencies, tests, root Gradle gates, CI workflow, security tooling, and remediation records. Three parallel reviewers covered Host surfaces, IPC/lifecycle, and worker/renderer/build architecture; the findings were reconciled against source and controlled checks.

| Module | Tracked files under `src/main` | Kotlin/Java/AIDL source files | Physical source lines |
|---|---:|---:|---:|
| `app-host` | 148 | 100 | 30,768 |
| `pdf-ipc` | 25 | 24 | 1,280 |
| `pdf-jail` | 58 | 57 | 8,768 |
| `pdf-renderer` | 3 | 2 | 75 |
| **Total** | **234** | **183** | **40,891** |

The IPC total includes 19 Kotlin files and five AIDL files. Counts use tracked files at the reviewed SHA, not generated classes or build outputs. All production source files were inventoried and screened for boundary, parser, package, dependency, and resource patterns. Deep control-flow reads concentrated on security-critical paths and their application consumers. All Host screens were screened for potentially sensitive content and window protection. This is not a claim of line-by-line semantic proof of all 40,891 lines.

Evidence labels in this report mean:

- **Code-confirmed:** the condition follows from checked-in control flow or declarations.
- **Demonstrated check:** a controlled build/configuration probe was actually executed during this analysis.
- **Existing runtime evidence:** an earlier run/log was inspected; it was not rerun during this analysis.
- **Runtime reproduction required:** source permits a failure, but the relevant timing, PID, or user-visible outcome has not been demonstrated.

Desktop/web implementations, opaque third-party SDK internals, complete native-library reverse engineering, exhaustive parser fuzzing, every SAF provider, and physical-device compatibility are outside this review. No new CVE conclusion is inferred from a library name or version. No source-only finding is presented as final release closure.

### Source-reference notation

Unless a full path is shown, references use these exact roots at the reviewed SHA:

| Prefix | Root |
|---|---|
| `H/` | `app-host/src/main/java/com/pdfchemy/app/` |
| `I/` | `pdf-ipc/src/main/java/com/pdfchemy/app/` |
| `J/` | `pdf-jail/src/main/java/com/pdfchemy/app/` |
| `R/` | `pdf-renderer/src/main/java/com/pdfchemy/app/` |

For example, `H/logic/PdfGateway.kt:77` identifies the exact Host source file and line. Finding IDs below group related work; they are not a count of independent exploitable vulnerabilities or CVSS scores.

## 2. Prioritized findings

| ID | Priority / classification | Finding | Evidence status | Planned work |
|---|---|---|---|---|
| F01 | HIGH | Targetless worker results bypass universal trusted validation | Code-confirmed | Chunk 3 |
| F02 | HIGH | Worker result files reach unbounded Host JSON/CSV ingestion | Code-confirmed; hostile runtime case pending | Chunk 3 boundary coverage |
| F03 | HIGH | Twelve Host Gson response-decoding sites lack explicit contracts | Code-confirmed | Chunk 3 |
| F04 | HIGH | Five split production packages; duplicate compiled `AppLogger` identity | Source and existing class artifacts confirmed | Chunk 4 |
| F05 | HIGH enforcement gap | Variant project dependency escapes `securityArchitecture` | Demonstrated controlled check | Architecture reconciliation |
| F06 | HIGH enforcement gap / MEDIUM obsolete gate | Exact-FQN checks miss counterfeit parsers; grep gate is misleading | Code-confirmed; counterfeit mutation pending | Chunk 5 |
| F07 | HIGH | Output, scratch, and renderer writable FDs lack independent storage quotas | Code-confirmed; raw quota bypass already tested | Chunk 9 design first |
| F08 | HIGH | Native renderer becomes available after an admitted failure without retirement | Code-confirmed; failure PID test pending | Focused lifecycle follow-up |
| F09 | MEDIUM candidate | Cancellation before BUSY acknowledgement may abort the actual owner | Source-permitted race; runtime reproduction required | Focused lifecycle review |
| F10 | MEDIUM protocol/resource hardening | Renderer pixel files lack private snapshot/exact frame validation | Code-confirmed; hostile renderer test pending | IPC/resource contract coverage |
| F11 | MEDIUM | Parser-only engines depend on unconditional renderer startup | Code-confirmed | Chunk 6 |
| F12 | MEDIUM cleanup | Legacy writer Binder paths have no production caller | Static reachability confirmed | Chunk 7 |
| F13 | Dependency cleanup | Host OCR dependency unused; security-crypto has a concrete Host purpose | Production call-site inventory | Chunk 8 |
| F14 | LOW privacy/identity cleanup | Reader keys reversibly encode staged URI and change across restaging | Code-confirmed | Chunk 8 |
| F15 | HIGH release blocker | API 36 isolated ML Kit OCR remains nonfunctional | Existing native crash evidence; current-head CI worker death | Chunk 10 investigation first |
| F16 | HIGH release integrity | Unprotected divergent branches; CI lacks actual release assembly | Live settings and workflow confirmed | Final release gates |
| F17 | Coverage gap; MEDIUM lifecycle candidate | Sensitive surfaces lack screen policy; overlapping secure screens share unsafe teardown | Source inventory; runtime capture/transition tests pending | Report policy trade-off, then focused verification |

## 3. Accepted controls and reconciled non-findings

### Chunk 1 remains accepted

The parser worker receives anonymous temporary output capabilities, not the real user destination FD. `I/jail/HostOutputTransaction.kt:26` exposes temporary worker FDs; allocation/unlink is at lines 39–43. Host validation measures the aggregate output before publication at lines 89–90, creates private snapshots for every output at lines 95–118, and only then opens real destinations at line 130. Snapshot copies use explicit-offset `pread` at line 101. Single-commit guards are at lines 121–123; Gateway callbacks use first-winner completion at `H/logic/PdfGateway.kt:64`.

No concrete regression in this destination authority separation was found. Existing tests cover worker death, cancellation, watchdog deadline, raw quota bypass, retained-FD mutation, duplicate callbacks, split/image batches, non-truncatable providers, and ordinary BUSY ownership.

Generic SAF providers cannot guarantee rollback once publication begins. Cancellation/provider failure during publication can leave a partial destination or partially published batch. Whole-batch validation before publication is present; cross-provider transactional rollback is not claimed. This limitation is not a sandbox escape and does not justify undoing Chunk 1.

### Chunk 2 handles classified parser failure correctly

`H/logic/PdfGateway.kt:84` preserves a worker after classified `429 BUSY`, while submitted non-429 failures cause abort. `J/jail/PdfJailService.kt:21` admits before operation validation; lines 22–25 reject overlapping work without retiring the owner. A failed admitted parser lease remains occupied and its watchdog remains armed at lines 57–59 until Host recycling. Batch identity validation now runs after admission at lines 136–140.

The preceding Chunk 2 run passed 20 focused instrumentation tests on each of API 24 and API 36. This supports the ordinary BUSY/admitted-failure invariant. It does not cover F08's separate renderer or F09's pre-acknowledgement cancellation window.

### Other useful safeguards remain

- Immutable Host input staging: `I/utils/DocumentStager.kt:64` bounds copying, line 74 seals the staged file, line 83 supplies leases, and lines 95–130 handle cancellation/partial cleanup. Gateway input FDs are read-only at `H/logic/PdfGateway.kt:43`. `I/security/StagedIdentity.kt:10` verifies size/hash and rewinds.
- Both worker services remain non-exported and isolated: `pdf-jail/src/main/AndroidManifest.xml:4` and `pdf-renderer/src/main/AndroidManifest.xml:4`.
- Dedicated watchdog execution remains at `I/security/WorkerGate.kt:11–15`; parser and renderer deadlines are 120 seconds and 30 seconds (`I/security/SecurityLimits.kt:35–36`).
- Cooperative parser protections are useful: archive entry/name/duplicate/expanded-byte checks in `J/jail/FdZipFile.kt:29–36`; pre-allocation image bounds in `J/jail/engines/SafeImageDecoder.kt:13–21` and `SafePdfImage.kt:10–15`; bounded sanitizer traversal in `ActiveContentScrubber.kt:28–34` and serialized-output re-audit in `PdfSanitizerEngineWorker.kt:39–41`. They are not independent quotas against a compromised process.
- Host backup is disabled; the FileProvider is non-exported with narrow cache scan/export paths. History opt-out clears history (`H/logic/HistoryRepository.kt:26–29`). Release logging is guarded by debug flags. These observations are not a full forensic guarantee about third-party SDK behavior.
- The secrets tool uses a SHA-pinned policy and exact baseline fingerprints with fail-closed errors. The dependency scanner operates on resolved production Maven coordinates and handles incomplete/error responses defensively. Neither passing check proves opaque native SDK internals are safe.

**SEC-908 is stale as written.** Ordinary Host `implementation(project(":pdf-jail"))` is already rejected; this was independently demonstrated in this analysis. F05 concerns a different, variant-specific omission.

**The original-URI Base64 leak claim is inaccurate at this head.** The reader encodes the already staged URI. F14 retains the agreed opaque-identity cleanup without inflating it into an external source-URI leak.

## 4. Findings and required regression evidence

### F01 — Universal worker-to-Host result validation is incomplete

**HIGH; code-confirmed.** `H/logic/PdfGateway.kt:64` accepts raw callback strings, line 77 validates only requests with destination targets, and line 81 returns targetless strings unchanged. The legacy query path similarly resumes with raw JSON at `I/jail/PdfJailClient.kt:168–171`. Worker-side `SecurityLimits.enforceResultSize` at `J/jail/PdfJailService.kt:42` is not a Host security boundary under the compromised-worker threat model.

`I/jail/HostOutputTransaction.kt:55–82` already checks callback UTF-8 size, nesting, framing/trailing data, generic success, and output counts for output-producing calls. It does not define complete operation-specific fields, nodes, collection sizes, and strings. Binder imposes transport constraints, but those constraints are not response schemas.

| Representative operation | Host consumer | Current parsing surface |
|---|---|---|
| `METADATA_READ` | `H/logic/PdfMetadataEngine.kt:28–43` | JSONObject plus defaults/coercions |
| `GET_PAGE_COUNT` | `H/logic/PdfEditor.kt:28`; `PdfManipulator.kt:330` | JSONObject/getInt |
| `SANITIZE_AUDIT` | `H/logic/PdfSanitizerEngine.kt:45–63` | JSONObject plus security-relevant defaults |
| `IMAGE_ANALYZE`, `IMAGE_BOUNDS` | `H/logic/ImageCompressor.kt:15,23` | Gson into application types |
| `TEXT_PAGES` | `H/logic/PdfDiffEngine.kt:36–37` | Gson string arrays |
| `FLATTEN_INSPECT` | `H/logic/PdfFlattenEngine.kt:30–31` | Gson object |
| `BOOKMARK_READ` | `H/logic/PdfBookmarkEngine.kt:23–32` | JSONArray/list loop |
| `ACRO_FORM` queries | `H/logic/AcroFormEngine.kt:17,29–50` | JSONObject and field/option loops |
| `ATTACHMENT_LIST` | `H/ui/AttachmentManagerScreen.kt:57–58` | UI directly parses JSONArray |
| `ARCHIVE_INSPECT` | `H/logic/PdfArchiveValidatorEngine.kt:30–42` | JSONObject/checks loop |
| `FONT_INSPECT` | `H/logic/PdfFontInspectorEngine.kt:24` | JSONArray/list construction |
| `SEARCH_REDACT` | `H/logic/PdfRedactionEngine.kt:36–57` | JSONObject/permissive box defaults |
| Split plans, encryption query | `H/logic/PdfManipulator.kt:96,143,268` | Caller-side JSON parsing |

**Concrete malformed-schema outcome:** a worker success response of `{}` for `SANITIZE_AUDIT` defaults all threat counts to zero, `isClean=true`, `isEncrypted=false`, and `parseFailed=false` at `PdfSanitizerEngine.kt:55–63`. `checkVanguardThreat()` then returns `Clean` at lines 94–104. Metadata similarly accepts `{}` through defaults. This is established by control flow; a hostile-worker fixture was not executed here. A schema can prevent malformed acceptance; it cannot make a compromised worker's semantically dishonest audit true.

Parsing occurs after Gateway returns, so failures in these application parsers do not necessarily trigger suspect-worker recycling. Malformed, deep, or collection-heavy data can reach trusted object construction/UI before a narrow boundary check.

**Required change, Chunk 3:** validate every response inside the trusted lifecycle boundary before application deserialization. Enforce UTF-8 bytes, depth, total nodes/items, exact framing, expected root type, required/allowed fields, exact primitives, valid enums and finite/ranged numbers, field strings, aggregate arrays/maps, and no trailing data. Define contracts per operation and submethod, including legitimate scalar/array/nullable forms. Construct typed trusted results only after validation. Retire suspect workers on protocol failures while preserving actual BUSY ownership.

**Required tests:** for representative metadata/page-count/image/text/sanitizer queries, oversized UTF-8, depth +1, node +1, wrong root, wrong primitive, missing required field, unexpected trailing data, huge array, and valid result. Assert rejection before facade/UI construction, Host survival, and appropriate worker retirement. Existing output-callback validation tests do not prove query validation.

### F02 — Result-file transports also cross the trust boundary

**HIGH; code-confirmed, hostile runtime reproduction pending.** `H/logic/PdfOutlineReader.kt:46–48` publishes worker-produced `OUTLINE_READ` JSON to a Host temporary destination. Line 50 reads the whole file, line 52 parses JSONObject, lines 54–69 construct collections, and lines 83–90 recursively parse bookmark children without an independent depth/item budget. The exception handler at line 77 does not cover `OutOfMemoryError` or `StackOverflowError`.

The output transaction validates the callback and a 250 MiB aggregate output cap, not the JSON file's response complexity or the 1 MiB callback response limit. Authority-safe publication can therefore be followed by unsafe Host ingestion of an oversized/deep result file. This is not PDF parser execution in Host or a destination-authority regression.

Related CSV ingestion reads whole output at `H/logic/PdfTableExtractorEngine.kt:22`; `H/ui/TableExtractorScreen.kt:64–84` materializes all tokens/rows. CSV is not JSON, but it needs independent Host byte/row/column/cell budgets.

**Required change:** include result files, not only Binder strings, in Chunk 3's response contract inventory. Use bounded decoding before application object construction, with explicit outline text/section/bookmark and CSV preview budgets. Preserve reflow/table behavior within those contracts.

**Required tests:** valid outlines/CSV plus bounded-size adversarial depth, many tiny nodes/rows/cells, excessive content, and malformed/truncated data. Prove Host survives rejection and collection construction does not begin before bounds. Do not merely increase heap or catch resource errors.

### F03 — Host Gson is an unconstrained response mapper at the boundary

**HIGH; code-confirmed.** `app-host/build.gradle.kts:182` declares `com.google.code.gson:gson:2.10.1`. There are 12 active untrusted response `fromJson` calls:

| Host logic file | Lines | Response type |
|---|---|---|
| `ImageCompressor.kt` | 15, 18, 20, 23 | ImageAnalysis, two compression results, ImageBounds |
| `MarkdownEngine.kt` | 20 | String |
| `PdfDiffEngine.kt` | 36, 37 | Two string arrays |
| `PdfFindAndReplaceEngine.kt` | 15, 19 | FindReplaceSummary, Int |
| `PdfFlattenEngine.kt` | 31 | FlattenDiagnostic |
| `PdfImageReplacerEngine.kt` | 18 | EmbeddedImageInfo array |
| `TextFormatConverter.kt` | 7 | String |

Trusted outbound request encoding is separately present at `H/logic/JailEngineBridge.kt:15–16`, `PdfEditor.kt:52`, and `PdfFlattenEngine.kt:50`. Unused imports were not counted as active parsing.

`I/logic/ImageCompressorContracts.kt:29–41` declares expected ImageAnalysis fields, but the mapper does not enforce that response contract. `H/ui/ImageCompressorScreen.kt:685` later dereferences `analysis.qualityLoss.level`. Missing/incorrect fields belong at the trusted boundary, not at an eventual UI exception.

This finding is architectural; no Gson-specific code execution, polymorphic gadget, or CVE was demonstrated. Replacing Gson with another arbitrary object mapper preserves the problem.

**Required change/tests, together with F01:** remove Host response Gson usage or constrain Gson strictly to trusted Host-generated requests. Decode responses through explicit defensive contracts/constructors. Add an enforceable regression against new direct untrusted response mapping and cover mandatory fields, primitive/range checks, and collection bounds for retained response types.

### F04 — Production package and class ownership are ambiguous

**HIGH; source and existing compiled identities confirmed.** Five production packages are declared across modules:

| Package | Declaring modules |
|---|---|
| `com.pdfchemy.app.logic` | Host, IPC |
| `com.pdfchemy.app.utils` | Host, IPC, jail |
| `com.pdfchemy.app.jail` | IPC, jail |
| `com.pdfchemy.app.sandbox` | Host, IPC (AIDL contract), jail, renderer |
| `com.pdfchemy.app.jail.engines` | Jail, renderer |

Eight IPC files declare the Host facade package at `I/logic/*:1`: `EditorContracts.kt`, `ImageCompressorContracts.kt`, `PdfContracts.kt`, `PdfFindAndReplaceEngineContracts.kt`, `PdfGrayscaleEngineContracts.kt`, `PdfHeaderFooterEngineContracts.kt`, `PdfImageReplacerEngineContracts.kt`, and `PdfLayoutEngineContracts.kt`.

The package inventory found these five overlaps in handwritten Kotlin. Including AIDL adds IPC ownership to the existing sandbox overlap: `pdf-ipc/src/main/aidl/com/pdfchemy/app/sandbox/IPdfNativeRendererService.aidl:1` declares that package. Generated contract declarations need explicit ownership policy too.

There is also a real duplicate FQN: `H/utils/AppLogger.kt:6` and `J/utils/AppLogger.kt:6` both define `com.pdfchemy.app.utils.AppLogger`. Existing Host and jail debug runtime JARs both contain `com/pdfchemy/app/utils/AppLogger.class`. No source-set exclusion was found. The preceding APK build succeeded; final APK/D8/R8 class-resolution behavior was not investigated here. This report does not claim an observed build failure or exploit from the duplicate.

**Required change, Chunk 4:** move shared contracts to an explicit neutral IPC namespace; assign production package ownership to each trust domain. Review every overlap, including generated AIDL policy, instead of broadly exempting existing packages. Add package-overlap and exact-class-identity invariants with only a tiny justified allowlist. Shared references to a single IPC declaration are legitimate; shared declarations are the condition to detect.

**Required tests:** split Host/IPC production package, duplicate production FQN, and counterfeit implementation fixtures must fail. Normal imports/references to IPC-owned types must pass.

### F05 — Variant-specific project edges bypass the current DAG task

**HIGH enforcement gap; demonstrated during this analysis.** Root `build.gradle.kts:31` examines only base `api`, `implementation`, `compileOnly`, and `runtimeOnly` declarations. The resolved Host compile-classpath check at line 41 casts to Maven `ModuleComponentIdentifier`; it does not reject unauthorized project components themselves.

Two disposable Gradle init-script probes were run without editing repository source:

| Probe | Actual outcome |
|---|---|
| Add Host `implementation(project(":pdf-jail"))` | Expected **FAIL**: `Forbidden module edge: :app-host implementation :pdf-jail` |
| Add Host `debugImplementation(project(":pdf-renderer"))` | Unexpected **PASS**: `BUILD SUCCESSFUL`; resolved Host debug compile classpath printed `project :pdf-renderer` |

This proves a gap in **the `securityArchitecture` task**. It does not prove the entire CI suite accepts every forbidden reference: compiled-bytecode checks may still reject direct references to their exact forbidden classes. It also does not revive stale SEC-908's claim about ordinary implementation edges.

**Required change:** enforce the intended dependency DAG across actual resolved project components and participating variants, including debug/release/compileOnly cases. Keep the existing base-edge and Maven compile-classpath controls.

**Required tests:** the ordinary control must continue failing, and debug/release variant compile edges must fail too. Appendix A contains reproducible probe contents. An initial probe implementation failed on Gradle's configuration-lock rules; the corrected task-time probe succeeded. That tooling error was not counted as a gate rejection.

### F06 — Counterfeit parser coverage is absent; raw grep is obsolete

**HIGH enforcement gap, MEDIUM misleading legacy gate; code-confirmed.** `app-host/src/test/java/com/pdfchemy/app/architecture/HostBytecodeSecurityTest.kt:15–20` tests six exact forbidden class identities, including real PDFBox PDDocument, PdfRenderer, and BitmapFactory. A local `com.pdfchemy.app.logic.PDDocument` implementation is not covered by those identities. Other architecture tests retain broad package/name exemptions (`architecture/ArchitectureBoundaryTest.kt:18`; `arch/ArchitectureBoundaryTest.kt:14`).

The legacy `securityAudit` task at `app-host/build.gradle.kts:185,193–195,207` scans raw source strings and runs before builds. Root `build.gradle.kts:45` has additional raw-string restrictions. Comments/strings can trigger these tests while a local counterfeit can avoid the literal names. This follows from the implementation; a complete counterfeit build mutation was not executed during this analysis.

**Required change, Chunk 5:** replace the obsolete grep checks with ownership and compiled invariants. Preserve module DAG enforcement, resolved Host classpath checks, compiled bytecode restrictions, and the architecture CI job. Include explicit counterfeit/parser-implementation ownership policy; do not replace narrow constraints with broad exemptions.

**Required controlled regression proof:** gates fail for Host implementation of jail, real PDDocument, real PdfRenderer, BitmapFactory, local Host PDDocument counterfeit, and split Host/IPC production package. Harmless comments/strings mentioning these names must pass. F05 adds variant edge coverage to the same invariant, rather than a duplicate ordinary-edge remediation.

### F07 — Writable regular FDs have no independent storage quota

**HIGH; code-confirmed, physical disk exhaustion not attempted.** `I/jail/HostOutputTransaction.kt:39–43` creates unrestricted writable regular files and unlinks them. `I/jail/OperationScratchBroker.kt:14` caps descriptor count at 64, but lines 18–22 grant the same regular-file write authority; aggregate size is checked later at lines 26–27. Renderer outputs also use regular writable FDs (`H/sandbox/NativeRendererCoordinator.kt:30–33,51–54`).

A compromised worker can bypass `J/jail/JailOutput.kt:15`, `J/jail/JailScratch.kt:35`, and other cooperative wrappers with direct writes/ftruncate. The existing debug fixture does this at `J/jail/PdfJailService.kt:103–106`. Host post-result validation prevents publication, but does not independently limit storage allocated before validation/watchdog death. Closing Host copies does not revoke a live worker's retained duplicate. Unlinking removes pathname authority, not the ability to grow storage. Sparse `ftruncate` rejection is not proof of physical-block exhaustion prevention.

**Design recommendation for Chunk 9, before implementation:** evaluate a Host-drained bounded output pipe/broker for sequential output, with accepted bytes capped across the whole batch and bounded Host-private backing storage. Independently solve seekable scratch and renderer requirements, including API 24. Preallocation, delayed fstat, and additional worker counters alone do not impose a hard growth quota. No kernel/proxy design was selected or proven here.

The proposal must preserve streaming, current large-PDF limits, multi-output SAF publication, absence of worker path access, and finite Host RAM. Include transient snapshot storage in the budget.

**Required proof after design:** adversarial direct writes and retained-FD writes to output and scratch cannot exceed independently accepted/backed bytes; quota failure leaves destinations untouched before publication and Host usable. Avoid deliberately filling the shared machine/device. Destination safety is already tested; resource consumption is a separate invariant.

### F08 — Native-renderer admitted failures are not retired

**HIGH; code-confirmed, PID outcome requires runtime test.** `R/jail/engines/NativeRenderWorker.kt:15–19` acquires a lease, then scopes it with `lease.use`; an exception closes the lease, making admission available again. Lines 20–22 truncate output and rethrow but do not retire the process. `H/sandbox/NativeRendererCoordinator.kt:21–23` binds/unbinds with no failure/cancellation retirement.

A failed native parse/render can leave unknown process state eligible for the next document. Parser Chunk 2 handles a different service and remains accepted. Existing `app-host/src/androidTest/java/com/pdfchemy/app/security/NativeRendererIsolationSecurityTest.kt:36` tests the renderer deadline, not admitted-failure retirement.

**Required focused follow-up:** apply the same admission-aware BUSY/failure lifecycle invariant to this boundary. Keep the failed lease unavailable until retirement, preserve another owner's BUSY, and retain the watchdog fallback. Do not infer admission merely from submission.

**Required runtime tests:** overlapping BUSY leaves owner PID alive; an admitted failing native call reports failure and old PID dies; rebind gets new PID; Host survives; next benign render succeeds. Source inspection alone cannot close this finding.

### F09 — Submitted does not necessarily mean admitted

**MEDIUM candidate; deterministic runtime reproduction required.** Gateway sets `requestSubmitted=true` immediately before a one-way invoke at `H/logic/PdfGateway.kt:71–72`; lines 84–85 abort on a subsequent non-429 exception, including cancellation. Legacy writers/query use the same submitted-state approximation at `I/jail/PdfJailClient.kt:94–106,185–186,213–215`. Actual admission/429 dispatch occurs later in `J/jail/PdfJailService.kt:21–25`.

Possible schedule: A owns the worker; B submits; B is cancelled before its BUSY callback is consumed; B's cancellation sends process-wide abort even though B never acquired admission. This can kill A. Existing BUSY tests wait for classification; cancellation tests cancel the admitted writer. Neither covers this window. No observed PID death from this schedule is claimed here.

**Required test first:** deterministically hold A, delay B's admission/result acknowledgement, cancel B, verify A's Binder/PID survives, then let A succeed. Start with live Gateway; test legacy routes only if retained. An operation ownership/admission token or equivalent association may be needed. Simply withholding all cancellation aborts would weaken recycling of genuinely admitted work.

### F10 — Renderer raw-pixel output needs a complete transport contract

**MEDIUM protocol/resource hardening; code-confirmed.** `H/sandbox/NativeRendererCoordinator.kt:30–37,51–56` transfers writable cache output to the renderer and then directly reads it through PixelWire, without private snapshot or exact file-size/frame validation. A hostile renderer retaining the FD can mutate/grow that file after return.

Bounds already exist and should be preserved: `I/security/PixelWire.kt:25–28` validates dimensions before bitmap allocation, lines 30–36 bound row buffering and recycle on short reads. Dimensions are at most 2048² pixels, approximately 16 MiB ARGB bitmap data. This is raw pixel transport, not encoded image decoding in Host and not an unbounded bitmap allocation claim.

PixelWire lacks exact trailing-data checks; the coordinator does not stabilize a privately validated frame. Disk growth is also F07. This concerns preview/result transport, not the real SAF destination or a Chunk 1 regression.

**Required tests/change:** define single/multiple-frame contracts deliberately, stabilize validated bytes where needed, and test invalid dimensions, truncated/oversized/trailing frames, retained-FD mutation, and failed-render cleanup. Preserve legitimate multi-frame consumers when adding EOF enforcement.

### F11 — Renderer startup is unnecessarily mandatory

**MEDIUM; code-confirmed.** `H/logic/PdfGateway.kt:57` binds the native renderer for every request. Metadata dispatch at `J/jail/PdfJailService.kt:194–195` and sanitizer at lines 235–236 do not consume it. The separate Binder is passed to REDACT and OFFICE_PPT at lines 221 and 225.

**Required change, Chunk 6:** explicit per-operation renderer capability declaration. Bind only declared requirements; pass null to parser-only engines. Do not add Host rendering fallback or infer capability from scattered nullable usage.

Fail-closed detail: forensic redaction rejects absent renderer (`J/jail/engines/PdfRedactionEngineWorker.kt:368`), while OFFICE_PPT can skip images when page count is null (`OfficeExportEngineWorker.kt:109`) and still return success at line 150. Current unconditional binding prevents the normal unavailable-service route from reaching that branch. Lazy binding must not make silent omission a compatibility fix.

**Required tests:** metadata and sanitizer work with renderer intentionally unavailable; declared renderer-required operations fail closed when unavailable and use an isolated renderer when available; no Host fallback. Other rendering performed inside an already isolated parser does not justify binding the separate service for every engine.

### F12 — Legacy writer paths are unreachable from production

**MEDIUM cleanup; checked-in production reachability confirmed.** Production Kotlin/Java searches across all four modules found no caller of `PdfJailClient` outside its definition. Instrumentation still calls it. Dead writer declarations remain at `I/jail/PdfJailClient.kt:23,35`, AIDL `pdf-ipc/src/main/aidl/com/pdfchemy/app/jail/IPdfJailService.aidl:22,48`, and service implementations `J/jail/PdfJailService.kt:85,121`.

Live compression is `H/ui/MainViewModel.kt:289,513` → `H/logic/PdfCompressor.kt:117` → Gateway `COMPRESS`. Live editing is MainViewModel line 1143 → `H/logic/PdfEditor.kt:53` → Gateway `EDITOR_EXPORT`. The shared `PdfEditorWorker.exportModifiedPdf` remains live via `J/jail/PdfJailService.kt:153` and must not be deleted with obsolete Binder compatibility.

The API 24 Jackson initializer failure belongs to the legacy export path. Current-head CI confirms it: `HostOutputCommitSecurityTest.legacyWritersCommitThroughHostSnapshotsAndLeaveFailuresUntouched` fails with `Isolated worker died`; its log shows `NoClassDefFoundError` for `java.lang.BootstrapMethodError`, Jackson initialization, and `PdfJailService.kt:124`. Repairing dead compatibility increases maintenance and attack surface without serving production. Static searches do not dynamically prove the absence of reflective/external binary callers, but no such compatibility commitment was found. See [API 24 job](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704/job/110723488616).

**Required change, Chunk 7:** final reachability check, then delete obsolete writer clients/AIDL/service compatibility and tests solely for those dead paths. Migrate valuable BUSY/death/publication invariants to retained live routes. Legacy client analysis is also test-only, but its deletion is an explicit scope decision; Gateway's live service analysis entry must remain.

**Required tests:** AIDL/build checks and live compression/editor success, admitted failure, cancellation, and publication safety. Do not suppress legacy failures merely to make the current matrix green.

### F13 — Host dependency necessity has a concrete answer

**Cleanup/ownership finding; no dependency-presence vulnerability claim.**

| Dependency | Exact production purpose/evidence | Disposition |
|---|---|---|
| `com.google.mlkit:text-recognition:16.0.1` | Direct Host dependency at `app-host/build.gradle.kts:151`; no Host TextRecognition/InputImage/OCR call found. Actual OCR is `J/jail/engines/PdfOcrEngineWorker.kt:27` and `PdfTextExtractorWorker.kt:54`. | Remove direct Host artifact in Chunk 8; preserve isolated worker OCR. Check resolved compile classpath and packaging. |
| `com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1` | Separate dependency at line 149; UI result/launcher use in `H/ui/ScanPdfScreen.kt:83–87,262–275` and `H/MainActivity.kt:1783,1830–1837`. | Host platform UI purpose exists. Do not conflate with unused OCR. External scanner activity behavior is not certified here. |
| `androidx.security:security-crypto:1.1.0-alpha06` | Dependency at line 157; `H/billing/BillingManager.kt:39–47` creates Keystore MasterKey/encrypted entitlement preferences, line 53 reads cache, line 154 persists billing result; MainViewModel line 129 owns manager. | Used by Host billing/UI state, not hostile document decoding. Retain/document rationale during cleanup. Alpha age alone is not a CVE. |

Current dependency-security CI passed the resolved Maven scan. That does not replace native SDK review or independently establish OCR compatibility. No Host document-derived OCR workaround is acceptable.

### F14 — Reader preference identity should be opaque and stable

**LOW cleanup; code-confirmed.** `H/ui/ReflowReaderScreen.kt:204` stages the original URI, then lines 219 and 240 Base64-encode the staged URI. The picker stages at line 403 and encodes at lines 418 and 441. Preference names at lines 256–270 use `reader_scroll_index_<id>` and `reader_scroll_offset_<id>`.

This is not the original external URI leak reported by Kilo. It still reversibly stores private staged path text in key names and gives different identity after restaging because `I/utils/DocumentStager.kt:51` creates random snapshot names. Line 75 supplies Host-computed content identity.

**Required change, Chunk 8:** domain-separated opaque SHA-256 identity from trusted staged identity, with any intentional document discriminator defined explicitly. Hashing a random staged pathname would remain unstable. Define restoration/migration behavior.

**Required tests:** deterministic identity where intended, intentional separation where needed, no URI/path text in keys, and scroll restoration across the intended reopen/restage path.

### F15 — API 36 isolated OCR remains a release blocker

**HIGH; existing native crash evidence plus current-head CI failure.** OCR initializes ML Kit at `J/jail/engines/PdfOcrEngineWorker.kt:26–27` and recognizes document bitmaps at line 61 inside the isolated jail. Existing `.security-runtime/ocr-native-crash36.txt:27,36–42` records null-address SIGSEGV through `getAllHalInstanceNames` → NNAPI DeviceManager → `ANeuralNetworks_getRuntimeFeatureLevel` → ML Kit OCR pipeline. Current-head [API 36 CI](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704/job/110723488687) independently fails `OcrIsolationCompatibilityTest.scannedTextRemainsSearchableThroughTheIsolatedOcrWorker` with `The isolated OCR worker must remain functional: Isolated worker died`. That CI log does not include the native tombstone, so it confirms continuing worker death/compatibility failure, not a newly verified NNAPI stack.

An earlier SharedPreferences error in the same buffer is historical and distinct. `IsolatedOcrRuntime.kt:17,20–21` and `OcrProcessContext.kt:13` address Java initialization using isolated ephemeral preferences; they do not remove the native NNAPI path.

The tested API 36 emulator is incompatible. This evidence does not prove identical behavior on every physical API 36 device. Earlier API 24 searchable OCR passed; the focused Chunk 2 API 36 pass excluded OCR and therefore does not close this blocker.

**Required investigation, Chunk 10, before implementation:** compare fully offline backends for isolated-process compatibility, API 24/30/36, supported ABIs, APK/model sizes, memory/CPU, native attack surface, licensing, languages, recognition quality, and model updates. Return a recommendation with evidence before choosing a backend. No replacement was benchmarked or selected in this analysis. Keep isolation and the searchable-output compatibility assertion; do not move OCR into Host or weaken tests.

### F16 — Release integrity is not enforced yet

**HIGH; live GitHub settings and workflow confirmed.** Live repository reads during this analysis returned:

| Branch | Head | Protection |
|---|---|---|
| Default `main` | `557d3348ebb2b44ead81b926ad26a6ec5e426d1f` | `protected=false` |
| `repo-android` | `cbafb998666219862b64e8d3d8dd1c172217b4e6` | `protected=false` |
| Review branch | `1bf2af87ec15485a83ac8076d580c9b6edf84366` | `protected=false` |

All five enumerated branches were unprotected; the repository rulesets response was empty. These are time-specific observations, not assumptions from old review notes. Sources: [branches API](https://api.github.com/repos/kiss2oblivion/pdfchemy-android/branches?per_page=100), [rulesets API](https://api.github.com/repos/kiss2oblivion/pdfchemy-android/rulesets), and [draft PR](https://github.com/kiss2oblivion/pdfchemy-android/pull/1).

`.github/workflows/android-release.yml:94` correctly requires architecture, secrets, dependency security, JVM tests, and instrumentation before the final build job. Line 103 runs **lintRelease + assembleDebug**, not assembleRelease/bundleRelease. It does not validate a final release artifact's compilation/minification/packaging.

The draft PR body also contains older output-cleanup/frozen-head verification language that predates accepted Chunk 1 and current Chunk 2. That text should be reconciled before final review; it was not edited during this read-only analysis. Current-head/full-matrix status is reported separately below.

**Required final work:** intentionally select the authoritative release branch; converge history; enforce named architecture/secrets/dependency/JVM/API 24/API 30/API 36/release lint-build checks and ordinary-workflow bypass restrictions; build an actual release artifact; re-audit the exact final merge candidate. CI without protected release history remains advisory. No merge, branch-policy write, or release was performed.

### F17 — Secure-screen coverage and lifecycle need targeted verification

**Coverage inventory, not automatic proof of 18 screenshot leaks.** All 100 Host production Kotlin files were screened for `SecureScreenContent`, `FLAG_SECURE`, policy overrides, document render/image previews, and extracted/imported text/form values. MainActivity dispatch was traced at `H/MainActivity.kt:774–934`. Fourteen protection call sites exist elsewhere; there is no global wrapper protecting the following surfaces. The only activity flag mutator is `H/ui/SecureScreen.kt:14–16`.

| Missing potentially sensitive surface | Evidence under `H/` |
|---|---|
| Quick Fill & Sign canvas and text/signature dialogs | `ui/QuickFillSignScreen.kt:112,477,526`; dialogs 230,258 |
| Form Builder canvas and field/default dialogs | `ui/FormBuilderScreen.kt:104,461–462`; dialogs 558,649; values 584,667 |
| Deskew document preview | `ui/DeskewScreen.kt:74,242` |
| Grayscale preview | `ui/GrayscaleOptimizerScreen.kt:316` |
| Paper Canvas Resizer preview | `ui/PageLayoutScreen.kt:81,236` |
| Page Number preview | `ui/PageNumberScreen.kt:79,217` |
| Images-to-PDF selected images | `ui/CreateScreens.kt:181` |
| Image Compressor previews | `ui/ImageCompressorScreen.kt:814,923` |
| Embedded-image thumbnails/replacement dialog | `ui/ImageReplacerScreen.kt:329,385,437,468` |
| Interactive form values/options | `ui/AcroFormScreens.kt:259,269,280–283` |
| Inspect/Edit Metadata fields | `ui/CheckScreens.kt:207–215,317–322` |
| Metadata Sanitizer field/edit view | `ui/MetadataSanitizerScreen.kt:69–79,408–439` |
| Extracted CSV cell preview | `ui/TableExtractorScreen.kt:273–275` |
| Find & Replace matched content/snippets | `ui/FindAndReplaceScreen.kt:390,446` |
| Markdown imported text/editor/preview | `ui/MarkdownStudioScreen.kt:88,282,306,427–504` |
| Text Format Converter imported text | `ui/textconverter/TextConverterScreen.kt:181`; ViewModel 59–76 |
| Text to PDF document text | `MainActivity.kt:3051` |
| Text Cleaner document text | `ui/TextScreens.kt:113` |

Source absence is confirmed. Whether each tool must prohibit legitimate screenshots is a security/product policy decision, and runtime capture/Recents/dialog behavior was not exercised. Filenames, font diagnostics, and configuration-only tools were not treated as defects. External SAF/GMS scanner activities are outside this activity's window ownership. Already protected screens' dialogs have no explicit contrary policy in source; runtime inheritance is unverified.

**Separate MEDIUM lifecycle candidate:** `H/ui/SecureScreen.kt:12–17` sets a shared activity flag per composition and unconditionally clears it on disposal. MainActivity's `AnimatedContent` at lines 746–774 permits overlapping outgoing/incoming compositions. An outgoing secure screen can clear protection still needed by the incoming screen. Source ownership risk is confirmed; an actual transition leak is not demonstrated.

**Required next evidence:** secure→secure overlap, secure→unprotected teardown, rotation/recreation, background/Recents, screenshot/capture, and sensitive dialog-window tests. Then choose explicit current-window policy ownership or reference accounting if reproduced. Report screenshot/capture behavior trade-offs before expanding coverage. Do not fix by permanently securing every screen or silently changing user behavior.

## 5. Verification ledger

### Checks executed during this analysis

| Check | Outcome | Exact interpretation |
|---|---|---|
| `securityArchitecture :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :pdf-jail:testDebugUnitTest` with offline, single-worker Gradle | **BUILD SUCCESSFUL**, 40 seconds; 99 tasks, 2 executed/97 up-to-date | Incremental verification completed. JVM tasks reused the existing 47-test passing results at unchanged source; this was not a fresh execution of 47 tests. |
| `python -m unittest discover -s tools -p test_check_secrets.py` | **10/10 PASS**, freshly executed | Tests the secrets policy/scanner behavior; not a new full repository/history secret scan. |
| Ordinary forbidden-edge control | **Expected FAIL** | Existing base DAG rule rejects Host implementation of jail. |
| Variant-edge corrected probe | **Unexpected PASS**, 13 seconds | Renderer resolved on Host debug compile classpath while `securityArchitecture` passed. |
| Initial variant probe attempt | Gradle configuration-lock tooling error | Discarded as a gate verdict; fixed by querying resolution inside task `doLast`. |
| Tracked source/package inventory | Completed | 183 source files; five package overlaps and one duplicate top-level production identity found. |
| Source call-site/screen inventory | Completed | Active response Gson, dependency usage, legacy writers, sensitive screen coverage reviewed. |

Local evidence logs: `.security-code-analysis-checks.log`, `.security-code-analysis-edge-control.log`, `.security-code-analysis-variant-edge.log`, and `.security-code-analysis-variant-edge-retry.log`. Inventory/reviewer notes are under `.security-runtime/code-analysis/`. These scratch artifacts are local/ignored, not release deliverables; the probes needed to reproduce the key gate result are included below.

No emulator/ADB was started during this analysis. No new instrumentation, OCR benchmark, physical disk-fill experiment, branch setting, source mutation, or merge was performed.

### Existing current-head Chunk 2 evidence inspected

| Run | Outcome | Limits |
|---|---|---|
| Low-memory retry build | **PASS**, 4m33s | Architecture/current legacy gate, 47 JVM tests (23 Host / 5 IPC / 19 jail), debug APK and test APK. Initial attempt ran out of native malloc memory while an emulator was active; successful retry used constrained JVM/workers with emulator stopped. |
| API 24 focused instrumentation | **20/20 PASS**, 126.582s | Six lifecycle tests, seven worker-isolation tests, seven publication cases. |
| API 36 focused instrumentation | **20/20 PASS**, 179.477s | Same focused selection and freshly built APKs; excludes OCR and legacy writer success cases. |

Publication selection included BUSY ownership/destination preservation, raw hostile overflow against non-truncatable provider, worker death/cancellation before publication, retained-FD mutation during provider commit, duplicate success exact-once commit, real split/PDF-to-images batch success, and second-output failure with no first-output publication. Existing legacy success/OCR assertions remain present and were not suppressed to produce these focused passes.

Logs: `.security-chunk2-build-retry.log`, `.security-chunk2-api24.log`, `.security-chunk2-api36.log`; source/build context is recorded in `SECURITY_REMEDIATION_STATUS.md`. Test APK SHA-256: `613EDF615D4CCD09DD2BAF0BDA369FF00E04270D138A5B0BFD475DCA44AA7F6C`. Host APK SHA-256: `B136AD418ACF2C23C2138FCD80F34BD58BA0A64C46682C3004EC65B58D2584F7`.

These are scoped evidence for accepted changes, not a full-head release matrix. F01/F02/F08/F09/F10/F17 require their own new regressions.

### Live GitHub CI snapshot

For source `1bf2af87ec15485a83ac8076d580c9b6edf84366`, [workflow run 36970254704](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704) was last read at approximately **2026-10-02 06:15 UTC**. It completed while this report was being checked:

| Job | Last observed status |
|---|---|
| dependency-security | PASS |
| secrets | PASS |
| architecture | PASS |
| JVM tests | PASS |
| API 24 instrumentation | **FAIL** — 153 tests finished, one failure: legacy writer success/publication test; Jackson/BootstrapMethodError stack confirmed |
| API 30 instrumentation | **PASS** — 153 tests finished; connectedDebugAndroidTest build successful |
| API 36 instrumentation | **FAIL** — 153 tests finished, one failure: isolated searchable OCR; worker died |
| Final build job | **SKIPPED** because required instrumentation failed |

The API 24 failure log names `HostOutputCommitSecurityTest.legacyWritersCommitThroughHostSnapshotsAndLeaveFailuresUntouched` and traces `NoClassDefFoundError: Failed resolution of: Ljava/lang/BootstrapMethodError;` through Jackson into legacy service export at line 124. API 36 names `OcrIsolationCompatibilityTest.scannedTextRemainsSearchableThroughTheIsolatedOcrWorker` and reports isolated worker death. Both failures align with tracked compatibility blockers; no assertion was weakened and neither is hidden by the focused Chunk 2 selection. Direct job sources: [API 24](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704/job/110723488616), [API 30](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704/job/110723488704), [API 36](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/36970254704/job/110723488687). Decoded job logs were retained locally under `.security-runtime/code-analysis/github-api*-job-*.log`.

This is a timestamped snapshot, not a promise about reruns. The full instrumentation matrix completed with failures; the head is not fully passing, and release lint/build was not reached. Actual release assembly is absent from the workflow even if its debug build job later succeeds.

## 6. Remediation sequence and stop conditions

Keep the agreed sequence and atomic-commit/review boundaries:

1. **Chunk 3:** universal trusted result validation plus Host Gson reduction, including result-file transports. This is the next implementation chunk; it was not started here.
2. **Chunk 4:** neutral IPC contracts and enforced package/class ownership.
3. **Chunk 5:** replace obsolete grep while preserving stronger gates; prove counterfeit/split/comment regressions and close variant-edge coverage.
4. **Chunk 6:** explicit lazy native-renderer capability declaration with fail-closed required paths.
5. **Chunk 7:** final legacy reachability check and dead writer deletion, preserving live engines and valuable regressions.
6. **Chunk 8:** remove unused direct Host OCR dependency, document security-crypto/UI dependency rationale, and opaque stable reader identity.
7. **Chunk 9:** independently enforced Host/kernel storage quota design recommendation before major implementation.
8. **Chunk 10:** offline isolated OCR backend evidence and recommendation before implementation.
9. **Final:** branch convergence/protection, actual release build and full API matrix, final-candidate independent re-audit.

F08/F09 should receive a narrowly scoped lifecycle reproduction/review before lifecycle closure; they do not justify undoing the accepted parser failure classification or silently merging unrelated work into Chunk 3. F10 belongs in the explicit IPC/resource inventory. F17 is an inventory and targeted policy/lifecycle verification item; no blanket protection change is authorized by this report.

After each implementation chunk: one atomic commit, exact changed files, actual tests and their outcomes, unresolved issues, then stop for independent review. Do not merge or release. Closure requires the condition removed, a regression capable of detecting its return, relevant tests actually passing, and final merged/release-candidate revalidation.

## Appendix A. Reproducing the demonstrated architecture gap

These scripts inject configuration only; they do not modify source files. Run each separately with the reviewed source and its configured JDK/Android SDK. Write them to temporary/local ignored `.gradle` files. They were tested with offline, single-worker Gradle; no APK was built for the probes.

**Control — ordinary forbidden implementation edge:**

```groovy
gradle.projectsEvaluated { currentGradle ->
    def host = currentGradle.rootProject.project(':app-host')
    host.dependencies.add('implementation',
        host.dependencies.project(path: ':pdf-jail'))
}
```

```powershell
.\gradlew.bat -I <control-file.gradle> securityArchitecture --offline --max-workers=1 --no-daemon
```

Observed rejection: `Forbidden module edge: :app-host implementation :pdf-jail`.

**Variant probe — forbidden renderer compile edge:**

```groovy
gradle.projectsEvaluated { currentGradle ->
    def host = currentGradle.rootProject.project(':app-host')
    host.dependencies.add('debugImplementation',
        host.dependencies.project(path: ':pdf-renderer'))
    currentGradle.rootProject.tasks.named('securityArchitecture').configure {
        doLast {
            host.configurations.debugCompileClasspath.incoming
                .resolutionResult.allComponents.each { component ->
                    if (component.id instanceof
                        org.gradle.api.artifacts.component.ProjectComponentIdentifier) {
                        println('PROBE_HOST_COMPILE_PROJECT: ' + component.id)
                    }
                }
        }
    }
}
```

```powershell
.\gradlew.bat -I <variant-file.gradle> securityArchitecture --offline --max-workers=1 --no-daemon
```

Observed: `BUILD SUCCESSFUL`, with `project :app-host`, `project :pdf-renderer`, and `project :pdf-ipc` printed from the resolved Host debug compile classpath. This is evidence against this task's project-edge coverage only; do not report it as an execution exploit or complete CI bypass.

## Appendix B. Coverage map

| Area | Review performed | Remaining evidence limits |
|---|---|---|
| Host application/facades | All 47 logic files screened; Gateway, active response parsers, staging consumers, editor/compressor, sanitizer/metadata/outline/forms/bookmarks/redaction/image paths deeply read | Every UI branch and document format not dynamically exercised |
| Host UI/navigation | All 48 UI files plus MainActivity screened; 18 missing sensitive surfaces traced; secure-window ownership/dialog paths inspected | No new screenshot, MediaProjection, Recents, rotation, or dialog-runtime suite |
| Host billing/platform | Billing/ads ownership, encrypted preferences, scanner integration, sharing/history/logging/provider declarations reviewed | Opaque SDK internals/network behavior not reverse engineered |
| IPC/shared boundary | All 19 Kotlin and five AIDL files inventoried; output transaction, client, scratch broker, limits, identity, gate, PixelWire, contracts read | Host response schemas, hard storage quotas, variant/ownership mutations not yet complete |
| Parser worker | All 57 sources inventoried/screened; admission/dispatch, request validation, scratch/output, archive/image limits, sanitizer and OCR deeply reviewed | Exhaustive PDFBox/native parser fuzzing and all operation runtime paths not performed |
| Native renderer | Both sources and service manifest reviewed; admission/failure/FD/pixel paths traced | Failure PID/BUSY race/hostile retained-FD frame tests pending |
| Architecture/build | Module Gradle files, root DAG/classpaths, source/bytecode gates, tests, workflow reviewed; ordinary/variant probes executed | Counterfeit/real-class/split/comment controlled compiled fixtures still required |
| Dependency/secrets tooling | Resolved inventory/security scripts/policy/tests reviewed; ten secret-tool unit tests executed; current CI conclusions read | No new full offline vulnerability database scan or third-party native audit |
| Release state | Live branches/rulesets/PR/current-head jobs and three completed instrumentation logs read | Full matrix failed on API 24/36; build skipped; branch policy unchanged and final-candidate audit incomplete |

The report records analysis and evidence at the reviewed source SHA. It does not change the release verdict or claim that a passing dashboard substitutes for an enforced trust-boundary invariant.
