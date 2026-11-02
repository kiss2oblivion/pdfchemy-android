# Independent Android security code analysis — 2026-10-18

**Decision: HOLD. Newly demonstrated confidentiality and destination-integrity defects require remediation before release.**

Reviewed production baseline: `670578355c4e408c2468ba1564b0e1a366faaa0c`, Android version 2.1.0 (14), from `fix/security-stabilization-antigravity`.

Review branch/worktree: `codex/security-review-2026-10-18` / `PDFchemy-security-review-2026-10-18`. Production source was not modified. New test files are audit probes, deliberately asserting vulnerable behavior. Their successful execution confirms defects; it does not mean the defects are fixed.

## Scope and confidence

This review independently traced exported input admission, staging and lease lifetime, output provenance/publication/cleanup, PDF worker gateway and host snapshots, native rendering transport, secure-window ownership, redaction export, audio service/publication, billing/consent gates, manifests and release configuration. It also compared the actual tests with the closure claims in earlier reports.

Evidence levels below are deliberately separate:

- **Runtime component proof:** local JVM/Robolectric execution against production classes or the actual bundled PDFBox Android library.
- **Source-confirmed:** a concrete code path was traced, without executing the full adverse scenario.
- **Device validation outstanding:** no fresh emulator instrumentation, physical-device test, signed-release execution, or malicious external-app proof was performed in this review.

The redaction probes exercise actual PDF serialization/import/replacement operations used by production. They substitute a clean replacement page/content for the rasterizer and therefore demonstrate object-retention failures independently of pixel rendering. They are not full UI → isolated-worker → native-renderer → provider end-to-end tests.

This is a targeted code/security analysis, not an exhaustive third-party-native-code audit or a refreshed dependency/secrets scan. Remote CI and Play Console state were not refreshed.

## Findings

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| SC-01 | P1 / high confidentiality | Editor redaction retains original sensitive resources after visible content replacement | Saved-PDF component proof |
| SC-02 | P1 / high confidentiality | Search redaction imports links that retain original sensitive page streams | Saved-PDF component proof |
| SC-03 | P1 / high integrity | Cancelling a batch deletes already-published outputs | Production ViewModel probe |
| SC-04 | P1 / high integrity | Successful target-missed compression output is deleted on ViewModel clearance | Production ViewModel probe |
| SC-05 | P1 / conditional integrity | Direct-screen success leaves published outputs globally classified as fresh | Production completion probe; subsequent misuse traced in source |
| SC-06 | P2 / availability | Outer dispatcher cancellation loses an acquired staging lease | Production stager + caller-boundary probe |
| SC-07 | P2 / availability | Untrusted provider I/O holds the global staging monitor | Source-confirmed; ANR not reproduced |
| SC-08 | P2 / availability | Markdown file import reads unbounded data into the host heap | Source-confirmed; OOM not induced |
| SC-09 | P2 / conditional integrity | Source=destination rejection is claimed but not enforced | Production admission probe + publication source trace |

### SC-01 — Editor redaction leaves original Form/image resources reachable

Source: [PdfEditorWorker.kt](../../pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfEditorWorker.kt), lines 85–98 and 171–172.

The editor creates a raster image, removes page annotations and replaces the page content stream with `AppendMode.OVERWRITE`. It saves the same loaded document and does not replace the page's original resource dictionary. Original Form/image XObjects can remain reachable through `/Resources` even when the visible page invokes only the new raster image.

**Demonstrated:** a fixture with a Form XObject containing `AUDIT_ORIGINAL_RESOURCE_842613` was overwritten using the same PDFBox replacement mode and saved. Ordinary page-tree text extraction no longer returned that marker. Decoding the saved object's streams still recovered it. The object is a retained resource, not an incremental-save artifact.

Impact: a recipient can recover sensitive original content/resources from an apparently redacted PDF. Documents containing original scans or reusable vector Forms are particularly relevant. This is independent of whether the black overlay pixels are correct.

Fix: construct a sanitized object graph for redacted pages; do not retain the original page resource dictionary or unrelated sensitive carriers. Preserve required product behavior deliberately. Test Forms, scanned images, shared resources, attachments/metadata where applicable, and references from other pages. Verify saved bytes and object reachability as well as extraction and pixels.

Acceptance: the original sensitive Form/image data is absent from the serialized output under forensic inspection. Execute the actual editor export on Android after the component-level fix.

### SC-02 — Internal links can bring original redacted pages into search-redaction output

Source: [PdfRedactionEngineWorker.kt](../../pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfRedactionEngineWorker.kt), lines 333–382, especially `newDoc.importPage(baseDoc.getPage(i))` at line 372.

