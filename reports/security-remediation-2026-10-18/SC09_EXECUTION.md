# SC-09 execution receipt

Date: 2026-10-22. Release: **HOLD**. SC-09 source implementation, staging provenance tracking, fail-closed same-document rejection policy, unit regression suite, and test assembly gates are complete.

## Problem and change

PDF export and modification operations (such as compression in `PdfCompressor`, engine operations via `PdfGateway`, and view model workflows in `MainViewModel`) lacked a unified pre-admission source preservation policy. If a user inadvertently selected their original source document (or a canonical file path alias or staged snapshot capability) as the destination target for an operation, opening the destination stream with truncate mode (`"wt"`) or deleting pending incomplete outputs would destroy or overwrite the source document before completion.

### Resolution

1. **Source Preservation Policy (`SourcePreservationPolicy.kt`):**
   Implemented `SourcePreservationPolicy` providing `isSameDocument`, `requireSeparateDestination`, and `requireSeparateDestinations`:
   - **Direct & Scheme Identity:** Matches identical URIs, string representations, and normalized schemes.
   - **Staged Capability Provenance:** Queries `DocumentStager.originalSourceFor(uri)` to trace staged snapshot files back to their original user-selected source documents symmetrically.
   - **File Path Aliases:** Canonicalizes file URIs on disk (`File.canonicalPath`), resolving relative segments (e.g. `./`, `../`) and symbolic links.
   - **DocumentsContract SAF Identity:** Matches content URIs within the same provider authority and document ID via `DocumentsContract.getDocumentId`.
   - **Cycle Protection:** Employs a `(sourceUri, destUri)` pair visited set to guarantee cycle safety during recursive capability resolution.

2. **Staging Provenance Retention (`DocumentStager.kt`):**
   - Added `originalSourceUris` registry in `DocumentStager`.
   - Maps staging snapshot paths and canonical aliases back to the original source `Uri`.
   - Cleans up provenance entries on document release/removal.
   - Exposes `originalSourceFor(uri)` and `originalSourceFor(document)`.

3. **Engine & Compressor Guardrails (`PdfCompressor.kt`, `PdfGateway.kt`):**
   - In `PdfCompressor.compressPdf`: Calls `SourcePreservationPolicy.requireSeparateDestination(context, sourceUri, destUri)` at entry inside the `try` block before staging documents or creating temporary files. Fails closed with `Result.failure(SecurityException)` and leaves the source file completely untouched.
   - In `PdfGateway.request`: Invokes `SourcePreservationPolicy.requireSeparateDestinations(context, sources, targets)` before staging inputs or initializing `HostOutputTransaction`.

4. **ViewModel Pre-Admission Guardrails (`MainViewModel.kt`):**
   - In `compressPdf` and `mergePdfs`: Validates `SourcePreservationPolicy.isSameDocument` before adding destinations to `addPendingOutputUri` or dispatching background coroutines. Prevents source files from ever being registered as pending outputs eligible for failure/cancellation cleanup.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 16 methods in `DestinationIntegritySecurityTest.kt`, including direct compression self-overwrite rejection, unchanged source bytes verification, canonical file path alias rejection, symmetric staged capability provenance rejection, merge multiple-source self-overwrite rejection, and distinct destination permission | `app-host/src/test/java/com/pdfchemy/app/security/DestinationIntegritySecurityTest.kt` |
| Release Workflow Regressions | PASS (`ReleaseWorkflowRegressionTest` in 13s) | Gradle task log |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest` in 16s) | Gradle task log |
