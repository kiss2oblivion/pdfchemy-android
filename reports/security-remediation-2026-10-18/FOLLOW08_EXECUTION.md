# FOLLOW-08 execution receipt: Reconcile redaction API count semantics between worker and host

Date: 2026-10-23. Commit: `b56af03f`. Release: **HOLD**. Retained FOLLOW-08 redaction count semantics reconciliation, strict validation in `WorkerResponseValidator`, unit regression tests, and build gates are complete.

## Problem and change

Previously:
1. **Count Semantics Divergence:**
   - In `PdfRedactionEngineWorker.kt`, the isolated worker accurately counts the number of applied redaction bounding boxes (`redactedCount`) and serializes it:
     `JSONObject().put("success", true).put("count", redactedCount).toString()`.
   - In `WorkerResponseValidator.kt`, the shared `parseStandardOutput` parser parsed `size`/`outBytes` and checked `renderedCount`, but ignored `count`.
   - Consequently, `StandardOutputContract` defaulted its `count` field to `1` (representing 1 output file produced).
2. **UI Misrepresentation:**
   - In `PdfRedactionEngine.kt`, `applyRedactions` evaluated:
     `Result.success(if (contract.count > 0) contract.count else redactions.size)`.
   - Because `contract.count` was always `1`, `applyRedactions` returned `Result.success(1)` regardless of whether 1, 5, or 20 redactions were actually performed.
   - In `MainViewModel.kt`, the success notification displayed `R.string.desc_redaction_success` ("Successfully applied %1$d permanent redactions") with `1`, falsely claiming only 1 redaction occurred when multiple boxes were applied.
   - In `SC-02`, multiline regressions had to work around this divergence by checking output absence rather than verifying `Result.getOrThrow()` matched box count.

### Resolution

1. **`WorkerResponseValidator.kt` (`parseStandardOutput`):**
   - Added validation for `"count"`:
     ```kotlin
     val count = if (root.has("count") && !root.isNull("count")) {
         root.requireInt("count", min = 0)
     } else {
         1
     }
     return StandardOutputContract(success = true, size = size, count = count)
     ```
   - Validates that `"count"` is non-negative and strictly integral using `requireInt(min = 0)`. Any negative or non-integral number is rejected fail-closed.
   - For standard single-file operations omitting `"count"`, defaults cleanly to `1`.

2. **`PdfRedactionEngine.kt` (`applyRedactions`):**
   - Simplified return to `Result.success(contract.count)`, directly propagating the worker's verified applied redaction count to host callers and UI notifications.

3. **`PdfRedactionEngineWorker.kt` (`redact`):**
   - Added preflight page-bounds validation before modifying any form data or painting:
     `require(boxes.all { it.pageIndex in 0 until pageCount }) { "Redaction page is outside the document" }`
   - Refuses the entire request fail-closed if any requested page index is out of bounds, preventing silent skips from becoming successful partial exports.

4. **Deterministic Unit Regression Suite (`WorkerResponseValidatorTest.kt`):**
   - Added `standardOutputCountSemanticsValidated`:
     - Explicit multi-box count (`{"count": 7}`) returns `StandardOutputContract(count = 7)`.
     - Zero count (`{"count": 0}`) returns `StandardOutputContract(count = 0)`.
     - Negative count (`{"count": -1}`) throws `IllegalArgumentException` fail-closed.
     - Omitted count defaults to `1`.

5. **Android Instrumented Security Regressions (`RedactionCountSecurityTest.kt`):**
   - `twoAppliedBoxesReturnTwo`: verifies returning 2 applied redactions.
   - `zeroSelectedBoxesReturnZero`: verifies returning 0 applied redactions without mutating content.
   - `invalidPageFailsWithoutPublishingPartialRedaction`: verifies nonexistent page fails closed and preserves destination.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Worker Response Unit Suite | PASS: `standardOutputCountSemanticsValidated` in `WorkerResponseValidatorTest.kt` | Gradle task log (`:pdf-ipc:testDebugUnitTest`) |
| Full IPC Unit Suite | PASS: all tests in `:pdf-ipc:testDebugUnitTest` (42s) | Gradle task log |
| Full App-Host JVM Unit Suite | PASS: 254 unit tests in `:app-host:testDebugUnitTest` | Gradle task log |
| Test APK Compilation & Dexing | PASS: `:app-host:assembleDebugAndroidTest` | Gradle task log |
| Redaction Security Regression Tests | PASS: `SearchRedactionObjectSecurityTest.kt`, `PdfRedactionEngineTest.kt` | Test suite logs |
