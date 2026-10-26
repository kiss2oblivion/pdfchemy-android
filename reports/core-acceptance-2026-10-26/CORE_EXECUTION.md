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

## CORE-02 and CORE-03

Pending in separate subsequent batches. PDF/A preliminary checks must not become a certification claim: [veraPDF's primary validation documentation](https://docs.verapdf.org/cli/validation/) describes validation profiles for the applicable PDF/A parts and conformance levels. Metadata tagging alone will not be retained as a conversion implementation.

## Open gates

No main merge, deployment, Play action, release tag or signing-key change is authorized or performed. Physical-device and printer checks remain pending by owner instruction. Billing, UMP, ads, Premium UI, translations and independent Gemini follow-ups remain outside core ownership. Final full-matrix CI, dependency/secrets checks, release lint/artifact hygiene, combined-source integration and owner-controlled signed/Play/physical gates remain open at this checkpoint. Debug passes alone do not establish deployment readiness.
