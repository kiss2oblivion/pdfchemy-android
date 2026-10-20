# SC-04 completed Warning outputs retain publication

Release: **HOLD**. Implementation and required local unit/native/debug/static/lint/unsigned artifact checks pass. Source commit/push/ref and complete same-source CI remain open.

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
| Focused existing ownership unit suite | Four pass, zero failures/errors/skips |
| Debug/test assembly and security audit | PASS; 135 host source files scanned |
| Fixed API24 native selection | Five pass; strict complete-result validation; empty crash buffer |
| Fixed API36 native selection | Five pass; strict complete-result validation; empty crash buffer |
| Release lint/minified unsigned assembly | PASS in 2m 26s with explicit unsigned-verification flag |
| Artifact/static corpus gate | PASS: 14 fixture hashes, 62 static matrix rows, IDs/version/SDK/manifests and artifact hygiene; no claim of 62 executed UI flows |
| Complete same-source CI | Not run yet |

Production signing, physical-device acceptance and Play checks remain separate release gates. Later source findings have not started in this batch.

The first local release command omitted the existing `-PunsignedReleaseVerification=true` option. Lint and minification passed, but packaging correctly rejected the unconfigured production signing `storeFile`. That attempt is retained as `release-signed-attempt.log`; it is not counted as a passing release build. The unchanged source passes with the explicit unsigned-verification flag used by CI. No signing configuration is weakened or production signing claimed.

`sc04/local-verification.json` fingerprints the source and retained baseline/fixed debug/test/unsigned-release APKs. The retained fixed APKs match current build outputs. All 24 raw logs/XML are byte-preserved with a manifest verified against staged Git blobs before commit. Both owned emulators were stopped after the native checks.
