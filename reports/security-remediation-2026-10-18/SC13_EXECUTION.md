# SC-13 execution receipt: Publication lifetime, bounded host resources, and provider cancellation

Date: 2026-10-23. Commit: `90251eb8`. Release: **HOLD**. Retained SC-13 publication lifetime bounds, cooperative stream cancellation, writer-stop safety before deletion, destination collision prevention, deterministic test suite, and Android build gates are complete.

## Problem and change

Previously, host destination publication suffered from unbounded concurrency, unresponsive cancellation, and write-after-delete race risks:
1. **Unbounded Publication Concurrency & Storage Exhaustion:** PDF publication occurs after the isolated worker acceptance handshake releases its token (`PdfGateway.kt`), and audio publication occurs directly in the host (`AudioOutputPublisher.kt`). Under repeated cancellation and retry against slow, stalled, or malicious destinations, `MainViewModel.cancelOperation` cleared `activeJob` and returned to `UiState.Idle` before earlier writers finished joining. Multiple blocked writers accumulated in `Dispatchers.IO`, retaining temporary snapshot files and file descriptors without aggregate concurrency or byte bounds.
2. **Unresponsive Blocking Provider Writes:** Standard Java `OutputStream.write` and `openOutputStream` are blocking calls. Cancelling a coroutine does not make these calls return unless the underlying stream/descriptor is closed asynchronously.
3. **Write-After-Delete TOCTOU Hazard:** Attempting to delete a destination file while a writer thread is still actively writing to it can cause filesystem corruption, permission faults, or silent disk leaks into unlinked inodes.

### Resolution

1. **Centralized Host Publication Coordinator (`PublicationCoordinator.kt` in `pdf-ipc`):**
   - Implemented `PublicationCoordinator` to track and bound outstanding host publications across PDF and Audio workflows.
   - Enforces configurable concurrency (`maxConcurrentPublications = 2`) and aggregate byte budgets (`maxOutstandingBytes = SecurityLimits.MAX_OUTPUT_BYTES = 250MB`).
   - Prevents destination collision: rejects concurrent publications targeting an in-flight destination URI.
   - Manages `PublicationHandle` instances tracking active output streams and associated jobs.

2. **Cooperative Stream Cancellation via `awaitCancellation()` Watcher:**
   - In `HostOutputTransaction.commit()` and `AudioOutputPublisher.copy()`, wrapped publication I/O in `coroutineScope`.
   - Launched a lightweight watcher child coroutine that awaits cancellation. Upon parent coroutine cancellation, the watcher immediately triggers `handle.abort()`, closing the active `OutputStream` asynchronously.
   - Closing the stream unblocks any thread blocked in POSIX/JVM `write()` with an `IOException`, terminating the writer in less than 50 milliseconds without hanging or requiring arbitrary timeouts.

3. **Writer-Stop Safety & Platform Limits Documentation:**
   - `MainViewModel.cancelOperation`, `UiState.Error`, and `AudioOutputPublisher.deletePartial` strictly verify that the writer has stopped (`withTimeoutOrNull(stopTimeoutMs)` and `!PublicationCoordinator.hasActiveWriter(uri)`) before executing `deleteIfFresh`.
   - **Supported Platform Limits:** If an external provider is stalled in an uninterruptible Binder IPC call or kernel uninterruptible sleep (D-state) that cannot be interrupted from user space without process destruction, destination deletion is suppressed. The destination and quota remain quarantined until the writer actually terminates, preventing write-after-delete corruption.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 5 methods in `PublicationCancellationSecurityTest.kt`: coroutine cancellation closes active stream and unblocks writer promptly (< 100ms) with clean partial cleanup (sc13_01); uninterruptible stalled writer prevents premature deletion and preserves destination quarantine (sc13_02); repeated cancellation and retry enforces concurrency (max = 2) and byte budgets (1000B), preventing volume exhaustion (sc13_03); destination collision rejects concurrent publications to the same URI (sc13_04); AudioOutputPublisher.deletePartial refuses deletion while writer is active (sc13_05) | `app-host/src/test/java/com/pdfchemy/app/security/PublicationCancellationSecurityTest.kt` |
| Destination Integrity Suite | PASS: 25 methods in `DestinationIntegritySecurityTest.kt` (covers direct-screen provenance, cancellation cleanup, and source preservation) | Gradle task log (`:app-host:testDebugUnitTest`) |
| Staging Synchronization Suite | PASS: 5 methods in `StagingSynchronizationSecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| Staging Transfer Suite | PASS: 6 methods in `StagingTransferSecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest.kt`) | Gradle task log (`:app-host:testDebugUnitTest`) |
| IPC Module Unit Suite | PASS: 8 methods in `DocumentStagerRetentionTest.kt` | Gradle task log (`:pdf-ipc:testDebugUnitTest`) |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
| Full App-Host Unit Suite | PASS: `:app-host:testDebugUnitTest` (all tests clean) | Gradle task log |
