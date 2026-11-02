# SC-08 execution receipt

Date: 2026-10-22. Release: **HOLD**. SC-08 source implementation, streaming admission bounds, unit regression suite, and test assembly gates are complete.

## Problem and change

In `MarkdownStudioScreen.kt:88`, opening a Markdown or plain text document via `openFileLauncher` executed `stream.bufferedReader().readText()` in the host application process with no byte or decoded-character limit. An oversized document or an endless provider stream could exhaust host heap memory (inducing an OOM crash) or freeze host worker threads. Furthermore, any exception during reading was caught and logged without user-facing feedback, and unbudgeted reading occurred before editor admission.

### Resolution

1. **Streaming Admission Boundary (`MarkdownAdmission.kt`):**
   Implemented `MarkdownAdmission.readBoundedText(stream, maxBytes, maxChars, charset)`:
   - **Byte Budget:** Documented and enforced at `DEFAULT_MAX_BYTES = 2 MB` (`SecurityLimits.MAX_TEXT_BYTES`), preventing excessive I/O before string allocation.
   - **Decoded Character Budget:** Documented and enforced at `DEFAULT_MAX_CHARS = 1,000,000` characters, protecting Compose text layout engine from heap exhaustion.
   - **Pre-Allocation Enforcement:** Uses a wrapped counting stream that measures bytes read and probes boundary bytes, terminating immediately with a `SecurityException` if input exceeds the budget. Endless streams and oversized inputs are aborted in bounded time without materializing giant memory buffers.
   - **Cancellation & Closure:** Checks `currentCoroutineContext().ensureActive()` between chunks and ensures input streams are deterministically closed on success, rejection, or cancellation.
2. **UI Integration & Error Feedback (`MarkdownStudioScreen.kt`):**
   - Updated `openFileLauncher` to stream through `MarkdownAdmission.readBoundedText`.
   - On rejection or failure, existing editor text (`markdownText`) remains completely untouched.
   - User receives immediate, clear visual feedback via `viewModel.showErrorToast` explaining that the file exceeded permitted size limits.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 10 methods in `MarkdownAdmissionSecurityTest.kt` covering exact byte limit, byte plus-one, unknown length stream, multibyte UTF-8 decoding & overflow, exact character limit, char plus-one, endless stream termination, cancellation propagation, and stream closure | `app-host/src/test/java/com/pdfchemy/app/security/MarkdownAdmissionSecurityTest.kt` |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest` in 6s) | Gradle task log |
