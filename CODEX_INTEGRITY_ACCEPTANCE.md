# PDFchemy 2.1.0 integrity acceptance

Status: CORE INTEGRITY IMPLEMENTATION VERIFIED; RELEASE HOLD. No merge or deployment is authorized.

Verified implementation SHA: `4cc401342f1d0253e0c288e2288f6fdd1414e484`. All nine jobs in the implementation CI run passed. The delivery head adds stronger recipient assertions and these reports; its live CI results are attached to PR #14. Production source is unchanged from the verified implementation and signed runtime artifact.

Base: PR #13, `2c188f6c5408a3b3a0bedf4c1ebe6ac024f36412`, verified against GitHub on 2026-10-27.
Branch: `codex/2.1.0-integrity-followups`; stacked PR target: `codex/2.1.0-core-acceptance`.
PR #13, PR #12 and the shared integration branch remain unchanged.

## Deskew

Confirmed source defects: `PDDocument.save(File)` attempts to reopen a scratch capability pathname; the host catches every exception and returns zero; per-page rendering failures also become zero-angle observations. Zero must mean a completed export requiring no corrections. Failures and coroutine cancellation must propagate.

Correction commit: `983f67d8e0978b4cfd4bd6b80c7cf93b213831c5`. Bounded capability output replaces path reopening; host and per-page errors propagate. Blank-page projection ties retain zero instead of choosing -15 degrees. The correction uses the renderer/PDF Y-axis sign conversion and the crop-box center without changing page boxes. The screen clears stale success state, marks publication only after engine completion and distinguishes zero corrections with the existing no-tilt text.

Regression class: `PdfDeskewIntegrityTest`, exercising actual host/isolated-worker exports, parsed output, rendering, and original/destination byte preservation. Original source: **6 tests, 6 expected failures**. Fixed source: **7 tests, 7 passed, 0 failures/errors/skips**, owned offline API 36 emulator. Successful tilted output parses, remains 300 x 200 points and renders with less than 0.5 degrees detected skew. A blank page produces a readable one-page output and returns zero. Malformed input, unavailable output, binding failure, rendering quota failure and pre-call cancellation propagate; tested source and old destination byte arrays remain equal. Security audit, architecture gate, debug/native compilation and diff check pass.

Raw receipts are in the shared validation directory, `core26-evidence/integrity27-deskew-baseline` and `core26-evidence/integrity27-deskew-fixed`. They bind source file hashes and archived debug/test APK hashes. These native runs precede the fix commit and bind the base HEAD plus the exact dirty source files; they are not evidence of minified release execution. The prior signed APK was restored with SHA-256 verification after each run.

Stacked PR: https://github.com/kiss2oblivion/pdfchemy-android/pull/14. The implementation matrix passed 350 tests per API on API 24/30/34/36, with zero failures/errors/skips.

## Deskew publication boundary

A further real Android reproduction on `43df902e3f431d73d9890dffaf072fcd35fbaf49` cancelled publication as a SAF-style provider opened an existing destination. Its prior **20 bytes became zero bytes**. Pre-publication validation alone cannot roll back an arbitrary provider's truncating open.

Commit `4cc401342f1d0253e0c288e2288f6fdd1414e484` restricts deskew to host-created fresh copies. Existing/unknown destinations fail explicitly before staging, worker dispatch or destination opening. The screen chooses a writable document-tree folder and uses `OutputPolicy.createCopy` to create a separate, uniquely named file; failure/cancellation cleans only that fresh copy. Publication is marked only after engine completion. UI export runs explicitly on Main. No generic host transaction, worker isolation or collector quota was weakened.

Final focused Android result: **12/12 passed** (9 engine cases, 1 existing-provider preservation case, 2 actual Compose/SAF publication cases). This includes successful and zero-correction exports, malformed input, unavailable output, worker binding/render failures, pre-call and in-flight cancellation, existing file/provider rejection, and failure cleanup without a success state. The first expanded run had 10 passes and 2 screen failures from an off-Main toast under Compose continuation interception; those results are retained. Pinning the export coroutine to Main resolved both. The fixed run is `core26-evidence/integrity27-publication-main-fixed`.

