# RA-05 execution receipt: Redaction operation lifetime

Date: 2026-10-22. Release: **HOLD**. Retained RA-05 redaction operation lifetime, ViewModel retained owner, explicit Back/input policy, failure/cancellation cleanup, unit regression suite, and test assembly gates are complete.

## Problem and change

Redaction export was previously owned by `RedactionScreen` inside its Composable lifecycle via `rememberCoroutineScope().launch`:
1. **Unretained Lifecycle & Scope Cancellation:** If the user pressed Back, rotated the screen, or navigated away during export, the Composable scope was cancelled mid-write without joining writer termination or invoking provenance-aware cleanup. Corrupt or zero-byte incomplete files were left in storage.
2. **Missing Destination Ownership & Freshness Retirement:** The destination URI was not registered in `MainViewModel.activeOperations` or tracked by `OutputPolicy`. Successful exports did not retire fresh copy status, leaving published files susceptible to cleanup if reused.
3. **Active Back & Input Manipulation During Export:** Back navigation and screen inputs (file picker, query input, pattern presets, target deletion) remained active during processing.

### Resolution

1. **Retained Owner in `MainViewModel.kt` (`applyRedactions`):**
   - Added `applyRedactions(context, sourceUri, destUri, redactions, config, onComplete)`:
     - Validates `SourcePreservationPolicy.requireSeparateDestination(context, sourceUri, destUri)` before modifying state or launching background jobs.
     - Registers destination via `addPendingOutputUri(destUri)`.
     - Transitions state to `UiState.Processing(R.string.tool_redact)`.
     - Assigns the background coroutine to `activeJob` under `viewModelScope.launch(Dispatchers.IO)`.
     - On successful completion: Marks output published in `activeOperations` and retires freshness via `OutputPolicy.markPublished(destUri)`, updates history, and sets `UiState.Success`.
     - On failure or cancellation: Executes non-cancellable cleanup via `OutputPolicy.deleteIfFresh(context, destUri)`, and transitions to `UiState.Error`.

2. **Screen Integration & Guardrails (`RedactionScreen.kt`):**
   - Collects `val uiState by viewModel.uiState.collectAsState()`.
   - Defines `val isProcessing = uiState is MainViewModel.UiState.Processing || uiState is MainViewModel.UiState.BatchProcessing`.
   - Binds `BackHandler(enabled = !isProcessing) { onBack() }` and `TopAppBar` navigation button with `enabled = !isProcessing`.
   - Disables all interactive inputs during export: file picker card, search text field, pattern presets, review dialog confirm/cancel buttons, and target delete buttons.
   - In `saveFileLauncher`: Delegates export directly to `viewModel.applyRedactions(...) { if (it) onBack() }`.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 25 methods in `DestinationIntegritySecurityTest.kt`, including 4 RA-05 methods: retained ownership and freshness retirement on success, engine failure cleanup of fresh outputs while preserving existing outputs, pre-admission same-document rejection, and cancelOperation cleanup | `app-host/src/test/java/com/pdfchemy/app/security/DestinationIntegritySecurityTest.kt` |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest`) | Gradle task log |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
