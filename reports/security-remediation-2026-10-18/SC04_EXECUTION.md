# SC-04 completed Warning outputs retain publication

Release: **HOLD**. SC-04 implementation, local checks, Android source push/ref and complete same-source CI pass. Later findings and external release acceptance gates remain open.

Application/test source is committed at `7f7fe75766e4ec55461b140faccde101eebb5aa2`, pushed only to the Android remote and matched against its branch ref. [Full CI run 39400342318](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39400342318) targets this exact source. The completed workflow verifies this exact application/test source; the subsequent closeout changes documentation/evidence only.

Baseline: SC-03 application/test source `7390523c61b3eedbf0be91bc9f0696811b0c4626`, closed by documentation/evidence commit `c5565c0cae44c79494e0350a2af56bcaa9062704`. The baseline APK is the retained, hash-verified SC-03 local debug APK. Original checkout work is preserved.

## Reproduction

Five new native methods use the actual `MainViewModel.compressPdf`, compressor, isolated PDF worker and the existing test-only SAF provider with real file descriptors. A generated one-page PDF and a ten-byte target produce a real successful `targetMissed` result. The saved output is read back and parsed before lifecycle cleanup. The tests invoke the same `resetState()` callback used by the Warning dialog; picker/dialog UI automation is not claimed.

On the unchanged baseline API24 app, three methods fail because the completed PDF is deleted after cancellation, ViewModel destruction, or Warning dismissal followed by a failed operation reusing the URI. Both control methods pass: a failed compression removes a proven fresh incomplete destination and preserves an existing destination's bytes. The failed native run and original APK identities are retained separately from the fixed run.

Cleanup is observed through coroutine completion, rather than a delay: cancellation joins newly created cleanup children, ViewModel destruction joins the existing `lastCleanupJob`, and failed reuse joins the finite operation/cleanup children after Error becomes observable. Successful outputs are compared byte for byte after cleanup. A ViewModelStore drives real `onCleared`; reflection only observes existing job fields and does not replace the writer or state transitions.

## Change

`compressPdf` now calls `markOutputPublished(destUri)` immediately after the compressor returns success, before file-size inspection, result formatting or the Warning/Success presentation branch. The compressor only returns success after closing the final destination stream. The existing publication helper updates both the operation's terminal state and the global fresh-copy registry. Failed results continue through the existing provenance-aware cleanup path.

## Verification

| Gate | Current result |
|---|---|
| Negative API24 native run | Expected three Warning-output deletion failures; both failure controls pass |
| Focused existing ownership unit suite | Four pass in 11.953s, zero failures/errors/skips |
| Debug/test assembly and security audit | PASS in 2m 21s; 135 host source files scanned |
| Fixed API24 native selection | Five pass in 1.955s; strict complete-result validation; empty crash buffer |
| Fixed API36 native selection | Five pass in 13.068s; strict complete-result validation; empty crash buffer |
| Release lint/minified unsigned assembly | PASS in 2m 26s with explicit unsigned-verification flag |
| Artifact/static corpus gate | PASS: 14 fixture hashes, 62 static matrix rows, IDs/version/SDK/manifests and artifact hygiene; no claim of 62 executed UI flows |
| Complete same-source CI | PASS: all nine jobs at `7f7fe757`; 236 native methods per API24/30/34/36, 944 executions |

Production signing, physical-device acceptance and Play checks remain separate release gates. Later source findings have not started in this batch.

The first local release command omitted the existing `-PunsignedReleaseVerification=true` option. Lint and minification passed, but packaging correctly rejected the unconfigured production signing `storeFile`. That attempt is retained as `release-signed-attempt.log`; it is not counted as a passing release build. The unchanged source passes with the explicit unsigned-verification flag used by CI. No signing configuration is weakened or production signing claimed.

`sc04/local-verification.json` fingerprints the source and retained baseline/fixed debug/test/unsigned-release APKs. The retained fixed APKs match current build outputs. The local checkpoint retains 24 raw logs/XML byte-preserved with a manifest verified against staged Git blobs before commit. Both owned emulators were stopped after the native checks.

## Retained CI failure

The first full run completes 236 API34 methods with one failure in the unchanged `StartupWindowRegressionTest.backgroundAndResumeRetainsContent`: `ActivityScenario` times out requesting STARTED, despite its message reporting the last lifecycle transition as STARTED. The other 235 methods, including all five new Warning methods, pass. Its root cause is not established; no infrastructure diagnosis is claimed. Raw job `118554850267`, original XML, failure summary and complete attempt-1 status are retained separately. The dependent build job `118566242722` is skipped. API24/30/36 passing original runs are not repeated or double-counted. One unchanged failed-job retry is requested after the original workflow finishes. No application, test or workflow changes are made for that retry.

The failed background/resume test's device logcat and the passing repeated-launch/recreation method's logcat are also retained byte for byte for FOLLOW-09. They preserve the lifecycle event sequence and comparison evidence without attributing the timeout to a specific subsystem.

## Complete same-source CI

[Run 39400342318](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39400342318) passes all nine jobs at `7f7fe75766e4ec55461b140faccde101eebb5aa2`. Downloaded native results independently validate **236 unique completed methods per API24/30/34/36, 944 executions total**, zero failures/errors/skips. All five Warning regressions, two batch-publication regressions, eight Search and eight Editor regressions are present per API. API24 passes strict result validation and has an empty crash buffer.

Architecture, secret scanning, dependencies and the complete JVM suite pass; the dependent release job passes lint, debug/minified unsigned assembly and artifact/static corpus checks. The following closeout commit changes documentation/evidence only; it does not change the tested application source.

API34 passes on the single unchanged failed-job retry. Its original startup lifecycle failure remains retained above and tracked under FOLLOW-09; successful retry is not root-cause attribution. The other three passing API results are carried forward once, not double-counted.

The final attempt assigns copied job-metadata IDs to previously successful jobs. `ci-final-status.json` retains those IDs; the table below identifies the original executions behind their passing results. Only API34 and the previously skipped build execute in the retry.

| Job | Actual passing execution ID |
|---|---|
| architecture | `118553184561` |
| android-instrumented-security (34, x86_64) | `118566936397` |
| dependency-security | `118553184722` |
| secrets | `118553184797` |
| android-instrumented-security (24, x86) | `118554850233` |
| android-instrumented-security (36, x86_64) | `118554850315` |
| tests | `118554850153` |
| android-instrumented-security (30, x86_64) | `118554850211` |
| build | `118578224666` |