The actual dependency bytecode was also inspected: PDFBox Android 2.0.27.0 `PDDocument.save(File)` constructs `FileOutputStream(File)` before calling `save(OutputStream)`. Evidence: shared validation `integrity27-pdfbox-bytecode`.

## Share-copy retention

The previous five-file pruning policy does not distinguish active writers or receiving applications holding URI grants. Android does not expose a complete public enumeration of other applications' temporary FileProvider grants. Automatic eviction cannot safely infer that a completed copy is unused.

Correction commit: `cd5335a4eaf67ead5d429c69d5ed81d50ba73dd8`. New copies live under the narrowly scoped FileProvider root `files/shared_copies`, outside cache eviction. A synchronized reservation accounts for active copies, completed copies and legacy `cache/exports/shared_*` files: maximum 64 retained copies, 250 MiB aggregate, 100 MiB per input. At capacity allocation fails explicitly while retaining existing copies. There is deliberately no automatic expiry/revocation of completed shares: explicit owner/app-data removal is needed to reclaim those copies. This conservative policy trades future allocation for preserving unobservable receiver lifetimes.

Copies use unique temporary files, an 8 KiB buffer, exact bounded length checks and rename only after completion. Active partials are protected from cleanup; abandoned partials are reclaimed on a subsequent allocation. Sync and async paths share cleanup and source leasing. The blocking compatibility API supports `CancellationSignal` and thread interruption; it remains blocking, so coroutine callers should use the async API. A transfer owner also reclaims a completed result discarded before caller resumption. No private staged root is exposed.

Original API 36: **4 tests, 2 expected failures, 2 passes**. The failures are an older granted URI reopening with `ENOENT` and a later copy unlinking an active writer. Fixed API 36: **13/13 pass**, including 10 integrity tests plus 3 existing Reader share tests. A real foreign test-APK UID is denied without a grant, holds two simultaneous consumer streams, reopens both after later shares/cleanup, and loses access only when the test explicitly revokes its grant. Count/byte quota refusal preserves retained files; async failure/cancellation and synchronous failure/cancellation remove partial files and preserve source bytes. **5/5 focused JVM tests pass**, including prompt cancellation before caller resumption. Architecture/security gates and native/debug compilation pass.

Receipts: shared validation `core26-evidence/integrity27-share-baseline` and `core26-evidence/integrity27-share-fixed`; build log `integrity27-share-fixed-build.log`. Native APK hashes and original signed-APK restoration are recorded. The complete implementation matrix passed on all four APIs. The expanded recipient test additionally passed on API 36 with the test-APK foreign UID and the separate Android shell UID 2000 both reading their granted copies while consumer streams overlapped. Both reopen after subsequent sharing and cleanup. The first expansion attempt failed because UiAutomation passed literal quote characters in a URI; direct tokenization fixed the harness, with byte assertions retained. The corrected 13-test receipt is `core26-evidence/integrity27-two-recipients-quote-fixed`. Those extra assertions are included in the final delivery-head CI run.

## Bitmap ownership

Original Gemini run: https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40390296693 at `8d7c24cfb61c363389a1b2f94775b9101d70b18d`.
The JVM job reports `IllegalArgumentException` at `EditorBitmapOwnershipRegressionTest.kt:68`, the `requireNotNull` around polling for a queued continuation. This is not a bitmap recycling assertion. `PdfEditor.kt`, `ResourceTransferOwner.kt` and the original regression test are byte-identical between the two PR heads.

Correction commit: `43df902e3f431d73d9890dffaf072fcd35fbaf49`. Production bitmap ownership code is unchanged. Two hundred ordinary local repetitions passed, so that result alone did not explain the CI failure. A controlled baseline dispatcher then ran the producer on the actual IO dispatcher and waited for completion before returning from dispatch. The original queue assumption failed deterministically with: `No caller resumption queued; completed=true, delivered=true, leftRecycled=false, rightRecycled=false`. Both live bitmaps had correctly transferred to the caller; there was no pending continuation to cancel. The negative probe and failed XML are archived in shared validation `integrity27-bitmap-forced-baseline`; the earlier 200-request run is in `integrity27-bitmap-baseline-200`.

