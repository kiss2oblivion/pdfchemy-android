package com.pdfchemy.app.security

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.StagedPdf
import com.pdfchemy.app.utils.DocumentIdentity
import com.pdfchemy.app.utils.DocumentStager
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DocumentIdentitySecurityTest {

    private lateinit var app: Application
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        DocumentIdentity.clearPreferencesForTesting(app)
        tempDir = File(app.cacheDir, "doc_identity_test_${System.currentTimeMillis()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        DocumentIdentity.clearPreferencesForTesting(app)
        DocumentStager.releaseAll()
        tempDir.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun sc14_01_distinctContentWithIdenticalMetadata_producesDistinctDocumentIds() {
        // Arrange: Mock two content URIs with identical metadata (name: "invoice.pdf", size: 16 bytes),
        // but completely different byte contents.
        val mockContext = mockk<Context>()
        val mockResolver = mockk<ContentResolver>()
        val doc1Uri = Uri.parse("content://com.example.provider/docs/1")
        val doc2Uri = Uri.parse("content://com.example.provider/docs/2")

        val content1 = "CONTENT_DOC_ONE_".toByteArray(Charsets.UTF_8) // 16 bytes
        val content2 = "CONTENT_DOC_TWO_".toByteArray(Charsets.UTF_8) // 16 bytes
        assertEquals(content1.size, content2.size)
        assertFalse(content1.contentEquals(content2))

        every { mockContext.contentResolver } returns mockResolver
        every { mockResolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf("invoice.pdf", 16L))
            }
        }
        every { mockResolver.openInputStream(doc1Uri) } answers { ByteArrayInputStream(content1) }
        every { mockResolver.openInputStream(doc2Uri) } answers { ByteArrayInputStream(content2) }

        // Act
        val id1 = DocumentIdentity.computeStableId(mockContext, doc1Uri)
        val id2 = DocumentIdentity.computeStableId(mockContext, doc2Uri)

        // Assert: Under legacy logic, both would collide because "$name::$size" was identical.
        // Under V2 content-derived identity, both IDs must be completely distinct.
        assertTrue("ID 1 must have V2 prefix", id1.startsWith(DocumentIdentity.PREFIX_V2))
        assertTrue("ID 2 must have V2 prefix", id2.startsWith(DocumentIdentity.PREFIX_V2))
        assertNotEquals("Distinct content with identical metadata must produce distinct IDs", id1, id2)

        // Also verify with actual files on disk
        val file1 = File(tempDir, "contract.pdf").apply { writeBytes("CONTRACT_VERSION_A".toByteArray()) }
        val file2 = File(tempDir, "contract_clone.pdf").apply { writeBytes("CONTRACT_VERSION_B".toByteArray()) }
        assertEquals(file1.length(), file2.length())

        val fileId1 = DocumentIdentity.computeStableId(app, Uri.fromFile(file1))
        val fileId2 = DocumentIdentity.computeStableId(app, Uri.fromFile(file2))
        assertNotEquals("Distinct disk files with same length must produce distinct IDs", fileId1, fileId2)
    }

    @Test
    fun sc14_02_admittedSnapshotCapability_usesPrecomputedStagedSha256WithoutRedundantIO() {
        val testSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val fakeUri = Uri.parse("file:///data/user/0/cache/staged/snapshot_test.pdf")
        val staged = StagedPdf(uri = fakeUri, sha256 = testSha256, size = 1024L)

        // Act: Compute with explicit capability
        val idWithCapability = DocumentIdentity.computeStableId(app, fakeUri, staged)

        // Assert: Fast path returns versioned hash directly
        assertEquals(DocumentIdentity.formatV2Id(testSha256), idWithCapability)
        assertEquals(testSha256, DocumentIdentity.extractSha256(idWithCapability))

        // Also test real staged snapshot via DocumentStager
        val realFile = File(tempDir, "sample_staged.pdf").apply { writeBytes("PDF_SAMPLE_DATA_12345".toByteArray()) }
        val stagedLease = realFile.inputStream().use { stream ->
            DocumentStager.stageStreamWithLease(app, stream, "pdf")
        }
        try {
            val stagedDoc = stagedLease.document
            val resolvedId = DocumentIdentity.computeStableId(app, stagedDoc.uri)
            assertEquals(
                "Must resolve SHA-256 directly from DocumentStager registry",
                DocumentIdentity.formatV2Id(stagedDoc.sha256),
                resolvedId
            )
        } finally {
            stagedLease.close()
            DocumentStager.release(stagedLease.document)
        }
    }

    @Test
    fun sc14_03_supportedAliasesAndRenames_preserveDocumentIdentityAndPreferences() {
        val originalFile = File(tempDir, "monthly_statement.pdf").apply {
            writeBytes("PDF_CONTENT_STATEMENT_OCTOBER_2026".toByteArray())
        }
        val originalUri = Uri.fromFile(originalFile)
        val originalId = DocumentIdentity.computeStableId(app, originalUri)

        // Save preferences under original file
        DocumentIdentity.saveBookmarks(app, originalId, setOf(2, 7, 15))
        DocumentIdentity.saveReaderPosition(app, originalId, page = 4, offset = 250)
        DocumentIdentity.saveEditorPage(app, originalId, page = 3)

        // Rename file on disk to simulate rename or access via alternate file path
        val renamedFile = File(tempDir, "statement_archived.pdf")
        assertTrue(originalFile.renameTo(renamedFile))
        assertFalse(originalFile.exists())
        assertTrue(renamedFile.exists())

        val renamedUri = Uri.fromFile(renamedFile)
        val renamedId = DocumentIdentity.computeStableId(app, renamedUri)

        // Assert: Same content produces IDENTICAL document ID across renames and path aliases
        assertEquals("Renamed file with identical content must retain stable ID", originalId, renamedId)

        // Assert: Preferences are completely preserved
        assertEquals(setOf(2, 7, 15), DocumentIdentity.getSavedBookmarks(app, renamedId))
        assertEquals(Pair(4, 250), DocumentIdentity.getSavedReaderPosition(app, renamedId))
        assertEquals(3, DocumentIdentity.getSavedEditorPage(app, renamedId))
    }

    @Test
    fun sc14_04_unambiguousLegacyState_migratesToContentIdAndRetiresLegacyKeys() {
        val docName = "legacy_document.pdf"
        val docBytes = "IMMUTABLE_LEGACY_CONTENT_BYTES_XYZ".toByteArray()
        val docSize = docBytes.size.toLong()

        val legacyId = DocumentIdentity.computeLegacyId(docName, docSize)
        val prefs = app.getSharedPreferences(DocumentIdentity.PREFS_NAME, Context.MODE_PRIVATE)

        // Seed legacy preferences (pre-SC-14 state)
        prefs.edit()
            .putInt("page_$legacyId", 5)
            .putInt("offset_$legacyId", 120)
            .putStringSet("bookmarks_$legacyId", setOf("1", "3", "8"))
            .putInt("editor_page_$legacyId", 4)
            .commit()

        val file = File(tempDir, docName).apply { writeBytes(docBytes) }
        val uri = Uri.fromFile(file)

        // Act: Open document and invoke migration
        val v2DocId = DocumentIdentity.getOrMigrateDocumentId(app, uri)
        val contentSha256 = DocumentIdentity.extractSha256(v2DocId)!!

        // Assert 1: Migrated state is available under new V2 ID
        assertEquals(Pair(5, 120), DocumentIdentity.getSavedReaderPosition(app, v2DocId))
        assertEquals(setOf(1, 3, 8), DocumentIdentity.getSavedBookmarks(app, v2DocId))
        assertEquals(4, DocumentIdentity.getSavedEditorPage(app, v2DocId))

        // Assert 2: Legacy keys are atomically retired to prevent subsequent collision
        assertFalse("Legacy page key must be removed", prefs.contains("page_$legacyId"))
        assertFalse("Legacy offset key must be removed", prefs.contains("offset_$legacyId"))
        assertFalse("Legacy bookmarks key must be removed", prefs.contains("bookmarks_$legacyId"))
        assertFalse("Legacy editor page key must be removed", prefs.contains("editor_page_$legacyId"))

        // Assert 3: Exclusive binding record is created
        assertEquals(
            "Legacy binding must record owner contentSha256",
            contentSha256,
            prefs.getString("${DocumentIdentity.KEY_LEGACY_BINDING_PREFIX}$legacyId", null)
        )
    }

    @Test
    fun sc14_05_ambiguousLegacyState_refusesMigrationToDifferentContent() {
        val docName = "shared_name.pdf"
        val contentDoc1 = "CONTENT_VERSION_PRIMARY_AAA".toByteArray()
        val contentDoc2 = "CONTENT_VERSION_SECONDARY_B".toByteArray()
        assertEquals(contentDoc1.size, contentDoc2.size) // Exact same size
        val sharedSize = contentDoc1.size.toLong()

        val legacyId = DocumentIdentity.computeLegacyId(docName, sharedSize)
        val prefs = app.getSharedPreferences(DocumentIdentity.PREFS_NAME, Context.MODE_PRIVATE)

        // Step 1: Document 1 migrates the legacy state
        prefs.edit()
            .putInt("page_$legacyId", 9)
            .putInt("offset_$legacyId", 300)
            .putStringSet("bookmarks_$legacyId", setOf("4", "9"))
            .commit()

        val file1 = File(tempDir, docName).apply { writeBytes(contentDoc1) }
        val id1 = DocumentIdentity.getOrMigrateDocumentId(app, Uri.fromFile(file1))
        assertEquals(Pair(9, 300), DocumentIdentity.getSavedReaderPosition(app, id1))

        // Step 2: Document 2 (different content, same name/size) is opened
        val file2 = File(tempDir, "doc2_dir").apply { mkdirs() }
        val doc2File = File(file2, docName).apply { writeBytes(contentDoc2) }
        val id2 = DocumentIdentity.getOrMigrateDocumentId(app, Uri.fromFile(doc2File))

        // Assert: id1 and id2 are completely distinct
        assertNotEquals("IDs must be distinct", id1, id2)

        // Assert: Document 2 does NOT inherit Document 1's migrated state!
        // It receives default state (page 0, offset 0, empty bookmarks).
        assertEquals(
            "Document 2 must NOT inherit Document 1's position",
            Pair(0, 0),
            DocumentIdentity.getSavedReaderPosition(app, id2)
        )
        assertTrue(
            "Document 2 must NOT inherit Document 1's bookmarks",
            DocumentIdentity.getSavedBookmarks(app, id2).isEmpty()
        )
        assertEquals(0, DocumentIdentity.getSavedEditorPage(app, id2))

        // Document 1's state remains intact
        assertEquals(Pair(9, 300), DocumentIdentity.getSavedReaderPosition(app, id1))
        assertEquals(setOf(4, 9), DocumentIdentity.getSavedBookmarks(app, id1))
    }

    @Test
    fun sc14_06_preExistingAmbiguity_marksLegacyAmbiguousAndPreventsSilentlyAssigningState() {
        val docName = "contested.pdf"
        val docSize = 256L
        val legacyId = DocumentIdentity.computeLegacyId(docName, docSize)
        val prefs = app.getSharedPreferences(DocumentIdentity.PREFS_NAME, Context.MODE_PRIVATE)

        // Seed legacy keys
        prefs.edit()
            .putInt("page_$legacyId", 12)
            .putStringSet("bookmarks_$legacyId", setOf("12"))
            .commit()

        // Explicitly mark as ambiguous before any document open (e.g. collision detected or administrative retirement)
        DocumentIdentity.markLegacyAmbiguous(app, legacyId)

        // Legacy keys are cleared and marked ambiguous
        assertTrue(prefs.getBoolean("${DocumentIdentity.KEY_LEGACY_AMBIGUOUS_PREFIX}$legacyId", false))
        assertFalse(prefs.contains("page_$legacyId"))

        // When a document opens with that metadata:
        val file = File(tempDir, docName).apply { writeBytes(ByteArray(docSize.toInt()) { 0x42 }) }
        val docId = DocumentIdentity.getOrMigrateDocumentId(app, Uri.fromFile(file))

        // It must NOT receive the ambiguous legacy state
        assertEquals(Pair(0, 0), DocumentIdentity.getSavedReaderPosition(app, docId))
        assertTrue(DocumentIdentity.getSavedBookmarks(app, docId).isEmpty())
    }
}
