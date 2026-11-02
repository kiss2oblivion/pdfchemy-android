# PDFchemy 2.1.0 release gate checklist

**Release status: HOLD.** The [current closure record](reports/integration-2.1.0/PR17_CLOSURE_2026_10_30.md) supersedes prior readiness recommendations. Audio cancellation and explicit share-management fixes are integrated at `e3f22dd19919e525bcbecd42bb83541234df91af`; final report-bearing exact-head CI, signed APK/AAB and runtime receipts belong in PR #17 metadata after verification. Historical checks below close only their explicitly named sources. Codex is the canonical writer/release engineer; Gemini is the independent product security auditor.

## Automated integration gates

- [x] Refresh #12/#13/#14/#15 remote heads, bases and latest CI on 2026-10-30. All input heads match the integrated code.
- [x] Preserve all merge parents and focused correction commits; no historic #1-#7 replay, original-branch rewrite or main merge.
- [x] Re-read all four #12 native failures: the unsupported-linearization test reaches a prohibited FD pathname reopen. Core #13 correction and regression survive in the combined tree.
- [x] Historical `de825ca1` verification: sixteen protected core Git blobs match #14 and both table/parser blobs match #12. Later OutputPolicy file-URI cleanup support changes that one blob; the current tree must not be described as sixteen identical blobs. Worker isolation, bounded pipe storage, geometry and original-source protections remain required.
- [x] Code SHA CI: all nine jobs pass in [40970821297](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40970821297). Baseline SHA `f5536f0e5f71abbaf59ac6192fa0a7479de00119` passes all nine jobs in push run [41026470374](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41026470374) and PR #17 run [41026479200](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41026479200). Verification SHA `477d99ec89e69c21099b15b7add10072b60caea2` (staging-test deterministic cancellation) runs in push run [41033146471](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41033146471) and PR #17 run [41033154506](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/41033154506).
- [x] Local JVM: 627 pass; GitHub Android: 420 per API 24/30/34/36, 1,680 total, zero failure/error/framework skip.
- [x] Actual exported-PDF geometry, forensic privacy, recursive sanitization, storage quotas, cancellation/failure cleanup and source/old-target preservation regressions pass at that code SHA.
- [x] Worker isolation, service restrictions, bounded host collectors, renderer scheduling and recovery remain enforced.
- [x] Architecture, secrets and dependency checks; release lint/build, artifact hygiene and R8 Gson contracts pass at that code SHA.
- [x] Standard signed/minified code-SHA APK passes the scoped Reader, route-restoration and PPTX public flows, plus static 16 KB ELF/ZIP checks.
- [ ] **Final report-bearing HEAD:** require all nine jobs on its exact SHA and attach the immutable run/job links in the draft PR verification comment. This committed checklist is a pre-run snapshot; a completed code-SHA run does not close this row for a later head.
- [x] **Gemini combined acceptance:** Reader/R01-R06 and G01-G08 report against the combined draft PR #17 documented in [READER_INTEGRATION_ACCEPTANCE_2_1_0.md](READER_INTEGRATION_ACCEPTANCE_2_1_0.md).

## Remaining gates and owners

