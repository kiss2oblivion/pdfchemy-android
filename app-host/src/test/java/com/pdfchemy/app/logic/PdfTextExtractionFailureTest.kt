package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.security.SecurityLimits
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfTextExtractionFailureTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val source = Uri.parse("content://fixture/source.pdf")
    private fun temporaryNames() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ocr_") }.map { it.name }.toSet()
    @Before fun setup() { mockkObject(PdfGateway) }
    @After fun cleanup() { unmockkAll() }
    private fun assertPropagatedCancellation(expected: CancellationException, thrown: Throwable?) {
        assertTrue("Cancellation must escape rather than become false/empty/failure", thrown is CancellationException)
        // Coroutine stack-trace recovery may copy the exception at a dispatcher boundary.
        val causes = generateSequence(thrown) { it.cause }.take(8).toList()
        assertTrue("Original cancellation must remain in the cause chain", causes.any { it === expected })
    }

    @Test fun failedOcrReturnsActualFailureAndRemovesEmptyTemporaryFile() = runBlocking<Unit> {
        val before = temporaryNames(); val failure = IOException("controlled worker failure")
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } throws failure
        val result = PdfTextExtractor.extractUsingOcr(context, source)
        assertTrue(result.isFailure); assertSame(failure, result.exceptionOrNull())
        assertEquals(before, temporaryNames())
    }
    @Test fun failedOcrRemovesPartiallyWrittenTemporaryOutput() = runBlocking<Unit> {
        val before = temporaryNames()
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } coAnswers {
            File(arg<Uri>(3).path!!).writeText("partial private text")
            throw IOException("worker failed after writing")
        }
        assertTrue(PdfTextExtractor.extractUsingOcr(context, source).isFailure)
        assertEquals(before, temporaryNames())
    }
    @Test fun recognizedTextIsReturnedAfterTemporaryOutputHasBeenRemoved() = runBlocking<Unit> {
        val before = temporaryNames()
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } coAnswers {
            File(arg<Uri>(3).path!!).writeText("Recognized Ω text")
            StandardOutputContract()
        }
        assertEquals("Recognized Ω text", PdfTextExtractor.extractUsingOcr(context, source).getOrThrow())
        assertEquals(before, temporaryNames())
    }
    @Test fun genuineEmptyOcrIsSuccessDistinctFromFailure() = runBlocking<Unit> {
        val before = temporaryNames()
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } returns StandardOutputContract()
        val result = PdfTextExtractor.extractUsingOcr(context, source)
        assertTrue(result.isSuccess); assertEquals("", result.getOrThrow())
        assertEquals(before, temporaryNames())
    }
    @Test fun ocrCancellationEscapesAndStillRemovesTemporaryOutput() = runBlocking<Unit> {
        val before = temporaryNames(); val cancellation = CancellationException("controlled cancellation")
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } throws cancellation
        val thrown = runCatching { PdfTextExtractor.extractUsingOcr(context, source) }.exceptionOrNull()
        assertPropagatedCancellation(cancellation, thrown); assertEquals(before, temporaryNames())
    }
    @Test fun callerCancellationDuringAnUncooperativeWorkerStillCleansAfterReturn() = runBlocking<Unit> {
        val before = temporaryNames(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            File(arg<Uri>(3).path!!).writeText("discarded text")
            StandardOutputContract()
        }
        val job = launch(Dispatchers.Default) { PdfTextExtractor.extractUsingOcr(context, source); fail("Cancelled OCR cannot return to its caller") }
        try { entered.await(); job.cancel() }
        finally { release.complete(Unit); job.join() }
        assertTrue(job.isCancelled); assertEquals(before, temporaryNames())
    }
    @Test fun oversizedOcrTextFailsBeforeReadingAndIsRemoved() = runBlocking<Unit> {
        val before = temporaryNames()
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } coAnswers {
            File(arg<Uri>(3).path!!).outputStream().use { it.write(ByteArray(SecurityLimits.MAX_TEXT_BYTES + 1)) }
            StandardOutputContract()
        }
        assertTrue(PdfTextExtractor.extractUsingOcr(context, source).isFailure)
        assertEquals(before, temporaryNames())
    }
    @Test fun ordinaryTextExtractionPropagatesCancellation() = runBlocking<Unit> {
        val cancellation = CancellationException("cancel text extraction")
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } throws cancellation
        val thrown = runCatching { PdfTextExtractor.extractText(context, source, Uri.parse("content://fixture/output.txt")) }.exceptionOrNull()
        assertPropagatedCancellation(cancellation, thrown)
    }
    @Test fun ordinaryTextExtractionKeepsItsFailureContract() = runBlocking<Unit> {
        coEvery { PdfGateway.executeEngine(any(), "TEXT_EXTRACT", any(), any(), any()) } throws IOException("controlled provider failure")
        assertFalse(PdfTextExtractor.extractText(context, source, Uri.parse("content://fixture/output.txt")))
    }
}
