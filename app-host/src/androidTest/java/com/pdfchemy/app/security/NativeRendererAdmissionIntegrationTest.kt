package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NativeRendererAdmissionIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun fixture(block: suspend (android.net.Uri) -> Unit) {
        PDFBoxResourceLoader.init(context)
        val bytes = ByteArrayOutputStream().use { buffer ->
            PDDocument().use { pdf -> repeat(4) { pdf.addPage(PDPage(PDRectangle(500f, 700f))) }; pdf.save(buffer) }
            buffer.toByteArray()
        }
        val staged = DocumentStager.stageStreamWithLease(context, bytes.inputStream(), "pdf")
        try {
            block(staged.document.uri)
            assertArrayEquals(bytes, java.io.File(staged.document.uri.path!!).readBytes())
        } finally { staged.close(); DocumentStager.release(staged.document) }
    }

    @Test fun sequentialFramesKeepEveryPageReadable() = runBlocking {
        fixture { uri ->
            repeat(4) { page ->
                val bitmap = NativeRendererCoordinator.renderUriToBitmap(context, uri, page, 600)
                try {
                    assertNotNull("Actual page $page must render", bitmap)
                    assertEquals(600, bitmap!!.width); assertEquals(840, bitmap.height)
                } finally { bitmap?.recycle() }
            }
        }
    }

    @Test fun overlappingHostFramesAllCompleteWithoutWorkerBusyFailures() = runBlocking {
        fixture { uri ->
            val start = CompletableDeferred<Unit>()
            val requests = (0..2).map { page -> async(Dispatchers.IO) {
                start.await()
                runCatching { NativeRendererCoordinator.renderUriToBitmap(context, uri, page, 1000) }
            } }
            start.complete(Unit)
            val results = requests.awaitAll()
            try {
                val failures = results.mapNotNull { it.exceptionOrNull() }
                assertTrue("Concurrent host preview requests must be admitted in order: " +
                    failures.joinToString { "${it.javaClass.simpleName}: ${it.message}" }, failures.isEmpty())
                for ((page, result) in results.withIndex()) {
                    val bitmap = result.getOrThrow()
                    assertNotNull("Concurrent page $page must render", bitmap)
                    assertEquals(1000, bitmap!!.width); assertEquals(1400, bitmap.height)
                }
            } finally { results.forEach { it.getOrNull()?.recycle() } }
        }
    }
}
