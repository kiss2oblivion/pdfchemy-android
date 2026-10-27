package com.pdfchemy.app.logic

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the host wrapper, isolated worker and actual published PDF. */
@RunWith(AndroidJUnit4::class)
class PdfDeskewIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var input: File
    private lateinit var output: File
    private val originalTarget = "existing private destination".toByteArray()

    @Before fun setUp() {
        PDFBoxResourceLoader.init(context)
        input = File.createTempFile("deskew_integrity_", ".pdf", context.cacheDir)
        output = File.createTempFile("deskew_target_", ".pdf", context.cacheDir)
        output.writeBytes(originalTarget)
        fixture(false)
    }

    @After fun cleanup() { input.delete(); output.delete() }

    private fun fixture(tilted: Boolean) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(300f, 200f))
            doc.addPage(page)
            if (tilted) PDPageContentStream(doc, page).use { stream ->
                stream.transform(Matrix.getRotateInstance(Math.toRadians(5.0), 0f, 0f))
                repeat(10) { row -> stream.addRect(30f, 25f + row * 12f, 200f, 2f); stream.fill() }
            }
            doc.save(input)
        }
    }

    private suspend fun execute(ctx: Context = context): Int =
        PdfDeskewEngine.deskewDocument(ctx, Uri.fromFile(input), Uri.fromFile(output))

    @Test fun successfulDeskewPublishesReadableCorrectedPdfAndPreservesSource() = runBlocking {
        fixture(true)
        val source = input.readBytes()
        assertEquals(1, execute())
        PDDocument.load(output).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(300f, doc.getPage(0).mediaBox.width, 0f)
            assertEquals(200f, doc.getPage(0).mediaBox.height, 0f)
        }
        val rendered = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderUriToBitmap(context, Uri.fromFile(output), 0, 250)
        try {
            assertNotNull("Published PDF must render", rendered)
            assertTrue("Exported scan must be straight", kotlin.math.abs(PdfDeskewEngine.detectSkewAngle(rendered!!)) < 0.5f)
        } finally { rendered?.recycle() }
        assertArrayEquals(source, input.readBytes())
    }

    @Test fun zeroCorrectionsStillPublishesAValidPdf() = runBlocking {
        val source = input.readBytes()
        assertEquals(0, execute())
        PDDocument.load(output).use { assertEquals(1, it.numberOfPages) }
        assertArrayEquals(source, input.readBytes())
    }

    @Test fun malformedInputPropagatesFailureAndPreservesBothFiles() = runBlocking {
        input.writeText("%PDF-1.4\nmalformed private source")
        val source = input.readBytes()
        assertTrue("Malformed input must fail", runCatching { execute() }.isFailure)
        assertArrayEquals(originalTarget, output.readBytes())
        assertArrayEquals(source, input.readBytes())
    }

    @Test fun unavailableOutputPropagatesFailureWithoutChangingSourceOrOldTarget() = runBlocking {
        val source = input.readBytes()
        val unavailable = Uri.parse("content://com.pdfchemy.missing.provider/output")
        assertTrue(runCatching { PdfDeskewEngine.deskewDocument(context, Uri.fromFile(input), unavailable) }.isFailure)
        assertArrayEquals(source, input.readBytes())
        assertArrayEquals(originalTarget, output.readBytes())
    }

    @Test fun workerBindingFailureCannotBecomeZeroCorrections() = runBlocking {
        val source = input.readBytes()
        val unavailableWorker = object : ContextWrapper(context) {
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        }
        val error = runCatching { execute(unavailableWorker) }.exceptionOrNull()
        assertTrue("Expected worker failure, got $error", error is PdfGateway.WorkerBindingException)
        assertArrayEquals(originalTarget, output.readBytes())
        assertArrayEquals(source, input.readBytes())
    }

    @Test fun workerRenderFailureAbortsBeforeDestinationPublication() = runBlocking {
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(10f, 10000f)))
            doc.save(input)
        }
        val source = input.readBytes()
        val error = runCatching { execute() }.exceptionOrNull()
        assertTrue("Rendering quota failure must propagate: $error", error is PdfGateway.WorkerFailure)
        assertTrue(error!!.message.orEmpty().contains("Raster quota"))
        assertArrayEquals(originalTarget, output.readBytes())
        assertArrayEquals(source, input.readBytes())
    }

    @Test fun cancellationPropagatesAndPreservesBothFiles() = runBlocking {
        val source = input.readBytes()
        var propagated = false
        launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            try { execute(); fail("Cancellation became a successful zero") }
            catch (_: CancellationException) { propagated = true }
        }.join()
        assertTrue(propagated)
        assertArrayEquals(originalTarget, output.readBytes())
        assertArrayEquals(source, input.readBytes())
    }
}
