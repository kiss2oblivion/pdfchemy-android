package com.pdfchemy.app.logic

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.ui.MainViewModel
import com.pdfchemy.app.Screen
import com.pdfchemy.app.readerAwareScreenSaver
import com.pdfchemy.app.utils.DocumentStager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderSessionLeaseTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val model = MainViewModel(app).also { store.put("reader-lease", it) }
    private val documents = mutableListOf<DocumentStager.StagedLease>()
    private fun document(value: String) = DocumentStager.stageStreamWithLease(app,
        "%PDF-1.4\n$value\n%%EOF".byteInputStream(), "pdf").also(documents::add)
    private fun file(lease: DocumentStager.StagedLease) = File(lease.document.uri.path!!)
    private fun saveAndRestore(screen: Screen): Screen {
        val saver = readerAwareScreenSaver(model)
        val scope = SaverScope { true }
        return requireNotNull(saver.restore(requireNotNull(with(saver) { scope.save(screen) })))
    }

    @After fun cleanup() {
        store.clear()
        documents.forEach { it.close(); DocumentStager.release(it.document) }
    }

    @Test fun uiDisposalDoesNotRetireTheSurvivingReaderSession() {
        val ui = document("first")
        val expected = file(ui).readBytes()
        model.readerSourceUri = ui.document.uri
        DocumentStager.release(ui.document)
        ui.close()
        assertTrue(file(ui).exists())
        assertArrayEquals(expected, file(ui).readBytes())
        model.exitReader()
        assertFalse(file(ui).exists())
        assertNull(model.readerSourceUri)
    }

    @Test fun replacingTheDocumentAcquiresTheNextLeaseBeforeRetiringThePrevious() {
        val first = document("first"); val second = document("second")
        model.readerSourceUri = first.document.uri
        DocumentStager.release(first.document); first.close()
        assertTrue(file(first).exists())
        model.readerSourceUri = second.document.uri
        assertFalse(file(first).exists())
        DocumentStager.release(second.document); second.close()
        assertTrue(file(second).exists())
        model.exitReader()
        assertFalse(file(second).exists())
    }

    @Test fun clearingTheViewModelClosesItsLastReaderLease() {
        val ui = document("model cleanup")
        model.readerSourceUri = ui.document.uri
        DocumentStager.release(ui.document); ui.close()
        assertTrue(file(ui).exists())
        store.clear()
        assertFalse(file(ui).exists())
    }

    @Test fun savingARecoveredReaderKeepsItsOwnedSnapshotAndSessionPosition() {
        val requested = File.createTempFile("reader-request-", ".pdf", app.cacheDir)
        val requestedUri = Uri.fromFile(requested)
        try {
            requested.writeText("%PDF-1.4\nrecovered reader\n%%EOF")
            val snapshot = DocumentStager.stageDocumentWithLease(app, requestedUri).also(documents::add)
            val expected = file(snapshot).readBytes()
            model.readerSourceUri = snapshot.document.uri
            model.readerPositions[requestedUri.toString()] = MainViewModel.ReaderPosition(2, 73)
            assertTrue(requested.delete())
            assertEquals(Screen.PdfReader(snapshot.document.uri), saveAndRestore(Screen.PdfReader(requestedUri)))
            assertEquals(MainViewModel.ReaderPosition(2, 73), model.readerPositions[snapshot.document.uri.toString()])
            assertArrayEquals(expected, file(snapshot).readBytes())
            assertEquals(Screen.PdfReader(snapshot.document.uri), saveAndRestore(Screen.PdfReader()))
        } finally { requested.delete() }
    }

    @Test fun savingRoutesNeverSubstitutesAnUnrelatedOrUnownedReaderDocument() {
        val snapshot = document("owned")
        model.readerSourceUri = snapshot.document.uri
        val unrelated = Screen.PdfReader(Uri.parse("content://provider/document/new-request.pdf"))
        assertEquals(unrelated, saveAndRestore(unrelated))
        for (screen in listOf(Screen.Home, Screen.PdfEditor(), Screen.SplitPdf())) {
            assertEquals(screen, saveAndRestore(screen))
        }
        model.readerSourceUri = Uri.parse("content://provider/document/unowned.pdf")
        assertEquals(Screen.PdfReader(), saveAndRestore(Screen.PdfReader()))
        model.exitReader()
        assertEquals(unrelated, saveAndRestore(unrelated))
    }
}
