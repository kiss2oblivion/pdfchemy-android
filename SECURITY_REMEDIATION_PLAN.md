# Android security remediation plan — 2026-10-18

Goal: close the independently identified Android security defects, then resume final verification for PDFchemy Android 2.1.0 (14).

**Release status: HOLD.** This plan supersedes the implementation order in the earlier release plan. The existing signing, device, Play Console and risk-acceptance gates remain required after remediation.

Baseline: `670578355c4e408c2468ba1564b0e1a366faaa0c`.

Evidence: [independent code analysis](reports/security-code-analysis-2026-10-18/SECURITY_CODE_ANALYSIS.md). Execution tracker: [SECURITY_TODO.md](SECURITY_TODO.md).

## Reanalysis update — 2026-10-21

The [fresh analysis](reports/security-code-reanalysis-2026-10-21/SECURITY_CODE_REANALYSIS.md) reviews `857c42b4`, whose application source matches CI target `7f7fe757`. SC-01 through SC-04 batch gates are complete; SC-05 through SC-09 remain open. Six new vulnerability-confirming probes establish five additional findings, SC-10 through SC-14. Their passing results confirm defects and do not complete remediation.

**SC-10 completed before SC-05:** source `4ec21c40`, full CI `39590800550` and 976 native executions pass. Validate source-level FOLLOW-10 before the next source batch, then resume SC-05 unless runtime proof warrants privacy-fix priority; see [post-fix review](reports/security-remediation-2026-10-18/SC10_POST_FIX_ANALYSIS.md). Its saved private metadata/attachment alias leak is covered by the new cleanup and real export regressions. Continue the existing open sequence after that, with SC-11 through SC-14 added as separate finding batches before release acceptance. Keep all existing native redaction/navigation regressions and the one-finding commit rule.

- SC-10: remove/reject aliases to complete private payload subgraphs with bounded traversal. Add real Editor and secure Search exports of the retained serialized fixtures; confirm private bytes are absent while legitimate page/navigation data survives.
- SC-11: coordinate startup cache cleanup with staging ownership. Age a leased snapshot safely and verify cleanup/recreation preserves it; reclaim only eligible unleased data and preserve quota bookkeeping.
- SC-12: give Reader sharing a permitted bounded capability and appropriate lifetime/read grant. Verify a separate recipient reads the exact document after Reader navigation.
- SC-13: define bounded host publication resources and supported provider cancellation/recovery. Exercise blocked open/write and repeated retry without volume exhaustion; a coroutine timeout alone is not proof of interruptible provider I/O.
- SC-14: replace filename/length-only identity with a documented stable identity policy and safe preference migration. Test distinct same-metadata content, supported aliases/renames and ambiguous legacy state.

SC-10's discovery and the new FOLLOW-10 hypothesis show why passing redaction fixtures do not establish complete forensic-redaction assurance. SC-10's bounded batch is complete; SR-01, external URI admission, FOLLOW-10 validation and the two distinct startup follow-ups remain open.

## Original starting position — 2026-10-18

The review produced nine new findings. Seven audit probes confirmed vulnerable component behavior; 34 existing focused tests also passed. The redaction probes recovered sensitive markers from saved PDF streams despite their absence from ordinary extraction. Full Android export reproduction remains outstanding.

The review worktree contains uncommitted probes, reports and evidence. These are inputs to implementation, not completed fixes. Retain the baseline evidence before converting probes to secure-behavior regressions.

## Working rules and batch exit

- Reconcile the current local/remote candidate and preserve all existing work. Use an isolated implementation worktree; do not overwrite the review evidence or the original implementation checkout.
- Freeze an inventory/hash manifest for the baseline probes, synthetic PDF fixtures, XML and logs. Keep baseline and post-fix evidence in different directories.
- One finding per implementation batch and commit. Introduce a meaningful regression that fails for the actual security contract before the patch and passes afterward.
- Existing audit probes intentionally assert vulnerable behavior. Convert or relocate each relevant probe as its issue is fixed; a passing vulnerability-confirmation test cannot serve as closure evidence.
- Run focused tests plus the relevant build/lint/static checks and `git diff --check`. Stop advancement on a required failure, diagnose it and resolve it before moving to the next dependent batch.
- Push completed batches only to `kiss2oblivion/pdfchemy-android`; confirm remote SHA and required same-source CI. Record validation levels separately. No merge or release is part of an implementation batch.
- Preserve private isolated workers, token ownership/ACK, bounded validation and staging, host-only destination publication, offline document processing, source protection, consent and opt-in history.

Batch closure requires a finding ID, exact patch scope, commit/remote SHA, test names and results, evidence locations, API/device where applicable, and remaining limits. Source-complete and device-verified are separate statuses.

## Ordered implementation batches

### Batch 1 — SC-01: remove retained original resources from Editor redaction

Primary scope: `PdfEditorWorker.kt`, its redacted-page construction/export helpers and regression fixtures.

