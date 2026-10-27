# PDFchemy Android 2.1.0 core acceptance

Baseline: `9ad4fdc0ec5c86d7af3f8b0a30f80603d5a93e36` on `integration/2.1.0-2026-10-26`. Work branch: `codex/2.1.0-core-acceptance`. Release disposition: HOLD. Final verification is pending until the final receipts below are recorded.

## Stage 0

Fetched Android remote refs and confirmed storage `06004a94322bb3f99e85adb2c55d386771f8083b` and Play `2c3a4c213dff0e6a8a89118745ad5bbd3830e233`. The shared baseline is an actual --no-ff merge with those two parents in that order, tree `dd34a578aa35bc4a0f34b85f1fb523c74881e370`. No conflicts or unexpected diffs occurred. The integration branch was pushed and its exact remote SHA verified. Existing PRs and main were unchanged.

Baseline diff, architecture/security gates, compilation and native APK assembly pass. Both existing worker linearization unit tests pass. Actual native PdfLinearizeEngineTest completes two methods on the owned offline API36 emulator: check passes; optimize fails with `Jail 400: /proc/self/fd/87: open failed: EACCES (Permission denied)`. This is retained as an expected failing baseline, not a passing test run.

Evidence outside Git in the shared validation directory: `core-stage0-baseline-build.log`, `core26-evidence/stage0-linearize-baseline/receipt.json`, raw instrumentation output, APK digests and restoration receipt. The shared `CORE_BASELINE_HANDOFF_2026-10-26.md` records the exact SHA, separate worktrees and ownership; delivery is by shared file, with direct Gemini receipt unconfirmed.

## CORE-01: Fast Web View permissions and correctness

The worker reopened `/proc/self/fd/N` via File.inputStream instead of using the capability adapter. Its separate marker test could mistake metadata or an unrelated catalog key for real linearization. The local PDFBox save path does not supply an independently validated linearization backend.

Generation now throws UnsupportedOperationException before accessing either descriptor or creating scratch. The public facade returns that explicit failure before any Gateway, history or publication call. Host response validation also rejects purported optimization success, and rejects affirmative or ambiguous inspection claims. Read-only inspection uses CapabilityIo, parses only in the isolated worker and reports NOT_VERIFIED with its page count. Zero-page inspection is rejected. The screen disables generation and removes its save-file launcher and success path; core feature descriptions describe the available information accurately. Pipe collectors and HostOutputTransaction semantics are unchanged.

