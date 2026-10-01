package com.pdfchemy.app.security

import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.IBinder
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrIsolationCompatibilityTest {
    @Test fun scannedTextRemainsSearchableThroughTheIsolatedOcrWorker() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("ocr_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("ocr_output_", ".pdf", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        val connected = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(IPdfJailService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE)
        try {
            assertTrue(bound)
            val worker = withTimeout(10_000) { connected.await() }
            suspend fun identity(): Int {
                val response = CompletableDeferred<String>()
                OperationScratchBroker(context).use { scratch ->
                    worker.executeEngine("DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratch, object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { response.complete(resultJson) }
                        override fun onFailure(errorCode: Int, errorMessage: String) { response.completeExceptionally(IllegalStateException(errorMessage)) }
                    })
                    return JSONObject(withTimeout(10_000) { response.await() }).getInt("pid")
                }
            }
            val workerPid = identity()
            PDFBoxResourceLoader.init(context)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText("LOCAL DOCUMENT", 80f, 250f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 86f; typeface = Typeface.DEFAULT_BOLD
            })
            PDDocument().use { document ->
                val page = PDPage(PDRectangle(600f, 800f)); document.addPage(page)
                val image = JPEGFactory.createFromImage(document, bitmap, 0.95f)
                PDPageContentStream(document, page).use { it.drawImage(image, 0f, 0f, 600f, 800f) }
                document.save(source)
            }
            val result = runCatching { PdfGateway.executeEngine(context, "OCR_PROCESS", Uri.fromFile(source), Uri.fromFile(output), "{}") }
            assertTrue("The isolated OCR worker must remain functional: ${result.exceptionOrNull()?.message}", result.isSuccess)
            PDDocument.load(output).use { document ->
                val text = PDFTextStripper().getText(document).uppercase()
                assertTrue("OCR output must contain a searchable text layer: $text", text.contains("LOCAL DOCUMENT"))
            }
            // Hold the original Binder across delayed GMS callbacks. A fresh
            // binding would hide a post-result isolated-process crash.
            repeat(6) {
                delay(1_000)
                assertEquals("OCR must keep the original worker alive", workerPid, identity())
            }
        } finally {
            if (bound) context.unbindService(connection)
            bitmap.recycle(); source.delete(); output.delete()
        }
    }
}
