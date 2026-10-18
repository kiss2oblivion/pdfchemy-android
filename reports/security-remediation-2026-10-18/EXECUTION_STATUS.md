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
| SC-01 | Implemented: fresh redacted-page resources; shared/inherited and nested pattern/mask resource pruning | 50 worker tests (including nine resource regressions), five host boundary tests, assembly and release lint pass | Baseline API24: three expected assertion failures. Final carrier source: API24/36 four tests pass each. Full CI attempt 1: API24 installation failure, zero tests; other API jobs pending | `a1077e47` and `9d7dc22d` pushed/remote-verified |
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

At this receipt update, API30/34/36 remain in progress. GitHub rejected an API24-only rerun while the workflow was still running (`job 117668349134 cannot be rerun`). A same-source fresh-runner retry must be requested after the active workflow completes. No API removal, test filtering or assertion weakening is authorized as a substitute for this gate. SC-02 remains unstarted.

## Historical documentation reconciliation

Root handoff/status documents now point to the dated analysis, implementation plan and this receipt. Earlier release receipts no longer assert unverified SR-01 owner acceptance. The [SR-01 reassessment](../security-code-reaudit-2026-10-10/SR-01_STORAGE_RESIDUAL_DECISION.md) corrects the host PDF-validation claim, worker-local timer guarantees and premature rejection of hard file-size limits/seekable proxy descriptors. FOLLOW-06 documentation reconciliation is complete; feasibility experiments, implementation/disposition and attributable sign-off remain open under FOLLOW-07. No source fix or hostile-worker verification is claimed by these corrections.
