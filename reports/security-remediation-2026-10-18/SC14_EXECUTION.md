# SC-14 execution receipt: Content-derived document identity, capability policy, and safe state migration

Date: 2026-10-23. Release: **HOLD**. Retained SC-14 content/capability document identity, alias and rename stability, atomic legacy state migration, ambiguous legacy collision rejection, deterministic test suite, and Android build gates are complete.

## Problem and change

Previously, host document identity was computed purely from volatile metadata:
1. **Metadata Collision Defect:** `DocumentIdentity.computeStableId` hashed `"$name::$size"`. Distinct documents with identical display names (e.g. `document.pdf`, `report.pdf`, `invoice.pdf`) and matching byte counts produced identical SHA-256 IDs.
2. **Preference Key Collision & State Leakage:** Reading bookmarks (`bookmarks_$docId`), remembered page (`page_$docId`), scroll offset (`offset_$docId`), and editor page index (`editor_page_$docId`) in `reader_prefs` were shared across completely distinct documents that happened to share the same file name and size.
3. **Ambiguous Legacy State:** Opening a newly created or imported document with a common name could inherit another document's bookmarks or scroll position.

### Resolution

1. **Content and Capability Identity Policy (`DocumentIdentity.kt`):**
   - Anchored document identity in the document's cryptographic content SHA-256 hash, namespaced under the versioned prefix `doc_v2_`.
   - **Admitted Snapshot Capability:** Leverages the admitted `StagedPdf` capability pre-computed during input staging (`DocumentStager.stagedFor(uri)?.sha256` or `staged.sha256`) for O(1) instantaneous resolution without redundant disk/provider I/O.
   - **Direct Stream Hashing:** For unstaged content or file URIs, opens the stream and computes the SHA-256 digest in 64KB buffers.
   - **Cross-Platform Path Parsing (`FileUtils.kt`):** Enhanced `FileUtils.getFileName` and `DocumentIdentity.computeLegacyId` to strip both forward (`/`) and backward (`\`) directory delimiters, ensuring identical identity across POSIX, Windows host environments, and Android runtimes.

2. **Rename and Alias Stability:**
   - Because identity is anchored in cryptographic content rather than file path or display name, renaming a file (e.g., `statement.pdf` to `statement_archived.pdf`) or accessing it via content URI aliases produces the exact same versioned document ID (`doc_v2_${sha256}`).
   - All bookmarks, reading positions, and editor page indices are seamlessly preserved across renames and aliases.

3. **Safe State Versioning & Ambiguity-Guarded Legacy Migration:**
   - New state is written exclusively to versioned keys (`page_doc_v2_${sha256}`, `bookmarks_doc_v2_${sha256}`, `offset_doc_v2_${sha256}`, `editor_page_doc_v2_${sha256}`).
   - **Atomic Single-Claim Migration:** When a document opens without V2 state, `getOrMigrateDocumentId` checks for legacy metadata-only keys (`page_$legacyId`, etc.). If present and not previously claimed:
     - Copies legacy state to the new versioned V2 keys.
     - Atomically deletes the legacy keys from SharedPreferences.
     - Records an exclusive binding: `legacy_binding_$legacyId = contentSha256`.
   - **Ambiguity & Collision Rejection:** If another document with the same metadata tuple ($name::$size) but different content arrives:
     - It checks `legacy_binding_$legacyId`.
     - Detects that the metadata tuple was already claimed by a different content hash.
     - Refuses migration, guaranteeing that ambiguous legacy state is NEVER silently assigned to another document.
     - Starts with clean default state (page 0, no bookmarks).
   - **Administrative Retirement:** `markLegacyAmbiguous(context, legacyId)` enables explicitly invalidating legacy keys if collisions or ambiguity are detected before open.

## Verification

| Layer | Result | Evidence |
|---|---|---|
| Unit Regression Suite | PASS: 6 methods in `DocumentIdentitySecurityTest.kt`: distinct content with identical metadata produces distinct IDs (sc14_01); admitted snapshot capability uses pre-computed SHA-256 without redundant I/O (sc14_02); supported aliases and renames preserve identity and saved preferences (sc14_03); unambiguous legacy state migrates to content ID and retires legacy keys atomically (sc14_04); ambiguous legacy state refuses migration to different content (sc14_05); pre-existing ambiguity marks legacy ambiguous and prevents assigning state (sc14_06) | `app-host/src/test/java/com/pdfchemy/app/security/DocumentIdentitySecurityTest.kt` |
| Publication Cancellation Suite | PASS: 5 methods in `PublicationCancellationSecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| Destination Integrity Suite | PASS: 25 methods in `DestinationIntegritySecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| Staging Synchronization Suite | PASS: 5 methods in `StagingSynchronizationSecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| Staging Transfer Suite | PASS: 6 methods in `StagingTransferSecurityTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| FileUtils Cross-Platform Suite | PASS: 2 methods in `FileUtilsTest.kt` | Gradle task log (`:app-host:testDebugUnitTest`) |
| IPC Module Unit Suite | PASS: 8 methods in `DocumentStagerRetentionTest.kt` | Gradle task log (`:pdf-ipc:testDebugUnitTest`) |
| Test APK Assembly & Dexing | PASS (`assembleDebugAndroidTest`) | Gradle task log |
| Full App-Host Security Suite | PASS: 76 tests in `:app-host:testDebugUnitTest` | Gradle task log |
