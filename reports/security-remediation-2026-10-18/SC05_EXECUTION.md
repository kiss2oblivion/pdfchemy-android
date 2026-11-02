# SC-05 execution receipt

Date: 2026-10-22. Release: **HOLD**. SC-05 direct-screen destination provenance and completion finalization, freshness retirement, failure cleanup, unit regression suite, and test assembly gates are complete.

## Problem and change

Direct screens (`FormBuilderScreen`, `DeskewScreen`, `DocumentSanitizerScreen`), direct scanner launcher (`MainActivity`), `MarkdownStudioScreen`, and direct viewmodel workflows (`exportEditedPdf`, `compressImage`, `compressImageToTargetSize`) previously lacked unified output completion finalization and freshness retirement.

1. **Unretired Freshness Gaps:**
   Operations that transitioned directly to `UiState.Idle` (such as `exportEditedPdf` and `compressImage`), or that displayed toast notifications without transitioning through `UiState.Success`, did not retire fresh destination status in `OutputPolicy.markPublished(uri)`. If an operation reused a previously written fresh destination, subsequent cancellations or errors in later workflows could mistakenly clean up the already completed document.

2. **Direct-Screen Failure Cleanup:**
   If export failed or was cancelled mid-write inside direct screens or the document scanner launcher, incomplete or zero-byte files created as fresh copies could be left behind in storage. Conversely, existing files (such as user-selected existing files) must never be truncated or deleted on error or cancellation.

### Resolution

1. **Output Finalization Contract (`OutputPolicy.kt`):**
   - Added `OutputPolicy.finalizeOutput(context: Context, destination: OutputDestination, success: Boolean)`:
     - On `success == true`: Retires freshness via `markPublished(destination.uri)`.
     - On `success == false`: Cleans up destination if and only if `destination.isFreshCopy` via `deleteIfFresh(context, destination.uri)`.
   - Added overload `OutputPolicy.finalizeOutput(context: Context, uri: Uri, success: Boolean, isFreshCopy: Boolean)`.

2. **MainViewModel Direct Workflows & Lifecycle (`MainViewModel.kt`):**
   - In `_uiState.set`: Added defense-in-depth hook ensuring any transition to `UiState.Success` automatically retires all `v.outputUris` in `OutputPolicy.markPublished(uri)`.
   - In `showSuccessToast(title, message, outputUri)` and `notifySuccess(title, message, outputUri)`: Automatically marks the output URI as published in both `MainViewModel`'s terminal state and `OutputPolicy`.
   - In `showErrorToast`: Accepts optional `failedOutputUri: Uri? = null` parameter; automatically calls `OutputPolicy.deleteIfFresh(context, failedOutputUri)` on error.
   - In `exportEditedPdf`, `compressImage`, and `compressImageToTargetSize`: Added explicit `markOutputPublished(destUri)` and `OutputPolicy.markPublished(destUri)` upon successful completion before transitioning to `UiState.Idle`.
   - Added retained lifecycle methods `convertMarkdownToPdf` and `saveMarkdownDocument`:
     - Registers destination via `addPendingOutputUri(destUri)`.
     - Sets `UiState.Processing`.
     - Automatically retires freshness and transitions to `UiState.Success` / `UiState.Idle` on completion.
     - In catch / `CancellationException` blocks, cleans up destination only if fresh and transitions to `UiState.Error`.

3. **Direct Screen & Activity Launchers:**
   - **`MarkdownStudioScreen.kt`:** Replaced local coroutine file writes in `savePdfLauncher` and `saveMdLauncher` with calls to `viewModel.convertMarkdownToPdf` and `viewModel.saveMarkdownDocument`.
   - **`MainActivity.kt`:** In `saveScannedPdfLauncher`, marks `OutputPolicy.markPublished(destUri)` on success, and in `CancellationException` and generic `Exception` handlers invokes `withContext(NonCancellable + Dispatchers.IO) { OutputPolicy.deleteIfFresh(context, destUri) }`.
   - **`FormBuilderScreen.kt`, `DeskewScreen.kt`, `DocumentSanitizerScreen.kt`:** Export launchers now wrap execution in try-catch-finally, calling `OutputPolicy.markPublished(destUri)` on success and `withContext(NonCancellable + Dispatchers.IO) { OutputPolicy.deleteIfFresh(context, destUri) }` on error or cancellation.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 21 methods in `DestinationIntegritySecurityTest.kt`, including: direct screen toast freshness retirement, `exportEditedPdf` freshness retirement, `compressImage` freshness retirement, `convertMarkdownToPdf` success retirement & failure cleanup, and `OutputPolicy.finalizeOutput` fresh vs existing policy | `app-host/src/test/java/com/pdfchemy/app/security/DestinationIntegritySecurityTest.kt` |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest`) | Gradle task log |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
