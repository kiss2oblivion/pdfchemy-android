# SC-12 execution receipt

Date: 2026-10-22. Release: **HOLD**. SC-12 source implementation, test assembly, lint, and verification gates are complete.

## Problem and change

In `PdfReaderScreen.kt:420`, the Reader share action invoked `DocumentActions.share(context, selectedPdfUri)`. Because `selectedPdfUri` was staged into internal private cache storage (`cache/staged/snapshot_*.pdf`), which was intentionally excluded from `file_paths.xml` for staging isolation, calling `FileProvider.getUriForFile` threw `IllegalArgumentException: Failed to find configured root that contains ...`. Furthermore, even if staged storage were exposed, closing the Reader session or navigating away evicted the staging lease and deleted the underlying snapshot before external recipient apps could complete reading.

### Resolution

1. **Bounded Share Capability & Isolated Export Storage:**
   Implemented `DocumentActions.getShareableContentUri(context, uri, customDisplayName)`.
   - If `uri.scheme != "file"`, returns `uri` directly.
   - If `uri.scheme == "file"` and the file is already within an exposed `FileProvider` root (e.g. `cache/exports`), `FileProvider` resolves it directly without redundant copies.
   - If the file is within unexposed storage (e.g. `cache/staged`), a bounded, sanitized copy is staged to `cache/exports/shared_${System.currentTimeMillis()}_${cleanName}`.
   - Automatic FIFO pruning (`pruneOldShareCopies`) bounds retained share copies to at most 5 items, preventing disk accumulation.
   - Returns a valid, secure `FileProvider` content URI with `FLAG_GRANT_READ_URI_PERMISSION`.
2. **Unified Action Integration:**
   - Updated `DocumentActions.share` and `DocumentActions.view` to resolve through `getShareableContentUri`.
   - Updated `ShareUtil.shareFiles` to resolve file URIs through `DocumentActions.getShareableContentUri`.
   - Updated `PdfReaderScreen.kt:420` to supply `fileName` to `DocumentActions.share`.
3. **Cross-Platform Path Normalization:**
   - Normalized XML directory paths in `app-host/src/main/res/xml/file_paths.xml`.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Native Instrumentation Test Suite | PASS: `ReaderShareSecurityTest.kt` added to `androidTest/com/pdfchemy/app/security/` covering staging snapshot resolution, exact byte matching, post-navigation/release read survival, direct exports reuse, and content URI pass-through | `app-host/src/androidTest/java/com/pdfchemy/app/security/ReaderShareSecurityTest.kt` |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest` in 31s) | Gradle task log |
| Host JVM Architecture & Unit Gates | PASS (`ReleaseWorkflowRegressionTest` and full unit suite pass) | Gradle test report |
| Static Lint & Security Audit | PASS (`lintDebug` passed in 4m 12s, zero errors) | `app-host/build/reports/lint-results-debug.html` |
