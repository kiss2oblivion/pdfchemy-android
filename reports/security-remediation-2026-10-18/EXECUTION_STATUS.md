# Security remediation execution — 2026-10-18

Release: **HOLD**. Implementation branch: `codex/security-remediation-2026-10-18`.

## Baseline preparation

- Local and remote implementation baseline match `670578355c4e408c2468ba1564b0e1a366faaa0c`.
- Android remote main: `4b3c157f3458f7df00ea94044258d33c7449ced9`.
- PR #2 remains open/draft on the prior implementation branch. Its baseline checks were all successful in run `38270216907`; this is historical baseline evidence, not verification of the new fixes.
- Review checkout, original implementation checkout and their untracked files are preserved. Production changes use a separate worktree.
- Baseline probes, synthetic leaking PDFs, XML and logs are archived under `reports/security-code-analysis-2026-10-18/`; `evidence/BASELINE_SHA256SUMS.txt` records their original review-relative identities. Probe source copies are under `baseline-probes/`.

## Batch status

| Finding | Implementation | Local verification | Android / CI | Commit / push |
|---|---|---|---|---|
| SC-01 | Implemented: fresh redacted-page resources; shared/inherited and nested pattern/mask resource pruning | 50 worker tests (including nine resource regressions), five host boundary tests, assembly and release lint pass | Final local API24 complete suite: 216 pass. Focused API36 passes; prior complete CI API30/34/36 216 tests each pass. Fresh complete CI at the installation repair is running | Source: `a1077e47` and `9d7dc22d`; CI repair: `9e03c062`, all pushed/remote-verified |
| SC-02 through SC-09 | Not started | Not run | Not run | None |
| Retained RA / external URI / SR-01 | Not started | Not run | Not run | None |

## SC-01 evidence and limits

The first focused JVM invocation failed because PDFBox font/glyph assets were unavailable in the worker module's test environment. Failure log/XML are retained in `sc01/`. Enabling Android resources for worker unit tests resolved that fixture problem; all three focused resource tests then passed.

The seven component regressions verify original Form/image removal, inherited/shared unused-resource removal while retaining unrelated vector text, nested Forms, resource-less Forms, implicit default color spaces and annotation appearance resources. Android tests invoke the actual Editor → gateway → isolated worker export, inspect saved decoded objects, retain unrelated text, and check opaque output pixels.

The actual baseline APK was assembled from the unchanged review worktree at `67057835`. All three new Android export tests failed at sensitive-data assertions on API24. Installing the fixed APK on the same isolated emulator changed those results to PASS; the existing eight-case mixed-annotation/rotation test also passed (four JUnit methods total, 22.884 seconds).

An earlier API36 iteration passed the same four Android test methods in 50.206 seconds. The final source, including additional resource-preservation cases, was rebuilt and rerun on API36: all four methods pass in 97.478 seconds. These are test durations, not device performance measurements. API30/34 and the complete same-source CI suites remain pending. No physical-device verification is claimed.

Final local build command: `:pdf-jail:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease`, offline, one Gradle worker, in-process Kotlin compilation. Result: BUILD SUCCESSFUL (4 minutes). Release lint reports 629 warnings and 38 unmatched baseline entries, not a warning-free result. Architecture/static host gates and five host boundary tests passed in the preceding broader verification. `sc01/artifact-and-source-hashes.json` identifies baseline/fixed APKs, test APK and changed production sources.

No later finding is advanced before the required SC-01 checks and commit/push/CI evidence. This receipt will be updated with actual outcomes rather than inferred completion.

## SC-01 nested-carrier extension

Pre-closeout review identified that a used tiling pattern or soft-mask group can hold its own unused original Form resource. Cleanup now prunes those resource scopes too, preserving resource-less mask inheritance. Two further production-helper regressions cover these carriers; all nine resource tests and the full 50-test worker suite pass. Build/Android-test assembly and release lint pass again (`sc01-resource-carriers.log`, 4m 9s). The extended APK passes the four Android methods on API36 in 39.983 seconds (`sc01-carriers-api36.log`). Earlier API24 evidence applies to the initial fix; the extended source's full API24/30/34/36 CI remains required.

The initial source commit is `a1077e47abd833a24018ec455aff3cbe6465d823`, pushed to the Android-only remediation branch with matching remote SHA. CI run `39124161582` covers that first commit; its architecture, secrets and dependency checks passed before the run was superseded by this carrier extension. It must not be counted as a complete pass for the extended source.

The carrier follow-up is `9d7dc22dd9ed38fb03ab50c52ac853f0e3fa72f9`, pushed with matching remote SHA. The final APK also passes all four Android methods on local API24 in 19.454 seconds; see `sc01/sc01-carriers-api24.log`, installation log and device fingerprint. Local API36 passes in 39.983 seconds. These focused native runs do not replace the complete CI suites.

## Current CI gate

[Run 39124748605](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39124748605), attempt 1, targets source commit `9d7dc22dd9ed38fb03ab50c52ac853f0e3fa72f9`. Architecture, history-secret, resolved-dependency and complete JVM test jobs pass. API24 job `117668349134` fails before test execution with `InstallException: Failed to install-write all apks`; its UTP report records zero tests. The full failed-job log is retained as `sc01/sc01-ci-api24-failure.log`. A more specific installation root cause has not been established.

