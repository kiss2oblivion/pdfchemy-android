package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.ComicBookEngine
import com.pdfchemy.app.logic.IsolatedImageDecoder
import com.pdfchemy.app.logic.PlacedSignature
import com.pdfchemy.app.logic.SignatureEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RasterQuotaSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    // A valid 8192-square PNG representing 256 MiB of pixels, generated without
    // allocating the expanded bitmap in the host test process.
    private fun oversizedPng(): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(1 + 8192 * 4)
            repeat(8192) { stream.write(row) }
        }
        val result = ByteArrayOutputStream()
        DataOutputStream(result).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(name: String, data: ByteArray) {
                val type = name.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size); output.write(type); output.write(data)
                val crc = CRC32().apply { update(type); update(data) }
                output.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { it.writeInt(8192); it.writeInt(8192); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray()); chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        }
        return result.toByteArray()
    }

    @Test fun oversizedImageIsDownsampledByTheIsolatedDecoder() = runBlocking {
        val hostPid = Process.myPid()
        val bitmap = requireNotNull(IsolatedImageDecoder.decode(context, oversizedPng()))
        try { SecurityLimits.requirePixels(bitmap.width, bitmap.height) }
        finally { bitmap.recycle() }
        assertEquals(hostPid, Process.myPid())
    }

    @Test fun oversizedCbzImageProducesOnlyBoundedPdfImages() = runBlocking {
        val cbz = File.createTempFile("oversized_cbz_", ".cbz", context.cacheDir)
        val output = File.createTempFile("bounded_cbz_", ".pdf", context.cacheDir)
        try {
            ZipOutputStream(cbz.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page.png")); zip.write(oversizedPng()); zip.closeEntry()
            }
            ComicBookEngine.convertCbzToPdf(context, Uri.fromFile(cbz), Uri.fromFile(output)).getOrThrow()
            PDFBoxResourceLoader.init(context)
            PDDocument.load(output).use { document ->
                assertEquals(1, document.numberOfPages)
                val resources = document.getPage(0).resources
                val images = resources.xObjectNames.mapNotNull { resources.getXObject(it) as? PDImageXObject }
                assertTrue(images.isNotEmpty())
                images.forEach { SecurityLimits.requirePixels(it.width, it.height) }
            }
        } finally { cbz.delete(); output.delete() }
    }

    @Test fun oversizedSignatureIsRejectedBeforeDecodeAndLeavesHostAlive() = runBlocking {
        val source = File.createTempFile("signature_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("signature_output_", ".pdf", context.cacheDir)
        val hostPid = Process.myPid()
        try {
            PDFBoxResourceLoader.init(context)
            PDDocument().use { it.addPage(PDPage()); it.save(source) }
            val signature = PlacedSignature(pageIndex = 0, xRatio = 0.1f, yRatio = 0.1f, widthRatio = 0.2f, heightRatio = 0.1f, bitmapBytes = oversizedPng())
            assertFalse(SignatureEngine.applySignatures(context, Uri.fromFile(source), Uri.fromFile(output), listOf(signature)))
            assertEquals(0, output.length())
            assertEquals(hostPid, Process.myPid())
        } finally { source.delete(); output.delete() }
    }
}
