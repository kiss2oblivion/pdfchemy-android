# Vanguard compatibility and OCR implementation verification

Scope: current Android implementation branch, following the already-merged Vanguard
false-positive remediation. This is compatibility regression evidence, not a new
security audit or release certification. Vanguard detection and sanitization policy
were preserved.

## Observed PDF behavior

On the Android 36 emulator, the combined native run passes **30 tests** in 148.45s:

- `BenignPdfCompatibilityTest`: all 14 existing benign corpus PDFs audit clean,
  pass Vanguard, and open in the isolated native renderer. First and last pages
  render; attachment and ordinary URI counts match the corpus manifest.
- `SanitizerScopeSecurityTest`: two tests retain the active-content scope.
- `PdfSanitizerAndBatesTest`: ten existing regression tests cover metadata,
  links/destinations, benign attachments, executable actions, encrypted fail-closed
  behavior, sanitization and related PDF operations.
- `OcrProgressIntegrationTest`: three tests cover real two-page OCR progress and
  searchable output, cancellation preserving existing destination bytes and
  suppressing later progress, and malformed input classified as ParseFailed
  while remaining fail closed.
- `OcrIsolationCompatibilityTest`: the existing worker identity and delayed OCR
  callback regression passes.

Separately, `VanguardReaderNavigationTest` passes on Android 36 (one test, 11.732s).
It opens the same actual Recent PDF with Vanguard enabled and disabled, verifies
the native Reader destination, and checks readable page count through the gateway.
The enabled path previously navigated to Editor; this concrete UX defect is fixed.
Unreadable/provider failure recovery from Batch 8 remains distinct from executable
content, and genuine threat/encrypted outcomes retain their fail-closed handling.

Worker host regressions `BenignPdfCorpusTest` and `ActiveContentScrubberTest` pass:
24 tests, zero failures/errors. These results support the tested corpus and cases;
they do not establish compatibility with every possible PDF.

## UX-05 implementation

OCR reports real completed-page counts from the worker, then a separate saving
phase. An appended dedicated AIDL method preserves existing transaction ordering;
bounded synchronous progress carries the admitted operation ID. The host validates
token, quota, fixed total, monotonic per-page counts and phase ordering, and closes
the tracker on terminal result/cancellation. ViewModel generation checks reject
queued progress after terminal state or replacement.

Progress is informational. Only the existing terminal contract, private output
snapshot, ACK and commit path accept output. The new endpoint uses the existing
service admission, input identity, quota, scratch, abort and terminal machinery.
No host PDF parsing or new destination authority was introduced. The original
terminal-only engine path remains compatible.

Full app-host unit run: **125 tests**, zero failures/errors. Debug application and
Android-test assembly pass. Lint has zero errors and 652 warnings. Local logs are
ignored scratch artifacts: `batch9-final-validation.log`,
`batch9-vanguard-ocr-final-runtime.log`, `batch9-reader-navigation-runtime.log`,
and `batch9-vanguard-validation.log`.

## Cross-version export regression

Batch 8 CI run 37755115243 passed architecture, dependency, secret, host-test and
Android 36 instrumentation jobs. Android 24, 30 and 34 each exposed the same single
new export-test assertion failure: opaque embedded black renders as `#FF020202`
instead of exact `#FF000000`. The assertion now requires alpha 255 and each RGB
channel at most 3; independent annotation centroids, page aspect ratio and all four
redaction corner checks remain. No production redaction policy was relaxed.
Local Android 24 native validation passes the full eight-case export test (one
test, 22.585s), plus OCR progress/cancellation/malformed-input and Vanguard on/off
Reader navigation (four tests, 5.958s). The corrected export assertion also passes
again on Android 36 (one test, 47.281s). Replacement CI validation is recorded after
the implementation push.

Implementation SHA `4e49ce68da3821d589cd2302b5a812cc04334523` was pushed only to
`kiss2oblivion/pdfchemy-android`, with the remote branch SHA verified. In CI run
[37759325012](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/37759325012),
the complete 209-test native suites passed on Android 24, 34 and 36. Architecture,
dependency, secret and full host-test jobs passed. Initial API 30 execution aborted
during the existing Audio foreground-service test when unchanged activity-startup
code raised `Window couldn't find content container view`; a same-source replay
was requested. That aborted suite is not counted as a pass or an OCR/Vanguard
failure. Final replay/current-report CI outcomes are recorded in the follow-up
receipt; no unsupported root-cause claim is made.

Local unsigned release assembly, R8/resource shrinking and release lint pass
(`batch9-release-validation.log`, six minutes; zero errors, 651 warnings). The
unchanged artifact checker initially found the new History route missing from
the smoke checklist. Adding its truthful, manually-unverified row restores all
62 route entries. `python scripts/check_release_gate.py --artifact` then passes
fixture hashes, corpus provenance, complete route coverage and artifact hygiene.
This is build/regression validation, not signed release certification.

## Remaining evidence

All 50 original UX IDs have a current disposition. UX-05 is source-DONE with native
progress/cancellation evidence. UX-23 remains PARTIAL with physical release-like
Reader/annotation/100-500-page memory and latency profiling explicitly deferred:
no physical device is attached. Hardware resumption and the other physical UI
gates are listed in `UX_RUNTIME_VALIDATION_2026-10-08.md`. No merge or audit
certification is claimed.