Secure search redaction builds a new document, adds new raster pages for targets and imports non-target pages from `baseDoc`. Imported page annotations/destinations can still refer to an original target page. That original page contains the original vector content underneath the appended overlay. Saving the new document follows the retained reference and serializes original sensitive streams outside the intended page tree.

**Demonstrated:** a two-page fixture used an internal link on page 2 to original sensitive page 1. A clean replacement page was added to the output and page 2 was imported using the exact production PDFBox operation. The saved output had two page-tree pages and normal extraction omitted `AUDIT_SECRET_731904`; scanning decoded saved streams recovered it.

Fix: remap internal destinations to sanitized output pages and rebuild/validate imported page dictionaries, resources and cross-references. Avoid importing original sensitive object graphs. Cover direct destinations, action dictionaries, shared resources and annotations that reference replaced pages. Rasterizing every page is one possible design tradeoff, not an automatic requirement.

Acceptance: benign navigation either targets sanitized output pages or is explicitly removed with documented behavior; no retained original sensitive content survives serialization. Add an actual secure search-redaction export regression on Android.

### SC-03 — Cancellation destroys the PUBLISHED state before checking cleanup eligibility

Source: [MainViewModel.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt), lines 137–165; PDF batch publication at line 847 and image batch publication at line 1455.

`markOutputPublished()` correctly sets `PUBLISHED` and clears global freshness. But `cancelOperation()` then changes **every** tracked output to `CANCELLED`. Cleanup uses the provenance captured in `TrackedOutput` and checks only that its state is no longer `PUBLISHED`. A completed fresh file from an earlier batch item now qualifies for deletion.

**Demonstrated:** register fresh output → track it → mark it published → cancel the still-owned operation. Production cleanup invoked `DocumentFile.delete()` exactly once for that published URI, even though global freshness had already been cleared.

Fix: publication must be terminal for deletion eligibility. Cancel only pending/in-progress destinations and retain completed batch outputs. Apply one authoritative cleanup contract across cancellation, error and clearance paths.

Acceptance: a deterministic two-item batch cancels while item 2 is active; item 1 and its bytes survive, only the fresh unfinished item is eligible for cleanup, and writers stop before deletion.

### SC-04 — Target-missed warnings bypass successful-publication finalization

Source: [MainViewModel.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt), lines 197–220, 263–301 and 640–646.

A successful compression whose requested target cannot be reached produces `UiState.Warning` with a real completed output. The state setter finalizes tracked publication only for `UiState.Success`. The warning leaves the output pending/fresh; `onCleared()` therefore deletes the completed destination. Explicit cancellation has the same eligibility problem. Dismissing the warning can instead drop ownership without finalizing global provenance.

**Demonstrated:** the production compression entry point received a successful `CompressionReport(targetMissed = true)`. After its Warning state, `onCleared()` invoked deletion of the output.

Fix: finalize publication based on successful writer completion, independently of presentation severity. Warning results containing successful outputs require the same ownership transition as ordinary success.

Acceptance: warning output survives dismissal, cancellation after completion, ViewModel/activity destruction and later reuse; its URI is not globally fresh.

### SC-05 — Direct-screen saves escape freshness finalization

Source: [OutputPolicy.kt](../../app-host/src/main/java/com/pdfchemy/app/logic/OutputPolicy.kt), lines 32–53; [MainViewModel.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt), lines 197–205 and 2018–2031; [RedactionScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/RedactionScreen.kt), lines 106–139; [MarkdownStudioScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MarkdownStudioScreen.kt), lines 124–167.

Saved-folder creation registers a URI in the process-wide `freshUris` set. Several screens write directly and report success through `showSuccessToast` without registering a pending output record. The Success setter only clears freshness for outputs in the current tracked record; it does not finalize the URIs carried by the success result itself.

**Demonstrated:** a registered fresh URI remained fresh after production `showSuccessToast(..., outputUri)`. Redaction and Markdown use this entry point without pending-output ownership.

Impact: if a later picker/provider supplies that now-existing URI, provenance lookup can classify the published document as deletable fresh output. Failure/cancellation can delete it. Provider reuse is a prerequisite; an ordinary default same-name save is not asserted to return the same URI.

Fix: bind creation/publication authority to an operation, and retire freshness after every successful writer. Complete the callback migration rather than relying on a process-wide URI set and partial registration.

Acceptance: successful direct-screen output loses freshness; seeded bytes survive a subsequent pre-publication failure/cancellation using that URI. Also clean up fresh failed/abandoned direct-screen saves safely.

### SC-06 — An outer withContext discards staging leases before UI ownership transfer

Source: [PdfReaderScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/PdfReaderScreen.kt), lines 174–181; [PdfEditorScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/PdfEditorScreen.kt), around line 333; [ReflowReaderScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/ReflowReaderScreen.kt), line 268.

