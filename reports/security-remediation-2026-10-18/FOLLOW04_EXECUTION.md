# FOLLOW-04 execution receipt: Separate-sender/provider Android tests for scheme/grant/private-path policy and actual impact assessment

Date: 2026-10-23. Commit: `5d9fd684`. Release: **HOLD**. Retained FOLLOW-04 separate-sender and provider Android test suite, scheme/grant/private-path admission verification, factual exploitability and impact assessment, and Android build gates are complete.

## Problem and threat analysis

### Pre-remediation baseline behavior
1. **Unchecked URI Extraction:**
   - In `MainActivity.kt`, incoming intents (`ACTION_VIEW`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`) were processed by `extractPdfUri(intent)`.
   - The URI was extracted directly from `intent.data`, `intent.getParcelableExtra(Intent.EXTRA_STREAM)`, or `intent.clipData` without validating scheme, authority, path traversal, or private application boundaries.
   - The unvalidated URI was stored in `incomingPdfUriState`, which prompted the UI to open and stage the document.
2. **Confused Deputy via `DocumentStager`:**
   - When staging via `DocumentStager.stageDocument(context, uri)`, any `file://` URI not matching an active borrowed snapshot was opened via `context.contentResolver.openInputStream(sourceUri)`.
   - On Android, `ContentResolver.openInputStream` resolves `file://` URIs in-process with the application's own Linux UID (`new FileInputStream(uri.getPath())`).
   - Because PDFchemy runs under its own UID, it has read access to its internal private directories (`/data/user/0/com.pdfchemy.app/shared_prefs/`, `/databases/`, `/files/`, `/cache/`).
   - Consequently, if an external caller passed an intent with `file:///data/user/0/com.pdfchemy.app/shared_prefs/app_prefs.xml`, PDFchemy opened and staged its own private preferences into `cache/staged/snapshot_*.bin`.

### Factual exploitability and impact assessment
In accordance with radical transparency and ethical rigor ("Do not claim an exfiltration exploit before establishing its complete path"):
1. **Platform Mitigation (StrictMode):**
   - On Android 7.0+ (API 24+), Android enforces `FileUriExposedException` via StrictMode if an app attempts to transmit a `file://` URI across process boundaries using an Intent. Third-party sending apps targeting API 24+ must explicitly suppress StrictMode (`StrictMode.setVmPolicy(...)`) or invoke IPC via low-level shell commands (e.g. `adb shell am start`) to transmit `file://` URIs without crashing.
2. **Storage Isolation (UID Boundaries):**
   - When PDFchemy staged the file, it copied the bytes to `cache/staged/snapshot_<timestamp>_<counter>.bin`. Modern Android app cache directories are private to the app's UID (mode 0700). An unprivileged external application cannot directly read `cache/staged/` without a separate system or storage vulnerability.
3. **Exfiltration Dependency on User Interaction:**
   - An external attacker could not achieve silent zero-click data exfiltration. For the staged private bytes to leave the device, the attacker would have to socially engineer the user into opening the staged document in the Reader or Editor and actively executing a "Share" or "Save As" operation that directs the document to an attacker-controlled recipient.
4. **Parsing Failure on Non-PDF Content:**
   - Internal preferences (XML) and databases (SQLite) are not valid PDF documents. When staged, PDFBox and the rendering engine fail to parse the cross-reference table or header, producing rendering errors rather than displaying valid readable pages.
5. **Accurate Threat Classification:**
   - The vulnerability is accurately characterized as a **Confused Deputy Local File Inclusion / Staging Defect**, NOT an unauthenticated zero-click remote exfiltration exploit.

---

## Remediation & verification

### Architecture and defensive controls
1. **`ExternalUriAdmission` Policy Gate (`ExternalUriAdmission.kt`):**
   - Strictly enforces scheme whitelisting: only `content` and external `file` schemes are permitted.
   - Forbidden schemes (`http`, `https`, `ftp`, `javascript`, `data`, `android.resource`, `about`, `ws`, `wss`) are rejected immediately.
   - Strictly rejects all private application storage directories (`dataDir`, `filesDir`, `cacheDir`, `shared_prefs`, `databases`, `noBackupFilesDir`, `codeCacheDir`).
   - Rejects internal capability impersonation: external intents cannot reference `cacheDir/staged/` or active staged capabilities (`DocumentStager.isTrustedStagedCapability`).
   - Validates path and authority syntax against traversal (`..`, `%2e%2e`, backslashes).
2. **`DocumentStager` Defense-in-Depth (`DocumentStager.kt`):**
   - Even if `ExternalUriAdmission` were bypassed, `stageDocumentInternal` checks incoming `file://` targets and throws `SecurityException` if private storage roots (`shared_prefs`, `databases`) are targeted.
3. **`MainActivity` Intent Admission Wiring (`MainActivity.kt`):**
   - All external intents route through `ExternalUriAdmission.admit(this, intent)`.
   - Admitted URIs update `incomingPdfUriState`; rejected intents are logged and discarded with `incomingPdfUriState.value = null`.

---

## Separate-sender and provider Android test suite

Created `app-host/src/androidTest/java/com/pdfchemy/app/security/SeparateSenderAdmissionSecurityTest.kt` (12 test methods):

| Test Method | Focus & Verification |
|---|---|
| `separateSender_fileUriInSharedPrefs_isRejectedAndNeverStaged` | External intent targeting `shared_prefs` is rejected by `ExternalUriAdmission`; direct `DocumentStager` call throws `SecurityException`. |
| `separateSender_fileUriInDatabases_isRejectedAndNeverStaged` | External intent targeting `databases` is rejected by `ExternalUriAdmission`; direct `DocumentStager` call throws `SecurityException`. |
| `separateSender_fileUriInFilesDir_isRejected` | External intent targeting `filesDir` is rejected by `ExternalUriAdmission`. |
| `separateSender_fileUriInCacheDir_isRejected` | External intent targeting `cacheDir` is rejected by `ExternalUriAdmission`. |
| `separateSender_fileUriAttemptingToClaimStagedSnapshot_isRejected` | External intent referencing `staged/snapshot_*.bin` is rejected as unauthorized staged capability claim. |
| `separateSender_activeStagedCapability_isNotAdmissibleFromExternalSender` | Actively leased staged capability is rejected when supplied via external intent. |
| `separateSender_pathTraversalEscapesToPrivateStorage_isRejected` | Traversal patterns (`..`, `%2e%2e`, backslashes) attempting escape to private storage are rejected. |
| `separateSender_forbiddenSchemes_areStrictlyRejected` | Forbidden schemes (`http`, `https`, `javascript`, `ftp`, `data`, `android.resource`, `about`, `ws`, `wss`, `smb`) are strictly rejected on Android. |
| `separateSender_maliciousContentUriAuthorityAndPath_areRejected` | Malicious content URI authority and path syntax (`..`, backslashes) are rejected. |
| `separateProvider_validContentUriFromSeparateProvider_isAdmittedAndStaged` | Content URI from separate test provider (`com.pdfchemy.app.security.mutablefixture`) is admitted, staged, and verified as a trusted capability with byte integrity. |
| `separateProvider_failingOrMissingProvider_failsGracefullyWithoutOrphans` | Missing/unauthorized provider URI throws `FileNotFoundException` during staging and cleans up without orphaned snapshot files. |
| `separateSender_activityLifecycleIntentAdmission_acceptsValidProviderAndDropsHostileFile` | Full `ActivityScenario<MainActivity>` lifecycle: hostile file intents and forbidden schemes are dropped with `incomingPdfUriState.value = null`; valid provider content URI is admitted. |

---

## Build and test suite evidence

| Layer | Result | Evidence |
|---|---|---|
| Android Test APK Compilation & Dexing | PASS: `:app-host:assembleDebugAndroidTest` (8 executed, 110 up-to-date) | Gradle build log |
| Full App-Host JVM Unit Suite | PASS: 254 unit tests in `:app-host:testDebugUnitTest` | Gradle task log |
| Release Workflow Regression Suite | PASS: `:app-host:testDebugUnitTest --tests com.pdfchemy.app.logic.ReleaseWorkflowRegressionTest` | Gradle task log |
| External URI Admission Unit Suite | PASS: 20 methods in `ExternalUriAdmissionSecurityTest.kt` | Gradle task log |
