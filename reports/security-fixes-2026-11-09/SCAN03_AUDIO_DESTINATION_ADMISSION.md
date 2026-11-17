# SCAN-03: admit and own audio destinations before destructive open

Baseline: `aa6d78184657f42b87349d98de1835e2520dd595` (audio publication retention). Its same-source CI run [42616950686](https://github.com/kiss2oblivion/pdfchemy-android/actions/runs/42616950686) passed all nine jobs and 518 native methods on each API 24/30/34/36. This batch closes the separate audio destination admission finding.

## Behavior

Audio job registration now requires host-recorded fresh provenance and claims the destination before staging or service launch. A cached or forged freshness Boolean cannot authorize an unknown URI. Each registered job retains its URI and private owner token through synthesis and publication. Duplicate registration fails before claiming a second URI; competing operations retain their claims.

Both the public publisher and the service publisher validate the current claim before any provider write open. Content URIs without a current authorized claim, including published destinations, are rejected. Publication retires the owning claim inside the successful close/commit boundary, preserving SCAN-02's receipt and output retention behavior.

Job cleanup abandons only its own unpublished output after the publisher releases its stream and writer lease. An active publication flag also protects the interval before writer acquisition. Launch rejection, pre-start cancellation, synthesis failure and service cleanup release the job's claim. Generic stale cleanup cannot delete a claimed output. Refused deletion retains fresh provenance for an explicit retry through the shared registry.

## Regression evidence

All six initial admission regressions failed against unchanged baseline production code. Preserved XML: `.scan03-evidence/baseline-regressions.xml`. The final 12-method unit class additionally covers claim acquisition before staging, duplicate registration, single-use registered publication, truncating-open failure, close failure and premature forget rejection. Existing service/retention and destination integrity fixtures now register actual fresh capabilities and use the owning cleanup API.

The first focused run had four cleanup assertion failures because the mock contexts lacked `DocumentsContract.isDocumentUri` provider classification. Configure that classification explicitly for these mock providers; the corrected focused suite passes all 72 methods. The first-run XML remains in `.scan03-evidence/first-focused-*.xml`.

Eight new native methods use the existing real provider fixture to check zero opens/unchanged original bytes for existing, unknown, published and foreign-claimed destinations; truncation followed by open failure; partial pipe write failure; cancellation during blocked provider open; and job-launch compensation. They also check source bytes, released writer leases and owned-output deletion. Cancellation during an open is verified after the provider returns; this does not claim that an arbitrary blocked provider call can be interrupted. The stopped-reader pipe cancellation finding remains a separate pending batch.

## Local verification

| Check | Result |
|---|---|
| Focused audio/destination unit suite | 72 passed |
| Complete host unit suite | 558 passed, zero failures/errors/skips |
| IPC unit suite | 115 passed, zero failures/errors/skips |
| Debug application and instrumentation APK builds | Passed |
| Release lint | Zero errors, 623 warnings (previous batch reported 622) |
| Host security audit and security architecture | Passed |
| API 24 native selection | 32 methods passed, strict complete-run validation |
| API 36 native selection | 32 methods passed, strict complete-run validation |
| TruffleHog 3.97.9 | Six exact reviewed historical matches, zero rejected |
| Diff whitespace check | Passed |
| Debug app APK SHA-256 | `5421c29d1e98b5d7036ca58fd18932cc690975d450a03b5602fd10204f8f1c9d` |
| Instrumentation APK SHA-256 | `68102f979f327b25697dd62073d1063f9c9c82f7973a77bd21d36d0842ccc360` |

Full Gradle verification passed in 6m 57s using JDK 21, offline cached dependencies and two workers:

```powershell
.\gradlew.bat :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease :app-host:securityAudit securityArchitecture --offline --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Native selection includes the new admission class, both SCAN-02 retention/UI classes, WAV platform checks and existing SAF/text/batch ownership regressions. Raw runner logs remain under `.scan03-evidence/emulator-5580/` and `emulator-5582/`; task logs are `scan03-*.log`. Generated outputs and scanner temporary storage use D:. Same-source CI follows the commit/push. Signed-release and physical-device checks remain open. Original external checkout work and the saved offline speech prototype remain preserved.