The retained cancellation test now blocks the second producer until `async` has returned and the caller has necessarily suspended. It then permits producer completion, cancels before the queued continuation runs, and retains both deterministic recycling assertions. Its 10-second watchdogs are unchanged; no sleeps were introduced. A separate immediate-delivery regression uses the controlled dispatcher to assert successful live ownership transfer without a queued continuation. All seven original scenarios remain. The focused fixed run passes **8 ownership tests (including 200 controlled cancellations), 1 immediate-delivery test and 5 share JVM tests**, 14 total, with no failures/errors/skips. The full local JVM suite and the Linux CI JVM job also passed, giving at least 600 controlled cancellation repetitions across the focused, full local and implementation CI runs. Final-head CI repeats the suite.

## Full verification

Implementation CI: [all nine jobs passed](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40533116957), exact implementation SHA above. Downloaded native artifact ZIPs were checked against GitHub SHA-256 digests and their workflow head SHA. Test identities, starts/completions, counts and absence of failures/errors/skips were independently checked.

| Gate | Actual result |
| --- | --- |
| Full local JVM: app-host | 312 passed, 0 failed/errors/skips |
| Full local JVM: pdf-ipc | 71 passed, 0 failed/errors/skips |
| Full local JVM: pdf-jail | 106 passed, 0 failed/errors/skips |
| Full JVM total | 489 passed; Linux CI full JVM job also passed |
| Native API 24 x86 | 350 passed, 0 failed/errors/skips |
| Native API 30 x86_64 | 350 passed, 0 failed/errors/skips |
| Native API 34 x86_64 | 350 passed, 0 failed/errors/skips |
| Native API 36 x86_64 | 350 passed, 0 failed/errors/skips |
| Native total | 1,400 actual executions |
| Checker unit tests | 20 passed: secrets 10, instrumentation 5, Gson gate 5 |
| Security audit / architecture | Passed locally and in CI |
| Secrets CI | TruffleHog 3.97.9: 6 exact reviewed historical matches, 0 rejected |
| Production dependency scan | 234 resolved dependencies, 0 affected packages |
| Local release lint | 0 errors, 642 warnings |
| Release build and artifact hygiene | Unsigned minified build passed; signed artifact checks also passed |
| Whitespace / scope | `git diff --check` passed; prohibited files unchanged |

Each native matrix leg includes all 22 new integrity cases, the prior 22 core acceptance cases and 16 raw-byte/decoded-stream redaction privacy cases. Existing cross-UID admission, form aliases, geometry (including crop offsets/rotation/large pages), worker pipe allocation/limits/cancellation, renderer readback, Reader-Editor-Reader lifecycle and PDF-EPUB round-trip methods were explicitly checked. No tests were disabled or skipped. The bitmap correction is a JVM scheduling-test correction; no production bitmap ownership code changed.

