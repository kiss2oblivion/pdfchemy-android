# SC-06 execution receipt: Cancellation-safe lease and bitmap transfer

Date: 2026-10-23. Release: **HOLD**. Retained SC-06 cancellation-safe lease and bitmap transfer, `ResourceTransferOwner` boundary bridge, screen lifecycle retention across Reader/Editor/Reflow, `NativeRendererCoordinator` bitmap lifecycle safety, unit regression suite, and test assembly gates are complete.

## Problem and change

Callers previously wrapped `stageDocumentCancellableWithLease` or bitmap decoders in `withContext(Dispatchers.IO)` and assigned ownership only after it resumed on `Dispatchers.Main`:
1. **Outer Dispatcher Cancellation Gap:** Kotlin coroutines documents that when `withContext` switches dispatchers, prompt cancellation occurring while resumption is scheduled or running discards the return value of the block and throws `CancellationException`.
2. **Staging Lease Drift & Snapshot Abandonment:** Staging completed successfully inside the background IO block, acquiring an active lease in `DocumentStager.leases`. When the outer `withContext` return was discarded by cancellation, the caller's assignment to `activeSessionLease` was never reached. The lease remained permanently elevated (`activeLeases = 1`), preventing unleased LRU pruning and exhausting the staging quota over repeated navigations/cancellations.
3. **Native Bitmap Leak:** In `NativeRendererCoordinator.renderUriToBitmap` and `renderPageToBitmap`, newly allocated native `Bitmap` instances were returned across `withContext(Dispatchers.IO)`. When resumption was cancelled, the bitmap reference was dropped without `recycle()`, leaking native memory until eventual GC.

### Resolution

1. **`ResourceTransferOwner<T : Any>` (`pdf-ipc/src/main/java/com/pdfchemy/app/utils/ResourceTransferOwner.kt`):**
   - Implemented thread-safe, disposal-safe resource transfer holder using `AtomicReference` and `@Volatile closed` state.
   - `stage(resource)`: Safely stages the allocated resource before leaving the background thread. Discards any previous or concurrent resource if already closed.
   - `commit()`: Caller on target dispatcher claims ownership upon successful resumption without intermediate suspension points.
   - `discard()` / `close()`: Idempotently extracts uncommitted resources and executes `onDiscard(resource)` to close leases or recycle bitmaps.

2. **`DocumentStager.kt` Cancellation-Safe APIs (`pdf-ipc`):**
   - Added `StagedLease.discard(borrowedUri: Uri? = null)`: If newly staged (`document.uri != borrowedUri`), marks `release(document)` before closing the lease, completely deleting the uncommitted temporary snapshot; if borrowed, safely decrements the lease count.
   - Added `stageDocumentCancellationSafe(context, sourceUri, transferOwner)` and `stageStreamCancellationSafe(context, input, transferOwner)`: Stages into the transfer owner before completing `withContext(Dispatchers.IO)`. Catches `Throwable` (including `CancellationException`) on resumption, calls `owner.discard()`, and rethrows.

3. **`NativeRendererCoordinator.kt` Bitmap Lifecycle Safety (`app-host`):**
   - Updated `renderUriToBitmap` and `renderPageToBitmap` to accept optional `ResourceTransferOwner<Bitmap>`.
   - Stages decoded bitmaps into the owner before dispatcher switch.
   - On resumption failure or cancellation, `owner.discard()` executes `if (!it.isRecycled) it.recycle()`, eliminating native bitmap leaks.

4. **Screen Integrations (`PdfReaderScreen.kt`, `PdfEditorScreen.kt`, `ReflowReaderScreen.kt`):**
   - Screens remember `stagingTransferOwner` across compositions:
     - `DisposableEffect(Unit)` closes `stagingTransferOwner` on disposal, guaranteeing cleanup even if the screen is destroyed while staging is in-flight.
     - `LaunchedEffect` stages via `DocumentStager.stageDocumentCancellationSafe(context, uri, stagingTransferOwner)`.
   - `PdfReaderPageItem`:
     - Retains `bitmapTransferOwner` scoped to `(uri, pageIndex)` and closes it in `DisposableEffect`.
     - Passes `bitmapTransferOwner` to `NativeRendererCoordinator.renderUriToBitmap`.
     - Recycles previous bitmaps upon new renders and recycles uncommitted bitmaps on cancellation.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 6 methods in `StagingTransferSecurityTest.kt`: cancellation before caller resumption leaves no lease or abandoned snapshot (sc06_01); transfer owner normal commit handoff (sc06_02); disposal before staging returns discards resource (sc06_02b); bitmap recycled on cancellation during resumption (sc06_03); normal commit preserves bitmap until explicit recycle (sc06_03b); 10x repeated cancellation does not cause quota drift and preserves active borrowers (sc06_04) | `app-host/src/test/java/com/pdfchemy/app/security/StagingTransferSecurityTest.kt` |
| Staging Retention Regressions | PASS: `StagedSessionRetentionTest.kt` (3 methods) & `DocumentStagerRetentionTest.kt` (7 methods) | Gradle task log |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest`) | Gradle task log |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
| Full App-Host Unit Suite | PASS: `:app-host:testDebugUnitTest` | Gradle task log |
