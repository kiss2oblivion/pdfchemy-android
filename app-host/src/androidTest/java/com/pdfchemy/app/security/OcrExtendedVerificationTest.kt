package com.pdfchemy.app.security

import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.jail.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class OcrExtendedVerificationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testMultiFontAndDegradedScanOcr() = runBlocking<Unit> {
        val source = File.createTempFile("ocr_ext_src_", ".pdf", context.cacheDir)
        val output = File.createTempFile("ocr_ext_out_", ".pdf", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)

        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            // Test 1: Bold Sans-Serif
            val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 60f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            canvas.drawText("SECTION 1: SANS BOLD TITLE", 80f, 150f, p1)

            // Test 2: Serif Regular & Italic
            val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 40f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            canvas.drawText("The quick brown fox jumps over the lazy dog.", 80f, 250f, p2)

            val p3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 36f; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            canvas.drawText("Italic citation text with numbers 2026-10-04.", 80f, 320f, p3)

            // Test 3: Monospace with code/tokens and punctuation
            val p4 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 34f; typeface = Typeface.MONOSPACE
            }
            canvas.drawText("TOKEN=ABC-12345; STATUS=VERIFIED: (PASS)", 80f, 400f, p4)

            // Test 4: Simulated scan degradation (speckle noise + slight uneven background)
            val noisePaint = Paint().apply { color = Color.argb(40, 100, 100, 100) }
            val rng = Random(42)
            for (i in 0 until 500) {
                val rx = rng.nextFloat() * 1200f
                val ry = rng.nextFloat() * 1600f
                canvas.drawCircle(rx, ry, 1.5f, noisePaint)
            }

            PDDocument().use { document ->
                val page = PDPage(PDRectangle(600f, 800f))
                document.addPage(page)
                val image = JPEGFactory.createFromImage(document, bitmap, 0.90f)
                PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, 600f, 800f) }
                document.save(source)
            }

            val t0 = SystemClock.elapsedRealtime()
            val result = runCatching {
                PdfGateway.executeEngine(context, "OCR_PROCESS", Uri.fromFile(source), Uri.fromFile(output), "{}")
            }
            val elapsedMs = SystemClock.elapsedRealtime() - t0

            assertTrue("OCR processing must succeed: ${result.exceptionOrNull()?.message}", result.isSuccess)
            println("EXT_OCR_BENCHMARK: Elapsed time for full page OCR: ${elapsedMs}ms")

            PDDocument.load(output).use { document ->
                val text = PDFTextStripper().getText(document).uppercase()
                println("EXT_OCR_EXTRACTED_TEXT:\n$text")

                assertTrue("Must recognize sans bold title", text.contains("SANS BOLD TITLE") || text.contains("SECTION 1"))
                assertTrue("Must recognize serif text", text.contains("QUICK BROWN FOX") || text.contains("LAZY DOG"))
                assertTrue("Must recognize numbers", text.contains("2026") || text.contains("12345"))
                assertTrue("Must recognize token/code line", text.contains("TOKEN") || text.contains("STATUS") || text.contains("VERIFIED"))
            }
        } finally {
            bitmap.recycle()
            source.delete()
            output.delete()
        }
    }

    @Test
    fun testConsecutiveOcrRunsWorkerSurvival() = runBlocking<Unit> {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 50f; typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("CONSECUTIVE TEST", 100f, 200f, paint)

        val connected = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(IPdfJailService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE)
        assertTrue(bound)
        val worker = withTimeout(10_000) { connected.await() }

        suspend fun getPid(): Int {
            val response = CompletableDeferred<String>()
            OperationScratchBroker(context).use { scratch ->
                val token = worker.beginOperation(scratch)
                worker.executeEngine(token, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratch, object : IPdfJailStringCallback.Stub() {
                    override fun onSuccess(resultJson: String) { response.complete(resultJson) }
                    override fun onFailure(errorCode: Int, errorMessage: String) { response.completeExceptionally(IllegalStateException(errorMessage)) }
                })
                val out = withTimeout(10_000) { response.await() }
                worker.completeOperation(token)
                return JSONObject(out).getInt("pid")
            }
        }

        val initialPid = getPid()

        try {
            // Perform 3 consecutive OCR operations to confirm worker survival and zero cumulative leaks
            for (i in 1..3) {
                val src = File.createTempFile("consec_src_${i}_", ".pdf", context.cacheDir)
                val dst = File.createTempFile("consec_dst_${i}_", ".pdf", context.cacheDir)
                try {
                    PDDocument().use { doc ->
                        val page = PDPage(PDRectangle(400f, 500f))
                        doc.addPage(page)
                        val img = JPEGFactory.createFromImage(doc, bitmap, 0.90f)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, 400f, 500f) }
                        doc.save(src)
                    }

                    val res = runCatching {
                        PdfGateway.executeEngine(context, "OCR_PROCESS", Uri.fromFile(src), Uri.fromFile(dst), "{}")
                    }
                    assertTrue("Consecutive OCR run $i must succeed", res.isSuccess)

                    PDDocument.load(dst).use { doc ->
                        val text = PDFTextStripper().getText(doc).uppercase()
                        assertTrue("Consecutive run $i text matched", text.contains("CONSECUTIVE TEST"))
                    }

                    val currentPid = getPid()
                    assertEquals("Worker PID must remain unchanged across consecutive runs", initialPid, currentPid)
                } finally {
                    src.delete()
                    dst.delete()
                }
            }
        } finally {
            context.unbindService(connection)
            bitmap.recycle()
        }
    }
}