This deliberately provides no claim of genuine generation or validation. Structural linearization checking requires more than a marker: [qpdf's primary implementation documentation](https://github.com/qpdf/qpdf/blob/main/manual/linearization.rst) describes validation of parameters, hints and object ordering. No new backend was added in this hotfix.

Focused verification before the source commit:
- IPC LinearizationClaimSecurityTest: 4 pass.
- Worker PdfLinearizeValidationTest: 5 pass.
- Native PdfLinearizeEngineTest plus CoreLinearizeHonestyTest: 10 pass, zero failures/errors/skips; crash buffer empty.
- Security audit, architecture, debug application and instrumentation compilation: pass.
- Release lint: initial run failed with seven MissingTranslation errors from newly introduced IDs (646 warnings). The exact failure report is retained; no baseline expansion or suppression was used. The correction reuses existing localized core IDs and moves only their revised English definitions into core_linearize.xml. Revised gates, nine unit tests, native assembly and release lint PASS in core01-resource-fixed-validation.log. All ten native methods PASS again against the rebuilt artifact, with zero failures/errors/skips and empty crash buffer.
- Diff whitespace: pass.

Adversarial cases include real non-linearized serialized PDFs, metadata markers, a forged catalog key, forged worker success/affirmative claims, malformed/missing/zero-page inputs, an existing target, an unopened SAF fixture provider, and cancellation propagation. Both source bytes and existing target bytes are preserved. Unsupported generation creates no output, and direct-worker failure does not open the provider. The UI test verifies the unavailable control is disabled and explains that no output is created.

The first fixed native APK SHA-256 is `69a421d4fe8ca39dd57a4b132aa2c73a74eb7e13cf740d0ff11f672e4f68fae5`; test APK is `b968c68e6b01b65d675f42b8de7e154a09ca66289ea91fb121df1e0ed8a7578f`. This is the checkpoint before resource-ID correction. These are debug diagnostic artifacts, signed with the existing configured certificate, not a minified release validation. The source was uncommitted on the shared baseline at this run; the complete file hashes in `core26-evidence/core01-fixed/receipt.json` bind the actual tested sources. The original signed release APK `1040f53d445cc70072828769a159f86520cb6a3b1e884f730877e41da3f4e219` was restored and its installed digest verified afterward.

CORE-01 changed files, excluding this execution report:
- `FEATURES_REGISTRY.md`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/PdfLinearizeEngineTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/ui/CoreLinearizeHonestyTest.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/PdfLinearizeEngine.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/LinearizePdfScreen.kt`
- `app-host/src/main/res/values/core_linearize.xml`
- `app-host/src/main/res/values/strings.xml` (core English messages only)
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/WorkerResponseValidator.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/logic/WorkerResponseContracts.kt`
- `pdf-ipc/src/test/java/com/pdfchemy/app/jail/LinearizationClaimSecurityTest.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfLinearizeEngineWorker.kt`
- `pdf-jail/src/test/java/com/pdfchemy/app/jail/engines/PdfLinearizeValidationTest.kt`

Final CORE-01 debug APK SHA-256: `3fd8b8835bc47c3a17bc5e019d87a781f15d92a1bfce6c9bd0eec8b93537e395`; test APK `3e9a1f84dbce1f7cc06bb5bc900d34b078981cce0d933dd2dd0efd2bd51f4d89`. Actual final-source file hashes, all ten method identities and signed-APK restoration are in `core26-evidence/core01-resource-fixed/`. These supersede the earlier resource-ID checkpoint above. Locale text updates are assigned to Gemini using the existing resource keys; no translated XML was edited here.

CORE-01 source commit: `8fc81091069ed4a3e096846f53b5ac2f19130153`, pushed and remote verified. [CI run 40390220523](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40390220523) completed all nine jobs successfully on that exact SHA, including API24/30/34/36. Native artifacts have been downloaded and their ZIP digests verified; detailed method/count reconciliation is recorded separately. Draft PR: https://github.com/kiss2oblivion/pdfchemy-android/pull/13 against the shared integration branch.

## CORE-02: preliminary PDF/A checks and unsupported conversion

The old worker inferred a PDF/A version from untrusted metadata and its conversion path rewrote metadata without a conformant conversion backend. [veraPDF's primary validation documentation](https://docs.verapdf.org/cli/validation/) describes validation profiles for the applicable PDF/A parts and conformance levels. This hotfix introduces no authoritative validator or converter.

Inspection now reports `assessmentScope=PRELIMINARY_PDF_A_CHECKS`, `certificationStatus=NOT_CERTIFIED`, `isCompliant=false` and an unknown version even when all preliminary checks pass. The five checks describe their narrow scope: encryption, profile-stream presence, metadata-stream presence, direct page-font embedding and catalog action entries. They do not validate ICC contents, XMP schemas, nested font resources or full ISO 19005 requirements. Legacy score fields remain compatibility data; the screen displays a count, no compliance percentage or archival-ready badge. Host validation rejects affirmative, ambiguous and forged success responses. Worker and facade conversion throw UnsupportedOperationException before descriptor access, scratch allocation, history or publication. Existing targets and originals are preserved.

Baseline probes on CORE-01 source: the first three-method run completed with one failure rather than the collector's expected two, so it is retained as a collector failure, not a passing receipt. Conversion returned success and changed the old target. Spoofed XMP produced a false PDF/A-1 version classification but `isCompliant` was already false for that fixture; no affirmative-compliance reproduction is claimed. The strengthened version-classification assertion then reproduced two failures and one pass with an EXPECTED_BASELINE_FAILURE receipt in `core26-evidence/core02-baseline-strengthened/`.

Focused fixed verification:
- IPC ArchiveClaimSecurityTest: 6 pass; preserved linearization contract tests: 4 pass.
- Worker PdfArchivePreliminaryChecksTest: 7 pass; preserved linearization tests: 5 pass. Total focused JVM: 22 pass, zero failures/errors/skips; XML snapshot `core26-evidence/core02-unit/`.
- Actual native PdfArchiveValidatorEngineTest (10), CorePdfAHonestyTest (1) and both CORE-01 classes (10): 21 pass, zero failures/errors/skips; empty crash buffer. `core26-evidence/core02-fixed/receipt.json` records all method identities and complete tested source-file hashes. HEAD was CORE-01 with the CORE-02 source changes present; this is not represented as a clean committed-source receipt.
- Security audit, architecture, compilation and release lint pass. Release lint has zero errors and 642 warnings. Initial compilation failed because PDOutputIntent.cosObject is COSBase; corrected with a checked COSDictionary cast, then the full focused command passed. Logs: `core02-focused-validation.log` (failure), `core02-focused-fixed-validation.log` (pass).
- Adversarial cases include missing ICC, nonembedded font, spoofed XMP/pdfaid, a fake profile stream, catalog JavaScript/action, invalid bytes, zero pages, forged worker certification/conversion, existing and absent destinations, cancellation and an unopened SAF fixture provider. UI checks exercise the actual worker report.

Fixed debug application SHA-256: `a54433b8b04d9ad93c8a9438e873bd1b389eb1ab19f16b25ae881af7ad71b3c5`; test APK: `2cf21ae88b0f386ac92eb084e8e1c7fd43880e341d3f878a08edccc4bffa10d3`. These are diagnostic, non-minified artifacts signed with the existing configured certificate. The original signed APK was restored and its installed digest verified. No signed minified CORE-02 artifact has yet been tested.

CORE-02 changed files, excluding this report:
- `FEATURES_REGISTRY.md`
- `app-host/src/androidTest/java/com/pdfchemy/app/logic/PdfArchiveValidatorEngineTest.kt`
- `app-host/src/androidTest/java/com/pdfchemy/app/ui/CorePdfAHonestyTest.kt`
- `app-host/src/main/java/com/pdfchemy/app/logic/PdfArchiveValidatorEngine.kt`
- `app-host/src/main/java/com/pdfchemy/app/ui/PdfAValidatorScreen.kt`
- `app-host/src/main/res/values/core_pdfa.xml`
- `app-host/src/main/res/values/strings.xml` (core English only)
- `pdf-ipc/src/main/java/com/pdfchemy/app/jail/WorkerResponseValidator.kt`
- `pdf-ipc/src/main/java/com/pdfchemy/app/logic/WorkerResponseContracts.kt`
- `pdf-ipc/src/test/java/com/pdfchemy/app/jail/ArchiveClaimSecurityTest.kt`
- `pdf-jail/src/main/java/com/pdfchemy/app/jail/engines/PdfArchiveValidatorEngineWorker.kt`
- `pdf-jail/src/test/java/com/pdfchemy/app/jail/engines/PdfArchivePreliminaryChecksTest.kt`

## CORE-03 and shared integration

Implementation SHA: `9de343e212fb63d411ca48baee5f029da7998f66` (CORE-02), pushed and remote verified. The following checks use this source; subsequent report-only changes do not alter tested production or test files. Final remote/documentation SHA and completed CI collection are recorded in the shared validation directory's final CORE_EXECUTION.md/receipt and PR13 validation notes.

Full local verification passed in `core03-full-validation.log` (7m52): securityAudit, securityArchitecture, dependencyInventory, all three module JVM suites, lintRelease and assembleRelease with `-PunsignedReleaseVerification=true`. Actual XML counts: app-host 309 across 57 suites; pdf-ipc 71 across nine; pdf-jail 106 across 13. Total 486, zero failures/errors/skips. Snapshot: `core26-evidence/core03-full-unit/`.

Checker verification: secret-checker unit tests 10 pass; instrumentation-result checker tests five pass; release Gson-contract checker tests five pass. TruffleHog 3.97.9 reports six exact reviewed historical matches, zero rejected findings. The live OSV check scans 234 resolved production dependencies, zero affected packages. Release artifact hygiene passes, including minification/runtime Gson contracts, isolated non-exported workers, restricted component/permission checks and no instrumentation/secret assets. Unsigned minified APK SHA-256: `f3cc7dc325988434e247a1be148be1d613a55e71b6664c8e1003527f6fcd1c5e`.

Actual critical native matrix: 69 distinct methods cover original geometry, A4/Letter/A2/A3, offsets and rotations, opaque placement, raw-byte/decoded-stream/object-stream redaction privacy, form aliases, Reader-Editor-Reader with Vanguard, storage allocation/cancellation/cleanup, renderer, host transaction/deadline and PDF-EPUB round-trip. The first run completed 65 passes and four failures because FormValueAliasSecurityTest's default evidence directory already contained immutable input files. It remains FAILED in `core26-evidence/core03-critical-native/failure-receipt.json`. No original evidence was deleted or test assertion weakened. All four affected methods pass using the existing follow10EvidenceRun argument with a fresh per-run directory, on byte-identical app/test APKs, in `core26-evidence/core03-form-alias-fresh/`. The aggregate receipt covers all 69 methods with successful outcomes across the two attempts; it is not represented as a single green 69-method run. Both runs restored the original signed APK. All other native methods passed without retry.

Final-source full CI: https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/40473874688 on implementation SHA `9de343e212fb63d411ca48baee5f029da7998f66`. At this report checkpoint, architecture, secrets, dependency-security and full JVM jobs pass; four native API jobs remain in progress and the dependent build job remains pending. Complete results require digest-verified native artifact/method reconciliation; see the final shared receipt rather than infer completion from this checkpoint.

### Signed minified artifact and actual PDF exports

The existing signing configuration was reused without changing keys. Exact implementation SHA, clean source tree, signature verification, zip alignment, non-debuggable/non-testOnly release manifest, R8 mapping and artifact hygiene are bound in `core26-signed-9de343e2/`. Signed APK: `PDFchemy-2.1.0-code14-9de343e2.apk`, SHA-256 `1fc70dd8c92bf36038c8abb8e86f6318d163efbea0ef1424ea61295ffaa86864` (97,462,658 bytes). R8 mapping SHA-256: `e10b17a6a6fcb5f3b328fb57af797eae484cf55e45b250ecd68226ef5e6ae389`. No new signed AAB was generated.

Three actual Reader-to-Editor public-UI redaction exports and SAF publication pass on the owned offline API36 emulator without instrumentation injection:

| Fixture | Geometry | Output SHA-256 |
| --- | --- | --- |
| NoView private field-value alias | 300 x 200 points preserved | `ddd7a72d7609e30d4043bbef741d43d60bc64f9d7113e39e3f10eac6edd4f0eb` |
| NoView private field-name alias | 300 x 200 points preserved | `ede6f75f6f1d4b9b0b305058b6a97b01bbfe473d9dc3cee38297af2a2a0f8f18` |
| Nonzero media/crop offsets, rotation 90 | Media [-30,40,570,840], Crop [50,100,350,300], rotation 90 preserved; visible 200 x 300 points | `6ddb9585740e6a661ed523b2081851425ca7c76177f8e48c3ccef4f9a6848cd0` |

Independent pypdf and PyMuPDF reopen the actual input/output PDFs. Positive input controls locate the private marker; each output has zero marker occurrences in extracted text, raw bytes (UTF-8 and both UTF-16 encodings), all decoded streams and serialized objects including object-stream entries. AcroForm and the alternate private-payload alias are absent. Rendered selected-region dark fractions exceed 98%; input/output PNGs were visually inspected and overlays are opaque and aligned. Full receipts: `core26-signed-value/`, `core26-signed-name-normalized/`, `core26-signed-crop90/`. This is evidence for these fixtures, not a universal PDF privacy proof. The original signed APK `1040f53d445cc70072828769a159f86520cb6a3b1e884f730877e41da3f4e219` was restored and its installed hash verified.

An initial signing coordinator was stopped before installation to keep emulator ownership exclusive to the native suite. Its unsigned artifact/source binding and coordination note are retained. The resumed coordinator requires completed native and restoration receipts; no test pass was claimed for the interrupted coordinator. Release-variant instrumentation is not claimed: prior harness runtime-library limitations remain, and these signed checks use the real public UI instead.

### Separate Gemini integration gate

Gemini PR12 uses the exact shared baseline; its reviewed source head is `8d7c24cfb61c363389a1b2f94775b9101d70b18d`. The shared handoff file records three outstanding Gemini-owned items: active-share output pruning (R3), absent live Premium provider in production startup (R4), and translation updates for the revised core claims. DocumentActions is unchanged from the earlier reproduced R3 failure, and current startup source still supplies captured false flags without the provider. This review does not claim an ad was displayed to Premium. Prior runtime probes and exact evidence are in `followup-review-evidence/REMAINING_FIXES.md`; no new PR12 runtime reproduction is claimed here.

The baseline and core string meanings were delivered in the shared handoff file and an authorized comment on new PR12; subsequent coordination uses the shared file per owner instruction. Gemini-owned files and translated XML were not edited. Combined-source integration and its all-nine-job CI on the actual resulting SHA remain pending until those acceptance follow-ups are ready. The current signed artifact covers the core branch only.

## Open gates

No main merge, deployment, Play action, release tag or signing-key change is authorized or performed. Physical-device and printer checks remain pending by owner instruction. Billing, UMP, ads, Premium UI, translations and independent Gemini follow-ups remain outside core ownership. Exact-source final full CI collection, combined-source integration, signed verification of that eventual integrated artifact, and owner-controlled Play/physical gates remain open at this checkpoint. Release lint retains 642 warnings. Genuine Fast Web View generation, authoritative PDF/A certification and compliant conversion are unavailable by design; the UI and protocols must continue to say so. Debug passes alone do not establish deployment readiness.
