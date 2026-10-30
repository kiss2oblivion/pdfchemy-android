package com.pdfchemy.app.logic

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.ui.MainViewModel
import com.pdfchemy.app.utils.DocumentIdentity
import com.pdfchemy.app.utils.DocumentLoadFailureKind
import com.pdfchemy.app.utils.DocumentStager
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderHistoryAndPositionTest {

    @Test
    fun stableIdRemainsIdenticalAcrossDifferentStagedSnapshotsOfSameDocument() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceDir1 = File(context.cacheDir, "origin_a").apply { mkdirs() }
        val sourceDir2 = File(context.cacheDir, "origin_b").apply { mkdirs() }
        val pdfBytes = "%PDF-1.4\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF".toByteArray()

        val fileA = File(sourceDir1, "Quarterly_Report.pdf").apply { writeBytes(pdfBytes) }
        val fileB = File(sourceDir2, "Quarterly_Report.pdf").apply { writeBytes(pdfBytes) }

        val stagedA = DocumentStager.stageDocument(context, Uri.fromFile(fileA))
        val stagedB = DocumentStager.stageDocument(context, Uri.fromFile(fileB))

        try {
            val idA = DocumentIdentity.computeStableId(context, stagedA.uri)
            val idB = DocumentIdentity.computeStableId(context, stagedB.uri)
            assertEquals(
                "Staged snapshots of the same named and sized PDF must resolve to the same stable docId",
                idA,
                idB
            )
        } finally {
            stagedA.uri.path?.let { File(it).setWritable(true) }
            stagedB.uri.path?.let { File(it).setWritable(true) }
            DocumentStager.releaseAll()
            fileA.delete()
            fileB.delete()
        }
    }

    @Test
    fun saveAndRestoreReaderPositionPreservesPageAndOffsetAndClampsOutOfRangePages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val docId = "test_doc_hash_01"

        DocumentIdentity.saveReaderPosition(context, docId, pageIndex = 7, scrollOffsetPx = 245)

        val restoredValid = DocumentIdentity.getSavedReaderPosition(context, docId, totalPages = 12)
        assertEquals(7, restoredValid.pageIndex)
        assertEquals(245, restoredValid.scrollOffsetPx)

        // If the document was later shortened to 5 pages, position clamps safely to last valid page (index 4)
        val restoredClamped = DocumentIdentity.getSavedReaderPosition(context, docId, totalPages = 5)
        assertEquals(4, restoredClamped.pageIndex)
        assertEquals(0, restoredClamped.scrollOffsetPx)
    }

    @Test
    fun revokedAccessAndMissingFilesAreClassifiedCorrectlyAndRemovedFromHistory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("history_enabled", true)
            .commit()

        val repo = HistoryRepository(app)
        repo.clearHistory()
        val revokedUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ARevoked.pdf")
        val validUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AValid.pdf")
        repo.addHistoryItem(revokedUri, "Revoked.pdf", "Read PDF")
        repo.addHistoryItem(validUri, "Valid.pdf", "Read PDF")

        val viewModel = MainViewModel(app)
        assertEquals(2, viewModel.historyList.value.size)

        assertEquals(
            DocumentLoadFailureKind.REVOKED_OR_INACCESSIBLE,
            DocumentIdentity.classifyLoadException(SecurityException("Permission Denial: opening provider"))
        )
        assertEquals(
            DocumentLoadFailureKind.REVOKED_OR_INACCESSIBLE,
            DocumentIdentity.classifyLoadException(FileNotFoundException("No such file or directory"))
        )
        assertEquals(
            DocumentLoadFailureKind.DAMAGED,
            DocumentIdentity.classifyLoadException(IllegalStateException("Corrupt xref table"))
        )

        viewModel.removeHistoryItem(revokedUri.toString())
        val remaining = viewModel.historyList.value
        assertEquals(1, remaining.size)
        assertEquals(validUri.toString(), remaining.first().uriString)
    }

    @Test
    fun ephemeralStagedSnapshotPathsAreNotPersistedInLongTermHistory() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("history_enabled", true)
            .commit()

        val repo = HistoryRepository(app)
        repo.clearHistory()
        val viewModel = MainViewModel(app)

        val ephemeralStagedUri = Uri.parse("file:///data/user/0/com.pdfchemy.app/cache/staged_documents/snapshot_999.pdf")
        viewModel.recordReaderHistory(ephemeralStagedUri, "Sensitive.pdf")
        assertTrue("Ephemeral staged snapshot paths must not be persisted into recent history", viewModel.historyList.value.isEmpty())

        val persistentContentUri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        viewModel.recordReaderHistory(persistentContentUri, "Manual.pdf")
        assertFalse(viewModel.historyList.value.isEmpty())
        assertEquals(persistentContentUri.toString(), viewModel.historyList.value.first().uriString)
    }
}

