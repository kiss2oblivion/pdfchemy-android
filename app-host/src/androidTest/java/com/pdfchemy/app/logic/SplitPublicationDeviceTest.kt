package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Public split/export wrappers against a separate-UID SAF provider and real isolated workers. */
@RunWith(AndroidJUnit4::class)
class SplitPublicationDeviceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
    private val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
    private val existing = DocumentsContract.buildDocumentUriUsingTree(tree, "existing")
    private lateinit var directory: DocumentFile
    private lateinit var source: File
    private lateinit var sourceBytes: ByteArray
    private lateinit var existingBytes: ByteArray

    @Before fun setup() {
        val state = context.contentResolver.call(base, "configure", null, null)!!
        assertNotEquals("The SAF fixture must run in a foreign UID", Process.myUid(), state.getInt("providerUid"))
        // The existing fixture can stall its second open; success smoke tests release that gate first.
        context.contentResolver.call(base, "release", null, null)
        directory = requireNotNull(DocumentFile.fromTreeUri(context, tree))
        existingBytes = read(existing)
        PDFBoxResourceLoader.init(context)
        source = File.createTempFile("split_publication_", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            val pages = List(3) { PDPage().also(document::addPage) }
            for (index in listOf(0, 2)) {
                PDPageContentStream(document, pages[index]).use {
                    it.addRect(100f, 100f, 200f, 200f)
                    it.fill()
                }
            }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            for (index in listOf(0, 2)) {
                outline.addLast(PDOutlineItem().apply {
                    title = "Synthetic section $index"
                    destination = PDPageFitDestination().apply { page = pages[index] }
                })
            }
            document.save(source)
        }
        sourceBytes = source.readBytes()
    }

    @After fun cleanup() {
        if (::directory.isInitialized) {
            directory.listFiles().filter { it.uri != existing }.forEach {
                OutputPolicy.deleteIfFresh(context, it.uri)
            }
        }
        if (::source.isInitialized) source.delete()
    }

    private fun read(uri: Uri) = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun verifyPublished(outputs: List<Uri>, expected: Int, images: Boolean = false) {
        assertEquals(expected, outputs.size)
        assertEquals(outputs.size, outputs.distinct().size)
        for (uri in outputs) {
            assertFalse(OutputPolicy.isFreshCopy(uri))
            assertFalse("Published copy must be protected from stale cleanup", OutputPolicy.deleteIfFresh(context, uri))
            val bytes = read(uri)
            if (images) {
                val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { assertTrue(bitmap.width > 0 && bitmap.height > 0) }
                finally { bitmap.recycle() }
            } else {
                PDDocument.load(bytes).use { assertTrue(it.numberOfPages > 0) }
            }
        }
        assertArrayEquals("Source changed during split/export", sourceBytes, source.readBytes())
        assertArrayEquals("Existing provider document changed", existingBytes, read(existing))
        assertEquals(0, context.contentResolver.call(base, "state", null, null)!!.getInt("deletedCount"))
    }

    @Test fun plainSplitPublishesReadableProtectedCopies() = runBlocking {
        verifyPublished(PdfManipulator.splitPdf(context, Uri.fromFile(source), directory, "plain"), 3)
    }

    @Test fun blankSplitPublishesReadableProtectedCopies() = runBlocking {
        verifyPublished(PdfManipulator.splitByBlankPages(context, Uri.fromFile(source), directory, "blank"), 2)
    }

    @Test fun bookmarkSplitPublishesReadableProtectedCopies() = runBlocking {
        verifyPublished(PdfManipulator.splitByBookmarks(context, Uri.fromFile(source), directory, "bookmark"), 2)
    }

    @Test fun imageExportPublishesReadableProtectedCopies() = runBlocking {
        verifyPublished(PdfManipulator.convertPdfToImages(context, Uri.fromFile(source), directory, "images", targetWidth = 240), 3, images = true)
    }
}
