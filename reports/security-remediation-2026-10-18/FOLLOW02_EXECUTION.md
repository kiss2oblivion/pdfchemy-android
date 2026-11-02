# FOLLOW-02 execution receipt: Single/multi picker provider failures and cancellation diagnosis with Vanguard on/off (RA-03)

Date: 2026-10-23. Commit: `162ca15c`. Release: **HOLD**. Retained RA-03 / FOLLOW-02 picker provider error isolation, cancellation preservation, Vanguard threat diagnosis segregation, deterministic test suite, and Android build gates are complete.

## Problem and change

Previously in `VanguardPicker.kt`:
1. **False Malware Threat Diagnostics on Provider Failure:**
   - In `rememberVanguardPdfPicker`: when Vanguard was disabled (`isVanguardEnabled = false`), staging failures caught general exceptions into `showBlockedDialog = true`:
     ```kotlin
     scope.launch {
         try {
             val stagedUri = DocumentStager.stageForProcessing(context, uri)
             onPdfSelected(stagedUri)
         } catch (e: Exception) {
             showBlockedDialog = true
         }
     }
     ```
     If a content provider failed (e.g. `IOException`, SAF permission revocation, or network provider disconnect), the app falsely displayed the alarming "Vanguard Blocked Threat: This document contains executable code or a malicious file signature..." dialog. This accused clean documents of containing malware when Vanguard was disabled and the root cause was a mundane provider error.
   - In `rememberVanguardMultiplePdfPicker`: when Vanguard was disabled, staging errors identically triggered `catch (_: Exception) { stagedUris.forEach { ... }; showBlockedDialog = true }`.
2. **Cancellation Swallowing & Misattribution:**
   - In multi-picker staging loops, generic `catch (e: Exception)` caught `CancellationException`, suppressing coroutine cancellation and displaying provider error dialogs instead of terminating cleanly.
3. **Partial Staged File Leaks on Multi-Picker Abortion:**
   - If user cancellation or a provider error occurred while staging item N of a batch, previously staged items (1 to N-1) were not reliably cleaned up across all error branches.

### Resolution

1. **`VanguardPickerProcessor` Logic Architecture (`VanguardPickerProcessor.kt`):**
   - Created a standalone, testable picker processing engine in `com.pdfchemy.app.logic`.
   - Defined type-safe `SinglePickerOutcome` and `MultiPickerOutcome` sealed hierarchies:
     - `Clean(stagedUri / stagedUris)`: File(s) staged and verified safe.
     - `BlockedThreat(threatResult)`: Executable malware or dangerous payload confirmed by Vanguard when enabled.
     - `ProviderError(throwable)`: SAF or I/O failure from the underlying content provider.
     - `Encrypted(uri / uris)`: Password-protected document requiring user unlock.
     - `Cancelled`: Processing cancelled by user or parent scope.
   - **Strict Threat Diagnosis Segregation:**
     - When Vanguard is disabled (`isVanguardEnabled = false`), provider and staging exceptions produce `ProviderError`, NEVER `BlockedThreat`.
     - When Vanguard is enabled, `BlockedThreat` is returned ONLY if `threatChecker` explicitly returns a `VanguardThreatResult.Threat`. Any I/O or staging error before or during evaluation produces `ProviderError`.
   - **Cancellation Preservation:**
     - `CancellationException` is caught specifically to trigger cleanup and either returned as `Outcome.Cancelled` or rethrown.
   - **Transactional Staging Cleanup:**
     - In `processMultiple`, if processing fails, cancels, or encounters a threat on item N, all previously staged items (1 through N-1) are immediately deleted via `releaser`.

2. **`VanguardPicker.kt` Integration:**
   - Refactored `rememberVanguardPdfPicker` and `rememberVanguardMultiplePdfPicker` to delegate staging and threat verification to `VanguardPickerProcessor`.
   - Mapped processor outcomes to UI states:
     - `Clean` -> invokes `onPdfSelected` / `onPdfsSelected`.
     - `BlockedThreat` -> sets `showBlockedDialog = true` and `lastBlockedThreat = threat`.
     - `ProviderError` -> sets `showProviderErrorDialog = true`.
     - `Encrypted` -> sets `showEncryptedDialog = true`.
     - `Cancelled` -> drops operation cleanly with zero UI dialog noise.

3. **Audio Publication Latency Hardening:**
   - In `AudioOutputPublisher.kt`, switched `watcher` coroutine launch to `CoroutineStart.UNDISPATCHED` to eliminate thread-pool dispatch delay during cancellation stream closure.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 11 methods in `VanguardPickerSecurityTest.kt`: Single picker clean pass (`testSinglePicker_cleanFile_withVanguardEnabled_returnsClean`); Single picker provider error when Vanguard OFF returns ProviderError, NOT BlockedThreat (`testSinglePicker_providerError_withVanguardDisabled_returnsProviderErrorNotThreat`); Single picker provider error when Vanguard ON returns ProviderError, NOT BlockedThreat (`testSinglePicker_providerError_withVanguardEnabled_returnsProviderErrorNotThreat`); Single picker executable threat returns BlockedThreat (`testSinglePicker_executableThreat_returnsBlockedThreat`); Single picker encrypted PDF produces Encrypted (`testSinglePicker_encryptedPdf_returnsEncrypted`); Single picker cancellation cleans up and returns Cancelled (`testSinglePicker_cancellation_cleansUpAndReturnsCancelled`); Multi picker clean pass (`testMultiPicker_cleanFiles_returnsCleanWithAllStagedUris`); Multi picker provider error when Vanguard OFF returns ProviderError and cleans previous items (`testMultiPicker_providerErrorOnSecondItem_withVanguardDisabled_cleansPreviousAndReturnsProviderError`); Multi picker threat on second item cleans previous items and returns BlockedThreat (`testMultiPicker_threatOnSecondItem_cleansPreviousAndReturnsBlockedThreat`); Multi picker cancellation on second item cleans previous items and returns Cancelled (`testMultiPicker_cancellationOnSecondItem_cleansPreviousAndReturnsCancelled`); Multi picker encrypted item returns Encrypted (`testMultiPicker_encryptedFile_returnsEncrypted`) | `app-host/src/test/java/com/pdfchemy/app/security/VanguardPickerSecurityTest.kt` |
| Full App-Host Unit Suite | PASS: 234 unit tests in `:app-host:testDebugUnitTest` | Gradle task log |
| Publication Cancellation Suite | PASS: 5 unit tests in `PublicationCancellationSecurityTest.kt` | Gradle task log |
| Android Test APK Assembly | PASS: `:app-host:assembleDebugAndroidTest` | Gradle task log |