These callers wrap `stageDocumentCancellableWithLease` in another `withContext(IO)` and assign ownership only after it returns. If cancellation wins the dispatcher return to Main, the outer call discards its acquired resource. The stager's internal cleanup cannot observe cancellation after it has already returned successfully to that outer block.

**Demonstrated:** a controlled dispatcher let the actual stager finish inside the outer IO block, then cancelled the caller before delivery. Caller cleanup was never reached, while `DocumentStager.activeLeases` remained 1. The probe explicitly released the handle afterward to avoid polluting the test process.

Repeated cancellation can leave snapshots permanently leased within the process, defeating unleased pruning and consuming the fixed stage budget. Native bitmap returns in `NativeRendererCoordinator.renderUriToBitmap` have the same ownership-transfer shape; that related bitmap leak is source-inferred, not separately reproduced here.

Fix: make acquisition/transfer cancellation-safe across the outermost dispatcher boundary, keeping an externally owned cleanup handle until transfer is committed. Verify the analogous renderer path.

Acceptance: cancellation immediately before caller resumption leaves no extra stage lease/snapshot or unowned bitmap; active borrowers and normal navigation continue to work.

Kotlin documents that dispatcher-changing `withContext` can discard a result on prompt cancellation: [official withContext reference](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html).

### SC-07 — Provider I/O executes while holding the process-wide staging monitor

Source: [DocumentStager.kt](../../pdf-ipc/src/main/java/com/pdfchemy/app/utils/DocumentStager.kt), lines 27–65, 131–165, 197–219 and 304–316.

Synchronized staging holds the global monitor during provider display-name queries, `openInputStream`, and the entire copy. `retain`, `release` and session-close operations need the same monitor; some are called during UI composition/disposal and ViewModel continuity updates.

A malicious or stalled provider can block opening/querying before an input stream is available for cancellation cleanup. Even after opening, the byte budget is not a wall-clock deadline. A Main-thread lease operation waiting for that monitor can freeze the UI. No actual ANR was induced in this review.

Fix: reserve quota and registry state in short synchronized sections; perform provider I/O outside the global lock with cancellation/deadline-aware admission and cleanup. Preserve atomic publication/lease acquisition and aggregate quotas.

Acceptance: a deterministic provider stalled in query/open/read does not block Main-thread lease operations or unrelated document sessions; cancellation reclaims reservations and partial files.

### SC-08 — Markdown import bypasses bounded input admission

Source: [MarkdownStudioScreen.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MarkdownStudioScreen.kt), lines 81–99.

The direct text picker calls `stream.bufferedReader().readText()` in the host with no byte/character budget. The later worker request limit cannot protect this earlier allocation. A selected oversized or endless provider stream can exhaust host heap; the Exception catch does not make an allocation OOM safe.

Fix: bound bytes and decoded characters while reading, check cancellation between chunks, close input on cancellation, and reject excessive input with a visible error. Choose an editor-appropriate limit rather than assuming the PDF input limit is a safe in-memory editor budget.

Acceptance: unknown-length boundary/plus-one and endless-stream fixtures fail predictably without host death or retained partial state. This review did not allocate a hostile large input.

### SC-09 — The claimed source=destination rejection test does not establish rejection

Source: [MainViewModel.kt](../../app-host/src/main/java/com/pdfchemy/app/ui/MainViewModel.kt), line 573 onward; [PdfCompressor.kt](../../app-host/src/main/java/com/pdfchemy/app/logic/PdfCompressor.kt), lines 17–32 and 75–95; [PdfGateway.kt](../../app-host/src/main/java/com/pdfchemy/app/logic/PdfGateway.kt), lines 27–54; existing `DestinationIntegritySecurityTest`, line 451 onward.

There is no source/destination equality guard in the reviewed compressor entry/publication path. Staging preserves input while processing, but final `openOutputStream(destUri, "wt")` can still replace the original if a provider supplies it as destination. Staging is not an original-preservation policy.

**Demonstrated:** identical source/destination URI arguments reached the mocked compression backend exactly once and the ViewModel reported Success. The existing rejection-named test only waits for Error with a nonexistent fixture provider and checks that deletion was not called. It neither requires a rejection reason nor proves the backend was not invoked.

Impact is conditional on destination capability reuse/aliasing; no common same-name picker behavior or complete device overwrite exploit is claimed. Provider aliases can also identify one document through different URI strings.

Fix: define and enforce source-preservation at output admission, including known same-document identities, before destructive opens. Do not confuse this with inability to roll back unrelated provider failure after publication starts.

Acceptance: seed source bytes, return its actual capability as destination, exercise real admission/publication, and assert rejection before destination open plus unchanged bytes. Include supported provider-alias cases.

## Earlier findings still visibly open or inadequately evidenced

