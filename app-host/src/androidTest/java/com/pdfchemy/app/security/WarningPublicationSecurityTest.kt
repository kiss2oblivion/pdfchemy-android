package com.pdfchemy.app.security

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.ui.MainViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real compressor, worker and SAF bytes; no picker/dialog automation. */
@RunWith(AndroidJUnit4::class)
class WarningPublicationSecurityTest {
    private enum class Completion { CLEAR, CANCEL, DISMISS_AND_REUSE }

    private suspend fun awaitWriter(vm: MainViewModel) {
        val writer = withContext(Dispatchers.Main) {
            MainViewModel::class.java.getDeclaredField("activeJob").apply { isAccessible = true }.get(vm) as Job?
        }
        withTimeout(15000) { writer?.join() }
    }

    private suspend fun awaitCleanup(vm: MainViewModel, action: () -> Unit) {
        val jobs = withContext(Dispatchers.Main) {
            val before = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            action()
            vm.viewModelScope.coroutineContext[Job]!!.children.filter { it !in before }.toList()
        }
        withTimeout(15000) { jobs.joinAll() }
    }

    private suspend fun clear(store: ViewModelStore, vm: MainViewModel) {
        val cleanup = withContext(Dispatchers.Main) {
            store.clear()
            MainViewModel::class.java.getDeclaredField("lastCleanupJob").apply { isAccessible = true }.get(vm) as Job?
        }
        withTimeout(15000) { requireNotNull(cleanup).join() }
    }

    private fun exercise(completion: Completion? = null, freshFailure: Boolean = true) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val resolver = app.contentResolver
        val control = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        val tree = DocumentsContract.buildTreeDocumentUri(control.authority!!, "root")
        resolver.call(control, "configure", null, null)
        val directory = requireNotNull(DocumentFile.fromTreeUri(app, tree))
        assertTrue(directory.exists() && directory.isDirectory && directory.canWrite())
        val output = if (completion != null || freshFailure) OutputPolicy.createCopy(directory, "application/pdf", "warning.pdf").uri
            else DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
        fun read() = requireNotNull(resolver.openInputStream(output)).use { it.readBytes() }
        fun exists() = requireNotNull(DocumentFile.fromSingleUri(app, output)).exists()
        val originalBytes = read()
        val source = File.createTempFile("sc04_source_", ".pdf", app.cacheDir)
        val invalid = File.createTempFile("sc04_invalid_", ".pdf", app.cacheDir).apply { writeText("Not a PDF") }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { MainViewModel(app).also { store.put("warning", it); it.setTargetMb(0.00001f) } }
        var cleared = false
        try {
            if (completion != null) {
                PDFBoxResourceLoader.init(app)
                PDDocument().use { doc ->
                    val page = PDPage().also(doc::addPage)
                    PDPageContentStream(doc, page).use {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                        it.newLineAtOffset(30f, 700f); it.showText("Completed target-missed PDF"); it.endText()
                    }
                    doc.save(source)
                }
                withContext(Dispatchers.Main) { vm.compressPdf(app, Uri.fromFile(source), output) }
                val warning = withTimeout(90000) { vm.uiState.first { it is MainViewModel.UiState.Warning || it is MainViewModel.UiState.Error } }
                assertTrue("A real successful target-missed result is required: $warning", warning is MainViewModel.UiState.Warning)
                awaitWriter(vm)
                val bytes = read()
                assertTrue(bytes.size > 10)
                PDDocument.load(bytes).use { assertEquals(1, it.numberOfPages) }
                when (completion) {
                    Completion.CLEAR -> { clear(store, vm); cleared = true }
                    Completion.CANCEL -> awaitCleanup(vm) { vm.cancelOperation(app) }
                    Completion.DISMISS_AND_REUSE -> {
                        withContext(Dispatchers.Main) { vm.resetState() }
                        assertSame(MainViewModel.UiState.Idle, vm.uiState.value)
                        // Reusing a published capability must not inherit the old fresh-copy claim.
                        withContext(Dispatchers.Main) { vm.compressPdf(app, Uri.fromFile(invalid), output) }
                        withTimeout(90000) { vm.uiState.first { it is MainViewModel.UiState.Error } }
                        // The failure handler starts cleanup before publishing Error. Join all
                        // finite operation/cleanup children after observing that state.
                        val jobs = withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]!!.children.toList() }
                        withTimeout(15000) { jobs.joinAll() }
                    }
                }
                assertTrue("Completed Warning output must survive $completion", exists())
                assertArrayEquals(bytes, read())
                assertFalse("Successful publication must retire freshness", OutputPolicy.isFreshCopy(output))
            } else {
                withContext(Dispatchers.Main) { vm.compressPdf(app, Uri.fromFile(invalid), output) }
                withTimeout(90000) { vm.uiState.first { it is MainViewModel.UiState.Error } }
                val jobs = withContext(Dispatchers.Main) { vm.viewModelScope.coroutineContext[Job]!!.children.toList() }
                withTimeout(15000) { jobs.joinAll() }
                assertEquals("Only a fresh incomplete destination is eligible for deletion", !freshFailure, exists())
                if (!freshFailure) assertArrayEquals(originalBytes, read())
                assertFalse(OutputPolicy.isFreshCopy(output))
            }
        } finally {
            resolver.call(control, "release", null, null)
            if (!cleared) clear(store, vm)
            OutputPolicy.unregisterFreshCopy(output)
            source.delete(); invalid.delete()
        }
    }

    @Test(timeout = 180000) fun targetMissedPdfSurvivesViewModelDestruction() = exercise(Completion.CLEAR)
    @Test(timeout = 180000) fun targetMissedPdfSurvivesCancellation() = exercise(Completion.CANCEL)
    @Test(timeout = 180000) fun dismissedWarningCanBeReusedWithoutStaleFreshnessDeletion() = exercise(Completion.DISMISS_AND_REUSE)
    @Test(timeout = 180000) fun failedCompressionDeletesFreshIncompleteDestination() = exercise()
    @Test(timeout = 180000) fun failedCompressionPreservesExistingDestination() = exercise(freshFailure = false)
}
