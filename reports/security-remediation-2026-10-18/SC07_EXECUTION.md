# SC-07 execution receipt: Staging synchronization and stalled providers

Date: 2026-10-23. Release: **HOLD**. Retained SC-07 staging synchronization, lock-free provider I/O decoupling, in-flight quota reservation tracking, atomic publication and initial lease preservation (CI46-01), deterministic test suite, and Android build gates are complete.

## Problem and change

Previously, `DocumentStager.stageDocument`, `stageDocumentWithLease`, `stageStream`, and `stageStreamWithLease` executed under `@Synchronized` on the global `DocumentStager` object monitor:
1. **Global Monitor Lock Contention During Untrusted Provider I/O:** Untrusted ContentProvider IPC operations (`FileUtils.getFileName` queries and `context.contentResolver.openInputStream`) and disk/network I/O (`input.read(buffer)` streaming loop) were performed while holding `DocumentStager`'s monitor lock.
2. **Main-Thread Lease Operations Blocked (ANR Vulnerability):** When an external ContentProvider took seconds to respond to queries, stalled on `openAssetFile`, or delayed stream reads, the Main thread attempting routine lease operations (`retain`, `release`, `activeLeases`, `isLeased`, or screen disposal) blocked on the object monitor, resulting in immediate UI freezes and ANRs.
3. **Unrelated Session Starvation:** Concurrent staging of unrelated documents on background threads was blocked behind slow or stalled providers.
4. **Quota Drift & Eviction Under Concurrency:** Without fine-grained in-flight capacity reservations, moving stream reads outside the monitor could allow concurrent staging threads to exceed aggregate document counts (`MAX_STAGED_DOCUMENTS = 64`) or batch byte limits (`MAX_BATCH_INPUT_BYTES = 250MB`), or prematurely evict newly created snapshots before their initial protective leases were established (violating the CI46-01 eviction invariant).

### Resolution

1. **Decoupled Untrusted Provider I/O from Global Monitor (`DocumentStager.kt`):**
   - Removed `@Synchronized` from `stageStream`, `stageStreamWithLease`, and `stageStreamInternal`.
   - `FileUtils.getFileName` ContentProvider queries run completely outside any synchronized monitor lock.
   - `openInputStream` runs completely outside any synchronized monitor lock.
   - The entire `input.read(buffer)` stream copy, SHA-256 digest computation, file output writing, `sync()`, and sealing (`file.setReadOnly()`) execute completely unlocked.

2. **Fine-Grained In-Flight Quota Reservations (`DocumentStager.kt`):**
   - Added `@Volatile inFlightSlots: Int` and `@Volatile inFlightBytes: Long`.
   - `reserveSlot()`: Short synchronized method that verifies `staged.size + inFlightSlots < MAX_STAGED_DOCUMENTS` (pruning unleased snapshots if needed), ensures available byte headroom, and increments `inFlightSlots`.
   - `accountInFlightBytes(bytes, currentSize)`: Short synchronized method called on chunk reads to verify single-file (`MAX_PDF_FILESIZE = 100MB`) and aggregate (`MAX_BATCH_INPUT_BYTES = 250MB`) limits, pruning unleased snapshots as needed and tracking `inFlightBytes += bytes`.
   - `releaseReservation(recordedInFlightBytes)`: Short synchronized method executed in `finally` blocks upon failure or cancellation to cleanly decrement `inFlightSlots` and `inFlightBytes`.
   - `cleanupUnpublishedFile(file)`: Short synchronized helper that cleans up partial temporary files and any stray registry mappings.
   - Hardened file removal: Calls `file.setWritable(true)` prior to `file.delete()` to ensure reliable cross-platform snapshot deletion on Windows NT and POSIX filesystems.
   - **Shutdown Quota Retention (`releaseAll`):** `DocumentStager.releaseAll()` clears registered staged files and active leases but strictly retains in-flight counters (`inFlightSlots` and `inFlightBytes`). This ensures that finishing activities or session resets do not zero out reservations while background streaming transfers are still active, allowing streaming `finally` blocks to decrement quota cleanly without underflow or capacity corruption.

3. **Atomic Publication & Initial Lease Protection (`publishStagedSnapshot`):**
   - Once a snapshot is fully written, synced, hashed, and sealed, `publishStagedSnapshot` executes under a short `@Synchronized` block:
     - Atomically registers the snapshot capability in `staged`.
     - Atomically acquires the initial protective lease (`retain(capability)`) before releasing the monitor, guaranteeing that concurrent unleased pruning never observes the newly created document with zero leases (preserving the CI46-01 fix).
     - Atomically transfers quota from in-flight reservations (`inFlightSlots--`, `inFlightBytes -= recordedInFlightBytes`) to active staged inventory.
     - Prunes excess unleased snapshots to `MAX_UNLEASED_DOCUMENTS = 4`.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 5 methods in `StagingSynchronizationSecurityTest.kt`: stalled provider query does not block Main-thread lease operations or unrelated sessions (sc07_01); stalled provider openFile does not block Main-thread lease operations or unrelated sessions (sc07_02); stalled stream read loop does not block Main-thread operations or unrelated staging, tracks in-flight reservations (sc07_03); cancellation during stalled read reclaims slot and byte reservations and deletes partial files (sc07_04); concurrent staging of 16 leased and 20 unleased documents preserves aggregate quotas, unleased bounds, and CI46-01 atomic lease eviction immunity (sc07_05) | `app-host/src/test/java/com/pdfchemy/app/security/StagingSynchronizationSecurityTest.kt` |
| IPC Stager Retention Suite | PASS: 8 methods in `DocumentStagerRetentionTest.kt` (sequential and concurrent retention, LRU trimming, atomic leasing, active lease eviction protection) | Gradle task log (`:pdf-ipc:testDebugUnitTest`) |
| IPC Shutdown Retention Suite | PASS: 2 methods in `DocumentStagerShutdownTest.kt` (preserves live reservations across `releaseAll()`, enforces 64 document quota during and after shutdown) | Gradle task log (`:pdf-ipc:testDebugUnitTest`) |
| Host Staged Session Retention | PASS: `StagedSessionRetentionTest.kt` (3 methods) & `StagingTransferSecurityTest.kt` (6 methods) | Gradle task log (`:app-host:testDebugUnitTest`) |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest.kt`) | Gradle task log (`:app-host:testDebugUnitTest`) |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
| Full App-Host Unit Suite | PASS: `:app-host:testDebugUnitTest` (all tests clean) | Gradle task log |
