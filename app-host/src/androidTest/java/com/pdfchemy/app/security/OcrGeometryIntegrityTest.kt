package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PdfGateway
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OcrGeometryIntegrityTest {
    @Test fun actualOcrExportPreservesFractionalBoxesCropRotationAndPixels() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("ocr_geometry_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("ocr_geometry_output_", ".pdf", context.cacheDir)
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.WHITE)
                val paint = Paint().apply { color = Color.BLUE }
                drawRect(0f, 0f, 55f, 55f, paint)
                paint.color = Color.RED; drawRect(545f, 345f, 600f, 400f, paint)
                paint.color = Color.BLACK; paint.textSize = 48f
                paint.typeface = Typeface.DEFAULT_BOLD; paint.isAntiAlias = true
                drawText("LOCAL DOCUMENT", 45f, 220f, paint)
            }
            PDDocument().use { doc ->
                val image = JPEGFactory.createFromImage(doc, bitmap, 0.98f)
                val pages = listOf(
                    PDPage(PDRectangle(300f, 200f)),
                    PDPage(PDRectangle(595.28f, 841.89f)),
                    PDPage(PDRectangle.LETTER).apply { rotation = 180 },
                    PDPage(PDRectangle(300f, 200f)).apply { cropBox = PDRectangle(30f, 20f, 240f, 160f); rotation = 90 },
                    PDPage(PDRectangle(300f, 200f)).apply { cropBox = PDRectangle(30f, 20f, 240f, 160f); rotation = 270 },
                    PDPage(PDRectangle(-20.125f, 10.875f, 360.655f, 240.875f)).apply {
                        cropBox = PDRectangle(10.125f, 30.25f, 280.375f, 190.625f)
                        cosObject.setFloat(COSName.USER_UNIT, 1.25f)
                    }
                )
                pages.forEach { page ->
                    doc.addPage(page)
                    val box = page.mediaBox
                    PDPageContentStream(doc, page).use {
                        it.drawImage(image, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                    }
                }
                doc.save(source)
            }
            val originalBytes = source.readBytes()
            PdfGateway.executeEngine(context, "OCR_PROCESS", Uri.fromFile(source), Uri.fromFile(output), "{}")
            assertArrayEquals("OCR must preserve its input", originalBytes, source.readBytes())
            PDDocument.load(source).use { original -> PDDocument.load(output).use { result ->
                assertEquals(6, result.numberOfPages)
                for (i in 0 until 6) {
                    val before = original.getPage(i); val after = result.getPage(i)
                    fun equalBox(label: String, left: PDRectangle, right: PDRectangle) {
                        assertArrayEquals("Page $i $label", floatArrayOf(left.lowerLeftX,left.lowerLeftY,left.upperRightX,left.upperRightY),
                            floatArrayOf(right.lowerLeftX,right.lowerLeftY,right.upperRightX,right.upperRightY), 0f)
                    }
                    equalBox("MediaBox", before.mediaBox, after.mediaBox)
                    equalBox("CropBox", before.cropBox, after.cropBox)
                    assertEquals("Page $i rotation", before.rotation, after.rotation)
                    assertEquals("Page $i UserUnit", before.cosObject.getFloat(COSName.USER_UNIT, 1f),
                        after.cosObject.getFloat(COSName.USER_UNIT, 1f), 0f)
                }
                assertTrue("Output needs an actual searchable text layer", PDFTextStripper().getText(result).contains("LOCAL"))
            } }
            fun renderer(file: File) = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
            renderer(source).use { original -> renderer(output).use { result ->
                for (i in 0 until 6) {
                    fun render(renderer: PdfRenderer): Bitmap = renderer.openPage(i).use { page ->
                        val scale = 800f / maxOf(page.width, page.height)
                        Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1), (page.height*scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    val a = render(original); val b = render(result)
                    try {
                        assertEquals(a.width,b.width); assertEquals(a.height,b.height)
                        val left = IntArray(a.width*a.height); val right = IntArray(left.size)
                        a.getPixels(left,0,a.width,0,0,a.width,a.height); b.getPixels(right,0,b.width,0,0,b.width,b.height)
                        var error = 0L
                        for (n in left.indices) for (shift in intArrayOf(0,8,16))
                            error += kotlin.math.abs(((left[n] ushr shift) and 255)-((right[n] ushr shift) and 255))
                        assertTrue("Page $i raster placement changed", error.toDouble()/(left.size*3) < 8.0)
                    } finally { a.recycle(); b.recycle() }
                }
            } }
        } finally { bitmap.recycle(); source.delete(); output.delete() }
    }
}
