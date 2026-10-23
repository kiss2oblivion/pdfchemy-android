# FOLLOW-03 execution receipt: External VIEW/SEND URI admission separately from trusted internal staged capabilities

Date: 2026-10-23. Commit: `bd747cd9`. Release: **HOLD**. Retained FOLLOW-03 external URI admission policy, private application path defense, internal staged capability separation, deterministic test suite, and Android build gates are complete.

## Problem and change

Previously:
1. **Unchecked External Intent URI Ingestion:**
   - In `MainActivity.kt`, `extractPdfUri` extracted candidate URIs from `ACTION_VIEW`, `ACTION_SEND`, and `ACTION_SEND_MULTIPLE` intents without verifying scheme, authority, path traversal, or private storage boundaries.
   - The unvalidated URI was directly stored in `incomingPdfUriState` and dispatched to `DocumentStager.stageDocumentCancellable`.
2. **Confused Deputy & Local File Inclusion Risk via Private File Paths:**
   - In `DocumentStager.kt`, if an incoming `file://` URI did not match a borrowed snapshot (`findBorrowedSnapshot`), `DocumentStager` called `context.contentResolver.openInputStream(sourceUri)`.
   - On Android, `ContentResolver.openInputStream` opens `file://` URIs with the app's own process UID.
   - If an external caller sent an intent with `file:///data/user/0/com.pdfchemy.app/shared_prefs/app_prefs.xml` or `databases/`, PDFchemy opened and staged its own private credentials, preferences, or databases as a readable PDF document.
3. **Ambiguity Between External Admissions and Internal Staged Capabilities:**
   - An external caller passing `file:///.../cache/staged/snapshot_*.bin` could claim or borrow internal staged document capabilities.
   - There was no distinct API separating external intent admission from internal capability validation.

### Resolution

1. **`ExternalUriAdmission` Policy Gate (`ExternalUriAdmission.kt`):**
   - Created `com.pdfchemy.app.security.ExternalUriAdmission` to govern all incoming external VIEW/SEND intents.
   - Type-safe `AdmissionResult` sealed hierarchy:
     - `Admitted(uri)`: Admitted external document URI safe for staging and processing.
     - `Rejected(reason, rejectedUri)`: Explicit rejection reason logged and dropped.
   - **Scheme Boundary Policy:**
     - Admitted schemes: `content` and legitimate external `file`.
     - Strictly rejected schemes: `http`, `https`, `ftp`, `javascript`, `data`, `android.resource`, `about`, `ws`, `wss`.
   - **Strict Private Application Storage Guard:**
     - For `file://` URIs, canonical paths are resolved and checked against all application private directories:
       - `context.dataDir` / `context.applicationInfo.dataDir`
       - `context.filesDir`
       - `context.cacheDir`
       - `context.noBackupFilesDir`
       - `context.codeCacheDir`
       - `shared_prefs` and `databases`
       - Device-protected storage equivalents and `/data/data/<pkg>`, `/data/user/<id>/<pkg>`.
     - If an external intent references ANY private app directory, it is rejected immediately: `AdmissionResult.Rejected("External intent cannot access private application storage: $path")`.
   - **Internal Staged Capability Claim Rejection:**
     - External callers cannot claim internal staged snapshots: if a file is in `cacheDir/staged` or starts with `snapshot_`, or satisfies `DocumentStager.isTrustedStagedCapability`, it is rejected for external intent admission: `AdmissionResult.Rejected("External intent cannot claim internal staged capability: $uri")`.
   - **Traversal & Syntax Guards:**
     - Rejects path traversal sequences (`..`, `%2e%2e`, backslashes) in `file://` and `content://` paths and authorities.

2. **`DocumentStager` Defense-in-Depth (`DocumentStager.kt`):**
   - Added `isTrustedStagedCapability(context, uri)`: strictly checks that the capability is a registered, un-tampered snapshot within `context.cacheDir/staged/` matching the recorded byte length.
   - Added `isPrivateAppPath(context, file, includeCacheAndFiles)`: checks canonical file paths against private application roots.
   - In `stageDocumentInternal`, added secondary defensive check: if `sourceUri.scheme == "file"` and targets private app storage (`shared_prefs`, `databases`), throws `SecurityException`.

3. **`MainActivity.kt` Wiring:**
   - Refactored `handleIncomingIntent` to route incoming intents through `ExternalUriAdmission.admit(this, intent)`.
   - Admitted URIs are passed to `incomingPdfUriState`; rejected intents are logged and discarded without invoking `DocumentStager`.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 20 methods in `ExternalUriAdmissionSecurityTest.kt`: Null intent rejected (`testAdmit_nullIntent_returnsRejected`); Unsupported action rejected (`testAdmit_unsupportedAction_returnsRejected`); ACTION_VIEW with content URI admitted (`testAdmit_viewAction_withValidContentUri_returnsAdmitted`); ACTION_SEND with content URI admitted (`testAdmit_sendAction_withValidContentUri_returnsAdmitted`); ACTION_SEND_MULTIPLE admitted (`testAdmit_sendMultipleAction_withValidContentUri_returnsAdmitted`); ClipData URI admitted (`testAdmit_clipDataUri_returnsAdmitted`); Forbidden schemes rejected (`testAdmit_forbiddenSchemes_rejected`); Null or empty URI rejected (`testAdmit_nullOrEmptyUri_rejected`); File URI in shared_prefs rejected (`testAdmit_fileUri_inSharedPrefs_strictlyRejected`); File URI in databases rejected (`testAdmit_fileUri_inDatabases_strictlyRejected`); File URI in filesDir rejected (`testAdmit_fileUri_inFilesDir_strictlyRejected`); File URI in cacheDir rejected (`testAdmit_fileUri_inCacheDir_strictlyRejected`); File URI attempting to claim staged snapshot rejected (`testAdmit_fileUri_attemptingToClaimStagedSnapshot_strictlyRejected`); Traversal to private storage rejected (`testAdmit_fileUri_withTraversalToPrivateStorage_strictlyRejected`); File URI on external storage admitted (`testAdmit_fileUri_onExternalStorage_returnsAdmitted`); Content URI with malicious authority rejected (`testAdmit_contentUri_maliciousAuthority_rejected`); Trusted capability returns true for staged snapshot (`testTrustedStagedCapability_validStagedSnapshot_returnsTrue`); Unregistered file returns false (`testTrustedStagedCapability_unregisteredFile_returnsFalse`); Tampered size returns false (`testTrustedStagedCapability_tamperedSize_returnsFalse`); DocumentStager refuses to stage shared_prefs (`testDocumentStager_refusesToStageSharedPrefs`) | `app-host/src/test/java/com/pdfchemy/app/security/ExternalUriAdmissionSecurityTest.kt` |
| Full App-Host Unit Suite | PASS: 254 unit tests in `:app-host:testDebugUnitTest` | Gradle task log |
| Staging Transfer Security Suite | PASS: 6 unit tests in `StagingTransferSecurityTest.kt` | Gradle task log |
| Destination Integrity Suite | PASS: 25 unit tests in `DestinationIntegritySecurityTest.kt` | Gradle task log |
| Android Test APK Assembly | PASS: `:app-host:assembleDebugAndroidTest` | Gradle task log |
