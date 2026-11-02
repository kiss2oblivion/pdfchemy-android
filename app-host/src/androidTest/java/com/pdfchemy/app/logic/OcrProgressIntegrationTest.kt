package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OcrProgressIntegrationTest {
    private fun writeScans(context: Context, source: File, pages: Int) {
        val bitmap = Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888)
        try {
            PDFBoxResourceLoader.init(context)
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                drawText("LOCAL DOCUMENT",80f,250f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=86f; typeface=Typeface.DEFAULT_BOLD })
            }
            PDDocument().use { document ->
                repeat(pages) {
                    val page = PDPage(PDRectangle(600f,800f)); document.addPage(page)
                    val image = JPEGFactory.createFromImage(document,bitmap,.95f)
                    PDPageContentStream(document,page).use { it.drawImage(image,0f,0f,600f,800f) }
                }
                document.save(source)
            }
        } finally { bitmap.recycle() }
    }

    @Test(timeout=180000) fun actualWorkerReportsEveryRecognizedPageThenSavesSearchableOutput() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("ux_ocr_", ".pdf", context.cacheDir)
        val output = File.createTempFile("ux_ocr_result_", ".pdf", context.cacheDir)
        val events = java.util.Collections.synchronizedList(mutableListOf<OcrProgress>())
        try {
            writeScans(context,source,2)
            val result = PdfGateway.executeOcr(context,Uri.fromFile(source),Uri.fromFile(output)) { events.add(it) }
            assertTrue(result.success)
            assertEquals(listOf(OcrProgress(0,2,false),OcrProgress(1,2,false),OcrProgress(2,2,false),OcrProgress(2,2,true)),events.toList())
            PDDocument.load(output).use { document ->
                assertEquals(2,document.numberOfPages)
                assertTrue(PDFTextStripper().getText(document).uppercase().contains("LOCAL DOCUMENT"))
            }
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout=60000) fun cancellationAfterAdmissionCannotPublishOutputOrLateProgress() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("ux_ocr_cancel_", ".pdf", context.cacheDir)
        val output = File.createTempFile("ux_ocr_keep_", ".pdf", context.cacheDir)
        val started = CompletableDeferred<Unit>()
        val events = java.util.Collections.synchronizedList(mutableListOf<OcrProgress>())
        try {
            writeScans(context,source,3)
            val original = "previous file contents".toByteArray()
            output.writeBytes(original)
            val job = async(Dispatchers.IO) {
                PdfGateway.executeOcr(context,Uri.fromFile(source),Uri.fromFile(output)) { event ->
                    events.add(event); started.complete(Unit)
                }
            }
            withTimeout(30000) { started.await() }
            job.cancelAndJoin()
            val count = events.size
            delay(500)
            assertEquals("No publication after cancellation",count,events.size)
            assertArrayEquals("Cancellation must not accept or overwrite output",original,output.readBytes())
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout=60000) fun malformedPdfIsClassifiedAsUnreadableAndDoesNotClaimExecutableContent() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("ux_bad_pdf_", ".pdf", context.cacheDir)
        try {
            source.writeText("This is not a PDF document.")
            val uri = Uri.fromFile(source)
            assertTrue(PdfSanitizerEngine.checkVanguardThreat(context,uri) is VanguardThreatResult.ParseFailed)
            assertTrue("Invalid PDF must remain fail closed",PdfSanitizerEngine.hasExecutableThreats(context,uri))
        } finally { source.delete() }
    }
}
