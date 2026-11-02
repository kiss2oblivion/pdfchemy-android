package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SanitizerCancellationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val source = Uri.parse("content://fixture/source.pdf")
    private val target = Uri.parse("content://fixture/output.pdf")

    @Before fun setup() { mockkObject(PdfGateway) }
    @After fun cleanup() { unmockkAll() }

    @Test fun auditPropagatesWorkerCancellation() = runBlocking<Unit> {
        val cancellation = CancellationException("audit cancelled")
        coEvery { PdfGateway.executeEngine(any(), "SANITIZE_AUDIT", any(), any(), any()) } throws cancellation
        val failure = runCatching { PdfSanitizerEngine.auditDocumentThreats(context, source) }.exceptionOrNull()
        assertSame("Cancellation must not become an ordinary failed audit", cancellation, failure)
    }

    @Test fun exportPropagatesWorkerCancellation() = runBlocking<Unit> {
        val cancellation = CancellationException("export cancelled")
        coEvery { PdfGateway.executeEngine(any(), "SANITIZE_CLEAN", any(), any(), any()) } throws cancellation
        val failure = runCatching { PdfSanitizerEngine.sanitizeDocument(context, source, target) }.exceptionOrNull()
        assertSame("Cancellation must escape the sanitizer boundary", cancellation, failure)
    }

    @Test fun unreadableAuditStillFailsClosed() = runBlocking<Unit> {
        coEvery { PdfGateway.executeEngine(any(), "SANITIZE_AUDIT", any(), any(), any()) } throws IOException("unavailable source")
        val result = PdfSanitizerEngine.auditDocumentThreats(context, source)
        assertFalse(result.isClean)
        assertTrue(result.parseFailed)
    }

    @Test fun failedExportCannotReportSuccess() = runBlocking<Unit> {
        coEvery { PdfGateway.executeEngine(any(), "SANITIZE_CLEAN", any(), any(), any()) } throws IOException("worker failed")
        assertFalse(PdfSanitizerEngine.sanitizeDocument(context, source, target).isSuccess)
    }
}