API30/34/36 each complete all 216 native tests with zero failures/skips in attempt 1. API30 reports BUILD SUCCESSFUL in 19m 44s, API34 in 20m 39s and API36 in 21m 11s. These are complete job script durations, not performance benchmarks.

GitHub initially rejected an API24-only rerun while the workflow was still running (`job 117668349134 cannot be rerun`). After attempt 1 completed, `gh run rerun --failed` was accepted. Attempt 2 retains source SHA `9d7dc22d`; API24 job `117673053473` also fails during installation, with `ShellCommandUnresponsiveException`, `Failed to install-write all apks` and zero tests executed. The other successful results are carried forward, and the dependent build is skipped. Its failed log is retained as `sc01-ci-api24-attempt2.log`. No API removal, test filtering or assertion weakening is authorized as a substitute for this gate. SC-02 remains unstarted.

Detailed review of the first failed log also identifies `executeRemoteCommand write timed out after 240000ms` in ddmlib during installation. Why that transfer stalled is not yet established; extending nominal timeout properties did not prevent this observed 240-second limit.

## Additional local API24 full-suite diagnostic

An unfiltered full-suite run on the local Google Play API24 image, after successful non-streaming installation, reports `Process crashed` during method 5 (after four methods completed). The crash buffer identifies host process `com.pdfchemy.app`, `HTMLParserThrea`, SIGILL and `/system/app/Chrome/Chrome.apk`; its bundled WebView version is 51.0.2704.90. This is a failed additional run and does not supersede the earlier focused native passes.

Attribution remains open. An unchanged-baseline APK (`67057835`) passes an eight-method startup/text/redaction selection after application-data clearing. Its subsequent unfiltered diagnostic completes all 216 methods with exactly the three expected new SC-01 assertion failures in 747.544 seconds; no process crash is reported. Those failure logs remain negative vulnerability evidence.

The matched clean-state fixed APK completes all 216 methods in 740.582 seconds, zero failures/skips, and passes the new runner's complete-result validation. Its crash buffer is empty. Logs are `sc01-api24-full-fixed.log`, the two installation logs, `sc01-api24-full-fixed-runner.log` and the empty `sc01-api24-full-fixed-crash.log`. The isolated emulator was stopped after verification. These are observed test durations, not performance benchmarks. The original WebView SIGILL remains retained and unexplained under FOLLOW-05; a successful retry is not evidence that startup/consent code was repaired.

## API24 CI installation repair

After two failed installation attempts, the workflow now builds both native-test APKs before starting the emulator, reducing concurrent build/boot load. API24 uses `adb install --no-streaming` and the same unfiltered AndroidJUnitRunner suite via `scripts/run_android_instrumentation.py`. API30/34/36 retain the Gradle connected-test path. The complete API matrix remains required, and native logs/results are uploaded on success or failure.

The runner rejects unsuccessful installation, nonzero subprocess exits, timeouts, crashes, failed/skipped test status codes, zero tests, inconsistent counts, unmatched starts/completions and missing final instrumentation success. A successful adb shell exit alone cannot pass the gate. Five unit tests pass, including negative cases; the validator also accepts the actual four-test local success log and rejects the real full-run crash and baseline assertion-failure logs. Workflow YAML, unchanged API membership and the folded shell command validate locally. Folding is required because the pinned emulator action executes each script line separately.

Local end-to-end installation and the complete 216-test fixed suite pass through this runner. Full CI and the dependent release build are still required. [Android's command-line test documentation](https://developer.android.com/studio/test/command-line) describes direct instrumentation invocation; this workflow retains `-w`, the full runner and strict result checking.

The CI repair is commit `9e03c062f9cb797573430e1b5bd8d7b15f24b696`, pushed to the Android remote with matching SHA. [Run 39160660276](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/39160660276) covers that exact head. Its architecture, secret-history, dependency and complete JVM gates pass; API24/30/34/36 jobs are running. Production PDF sources and APK identities are unchanged from the verified carrier fix. The local APK, test APK and resource-helper SHA-256 identities have been rechecked against `carrier-artifact-and-source-hashes.json`.

`sc01/RAW_LOG_XML_SHA256SUMS.txt` records the 39 archived raw log/XML files at this checkpoint, including failed attempts. Scoped Git attributes preserve these raw file bytes across checkouts. APK/source identities are separately recorded in the two JSON manifests; the baseline review's original hash manifest remains separate.

A follow-up comparison against Git blobs detected four early files staged before the raw-byte attributes took effect: `sc01-unit-passing.xml`, `sc01-unit-retry.log`, `sc01-unit.log`, and `TEST-com.pdfchemy.app.jail.engines.RedactionResourcesTest.xml`. Those blobs had LF normalization while retained originals used CRLF; content differed only in line endings. The intact raw originals were restaged under the scoped attributes. All 39 staged evidence blobs now match the recorded SHA-256 values exactly. Historical normalized blobs remain in Git history; the correction does not rewrite or discard them.

## Historical documentation reconciliation

Root handoff/status documents now point to the dated analysis, implementation plan and this receipt. Earlier release receipts no longer assert unverified SR-01 owner acceptance. The [SR-01 reassessment](../security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md) corrects the host PDF-validation claim, worker-local timer guarantees and premature rejection of hard file-size limits/seekable proxy descriptors. FOLLOW-06 documentation reconciliation is complete; feasibility experiments, implementation/disposition and attributable sign-off remain open under FOLLOW-07. No source fix or hostile-worker verification is claimed by these corrections.
