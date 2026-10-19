package com.pdfchemy.app.security

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.ImageOutputFormat
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.ui.MainViewModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PublishedBatchCancellationSecurityTest {
    private fun runBatch(images: Boolean) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val resolver = app.contentResolver
        val authority = "com.pdfchemy.app.security.batchfixture"
        val control = Uri.parse("content://$authority")
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        fun document(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        fun exists(uri: Uri) = requireNotNull(DocumentFile.fromSingleUri(app, uri)).exists()
        fun read(uri: Uri) = requireNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        resolver.call(control, "configure", null, null)
        val directory = requireNotNull(DocumentFile.fromTreeUri(app, tree))
        assertTrue("Fixture tree must exist", directory.exists())
        assertTrue("Fixture tree must be a directory", directory.isDirectory)
        assertTrue("Fixture tree must have a writable SAF grant", directory.canWrite())
        val inputs = (1..2).map { File.createTempFile("sc03_input_${it}_", if (images) ".png" else ".pdf", app.cacheDir) }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { MainViewModel(app).also { store.put("batch", it); it.setTargetMb(null) } }
        try {
            if (images) {
                val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.BLUE)
                    inputs.forEach { file -> file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                } finally { bitmap.recycle() }
                withContext(Dispatchers.Main) {
                    vm.compressBatchImages(app, inputs.map(Uri::fromFile), directory,
                        75, ImageOutputFormat.JPEG, 0, true) {}
                }
            } else {
                PDFBoxResourceLoader.init(app)
                inputs.forEach { file -> PDDocument().use { doc ->
                    val page = PDPage().also(doc::addPage)
                    PDPageContentStream(doc, page).use {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                        it.newLineAtOffset(30f, 700f); it.showText("Synthetic batch publication"); it.endText()
                    }
                    doc.save(file)
                } }
                withContext(Dispatchers.Main) {
                    @Suppress("UNCHECKED_CAST")
                    val selection = MainViewModel::class.java.getDeclaredField("_selectedFiles").apply { isAccessible = true }
                        .get(vm) as MutableStateFlow<List<MainViewModel.SelectedFile>>
                    selection.value = inputs.map { MainViewModel.SelectedFile(Uri.fromFile(it), it.name, it.length()) }
                    vm.compressBatch(app, tree)
                }
            }
            val status = withTimeout(90000) {
                while (true) {
                    check(vm.uiState.value !is MainViewModel.UiState.Error) { "Batch failed before publication: ${vm.uiState.value}" }
                    val current = requireNotNull(resolver.call(control, "status", null, null))
                    if (current.getString("blockedId") != null) return@withTimeout current
                    delay(25)
                }
                @Suppress("UNREACHABLE_CODE") error("No blocked output")
            }
            val published = document(requireNotNull(status.getString("firstId")))
            val pending = document(requireNotNull(status.getString("blockedId")))
            val publishedBytes = read(published)
            assertTrue("Item 1 must have real completed bytes before item 2 blocks", publishedBytes.isNotEmpty())
            assertFalse("Item 1 must already be marked published", OutputPolicy.isFreshCopy(published))
            val existing = document("existing")
            val existingBytes = read(existing)
            val sentinel = requireNotNull(DocumentsContract.createDocument(resolver, document("root"), "application/pdf", "cleanup-sentinel.pdf"))
            withContext(Dispatchers.Main) {
                vm.addPendingOutputUri(existing, false)
                vm.addPendingOutputUri(sentinel, true)
                vm.cancelOperation(app)
            }
            assertTrue("Cleanup must wait for the blocked writer", exists(sentinel))
            resolver.call(control, "release", null, null)
            // This final tracked destination is visited after the two batch
            // outputs, so its deletion is a cleanup-completion barrier.
            withTimeout(15000) { while (exists(sentinel)) delay(25) }
            assertTrue("Published item 1 must survive cancellation of item 2", exists(published))
            assertArrayEquals(publishedBytes, read(published))
            assertFalse("Only the incomplete batch item should be removed", exists(pending))
            assertArrayEquals(existingBytes, read(existing))
        } finally {
            resolver.call(control, "release", null, null)
            withContext(Dispatchers.Main) { vm.cancelOperation(app); store.clear() }
            inputs.forEach { it.delete() }
        }
    }

    @Test(timeout = 180000) fun completedPdfBatchItemSurvivesCancellationDuringSecondPublication() = runBatch(false)
    @Test(timeout = 180000) fun completedImageBatchItemSurvivesCancellationDuringSecondPublication() = runBatch(true)
}
