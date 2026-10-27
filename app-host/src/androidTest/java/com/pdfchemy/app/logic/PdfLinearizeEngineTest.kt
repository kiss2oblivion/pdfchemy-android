package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfLinearizeEngineTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var input: File
    private lateinit var output: File
    @Before fun setUp() {
        PDFBoxResourceLoader.init(context)
        input = File.createTempFile("linearize_input_", ".pdf", context.cacheDir)
        output = File(context.cacheDir, "linearize_output_${System.nanoTime()}.pdf")
        PDDocument().use { doc -> doc.addPage(PDPage()); doc.addPage(PDPage()); doc.save(input) }
    }
    @After fun cleanup() { input.delete(); output.delete() }
    @Test fun testCheckLinearized() = runBlocking {
        val report = PdfLinearizeEngine.checkLinearized(context, Uri.fromFile(input)).getOrThrow()
        assertEquals(2, report.pageCount)
        assertFalse(report.isLinearized)
        assertEquals("NOT_VERIFIED", report.verificationStatus)
    }
    @Test fun testOptimizeFastWebViewFailsExplicitlyWhenLinearizationUnsupported() = runBlocking {
        val result = PdfLinearizeEngine.optimizeFastWebView(context, Uri.fromFile(input), Uri.fromFile(output))
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertFalse("Unsupported generation must not create a misleading file", output.exists())
    }
    @Test fun metadataMarkerDoesNotReportFastWebViewEnabled() = runBlocking {
        PDDocument.load(input).use { doc ->
            doc.documentInformation.subject = "Unrelated /Linearized marker"
            doc.save(input)
        }
        val report = PdfLinearizeEngine.checkLinearized(context, Uri.fromFile(input)).getOrThrow()
        assertFalse(report.isLinearized)
        assertEquals("NOT_VERIFIED", report.verificationStatus)
    }
    @Test fun missingInputInspectionFails() = runBlocking {
        assertTrue(input.delete())
        assertTrue(PdfLinearizeEngine.checkLinearized(context, Uri.fromFile(input)).isFailure)
    }
    @Test fun zeroPageInspectionFails() = runBlocking {
        PDDocument().use { it.save(input) }
        assertTrue(PdfLinearizeEngine.checkLinearized(context, Uri.fromFile(input)).isFailure)
    }
    @Test fun unsupportedGenerationPreservesExistingTargetAndSource() = runBlocking {
        val original = input.readBytes()
        val existing = "private existing destination".toByteArray()
        output.writeBytes(existing)
        assertTrue(PdfLinearizeEngine.optimizeFastWebView(context, Uri.fromFile(input), Uri.fromFile(output)).exceptionOrNull() is UnsupportedOperationException)
        assertArrayEquals(existing, output.readBytes())
        assertArrayEquals(original, input.readBytes())
    }
    @Test fun actualIsolatedWorkerRejectsBeforeOpeningProvider() = runBlocking {
        val id = "linearize_${System.nanoTime()}"
        val target = Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id")
        context.contentResolver.call(target, "configure", id, Bundle())
        val result = runCatching { PdfGateway.executeEngine(context, "LINEARIZE_OPTIMIZE", Uri.fromFile(input), target, "{}") }
        assertTrue("Worker must explain unsupported generation: $result", result.exceptionOrNull()?.message.orEmpty().contains("not supported"))
        val state = requireNotNull(context.contentResolver.call(target, "state", id, null))
        assertEquals(0, state.getInt("opens"))
        assertEquals("existing destination", String(state.getByteArray("bytes")!!))
    }
    @Test fun malformedInputCannotCauseTargetPublication() = runBlocking {
        input.writeText("%PDF-1.4\n/Linearized 1\nnot a valid PDF")
        output.writeText("existing target")
        assertTrue(runCatching { PdfGateway.executeEngine(context, "LINEARIZE_OPTIMIZE", Uri.fromFile(input), Uri.fromFile(output), "{}") }.isFailure)
        assertEquals("existing target", output.readText())
    }
    @Test fun cancelledCallPropagatesCancellationAndPreservesTarget() = runBlocking {
        output.writeText("existing target")
        var observed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try {
                PdfLinearizeEngine.optimizeFastWebView(context, Uri.fromFile(input), Uri.fromFile(output))
                fail("Cancellation was swallowed")
            } catch (_: CancellationException) { observed = true }
        }
        job.join()
        assertTrue(observed)
        assertEquals("existing target", output.readText())
    }
}
