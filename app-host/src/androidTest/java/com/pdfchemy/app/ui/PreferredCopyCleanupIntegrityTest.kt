package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PreferredCopyCleanupIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fresh(): DocumentFile {
        val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        context.contentResolver.call(base, "configure", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
        return OutputPolicy.createCopy(DocumentFile.fromTreeUri(context, tree)!!, "application/pdf", "existing.pdf")
    }
    private fun oldBytes() = context.contentResolver.openInputStream(
        Uri.parse("content://com.pdfchemy.app.security.batchfixture/tree/root/document/existing"))!!.use { it.readBytes() }
    @Test fun publishedExportSurvivesLateCreatorCleanup() = runBlocking {
        val output = fresh()
        val before = oldBytes()
        val source = File.createTempFile("preferred_cleanup_", ".pdf", context.cacheDir)
        PDFBoxResourceLoader.init(context)
        PDDocument().use { it.addPage(PDPage()); it.save(source) }
        val sourceBytes = source.readBytes()
        try {
            PdfGateway.executeEngine(context, "METADATA_WRITE", Uri.fromFile(source), output.uri, "{}")
            val exported = context.contentResolver.openInputStream(output.uri)!!.use { it.readBytes() }
            assertFalse(OutputPolicy.isFreshCopy(output.uri))
            // Same cleanup used by the saved-folder callback's NonCancellable finally.
            cleanupPreferredDocumentCopy(context, output)
            assertTrue("Late cancellation must not delete a published export", output.exists())
            assertArrayEquals(exported, context.contentResolver.openInputStream(output.uri)!!.use { it.readBytes() })
            assertArrayEquals(before, oldBytes())
            assertArrayEquals(sourceBytes, source.readBytes())
        } finally { output.delete(); source.delete() }
    }
    @Test fun activeOutputOwnerSurvivesCreatorCleanup() {
        val output = fresh()
        HostOutputTransaction(context, listOf(output.uri)).use {
            cleanupPreferredDocumentCopy(context, output)
            assertTrue("Creator cannot delete another operation's active copy", output.exists())
            assertTrue(OutputPolicy.isFreshCopy(output.uri))
        }
        assertFalse(output.exists())
        assertEquals("existing destination bytes", oldBytes().toString(Charsets.UTF_8))
    }
    @Test fun abandonedUnpublishedCopyIsRemoved() {
        val output = fresh()
        cleanupPreferredDocumentCopy(context, output)
        assertFalse(output.exists())
        assertFalse(OutputPolicy.isFreshCopy(output.uri))
        assertEquals("existing destination bytes", oldBytes().toString(Charsets.UTF_8))
    }
}
