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
class PowerPointIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private val presentation = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private val relationships = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private fun pdf(pages: Int = 2) = File.createTempFile("ppt_integrity_", ".pdf", context.cacheDir).also { file ->
        PDFBoxResourceLoader.init(context)
        PDDocument().use { doc -> repeat(pages) { doc.addPage(PDPage(PDRectangle(300f, 200f))) }; doc.save(file) }
    }
    private fun xml(zip: ZipFile, name: String) = zip.getInputStream(requireNotNull(zip.getEntry(name))).use {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it)
    }

    /** Only synthetic fixtures, opt-in and bounded; permits independent inspection of non-debuggable exports. */
    private fun collectFixture(key: String, source: File, output: File) {
        if (InstrumentationRegistry.getArguments().getString("collectOfficeFixture") != "true" || Build.VERSION.SDK_INT < 26) return
        val result = Bundle()
        for ((extension, file) in listOf("pdf" to source, "pptx" to output)) {
            check(file.length() in 1..(512 * 1024))
            result.putString("fixture_" + key + "_" + extension,
                android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
        }
        InstrumentationRegistry.getInstrumentation().addResults(result)
    }
    @Test fun mixedPageAspectRatiosCropAndRotationRemainVisible() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val phase = InstrumentationRegistry.getArguments().getString("follow10EvidenceRun", "ci")
        require(phase.matches(Regex("[a-z0-9-]+")))
        val source = File(context.cacheDir, "closure28_office_$phase.pdf")
        val output = File(context.cacheDir, "closure28_office_$phase.pptx")
        val colors = listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW)
        val aspects = listOf(1.5, 2.0 / 3, 2.0 / 3, 2.0)
        PDDocument().use { doc ->
            for (index in 0..3) {
                val page = PDPage(if (index == 1) PDRectangle(200f, 300f) else PDRectangle(300f, 200f))
                if (index == 2) page.rotation = 90
                if (index == 3) page.cropBox = PDRectangle(30f, 20f, 200f, 100f)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    val color = colors[index]
                    stream.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
                    stream.addRect(0f, 0f, page.mediaBox.width, page.mediaBox.height); stream.fill()
                }
            }
            doc.save(source)
        }
        val original = source.readBytes()
        val result = OfficeExportEngine.exportToPowerPoint(context, Uri.fromFile(source), Uri.fromFile(output))
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals(4, result.getOrThrow().pageCount)
        assertArrayEquals(original, source.readBytes())
        // Keep these named, bounded fixture artifacts for independent desktop parsing.
        ZipFile(output).use { zip ->
            val main = xml(zip, "ppt/presentation.xml")
            assertEquals(4, main.getElementsByTagNameNS(presentation, "sldId").length)
            val size = main.getElementsByTagNameNS(presentation, "sldSz").item(0) as Element
            val slideWidth = size.getAttribute("cx").toLong(); val slideHeight = size.getAttribute("cy").toLong()
            for (index in 0..3) {
                val slide = xml(zip, "ppt/slides/slide${index + 1}.xml")
                val pictures = slide.getElementsByTagNameNS(presentation, "pic")
                assertEquals("Page ${index + 1} has no visible page picture", 1, pictures.length)
                val picture = pictures.item(0) as Element
                val blip = picture.getElementsByTagNameNS(drawing, "blip").item(0) as Element
                val id = blip.getAttributeNS(relationships, "embed")
                assertTrue(id.isNotBlank())
                val rels = xml(zip, "ppt/slides/_rels/slide${index + 1}.xml.rels")
                    .getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship")
                val relation = (0 until rels.length).map { rels.item(it) as Element }.single { it.getAttribute("Id") == id }
                assertEquals("$relationships/image", relation.getAttribute("Type"))
                val mediaPath = "ppt/" + relation.getAttribute("Target").removePrefix("../")
                val data = zip.getInputStream(requireNotNull(zip.getEntry(mediaPath))).use { it.readBytes() }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(data, 0, data.size))
                try {
                    assertEquals("The page image itself must preserve aspect ratio", aspects[index],
                        bitmap.width.toDouble() / bitmap.height, 2.0 / minOf(bitmap.width, bitmap.height))
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                        assertTrue("Slide ${index + 1} contains another page's image", kotlin.math.abs(channel(pixel) - channel(colors[index])) <= 8)
                    }
                } finally { bitmap.recycle() }
                val transform = picture.getElementsByTagNameNS(drawing, "xfrm").item(0) as Element
                val extent = transform.getElementsByTagNameNS(drawing, "ext").item(0) as Element
                val offset = transform.getElementsByTagNameNS(drawing, "off").item(0) as Element
                val width = extent.getAttribute("cx").toLong(); val height = extent.getAttribute("cy").toLong()
                val x = offset.getAttribute("x").toLong(); val y = offset.getAttribute("y").toLong()
                assertEquals(aspects[index], width.toDouble() / height, 0.00001)
                assertTrue(x >= 0 && y >= 0 && width > 0 && height > 0 && x + width <= slideWidth && y + height <= slideHeight)
            }
        }
        collectFixture("mixed", source, output)
    }
    @Test fun blankA4LetterAndRemainingRotationsHaveVisiblePagePictures() = runBlocking {
        val source = File.createTempFile("ppt_acceptance_", ".pdf", context.cacheDir)
        val target = File.createTempFile("ppt_acceptance_", ".pptx", context.cacheDir)
        PDFBoxResourceLoader.init(context)
        val aspects = listOf(595.27563 / 841.8898, 612.0 / 792, 1.5, 0.5)
        try {
            PDDocument().use { document ->
                for (index in 0..3) {
                    val page = PDPage(when (index) { 0 -> PDRectangle.A4; 1 -> PDRectangle.LETTER; else -> PDRectangle(300f, 200f) })
                    if (index == 2) page.rotation = 180
                    if (index == 3) { page.cropBox = PDRectangle(30f, 20f, 200f, 100f); page.rotation = 270 }
                    document.addPage(page)
                    if (index >= 2) PDPageContentStream(document, page).use {
                        val color = if (index == 2) Color.RED else Color.BLUE
                        it.setNonStrokingColor(Color.red(color), Color.green(color), Color.blue(color))
                        it.addRect(0f, 0f, 300f, 200f); it.fill()
                    }
                }
                document.save(source)
            }
            val original = source.readBytes()
            val result = OfficeExportEngine.exportToPowerPoint(context, Uri.fromFile(source), Uri.fromFile(target))
            assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
            assertEquals(4, result.getOrThrow().pageCount)
            assertArrayEquals(original, source.readBytes())
            ZipFile(target).use { zip ->
                assertEquals(4, xml(zip, "ppt/presentation.xml").getElementsByTagNameNS(presentation, "sldId").length)
                for (index in 0..3) {
                    val slide = xml(zip, "ppt/slides/slide${index + 1}.xml")
                    assertEquals(1, slide.getElementsByTagNameNS(presentation, "pic").length)
                    assertEquals(1, slide.getElementsByTagNameNS(drawing, "blip").length)
                    val bitmap = zip.getInputStream(zip.getEntry("ppt/media/image${index + 1}.jpeg")).use { BitmapFactory.decodeStream(it) }!!
                    try {
                        assertEquals(aspects[index], bitmap.width.toDouble() / bitmap.height, 2.0 / minOf(bitmap.width, bitmap.height))
                        val expected = if (index < 2) Color.WHITE else if (index == 2) Color.RED else Color.BLUE
                        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue))
                            assertTrue(kotlin.math.abs(channel(expected) - channel(center)) <= 8)
                    } finally { bitmap.recycle() }
                }
            }
            collectFixture("blank", source, target)
        } finally { source.delete(); target.delete() }
    }

    @Test fun malformedAndZeroPageSourcesPreserveOldTarget() = runBlocking {
        val malformed = File.createTempFile("ppt_bad_", ".pdf", context.cacheDir).apply { writeText("malformed PDF") }
        val zero = pdf(0)
        val target = File.createTempFile("ppt_old_", ".pptx", context.cacheDir).apply { writeText("old destination") }
        try {
            for (source in listOf(malformed, zero)) {
                val bytes = source.readBytes()
                assertTrue(OfficeExportEngine.exportToPowerPoint(context, Uri.fromFile(source), Uri.fromFile(target)).isFailure)
                assertEquals("old destination", target.readText())
                assertArrayEquals(bytes, source.readBytes())
            }
        } finally { malformed.delete(); zero.delete(); target.delete() }
    }
    private suspend fun rawFailure(renderer: IBinder?, expectedError: String? = null, deadline: Long = 15_000) {
        val sourceFile = pdf()
        val original = sourceFile.readBytes()
        val source = DocumentStager.stageDocument(context, Uri.fromFile(sourceFile))
        val target = File.createTempFile("ppt_old_", ".pptx", context.cacheDir).apply { writeText("old destination") }
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
                            worker.executeEngine(token, "OFFICE_PPT", input, transaction.workerDescriptors.single(), "{}", renderer,
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
