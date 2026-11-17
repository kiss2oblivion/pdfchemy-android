package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.PdfCompressor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random

/** Real public compressor, isolated worker and hostile SAF-style output capabilities. */
@RunWith(AndroidJUnit4::class)
class CompressorPublicationIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val original = "existing destination".toByteArray()

    private fun fixture(fresh: Boolean, failure: String? = null, blockRead: Boolean = false): Uri {
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/compress_${System.nanoTime()}")
        context.contentResolver.call(uri, "configure", uri.lastPathSegment, Bundle().apply {
            putString("failure", failure)
            putBoolean("allowDelete", fresh)
            putBoolean("blockRead", blockRead)
        })
        if (fresh) OutputPolicy.registerFreshCopy(uri)
        return uri
    }

    private fun state(uri: Uri, method: String = "state"): Bundle =
        requireNotNull(context.contentResolver.call(uri, method, uri.lastPathSegment, null))

    private fun source(large: Boolean = false): File {
        PDFBoxResourceLoader.init(context)
        return File.createTempFile("compress_publication_", ".pdf", context.cacheDir).also { file ->
            PDDocument().use { document ->
                val page = PDPage().also(document::addPage)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 10f)
                    val random = Random(1234)
                    repeat(if (large) 8192 else 1) {
                        content.showText(CharArray(80) { ('A'.code + random.nextInt(26)).toChar() }.concatToString())
                    }
                    content.endText()
                }
                document.save(file)
            }
        }
    }

    @Test(timeout = 180000) fun existingDestinationIsRejectedBeforeDestructiveOpen() = runBlocking {
        val input = source()
        val bytes = input.readBytes()
        val destination = fixture(fresh = false, failure = "open")
        try {
            assertTrue(PdfCompressor.compressPdf(context, Uri.fromFile(input), destination).isFailure)
            assertEquals(0, state(destination).getInt("opens"))
            assertArrayEquals(original, state(destination).getByteArray("bytes"))
            assertArrayEquals(bytes, input.readBytes())
            assertFalse(state(destination).getBoolean("deleted"))
        } finally { input.delete() }
    }

    @Test(timeout = 180000) fun freshCopyPublishesLargePdfAndCannotBeReusedAsFresh() = runBlocking {
        val input = source(large = true)
        val sourceBytes = input.readBytes()
        val destination = fixture(fresh = true)
        try {
            PdfCompressor.compressPdf(context, Uri.fromFile(input), destination).getOrThrow()
            val published = state(destination, "awaitWritten").getByteArray("bytes")!!
            assertTrue("Exercise output larger than the pipe buffer", published.size > 128 * 1024)
            PDDocument.load(published).use { assertEquals(1, it.numberOfPages) }
            assertFalse(OutputPolicy.isFreshCopy(destination))
            assertFalse(PublicationCoordinator.hasActiveWriter(destination))
            assertArrayEquals(sourceBytes, input.readBytes())
            assertTrue(PdfCompressor.compressPdf(context, Uri.fromFile(input), destination).isFailure)
            assertEquals(1, state(destination).getInt("opens"))
            assertArrayEquals(published, state(destination).getByteArray("bytes"))
            assertFalse(state(destination).getBoolean("deleted"))
        } finally { OutputPolicy.unregisterFreshCopy(destination); input.delete() }
    }

    @Test(timeout = 180000) fun freshDestinationIsCleanedAfterOpenOrPartialWriteFailure() = runBlocking {
        val input = source(large = true)
        try {
            for (failure in listOf("open", "partial")) {
                val destination = fixture(fresh = true, failure = failure)
                try {
                    assertTrue(PdfCompressor.compressPdf(context, Uri.fromFile(input), destination).isFailure)
                    assertTrue(state(destination).getBoolean("deleted"))
                    assertFalse(OutputPolicy.isFreshCopy(destination))
                    assertFalse(PublicationCoordinator.hasActiveWriter(destination))
                } finally { OutputPolicy.unregisterFreshCopy(destination) }
            }
        } finally { input.delete() }
    }

    @Test(timeout = 180000) fun sourceReadFailureCleansNeverOpenedFreshCopy() = runBlocking {
        val missing = File(context.cacheDir, "missing_compress_${System.nanoTime()}.pdf")
        val destination = fixture(fresh = true)
        try {
            assertTrue(PdfCompressor.compressPdf(context, Uri.fromFile(missing), destination).isFailure)
            assertEquals(0, state(destination).getInt("opens"))
            assertTrue(state(destination).getBoolean("deleted"))
            assertFalse(OutputPolicy.isFreshCopy(destination))
        } finally { OutputPolicy.unregisterFreshCopy(destination) }
    }

    @Test(timeout = 180000) fun cancellationClosesBlockedFreshPublicationBeforeCleanup() = runBlocking {
        val input = source(large = true)
        val destination = fixture(fresh = true, blockRead = true)
        val operation = async(Dispatchers.IO) { PdfCompressor.compressPdf(context, Uri.fromFile(input), destination) }
        try {
            withContext(Dispatchers.IO) { state(destination, "awaitRead") }
            assertTrue("The real final save must be coordinated", PublicationCoordinator.hasActiveWriter(destination))
            operation.cancel()
            val stopped = withTimeoutOrNull(5000) { operation.join(); true } ?: false
            assertTrue("Closing the active stream must stop its blocked writer", stopped)
            assertTrue(operation.isCancelled)
            assertFalse(PublicationCoordinator.hasActiveWriter(destination))
            assertTrue(state(destination).getBoolean("deleted"))
            assertFalse(OutputPolicy.isFreshCopy(destination))
        } finally {
            state(destination, "releaseRead")
            operation.cancelAndJoin()
            OutputPolicy.unregisterFreshCopy(destination)
            input.delete()
        }
    }
}