Implementation:

1. Reproduce the issue through the actual editor worker/export path, complementing the retained PDFBox component proof.
2. Replace the redacted page's original resource/object graph with a sanitized representation containing only the required output content. Merely overwriting `/Contents` or pruning one named resource is insufficient.
3. Handle shared Forms/images and original metadata/annotation carriers explicitly. Preserve unrelated pages, annotation fidelity, rotations and supported document behavior.
4. Keep secure export fail-closed if required rasterization or sanitization fails.

Follow-up implementation policy: flatten supported form appearances before replacing redacted content, detach form values and reject XFA. Rasterize the bounded transitive closure of pages sharing original streams with redacted pages; keep independent pages as vectors. Shared embedded-font streams may expand that closure and remove text selection from additional pages. Before writing output, reject any remaining reachable original stream from a replaced page. Unsupported retained references must preserve an existing destination on failure.

Secure redaction also removes document information, XMP/private application metadata, accessibility structure and embedded/associated original files, including recorded alternate references. Reuse the bounded attachment scrubber and retain benign named destinations. These removals require helper and actual-worker saved-object regressions; an earlier green suite without these cases cannot close SC-01.

Acceptance:

- Form XObject and scanned-image fixtures cannot yield original sensitive text or image content from decoded saved objects/resources.
- Page-tree text extraction and pixel checks pass; neither alone substitutes for saved-object inspection.
- Shared-resource and mixed redacted/unredacted-page fixtures preserve permitted content without retaining the original sensitive resource through another reference.
- Actual Android editor export passes focused API 24/30/34/36 instrumentation before full closure.

### Batch 2 — SC-02: prevent original-page retention through search-redaction links

Primary scope: `PdfRedactionEngineWorker.kt`, page import/destination reconstruction and regression fixtures.

Implementation:

1. Reproduce the two-page internal-link fixture through secure search → review → export.
2. Build output pages and remap internal destinations to their sanitized replacements. Audit imported annotations, action destinations and shared resources for references into the original sensitive graph.
3. Preserve benign navigation where safe. Record any deliberately removed unsupported carrier instead of silently retaining it.

Acceptance:

- Direct destinations, GoTo actions, cross-page links and shared-resource variants cannot serialize the original sensitive page/content outside the output page tree.
- Ordinary extraction, decoded-object inspection and visual output all meet the security contract.
- Crop origins, all four rotations, multiline targets and safe navigation work through the real Android export path.

### Batch 3 — SC-03: preserve published batch outputs during cancellation

Primary scope: `MainViewModel` operation records, PDF/image batch publication and cancellation cleanup.

Implementation: make publication terminal for deletion eligibility; cancellation must not replace `PUBLISHED` with `CANCELLED`. Keep writer-stop/join ordering and operation identity protection.

Acceptance: in deterministic PDF and image two-item batches, cancel while item 2 is active. Completed item 1 and its seeded bytes survive; only a proven fresh unfinished item is cleaned up after its writer stops. Error, stale-cancel and destruction variants cannot delete completed outputs.

### Batch 4 — SC-04: finalize successful outputs independently of Warning presentation

Primary scope: compression result publication, `UiState` completion and ViewModel clearance.

Implementation: transition output ownership on successful write, including `targetMissed`, before presenting a Warning. Dismissal must not discard unresolved ownership or leave published URIs fresh.

Acceptance: a real successfully written target-missed PDF survives warning dismissal, later cancellation, ViewModel destruction and URI reuse. Both record state and freshness reflect publication; unsuccessful writes retain proper cleanup.

### Batch 5 — SC-05 and retained RA-05: complete direct-screen ownership integration

Use separate finding commits for SC-05 and RA-05, even if both use the same ownership API.

Primary scope: destination creation/callbacks, `OutputPolicy`, direct-screen completion, Redaction/Markdown and every equivalent save callback found by inventory.

Implementation:

1. Inventory every destination creator and writer; identify creation, handoff, writer ownership, successful publication, failure and cancellation paths.
2. Carry operation-scoped creation provenance into each writer. Retire freshness on every successful publication; eliminate dependence on stale process-wide URI membership.
3. For RA-05, give redaction export a retained operation owner and an explicit Back/input policy using the shared processing/cancellation contract.

Acceptance:

- Direct-screen successful output is no longer fresh. A later operation using the same existing capability preserves its bytes on pre-publication failure/cancellation.
- Failures, rejected initialization and abandoned callbacks clean up only proven fresh incomplete outputs, after writer termination.
- Delayed redaction export, Back, input change, recreation and provider failures obey the retained owner; the UI reports the actual outcome.
- Audio and saved-folder/system-picker paths retain their supported behavior. Document the existing possibility of partial provider writes after publication starts.

### Batch 6 — SC-06: close cancellation gaps during resource ownership transfer

Primary scope: Reader/Editor/Reflow staging callers, `DocumentStager` acquisition API as needed, and native bitmap return/ownership paths.

