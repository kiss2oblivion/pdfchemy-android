package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SafFreshCopyPublicationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(failure: String? = null, fresh: Boolean = true): Uri {
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/copy_${System.nanoTime()}")
        context.contentResolver.call(uri, "configure", uri.lastPathSegment, Bundle().apply {
            putString("failure", failure); putBoolean("allowDelete", fresh)
        })
        if (fresh) OutputPolicy.registerFreshCopy(uri)
        return uri
    }
    private fun state(uri: Uri) = context.contentResolver.call(uri, "state", uri.lastPathSegment, null)!!
    private suspend fun stage(transaction: HostOutputTransaction) {
        withContext(Dispatchers.IO) {
            val bytes = ByteArray(8192) { 42 }
            transaction.workerDescriptors.forEach { fd -> repeat(128) { Os.write(fd.fileDescriptor, bytes, 0, bytes.size) } }
        }
        transaction.validateAndSnapshot("true")
    }
    @Test fun wholeBatchIsCheckedBeforeFirstOpen() = runBlocking {
        val fresh = fixture(); val old = fixture(fresh = false)
        HostOutputTransaction(context, listOf(fresh, old)).use {
            stage(it)
            assertTrue(runCatching { it.commit() }.exceptionOrNull() is UnsupportedOperationException)
        }
        assertEquals(0, state(fresh).getInt("opens"))
        assertTrue(state(fresh).getBoolean("deleted"))
        assertEquals(0, state(old).getInt("opens"))
        assertArrayEquals("existing destination".toByteArray(), state(old).getByteArray("bytes"))
    }
    @Test fun failedFreshCopyIsCleanedWithoutAffectingOldDocument() = runBlocking {
        val old = fixture(fresh = false)
        for (failure in listOf("open", "partial")) {
            val target = fixture(failure)
            HostOutputTransaction(context, listOf(target)).use {
                stage(it)
                assertTrue(runCatching { it.commit() }.isFailure)
            }
            assertTrue(state(target).getBoolean("deleted"))
            assertFalse(OutputPolicy.isFreshCopy(target))
        }
        assertArrayEquals("existing destination".toByteArray(), state(old).getByteArray("bytes"))
    }
    @Test fun successfulSafMetadataCopyPreservesOldNameAndSource() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val base = Uri.parse("content://com.pdfchemy.app.security.batchfixture")
        context.contentResolver.call(base, "configure", null, null)
        val tree = DocumentsContract.buildTreeDocumentUri(base.authority!!, "root")
        val directory = DocumentFile.fromTreeUri(context, tree)!!
        val old = directory.listFiles().single()
        val oldBytes = context.contentResolver.openInputStream(old.uri)!!.use { it.readBytes() }
        val source = File.createTempFile("saf_copy_source_", ".pdf", context.cacheDir)
        PDDocument().use { it.addPage(PDPage()); it.save(source) }
        val sourceBytes = source.readBytes()
        val fresh = OutputPolicy.createCopy(directory, "application/pdf", "existing.pdf")
        try {
            assertEquals("existing (2).pdf", fresh.name)
            PdfGateway.executeEngine(context, "METADATA_WRITE", Uri.fromFile(source), fresh.uri, "{}")
            context.contentResolver.openInputStream(fresh.uri)!!.use { input ->
                PDDocument.load(input).use { assertEquals(1, it.numberOfPages) }
            }
            assertFalse("A published URI must not remain reusable as fresh", OutputPolicy.isFreshCopy(fresh.uri))
            assertArrayEquals(oldBytes, context.contentResolver.openInputStream(old.uri)!!.use { it.readBytes() })
            assertArrayEquals(sourceBytes, source.readBytes())
            assertTrue(runCatching { PdfGateway.executeEngine(context, "METADATA_WRITE", Uri.fromFile(source), fresh.uri, "{}") }
                .exceptionOrNull() is UnsupportedOperationException)
            assertTrue(fresh.exists())
        } finally { fresh.delete(); source.delete() }
    }
    @Test fun overlappingOperationCannotDeleteClaimedFreshCopy() = runBlocking {
        val target = fixture()
        HostOutputTransaction(context, listOf(target)).use {
            assertFalse(OutputPolicy.deleteIfFresh(context, target))
            assertTrue(runCatching { HostOutputTransaction(context, listOf(target)) }.isFailure)
            assertFalse(state(target).getBoolean("deleted"))
        }
        assertTrue(state(target).getBoolean("deleted"))
    }
}
