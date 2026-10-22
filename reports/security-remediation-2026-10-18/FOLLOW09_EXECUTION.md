# FOLLOW-09: API34 startup lifecycle regression

Date: 2026-10-22. Release: **HOLD**. Test-driver repair passes corrected local API24 verification; source commit/push and full same-source CI are pending at this checkpoint. Local API34/36 limitations are retained below.

## Failure and scope

SC-04 and SC-10 CI each retained an API34 failure in `StartupWindowRegressionTest.backgroundAndResumeRetainsContent`. The former driver requested `ActivityScenario.moveToState(Lifecycle.State.STARTED)`. That is a supported AndroidX API: it launches `EmptyFloatingActivity` and expects the original activity to settle in test-monitor stage `PAUSED`. It does not require a fully stopped, nonvisible activity.

Both failures instead show the original activity stop, restart and reach raw test-monitor stage `STARTED`, without reaching the requested steady `PAUSED` state before the timeout. A raw test-monitor `STARTED` stage is transient and maps to no steady Lifecycle state; this also explains the secondary null-state exception from `ActivityScenario.close()`. The exact reason for the floating-helper divergence remains unestablished. Neither an ads/consent defect nor an infrastructure cause has been proven.

The exact published AndroidX core 1.5.0 sources JAR and source-line mapping are retained under [upstream](follow09/upstream/source-semantics.json). The original failures remain in [SC-04 evidence](SC04_EXECUTION.md) and [SC-10 analysis](SC10_POST_FIX_ANALYSIS.md). Unchanged retries are historical outcomes, not causal proof.

## Implemented contract

Only the background/resume instrumentation driver changes. Each of ten cycles sends the real accessibility Home action, waits for the original activity's pause/stop callbacks, and verifies its lifecycle is `CREATED`, nonfinishing and nondestroyed. It then programmatically starts the normal package launcher intent and waits for that same activity to resume. Each cycle requires exactly `PAUSED -> STOPPED -> STARTED -> RESUMED`, the original task, framework content container and Compose child, with nonempty content. Destruction fails the test, including when it wakes a waiter. Callbacks are unregistered in `finally`.

The original ten cold-launch/recreation cycles remain. Normal consent/ads startup remains enabled. No retry, delay, SDK bypass, production change, dependency update or workflow relaxation is introduced. Independent source review found no material race or false-pass path; device execution must still establish that the instrumented launcher return works on supported APIs.

## Verification

| Layer | Result at this checkpoint |
|---|---|
| Debug and instrumentation APK assembly | PASS |
| Focused `StartupContentContainerTest` JVM suite | PASS: 6 methods, zero failures/errors/skips |
| Static security and architecture gates | PASS |
| Release lint and minified unsigned assembly | PASS |
| Artifact gate | PASS: 14 fixture hashes, 62 static route rows, identifiers/version/SDK and artifact hygiene |
| Native API34 | INCOMPLETE locally: three Windows emulator exits; Linux CI required |
| Native API24 | PASS: 2 methods, ten Home cycles, empty crash buffer |
| Native API36 | INCOMPLETE locally: three process-start ANRs before methods and one ADB setup failure; Linux CI required |
| Android source commit/push/ref | PENDING |
| Full same-source API24/30/34/36 CI | PENDING; first-attempt pass required for this repair |

Baseline and revised application APKs are byte-identical: SHA-256 `ac95a5901919567f6f214291c7a5948b490ec273ffa07afc1c38bf280acafa0f`. Only the test APK differs. This keeps application behavior constant in the comparison.

## Local host attempts and limits

Three local API34 baseline attempts ended before any completed test result when the Windows emulator process exited during the unchanged cold-launch/recreation method. The monitored second and third attempts returned `3221225477` (`0xC0000005`), including the third diagnostic launch with Vulkan disabled. These are incomplete host attempts, excluded from application pass/fail counts. No local baseline reproduction or revised API34 test result is claimed; full Linux CI is required. Raw logs are retained.

The first revised API24 run passed the unchanged recreation method and all ten Home cycles, then failed at `ActivityScenario.close()`. Its log records actual activity destruction, but AndroidX rejected that event because the initial component-only intent differed from the launcher intent stored by `MainActivity.onNewIntent()`. The correction starts this test with the same launcher intent used for returns, preserving AndroidX's lifecycle observer match. All original assertions and cleanup remain. The failed instrumentation and lifecycle logs are retained.

The first revised API36 attempt returned `Process crashed` before any test start. `dumpsys activity exit-info` records a process-start ANR (`reason=6`, failed to complete startup), not a completed assertion failure; the crash buffer was empty. Its cause is unestablished and the result is excluded from completed test counts. The corrected driver must pass on an already booted device before advancement.

One corrected API36 setup attempt then failed to connect to the ADB daemon while setting the diagnostic log buffer, before instrumentation began. The device subsequently appeared connected; boot and the same log-buffer command passed before verification restarted. That setup failure is retained separately and is not an application test result.

The corrected API36 driver also encountered a process-start ANR before any test method. Its retained log reports slow system operations and 99% guest CPU use (85% kernel). Host free physical memory fell below 1 GB. These observations do not prove the cause of either ANR. The completed API24 emulator and the task-owned idle Gradle daemon were stopped; the installed app/test SHA-256 hashes were verified before a final focused attempt. Full Linux CI is required independently of the local result.

That final reduced-host-load API36 attempt also ended in a process-start ANR before any method. Local API36 verification is incomplete; three prestart ANRs and one setup failure are retained, with no passing local API36 method claim. Further local retries were stopped. The completed/incomplete task-owned emulators were shut down.

This batch repairs and verifies the intended background/resume test contract. It does not establish the internal cause of AndroidX's former floating-helper divergence, universal lifecycle reliability, signed physical-device acceptance or release approval. Other security findings and release gates remain open.
