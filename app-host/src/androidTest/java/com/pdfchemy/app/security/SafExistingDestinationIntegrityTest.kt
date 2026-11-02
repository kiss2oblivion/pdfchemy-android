package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Existing SAF documents are never disposable capabilities, including during provider failure. */
@RunWith(AndroidJUnit4::class)
class SafExistingDestinationIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val old = "existing destination".toByteArray()
    private fun fixture(failure: String? = null, block: Boolean = false): Uri {
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/saf_${System.nanoTime()}")
        context.contentResolver.call(uri, "configure", uri.lastPathSegment,
            Bundle().apply { putString("failure", failure); putBoolean("block", block) })
        return uri
    }
    private fun state(uri: Uri, method: String = "state") =
        context.contentResolver.call(uri, method, uri.lastPathSegment, null)!!
    private fun preserved(uri: Uri) = assertArrayEquals(old, state(uri).getByteArray("bytes"))
    private suspend fun ready(transaction: HostOutputTransaction) = coroutineScope {
        val producer = async(Dispatchers.IO) {
            val buffer = ByteArray(8192) { 65 }
            repeat(128) { Os.write(transaction.workerDescriptors.single().fileDescriptor, buffer, 0, buffer.size) }
        }
        producer.await()
        transaction.validateAndSnapshot("true")
    }
    @Test fun providerFailureAfterTruncationCannotDestroyExistingDocument() = runBlocking {
        val uri = fixture("open")
        HostOutputTransaction(context, listOf(uri)).use {
            ready(it)
            assertTrue(runCatching { it.commit() }.isFailure)
        }
        preserved(uri)
    }
    @Test fun partialProviderWriteCannotReplaceExistingDocument() = runBlocking {
        val uri = fixture("partial")
        HostOutputTransaction(context, listOf(uri)).use {
            ready(it)
            assertTrue(runCatching { it.commit() }.isFailure)
        }
        preserved(uri)
    }
    @Test fun cancellationAtProviderOpenCannotTruncateExistingDocument() = runBlocking {
        val uri = fixture(block = true)
        HostOutputTransaction(context, listOf(uri)).use { transaction ->
            ready(transaction)
            val publishing = async(Dispatchers.IO) { runCatching { transaction.commit() } }
            publishing.invokeOnCompletion { state(uri, "publicationFinished") }
            try {
                // Fixed implementation rejects before opening. Original implementation blocks inside open.
                withContext(Dispatchers.IO) { state(uri, "awaitOpen") }
                publishing.cancel()
            } finally {
                state(uri, "release")
                publishing.cancelAndJoin()
            }
        }
        if (state(uri).getInt("opens") > 0) state(uri, "awaitWritten")
        preserved(uri)
    }
    @Test fun reachableMetadataWorkflowRejectsExistingSafAndPreservesSource() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val input = File.createTempFile("saf_source_", ".pdf", context.cacheDir)
        PDDocument().use { it.addPage(PDPage()); it.save(input) }
        val original = input.readBytes()
        val uri = fixture("open")
        try {
            assertTrue(runCatching { PdfGateway.executeEngine(context, "METADATA_WRITE", Uri.fromFile(input), uri, "{}") }.isFailure)
            assertArrayEquals(original, input.readBytes())
            preserved(uri)
        } finally { input.delete() }
    }
}