Implementation: retain an owner capable of cleanup across the outermost dispatcher return. Commit transfer explicitly; close/release acquired leases or bitmaps when cancellation discards the result.

Acceptance: deterministic cancellation after allocation but before Main-thread resumption leaves no extra stage lease, abandoned snapshot or unowned bitmap. Repeat navigation/retry/recreation without quota drift; keep live worker/tool borrowers intact. Exercise real Compose/render boundaries, not copied cleanup pseudocode.

### Batch 7 — SC-07: remove untrusted I/O from the staging monitor

Primary scope: `DocumentStager` registry, quota reservations, copy admission and cancellation.

Implementation: keep monitor-held sections short and limited to state transitions. Reserve aggregate capacity, perform provider metadata/open/read outside the global monitor, and publish the completed snapshot plus initial lease atomically. Bound/cancel stalled admission using supported I/O APIs; do not claim arbitrary Binder I/O can always be interrupted.

Acceptance: controlled providers stalled in query/open/read cannot block Main-thread retain/release or unrelated sessions. Cancellation/failure reclaims reservations and partial files; concurrent staging preserves quotas and the CI46-01 eviction fix. Use bounded test doubles, not real volume exhaustion or indefinite test hangs.

### Batch 8 — SC-08: bound Markdown import in the host

Primary scope: Markdown picker/read helper and user-visible import failure handling.

Implementation: choose and document an editor-appropriate byte/decoded-character budget; enforce both during reading, check cancellation and close input. Reject excessive input before materializing an unbounded string.

Acceptance: known/unknown-length boundary and plus-one inputs, multibyte text and a controlled endless stream terminate safely. Existing editor content remains intact after rejection; the user receives a clear error. No large hostile allocation is required to prove the limit.

### Batch 9 — SC-09: enforce original preservation at destination admission

Primary scope: source identity/provenance passed into output admission, compression/gateway writer entry points and save callbacks.

Implementation: reject known same-document destinations before destructive opens. Account for original provider identity even when input has been staged. Cover supported URI aliases and conservatively handle cases where a fresh separate copy cannot be established; do not promise universal alias detection from string equality.

Acceptance: an actual seeded source returned as destination is rejected before destination open and remains byte-identical. Test known aliases, existing unrelated outputs and fresh outputs. Replace the old rejection-named test with assertions that prove policy rejection and no backend/destructive-open invocation.

## Retained findings and residual-risk work

After the confidentiality/integrity batches, reconcile these in separate focused commits where a patch is needed:

- RA-02: invalidate Reader search generations on Clear and reject delayed results/scrolling.
- RA-03: single/multi picker provider failures and cancellation must not become executable-threat messages, with Vanguard on and off.
- Incoming URI trust: separate externally supplied VIEW/SEND capabilities from internal staged URIs; define supported schemes/grants/private-path admission. Test with a separate Android sender/provider. Do not claim an exfiltration exploit before establishing its complete path.
- Previously reported window privacy, startup, billing, consent and audio controls: verify current code/evidence; avoid reimplementing correct fixes merely because reports are inconsistent.
- SR-01: correct unsupported guarantees, retain the distinction between duration defense and hostile-worker allocation enforcement, and obtain attributable owner disposition or implement a stronger boundary. An earlier approval claim is not sufficient.

## Integrated verification and return to the release plan

Once required fixes are committed:

1. Run architecture, host boundary, required module unit suites, debug/Android-test assembly and release lint/build checks.
2. Run complete same-candidate API 24/30/34/36 instrumentation, including real worker redaction exports and cancellation/concurrency/provider regressions.
3. Run current resolved-dependency and complete reachable-history secret gates; retain actual findings/exceptions and scan completeness.
4. Verify current corpus provenance/hashes, all smoke-route entries, manifests, production IDs and artifact hygiene.
5. Build signed/minified APK and AAB from the clean final candidate; verify signatures, hashes, version/code availability and Play upload-key reconciliation.
6. Complete signed physical workflow/audio/privacy/forensic testing, Play Billing/UMP testing, accessibility and UX-23 performance evidence or explicit disposition.
7. Finish required Play declarations/video, privacy/Data Safety reconciliation and SR-01 disposition. Present a release decision receipt with no hidden NOT RUN gates.

The earlier release plan/checklist remain in the sibling `PDFchemy-antigravity` worktree. Resume their remaining release gates only after incorporating this plan's findings. Reconcile stale candidates and evidence instead of checking old tasks complete automatically.

## Completion definition

All recorded SC findings (currently SC-01 through SC-14) and retained open criteria have verified dispositions; secure behavior is covered by regressions and required Android integration tests; final source/artifact identities match the retained evidence; external/device/risk gates have explicit outcomes. Until that evidence exists, **HOLD remains in force**.

Merge, upload and rollout are separate explicitly authorized actions. Preparing this plan performs none of them.