Raw implementation evidence: shared validation `integrity27-ci-first/` (`download-receipt.json`, `native-receipt.json`, `integrity-receipt.json`), `core26-evidence/integrity27-full-unit/`, `integrity27-full-validation.log`, `integrity27-dependencies.log` and `integrity27-release-gate.log`. Final delivery-head checks are linked from [PR #14](https://github.com/kiss2oblivion/pdfchemy-android/pull/14).

## Signed minified runtime verification

The actual release APK was built from the implementation SHA, signed with the existing F7 certificate, verified with APK Signature Scheme v2 and 16 KiB alignment, and confirmed non-debuggable/non-testOnly. No signing key changed. Public UI navigation on the owned offline API 36 emulator exercised Organize -> Deskew -> Vanguard PDF picker -> the real Android document-tree picker -> a fresh numbered copy.

Independent pypdf and PyMuPDF inspection of the actual exported PDF confirmed two 300 x 200-point pages with original boxes/rotation. The tilted page changed from -5.25 degrees detected skew to 0.0 degrees. The second page remained white and received no false deskew transform. Rendered input, corrected output and blank output were visually inspected. The original source and a same-name existing PDF retained their SHA-256 hashes; the export was saved as `..._straightened (2).pdf`. The crash buffer was empty. The prior signed APK was restored and hash-verified after this run and every instrumentation run.

| Artifact | SHA-256 |
| --- | --- |
| Unsigned minified APK, implementation SHA | `74794af296bc4987c4b110bad9e1267f558a8cf8ad07e67be17758cb85e34a8c` |
| Signed minified APK, implementation SHA | `a1de10233dc92276cc363979c5403341d8bd8b1ee423b327162339d8f1231139` |
| R8 mapping, signed and unsigned builds | `5ee0b441c35b0909205e056bcdb851baf03a770c048b4869ec0e2ab1695beea1` |
| Actual signed deskew export | `e63b41200283954f795d6c09df1cc1f13e4ca8d2bcf5968c18f62f5c9013ed50` |
| Original source, unchanged | `3c253378d43f573b2d9c9cfa5a2d4d320fbcb1b6313b02713e0f3757a9d3fa9c` |
| Same-name existing PDF, unchanged | `b22c597fc1460b51f1f562dd952fb331150f4d74410c7d5020a15754a9810d83` |

Archives: shared validation `integrity27-unsigned-4cc40134/`, `integrity27-signed-4cc40134/` and `integrity27-signed-deskew-a/`. Signed runtime coverage is this public deskew export; foreign-recipient sharing was exercised through the actual debug native harness. No signed AAB was generated.

## Exact changed files

- `CODEX_INTEGRITY_ACCEPTANCE.md`
- `app-host/src/androidTest/AndroidManifest.xml`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/DeskewPublicationIntegrityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/PdfDeskewIntegrityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/ShareCopyIntegrityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/BatchOutputProvider.java`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/OutputCommitProvider.java`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/ReaderShareSecurityTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/security/ShareRecipientProvider.java`
- `app-host/src/androidTest/java/com/pdfchemy/app/ui/DeskewCopyPublicationTest.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/DocumentActions.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/PdfDeskewEngine.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/DeskewScreen.kt`
- `app-host/src/main/res/xml/file_paths.xml`
- `app-host/src/test/java/com/pdfchemy/app/logic/EditorBitmapImmediateDeliveryTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/EditorBitmapOwnershipRegressionTest.kt`
- `app-host/src/test/java/com/pdfchemy/app/logic/ReaderShareAsyncTest.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfDeskewEngineWorker.kt`
- `reports/INTEGRITY_NEXT_CYCLE.md`

## Outstanding limitations and owner gates

- Deskew now requires a writable document-tree folder and always creates a fresh copy. Existing/unknown targets and providers offering only single-document output are not accepted by this engine.
- Completed share copies have no automatic expiry because receiver grants cannot be exhaustively observed. New allocation fails at the budget; reclaiming completed copies requires explicit owner removal. Legacy cached copies remain subject to OS cache eviction. Failed/abandoned private copies are cleaned automatically.
- The synchronous share compatibility API remains blocking and cooperatively checks cancellation/interruption between bounded reads. Coroutine callers should use the async API.
- Generic SAF rollback behavior for other engines was not changed. The proven publication-cancellation issue requires separate review for callers that still accept existing/unknown destinations.
- Physical-device, Play, upload-key registration and integration/release gates remain owner-controlled and pending. Signed emulator evidence does not close them. Gemini's PR #12 and the shared integration branch were not modified; no main merge, release tag or deployment occurred.

Read-only next-cycle recommendations and evidence for Find & Replace rasterization and Repair recovery fidelity are in [INTEGRITY_NEXT_CYCLE.md](reports/INTEGRITY_NEXT_CYCLE.md). Neither engine was rewritten.