- **RA-05 redaction operation lifetime:** RedactionScreen still owns export in its composition scope, uses a local processing flag, leaves Back/input selection active, and does not register destination ownership. The reviewed source still exhibits the earlier finding; it overlaps SC-05 but also needs cancellation/navigation integration coverage.
- **RA-02 search Clear race:** Reader Clear still clears query/matches without invalidating `searchGeneration`; a pending response can republish results and scroll. This is a retained correctness finding, not a newly discovered data-exfiltration vulnerability.
- **RA-03 picker recovery:** Vanguard-disabled single/multi picker staging still catches Exception into the blocked-threat dialog; cancellation is also swallowed in those branches. The inaccurate threat message remains source-visible.
- **Incoming URI trust:** exported MainActivity returns externally supplied VIEW/SEND file URIs and stages them directly. DocumentStager allows general file reads after its internal-staged fast path; it does not reject untrusted private paths. A complete exfiltration chain was not established. Add an external-app admission test and distinguish internal staged capabilities from externally supplied URIs. Android describes this risk class in its [ContentResolver security guidance](https://developer.android.com/privacy-and-security/risks/content-resolver).
- **SR-01:** raw worker scratch allocation remains a documented residual. Neither worker-local watchdogs nor cooperative wrappers alone establish a hard allocation bound under arbitrary code execution inside that worker. Existing report approval contradictions must be reconciled; this review supplies no new owner acceptance or adversarial runtime guarantee.

## Why prior green tests did not close these issues

1. Editor preview/export pixel parity cannot prove that unused resources or non-page-tree streams are absent. Search bounding-box tests likewise do not inspect the final saved object graph.
2. Existing destination-success tests exercise whole-operation Success, not an already-published item within an active batch, a Warning result, or direct-screen completion.
3. The Reader bitmap cancellation test recreates assignment/cleanup logic locally; it does not invoke the actual dispatcher-return boundary. Stager concurrency tests cover atomic retain/eviction but not outer caller cancellation after acquisition.
4. Some fixture names overstate their assertions: the source=destination test does not prove rejection, and existing-destination “seeded bytes” unit tests mock deletion without actually seeding/checking a provider's bytes.

These are test-coverage limitations, not evidence that all earlier fixes are invalid. The window lease fix and host/private snapshot architecture remain material protections.

## Fresh validation performed

Environment: Windows, Android Studio JBR 21, local Android SDK, Gradle offline, one worker, in-process Kotlin compiler. Runs used the isolated review worktree.

| Check | Result |
|---|---|
| `securityArchitecture` | PASS, freshly executed |
| `:app-host:securityAudit` | PASS, freshly scanned 135 host source files |
| Initial two cleanup/provenance audit probes | 2/2 executed successfully, confirming observed defects |
| Expanded probes | 5/5 total across IndependentSecurityReviewProbeTest and IndependentLifecycleReviewProbeTest |
| Existing focused suites | DestinationIntegritySecurityTest 12/12; WindowSecureManagerTest 11/11; PdfReaderBitmapOwnershipTest 3/3; DocumentStagerRetentionTest 8/8 |
| Redaction saved-object probes | 2/2 executed successfully; both recovered a secret marker absent from ordinary page-tree extraction |
| Full unit suite, emulator/physical instrumentation, release lint/build, signed artifacts, dependency/secret scan, current remote CI | NOT RUN in this review |

Total distinct tests in the retained focused evidence: **41** (34 existing tests and 7 audit probes). Repeated probe execution is not counted again. Existing tests passing does not negate probe-confirmed failures of the security contracts.

Commands and XML are retained under [evidence](evidence/). Audit source files:

- [IndependentSecurityReviewProbeTest.kt](baseline-probes/IndependentSecurityReviewProbeTest.kt)
- [IndependentLifecycleReviewProbeTest.kt](baseline-probes/IndependentLifecycleReviewProbeTest.kt)
- [RedactionObjectGraphReviewProbeTest.kt](baseline-probes/RedactionObjectGraphReviewProbeTest.kt)

## Required implementation order

1. Fix SC-01 and SC-02 as separate redaction confidentiality batches. Convert each confirming probe into a regression asserting the secret is absent, and extend to real worker/native Android exports before closure.
2. Fix SC-03, then SC-04, then SC-05 with real seeded-byte provider regressions and batch/direct-screen integration coverage.
3. Fix SC-06/SC-07 without weakening quotas or live-session continuity; verify native bitmap ownership across dispatcher cancellation too.
4. Fix bounded Markdown admission and source/destination protection; reconcile retained RA findings and externally supplied URI policy.
5. Run the integrated release gates against the resulting candidate and retain SR-01 and external/device gates explicitly.

Each fix remains a separate verified batch/commit under the existing execution discipline. No production fix, commit, push, merge, release, or risk acceptance was performed by this review.
