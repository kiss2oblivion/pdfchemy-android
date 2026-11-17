**SCAN-02: preserve completed audio and its saved-file result**

Baseline: `490cd688f526a1f8a969ffe35e8c454a7bd4083f` in `kiss2oblivion/pdfchemy-android`.
Branch: `fix/2.1.0-audio-published-retention`.
Scope: completed-output retention. Destination admission, offline speech, output limits and blocked-pipe behavior remain separate plan items.

**Behavior**

Audio cleanup now calls `OutputPolicy.deleteIfFresh`. A cached freshness Boolean cannot delete a published or claimed output; a deletion failure keeps provenance available for retry. Successful deletion is idempotent.

Publication retires freshness after successful stream closure inside the copy scope, before cancellable result delivery. A synchronous commit callback records an immutable `AudioPublicationReceipt` in the job store. Cancellation and failure keep that receipt and its URI without resurrecting terminal job states or replacing a published result with another URI.

The service records the receipt at that callback and performs its existing staged-file/resource cleanup after interruption. Reflow presents the saved-file Open/Share actions when a receipt exists, even if job result delivery was cancelled or failed. Existing localized saved-file strings are reused.

**Regressions**

All five new cleanup tests failed against unchanged production source. They reproduced deletion after publication, lost provenance after refused/error deletion, repeated deletion and an exception when cleanup encountered another owner's claim. The baseline XML is retained in `.scan02-evidence/baseline-regressions.xml`.

Three service tests drive the real callback-PCM-to-WAV pipeline with a Robolectric TTS shadow. An explicit test seam pauses immediately after publication and then exercises cancellation, service destruction and result-delivery error. Each retains the 48-byte WAV and URI, attempts zero provider deletions, clears staging, forgets transient destination metadata and completes the service job's cleanup.

Five job-store regressions cover cancellation/failure retention, abort racing the commit callback, receipt immutability/idempotence and rejection of receipts on unstarted or fully cancelled jobs. Terminal job state is preserved independently of published-file disposition.

The first service-test attempt incorrectly called a directory-creating accessor to check staging deletion. Its file/URI retention checks passed, but that check recreated the directory. The test now captures the path before the pipeline and checks it without mutation. The failed attempt is retained at `.scan02-evidence/service-first-attempt.xml`; no production assertion was weakened.

**Verification available before commit**

| Check | Result |
|---|---|
| Focused audio and publication/destination security units | 79 pass, zero failures/errors/skips |
| Complete host units | 546 pass, zero failures/errors/skips |
| IPC units | 115 pass, zero failures/errors/skips |
| API 24 x86 native scope | 24 complete passing methods |
| API 36 x86_64 native scope | Same 24 complete passing methods |
| Debug app/test APK builds | Pass |
| Release lint | Zero errors; 622 warnings |
| Security audit, architecture and diff checks | Pass |

The native scope includes the two new separate-UID SAF publication tests, two new Reflow saved-result UI tests, all five `AudioWavPlatformTest` methods, two `SafCleanupOwnershipTest` methods, seven text-publication methods, four fresh-SAF publication methods and two batch-cancellation methods. New SAF tests verify exact WAV/source bytes and zero provider deletions, including cancellation at the commit callback. UI tests independently verify saved-result presentation and visible Open/Share actions for cancelled and failed delivery. They do not claim real-engine speech or physical-device verification.

Both native runs installed identical artifacts:

| Artifact | SHA-256 |
|---|---|
| Debug app APK | `af5bb04dbf42aeed9a7230f9b0a918abd6113a3a7a6b3885c2e5572f51429bff` |
| Instrumentation APK | `491730815255edd959aa4aa407074f704515a63c0eca591e8a43c645f2ca4197` |

The complete local Gradle command passed in 8m 51s using JDK 21:

```powershell
.\gradlew.bat :app-host:testDebugUnitTest :pdf-ipc:testDebugUnitTest :app-host:assembleDebug :app-host:assembleDebugAndroidTest :app-host:lintRelease :app-host:securityAudit securityArchitecture --offline --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Native raw runner output was validated with `scripts/run_android_instrumentation.py::validate_result`, requiring a successful terminal summary and passing completion for every declared method. Raw logs and focused/baseline XML remain under `.scan02-evidence/`, with task logs named `scan02-*.log`. Generated build output and scanner clones use roomy storage on D:.

Secret scan: TruffleHog 3.97.9 found six exact reviewed historical matches and zero rejected findings. Same-source CI is tracked after the implementation commit/push; baseline CI is not verification of this change. Signed-release and physical-device checks were not run. Original checkout work and the saved speech prototype remain preserved.
