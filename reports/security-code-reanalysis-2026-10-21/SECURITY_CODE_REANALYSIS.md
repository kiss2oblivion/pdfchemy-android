# Android code and security reanalysis — 2026-10-21

**Release: HOLD. Five additional findings are confirmed. SC-10 is a confidentiality defect in the shared redaction cleanup path and should be addressed before the next planned batch.**

Reviewed HEAD: `857c42b438e46ffcbb0a9f9650536446591dabaa`. Application/test source matches `7f7fe75766e4ec55461b140faccde101eebb5aa2`; the intervening commit contains documentation/evidence only. Review worktree/branch: `PDFchemy-security-reanalysis-2026-10-21` / `codex/security-reanalysis-2026-10-21`. No production code was changed for this analysis.

SC-04 finished before this review: source and evidence were pushed to the Android repository, remote SHA matched `857c42b4`, and [CI run 39400342318](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39400342318) passed all nine jobs and 944 native tests across API24/30/34/36. API34 required one unchanged retry after a startup lifecycle timeout; FOLLOW-09 remains open. Those results cover the prior regression suite, not the new vulnerability probes below.

## Scope and evidence

This is a targeted fresh review of input admission, staging/lease lifetime, cache deletion, destination publication/cancellation, worker/renderer capabilities, PDF object cleanup, Reader sharing and persisted identity. An inventory found 221 production Kotlin/Java/manifest files in the three searched modules; this is not a claim that every file was read line by line. Selected current source ranges and hashes are retained in [SOURCE_REVIEW.md](SOURCE_REVIEW.md) and [verification.json](evidence/verification.json).

Six final unique probes deliberately assert the observed vulnerable behavior: three host JVM/Robolectric methods, two PDF-worker JVM/Robolectric methods, and one API24 native sharing-component method. **Passing these probes confirms defects; it does not mean the defects are fixed.** Probe sources are archived outside compiled source sets under [probes](probes/).

The redaction probes serialize and reload their input, run the actual production preparation/plan/replacement/pruning/detachment-guard helpers, save, reload and inspect the reachable payload. They use a clean rectangle as the replacement content instead of executing native rasterization. They are component proofs, not new native Editor/Search export tests. The cancellation probe uses a bounded, explicitly released blocking OutputStream; a real stalled SAF provider and disk exhaustion were not exercised.

## New findings

| ID | Priority | Issue | Evidence |
|---|---|---|---|
| SC-10 | P1 confidentiality | Nested private payloads survive redaction cleanup through alternate references | Two saved-PDF component proofs |
| SC-11 | P2 availability | Startup cache sweep deletes an actively leased staged PDF | Production stager + actual sweep method |
| SC-12 | P2 functionality | Reader sharing fails because staged files are outside FileProvider roots | Actual sharing component on API24, with a passing export-root control |
| SC-13 | P2 conditional availability | Blocking publication I/O prevents cancellation/cleanup from completing | Production audio-copy component + PDF host-commit source trace |
| SC-14 | P3 correctness | Same-name/same-size documents share persisted Reader/Editor state identity | Production identity function + distinct-content provider metadata fixture |

### SC-10 — Carrier removal does not remove aliases to descendant payloads

Sources: `RedactionResources.kt:138–186`, particularly `record()` and `detachReferences()`; selected-page stream collection at `29–61`. Both `PdfEditorWorker.kt:40–45,185–188` and secure `PdfRedactionEngineWorker.kt:245,340–405` use this helper pipeline.

The scrubber records carrier containers such as PieceInfo and AF/EF dictionaries. It removes references to those recorded identities, but does not collect all private descendant payloads. A catalog reference to a nested payload can therefore remain after the normal carrier is removed. The final detachment guard tracks original streams reachable from selected pages; it does not add these catalog-private descendants to that set.

Two serialized/reloaded fixtures demonstrate the gap:

1. A PieceInfo application dictionary owns a private stream; an alternate catalog entry refers to that same stream. The PieceInfo carrier disappears, but the saved output's alternate reference still yields `REANALYSIS_NESTED_PRIVATE_PAYLOAD_529814`.
2. An AF/EF attachment graph owns a payload stream without a Type entry. The bundled parser accepts the input. Carrier cleanup removes AF, but the alternate reference retains the payload because the separate stream filter only records streams explicitly typed EmbeddedFile.

In both cases ordinary page text extraction omits the marker, while decoding the saved reachable payload recovers it. `requireOriginalsDetached()` passes. This is residual private application/attachment data surviving the cleanup contract, not an incremental-save artifact. Existing tests cover aliases to some top-level carriers and explicitly typed attachment streams; these descendant variants were missing.

Evidence: [redaction-final.xml](evidence/redaction-final.xml), [serialized-fixtures.log](evidence/serialized-fixtures.log), [probe source](probes/FreshRedactionAliasProbeTest.kt), and the four [input/output PDFs](fixtures/). Native end-to-end publication of these particular fixtures remains unexecuted.