| Gate | Owner | State and evidence required |
|---|---|---|
| Independent integration review and any approved merge | User/reviewer | PENDING. Draft only. Later merge head requires its own required CI. |
| Retained-share Settings management | Codex | IMPLEMENTED at `e3f22dd1`. Explicit confirmed cleanup, statistics and partial results; history clearing/disabling preserves recipients. Focused 26 JVM and 18 native API36 cases pass. Final-source four-API/signed verification is recorded separately. No automatic expiry/eviction or grant revocation. |
| Physical ARM64 and 16 KB runtime | User/device owner | PENDING by owner direction. Include signed APK, API/device identity, memory pressure, background/process recovery, rendering/export and runtime page size. x86 emulators/static alignment do not close this. |
| Signed/minified forensic Editor/redaction export | Codex verification plus device owner | OPEN. Final actual saved PDFs must retain geometry and pass text/raw-byte/decoded-stream/private-alias checks under the required signed/device gate. Four-API native debug evidence and signed Reader round trip are scoped separately. |
| Play upload-key registration and version-code availability | User/Play owner | UNKNOWN. Compare the local certificate with Play's registered upload key and verify highest used version code. Code 14 and local signature verification alone are insufficient. No Console access/change is implied. |
| Billing purchase/restore and UMP regional/account behavior | Codex/Play owner | OPEN. Real test-account, Play-delivered build and consent scenarios, including Reader/Reflow ad exclusions. Standalone unit/CI success does not prove store behavior. |
| Play policy declarations/Data safety and SDK privacy | Owner; Codex evidence; Gemini independent audit | OPEN. Review declarations and actual online SDK behavior; first-party local processing and offline emulator checks are not a complete third-party network/log audit. |
| Full public processing journeys | Codex/device owner | PARTIAL. Historical signed Reader/Editor continuity and PPTX export pass. Final-source results and any additional public journeys require their own receipts. Complete signed compress/merge/split/rotate/extraction/OCR/redaction/sanitize/EPUB/password journeys are not all executed. Reopen produced outputs and check old bytes. |
| Accessibility/localization/performance | Codex/device owner | OPEN. Resource checks and historical lint pass; TalkBack, all locales/font scales, low-memory/endurance and physical startup/loading require review. New shared-copy labels intentionally use English fallback. |
| Find & Replace fidelity | Next-cycle core owner; user scope decision | DEFERRED SOURCE RISK, not closed. Current raster replacement can reset geometry and remove annotations/selectable content; private-graph erasure is unproven. Do not present it as forensic redaction. See [investigation](reports/INTEGRITY_NEXT_CYCLE.md). |
| Repair recovery fidelity | Next-cycle core owner; user scope decision | DEFERRED SOURCE RISK, not closed. Loaded-page count does not establish recovery fidelity or signature preservation. Compare against known damaged originals before making complete-recovery claims. |
| External PDF compatibility corpus | Core/device owner | PARTIAL. Built-in malicious/geometry/native fixtures run; optional tests that self-return without external files are not corpus evidence. |

The reproduced critical/high integration defects listed in the report are corrected. Deferred fidelity risks, incomplete workflows and owner-controlled gates remain visible; neither CI nor this checklist certifies a release.

## Retained-share UI interface

Use `DocumentActions.inspectRetainedShares(context)` off the main thread. It returns retained copy count/bytes, total bytes, reserved copy bytes, active copies/readers/transfers, incomplete copies and protected opaque selection IDs. Do not expose private filesystem paths.

Only on explicit user selection, call `cleanupRetainedShares(context, ids, cancellationSignal)`. Report deleted count/bytes, protected IDs/reasons, failures, missing IDs, unprocessed IDs and cancellation from `RetainedShareCleanupResult`. `complete` is false when protected, failed, unprocessed or canceled work remains. Active readers/copies and unverifiable legacy ownership are protected. Handle `RetainedShareQuotaException` by explaining the 64-copy/250 MiB limit and offering explicit management; never silently remove an older completed recipient copy.

## Evidence handoff

The machine-readable [validation evidence](reports/integration-2.1.0/validation-evidence.json) contains refreshed remote metadata, failed #12 log excerpts, merge/fix provenance, protected-file blob IDs, code-SHA native artifact digests, local counts and signed-artifact checksums. Complete local receipts remain under the shared `release-validation-2.1.0-2026-10-23` validation directory; production document data and signing credentials are not committed.

The draft PR verification comment records the final report-bearing SHA and same-SHA CI results without changing that validated commit afterward. Unchecked owner gates remain unchecked until their separate evidence arrives. No release/tag/deploy action follows automatically.
