# SC-11 execution receipt

Date: 2026-10-22. Release: **HOLD**. SC-11 source implementation, unit regression suite, test APK assembly, and lint validation gates are complete.

## Problem and change

In `MainActivity.kt:cleanupOrphanedCacheFiles`, the application launched a background cache cleanup routine on startup (`Dispatchers.IO`) that recursively scanned `context.cacheDir` and deleted files with modification timestamps older than one hour (`lastModified() < oneHourAgo`) matching document extensions (e.g. `.pdf`).

Because `DocumentStager` stages internal document snapshots under `context.cacheDir/staged/snapshot_*.pdf`, any document held open in Reader, held in tool continuity, or backgrounded across a 1-hour boundary had its underlying backing snapshot silently unlinked and deleted from storage by the background startup sweep. Meanwhile, `DocumentStager`'s in-memory lease table still recorded active leases, leading to silent I/O crashes, blank pages, and state desynchronization upon user interaction. Furthermore, when unleased snapshots aged out and were deleted by the sweeper, `DocumentStager`'s in-memory maps were not notified, causing stale quota and document-count accounting.

### Resolution

1. **Active Staging Lease Inspection:**
   Added `@Synchronized fun isLeased(file: File): Boolean` and `@Synchronized fun isLeased(uri: Uri): Boolean` to `DocumentStager`. The checks resolve across canonical, absolute, and path variants, verifying if any active lease (`leases[path] > 0`) is currently held on the document capability.

2. **Synchronized Unleased Snapshot Reclamation:**
   Added `@Synchronized fun reclaimUnleasedSnapshot(file: File): Boolean` to `DocumentStager`. If a file corresponds to a staged snapshot with zero active leases, it cleanly removes it from `staged`, `displayNames`, adjusts internal quota accounting, and deletes the file from disk.

3. **Defensive Sweeper Integration:**
   Updated `MainActivity.cleanupOrphanedCacheFiles`:
   - Checks `DocumentStager.isLeased(file)` before deleting any matching cache file; if leased, deletion is skipped immediately, preserving the backing snapshot and its bytes intact.
   - For unleased matching snapshots, calls `DocumentStager.reclaimUnleasedSnapshot(file)` to ensure memory structures and disk state stay synchronized.
   - Normal unmanaged temporary cache files continue to be deleted as before.

4. **Path Canonicalization & Quota Deduplication:**
   Hardened `DocumentStager` so that canonical and absolute path variants are mapped correctly without inflating `stagedCount` or `totalStagedBytes` (using set semantics on distinct capabilities).

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: Added `startupSweepPreservesActivelyLeasedSnapshotEvenWhenAged`, `startupSweepReclaimsEligibleUnleasedAgedSnapshot`, and `startupSweepPreservesRecentUnleasedSnapshot` to `StagedSessionRetentionTest.kt` | `app-host/src/test/java/com/pdfchemy/app/security/StagedSessionRetentionTest.kt` |
| General Release Workflow Regression | PASS: `ReleaseWorkflowRegressionTest` passes, confirming scans are retained and stale unmanaged cache is purged | `ReleaseWorkflowRegressionTest.kt` |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest` in 7s) | Gradle task log |
| Static Lint & Security Audit | PASS (`lintDebug` passed in 3m 30s, zero errors) | `app-host/build/reports/lint-results-debug.html` |