Remediation: remove or reject alternate references to private payload subgraphs using bounded graph traversal and explicit safe-object handling. Preserve legitimate page/navigation references deliberately. Add actual Editor and secure Search export regressions for both variants; require absence in all saved streams/strings and retained unrelated-page behavior.

### SC-11 — Cache aging overrides active lease ownership

Sources: `MainActivity.kt:408–439`; `DocumentStager.kt:144–151` and lease-aware release/pruning methods.

Every Activity creation launches a recursive cache sweep. It deletes PDFs older than one hour, including files under `cache/staged`, without consulting DocumentStager ownership. Reading a snapshot does not refresh its modification time. An older active snapshot can therefore be removed when another Activity creation/recreation starts cleanup.

The probe acquires a real initial staging lease, ages only its synthetic PDF snapshot, invokes the actual private sweep method, and observes that the file is deleted while `activeLeases()` still reports one. It releases the lease afterward. Original provider-document deletion, an actual ANR, and a full long-running Reader/recreation scenario were not demonstrated.

Impact: active viewing/export sessions can lose their named staged input, and stager bookkeeping continues to advertise a protected capability whose file has disappeared.

Remediation: make the staging owner authoritative for snapshot reclamation; coordinate generic cache cleanup with ownership rather than deleting leased files by age. Verify old leased snapshots survive cleanup/recreation, eligible unleased files are reclaimed, and later reads still succeed.

### SC-12 — Reader sharing uses an unexposed staging path

Sources: `PdfReaderScreen.kt:174–181,420`; `DocumentActions.kt:26–30`; `res/xml/file_paths.xml:3–5`.

After loading, Reader replaces its selected URI with `cache/staged/snapshot_*.pdf`. Its Share action passes that URI to `DocumentActions.share`, which asks FileProvider for a content URI. Configured roots expose `cache/scans`, `cache/exports`, and `files/scans`; they do not expose `cache/staged`. Root resolution throws before the share chooser starts. Reader then reports a misleading sharing-app error.

The API24 probe stages a real generated PDF and invokes the actual sharing method. It receives the configured-root exception. A control PDF under `cache/exports` resolves through the same FileProvider and reads back byte-identically. Strict instrumentation validation confirms one completed probe, zero failures/skips and an empty crash buffer. Reader UI and a separate recipient application were not automated.

Evidence: [native-api24.log](evidence/native-api24.log), [native-runner.log](evidence/native-runner.log), and [probe source](probes/FreshReaderShareProbeTest.kt).

Remediation: provide a bounded share capability with an appropriate lifetime, for example a controlled copy under the existing exports root, and retain the required read grant. Test actual Reader → chooser → recipient reads after Reader navigation, with exact byte preservation.

### SC-13 — Publication lifetime is not bounded by coroutine cancellation

Sources: `HostOutputTransaction.kt:122–144`; `PdfGateway.kt:126–145`; `AudioOutputPublisher.kt:15–62`; `MainViewModel.kt:146–171`.

PDF publication occurs after the worker acceptance handshake releases its token. Real provider `openOutputStream` and `write` calls then run in the host. Audio publication similarly invokes ordinary blocking writes and checks cancellation only between them. Cancelling the coroutine does not make these calls return.

The controlled probe enters production `AudioOutputPublisher.copy`, blocks its first write, cancels the writer, and verifies that join and cleanup do not complete during the observation window. Releasing the write allows termination and cleanup. This proves the blocking boundary; it does not establish a real-provider ANR, measured storage exhaustion or permanent resource leakage on a device.

A stalled or malicious chosen destination can retain host jobs, descriptors and temporary output until its I/O returns. The PDF worker's deadline does not cover this post-ACK publication phase. MainViewModel clears its active job and returns to Idle before a separate cleanup coroutine joins the previous writer, so another operation can start while the earlier host publication remains blocked. Per-operation size limits do not bound the aggregate of these outstanding transactions. The missing backpressure/recovery policy is the availability gap; this review does not assume arbitrary provider I/O can always be interrupted. This is separate from SC-07's input-provider I/O under the global staging monitor.

Remediation: define supported provider cancellation semantics, bounded publication resources and recovery. Exercise blocked open/write and repeated cancel/retry with small controlled budgets. Cancellation is cooperative; a timeout wrapper alone does not establish interruption of arbitrary blocking I/O. See the [official withTimeout documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html).

### SC-14 — Document state is keyed by a non-unique metadata tuple

Sources: `DocumentIdentity.kt:15–47`; `PdfReaderScreen.kt:173,188,230–246,416`; `PdfEditorScreen.kt:348–353,394–396`.

