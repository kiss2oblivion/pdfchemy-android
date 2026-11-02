package com.pdfchemy.app.logic

import android.content.*
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.sandbox.IPdfNativeRendererService
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.w3c.dom.Element
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class ComicBookExportIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun pdf(pages: Int = 2) = File.createTempFile("cbz_integrity_", ".pdf", context.cacheDir).also { file ->
        PDFBoxResourceLoader.init(context)
        PDDocument().use { doc -> repeat(pages) { doc.addPage(PDPage(PDRectangle(300f, 200f))) }; doc.save(file) }
    }
    @Test fun pdfToCbzWritesEveryPageWithCropAndRotation() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = pdf(0)
        val output = File.createTempFile("comic_export_", ".cbz", context.cacheDir)
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        val aspects = listOf(1.5, 2.0 / 3, 2.0 / 3, 2.0)
        try {
            PDDocument().use { doc ->
                for (index in 0..3) {
                    val page = PDPage(if (index == 1) PDRectangle(200f, 300f) else PDRectangle(300f, 200f))
                    if (index == 2) page.rotation = 90
                    if (index == 3) page.cropBox = PDRectangle(30f, 20f, 200f, 100f)
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { stream ->
                        val c=colors[index];stream.setNonStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
                        stream.addRect(0f,0f,page.mediaBox.width,page.mediaBox.height);stream.fill()
                    }
                }
                doc.save(source)
            }
            val bytes=source.readBytes()
            val result=ComicBookEngine.convertPdfToCbz(context,Uri.fromFile(source),Uri.fromFile(output))
            assertTrue(result.exceptionOrNull()?.toString(),result.isSuccess)
            assertArrayEquals(bytes,source.readBytes())
            ZipFile(output).use { zip ->
                val entries=zip.entries().asSequence().toList()
                assertEquals((1..4).map { "page_%05d.jpg".format(java.util.Locale.ROOT,it) },entries.map { it.name })
                for ((index,entry) in entries.withIndex()) {
                    val bitmap=zip.getInputStream(entry).use { requireNotNull(BitmapFactory.decodeStream(it)) }
                    try {
                        assertEquals(aspects[index],bitmap.width.toDouble()/bitmap.height,2.0/minOf(bitmap.width,bitmap.height))
                        val pixel=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                        for (channel in listOf<(Int)->Int>(Color::red,Color::green,Color::blue))
                            assertTrue(kotlin.math.abs(channel(pixel)-channel(colors[index]))<=8)
                    } finally { bitmap.recycle() }
                }
            }
        } finally { source.delete();output.delete() }
    }
    @Test fun malformedAndZeroPageSourcesPreserveOldTarget() = runBlocking {
        val malformed=File.createTempFile("comic_bad_",".pdf",context.cacheDir).apply { writeText("malformed PDF") }
        val zero=pdf(0)
        val target=File.createTempFile("comic_old_",".cbz",context.cacheDir).apply { writeText("old destination") }
        try {
            for (source in listOf(malformed,zero)) {
                val bytes=source.readBytes()
                assertTrue(ComicBookEngine.convertPdfToCbz(context,Uri.fromFile(source),Uri.fromFile(target)).isFailure)
                assertEquals("old destination",target.readText());assertArrayEquals(bytes,source.readBytes())
            }
        } finally { malformed.delete();zero.delete();target.delete() }
    }
    private suspend fun rawFailure(renderer: IBinder?, expectedError: String? = null, deadline: Long = 15_000) {
        val sourceFile = pdf()
        val original = sourceFile.readBytes()
        val source = DocumentStager.stageDocument(context, Uri.fromFile(sourceFile))
        val target = File.createTempFile("cbz_old_", ".cbz", context.cacheDir).apply { writeText("old destination") }
        val connected = CompletableDeferred<IPdfJailService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { connected.complete(IPdfJailService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) { connected.completeExceptionally(IllegalStateException("Worker disconnected")) }
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try {
            val worker = withTimeout(15_000) { connected.await() }
            OperationScratchBroker(context).use { scratch ->
                HostOutputTransaction(context, listOf(Uri.fromFile(target)), scratch.storage).use { transaction ->
                    val token = worker.beginOperation(scratch)
                    check(token > 0)
                    try {
                        context.contentResolver.openFileDescriptor(source.uri, "r")!!.use { input ->
                            val response = CompletableDeferred<String>()
                            worker.executeEngine(token, "COMIC_BOOK", input, transaction.workerDescriptors.single(), "{\"action\":\"pdf2cbz\"}", renderer,
                                source.sha256, source.size, scratch, object : IPdfJailStringCallback.Stub() {
                                    override fun onSuccess(resultJson: String) { response.complete(resultJson) }
                                    override fun onFailure(code: Int, message: String) { response.complete(JSONObject().put("error", message).toString()) }
                                })
                            transaction.releaseWorkerDescriptors()
                            val json = withTimeout(deadline) { response.await() }
                            assertTrue("A missing/failed renderer must be reported: $json", JSONObject(json).has("error"))
                            if (expectedError != null) assertTrue(json, JSONObject(json).getString("error").contains(expectedError))
                            assertTrue(runCatching { transaction.validateAndSnapshot(json) }.isFailure)
                        }
                    } finally { worker.abortOperation(token) }
                }
            }
            assertEquals("old destination", target.readText())
            assertArrayEquals(original, sourceFile.readBytes())
        } finally { context.unbindService(connection); DocumentStager.release(source); sourceFile.delete(); target.delete() }
    }
    private fun renderer(count: Int = 2, fail: Boolean = false, jpeg: ByteArray? = null, trailingBytes: Long = 0) = object : IPdfNativeRendererService.Stub() {
        override fun getWorkerPid() = Process.myPid()
        override fun getWorkerUid() = Process.myUid()
        override fun abortWorker() {}
        override fun debugBlock(pdf: ParcelFileDescriptor, hash: String, size: Long) { pdf.close() }
        override fun getPageCount(pdf: ParcelFileDescriptor, hash: String, size: Long): Int { pdf.close(); return count }
        override fun renderPageToJpeg(pdf: ParcelFileDescriptor, index: Int, output: ParcelFileDescriptor, hash: String, size: Long) {
            try {
                if (fail) throw IllegalStateException("Synthetic renderer failure")
                ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                    it.write(jpeg ?: byteArrayOf(0xff.toByte(), 0xd8.toByte()))
                    val block = ByteArray(64 * 1024)
                    var left = trailingBytes
                    while (left > 0) { val size = minOf(block.size.toLong(), left).toInt(); it.write(block, 0, size); left -= size }
                }
            } catch (error: java.io.IOException) {
                if (trailingBytes == 0L) throw error // Oversize fixture acknowledges reader quota closure.
            } finally { pdf.close(); output.close() }
        }
        override fun renderPageToPixels(pdf: ParcelFileDescriptor, index: Int, output: ParcelFileDescriptor, hash: String, size: Long, width: Int) {
            pdf.close(); output.close(); throw UnsupportedOperationException()
        }
    }
    @Test fun missingRendererFailsBeforePublication() = runBlocking { rawFailure(null) }
    @Test fun rendererPageCountMismatchFailsBeforePublication() = runBlocking { rawFailure(renderer(count = 0)) }
    @Test fun truncatedJpegFailsBeforePublication() = runBlocking { rawFailure(renderer()) }
    @Test fun rendererExceptionFailsBeforePublication() = runBlocking { rawFailure(renderer(fail = true)) }

    private fun validJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)); it.toByteArray() } }
        finally { bitmap.recycle() }
    }
    @Test fun oversizedJpegDimensionsFailBeforePublication() = runBlocking {
        val jpeg = validJpeg()
        val marker = (0 until jpeg.size - 8).first { (jpeg[it].toInt() and 255) == 255 && (jpeg[it + 1].toInt() and 255) in listOf(0xc0, 0xc1, 0xc2) }
        for (offset in listOf(5, 7)) { jpeg[marker + offset] = 0x4e; jpeg[marker + offset + 1] = 0x20 }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)?.recycle()
        assertEquals(20000, bounds.outWidth); assertEquals(20000, bounds.outHeight)
        rawFailure(renderer(jpeg = jpeg), "Raster quota exceeded")
    }
    @Test fun imageByteQuotaFailsBeforePublication() = runBlocking {
        // Stream one over the real 250 MiB image budget; never allocate that payload in memory.
        rawFailure(renderer(jpeg = validJpeg(), trailingBytes = com.pdfchemy.app.security.SecurityLimits.MAX_OUTPUT_BYTES),
            "Presentation image quota exceeded", com.pdfchemy.app.security.SecurityLimits.WORKER_DEADLINE_MS + 5000)
    }
}