Identity hashes only display name and reported byte length. Two different provider documents with the same name and size feed the same `name::size` input into SHA-256. Reader bookmarks/remembered page and offset, and Editor remembered page, consequently share preference keys.

The probe supplies distinct content URIs and different byte streams with identical metadata, obtains identical production IDs, then changes one reported size and observes distinct IDs. Provider metadata is controlled at the resolver boundary; no full Reader/Editor UI state migration was executed. Document-content exfiltration is not demonstrated.

Remediation: define a content/capability identity policy, use the admitted immutable snapshot identity where appropriate, and version stored state safely. Verify equal metadata with different content remains separate, supported aliases/renames behave as intended, and ambiguous legacy state is not silently assigned to another document.

## Earlier findings and remaining threats

| Area | Current assessment |
|---|---|
| SC-01/02 original redaction cases | Implemented and covered by the prior current-source CI cases. New SC-10 prevents treating those results as complete forensic-redaction assurance. |
| SC-03/04 publication cases | Implemented and CI-covered; the new audit did not find a regression in those specific changes. |
| SC-05 | Still open: direct Redaction/Markdown success paths do not retire freshness through operation ownership. |
| SC-06 | Still open: outer dispatcher return can lose staging leases/bitmaps before caller ownership transfer. |
| SC-07 | Still open: provider metadata/open/read occurs under DocumentStager's global synchronized monitor. |
| SC-08 | Still open: Markdown import uses unbounded `readText()` before editor admission. |
| SC-09 | Still open: source/destination identity rejection is not enforced before destructive destination opens. |
| RA-02/03/05 and external URI admission | Retained open criteria: stale search completion, cancellation/provider diagnosis, Redaction lifecycle ownership, and explicit VIEW/SEND file/content capability policy still need their own implementation/verification. No separate-sender private-file exfiltration proof is claimed here. |
| SR-01 / FOLLOW-07 | Still open: writable worker descriptors and post-execution logical-size checks do not establish a hard hostile-worker allocation bound. Worker-local watchdogs are not a demonstrated non-bypassable lifetime guarantee against arbitrary native execution in that process. See the [corrected assessment](../security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md). |
| FOLLOW-05 / FOLLOW-09 | Prior API24/WebView SIGILL and the separate API34 startup lifecycle timeout remain unattributed. This audit's sharing-component run does not close either issue. |

Input staging, non-exported isolated workers, typed response validation, private output snapshots, pixel bounds, FileProvider root restrictions and secure-window ownership remain material protections. No new sandbox escape, remote code execution, or successful cross-application data theft was established in this pass.

## Fresh verification and limits

| Check | Result |
|---|---|
| Final host component probes | Three vulnerability-confirming methods pass; 7.216s; zero failures/errors/skips |
| Final serialized/reloaded PDF component probes | Two vulnerability-confirming methods pass; 4.324s; zero failures/errors/skips |
| Native sharing component | One vulnerability-confirming method on API24; 0.258s; strict complete-result validation; empty crash buffer |
| Security audit / architecture | PASS; 135 host files scanned by the existing static task |
| Android test APK assembly / resolved inventory | PASS; combined command 2m 14s; 234 resolved Maven coordinates |
| Live OSV query | Zero affected matches for those 234 package/version pairs. [API scope](https://google.github.io/osv.dev/post-v1-querybatch/); this is not complete native/OS/zero-day coverage. |
| Fresh reachable-history secret gate | TruffleHog 3.97.9: six exact reviewed historical matches, zero rejected findings; candidate values were not printed |
| New full CI, signed release, physical/Play tests | NOT RUN for this audit |

The first host probe attempt had two fixture failures: Windows path parsing prevented the file-identity assumption, and Robolectric FileProvider root matching rejected the supposedly valid export control. Neither failure is counted as vulnerability evidence. The corrected identity fixture uses content-provider metadata, and sharing is proven on Android. The initial logs/XML/source are retained. The earlier constructed-object redaction checkpoint is also retained separately and not added to the final six unique probes.

Immutable logs, XML, fixtures, probe sources, reviewed source excerpts and verification metadata are covered by [EVIDENCE_SHA256SUMS.txt](EVIDENCE_SHA256SUMS.txt). Generated APKs remain in the ignored local runtime directory with their hashes recorded. Production source remains unchanged; compiled audit probes are removed after their exact sources are archived.

## Remediation priority

1. Implement SC-10 as the next separate finding batch, with native Editor and secure Search export tests of the retained fixtures.
2. Continue SC-05 and the existing open batch sequence, retaining one finding per implementation commit.
3. Add SC-11 through SC-14 as explicit separate work before final release acceptance. Preserve the distinct stalled-input and stalled-publication threat models.
4. Complete SR-01 enforcement/risk disposition, startup reliability, external-app/provider and signed physical/Play gates. A green build alone does not close these findings.
