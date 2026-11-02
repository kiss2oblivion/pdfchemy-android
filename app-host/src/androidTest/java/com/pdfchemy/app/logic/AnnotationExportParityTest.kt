package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.*
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AnnotationExportParityTest {
    // Small fixture authored as bytes: the host never imports a PDF parser.
    private fun fixture(rotation: Int): ByteArray {
        val stream = "q 1 1 1 rg 0 0 400 600 re f Q\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 600] /Rotate $rotation /Resources << >> /Contents 4 0 R >>",
            "<< /Length ${stream.length} >>\nstream\n${stream}endstream")
        val pdf = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, value -> offsets += pdf.length; pdf.append("${i+1} 0 obj\n$value\nendobj\n") }
        val xref = pdf.length
        pdf.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("%010d 00000 n \n".format(java.util.Locale.US, it)) }
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun centroid(bitmap: Bitmap, channel: Int): Pair<Double,Double> {
        var x = 0L; var y = 0L; var count = 0L
        for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
            val c = bitmap.getPixel(px,py)
            val values = intArrayOf(Color.red(c),Color.green(c),Color.blue(c))
            if (values[channel] > 90 && values[channel] > values[(channel+1)%3]*1.6 && values[channel] > values[(channel+2)%3]*1.6) {
                x += px; y += py; count++
            }
        }
        assertTrue("Missing annotation channel $channel", count > 20)
        return (x.toDouble()/count/bitmap.width) to (y.toDouble()/count/bitmap.height)
    }

    @Test(timeout=240000) fun exportedMixedAnnotationsFollowExistingAndAddedRotations() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (rotation in listOf(0,90,180,270)) for (redact in listOf(false,true)) {
            val source = File.createTempFile("ux_parity_", ".pdf", context.cacheDir)
            val output = File.createTempFile("ux_export_", ".pdf", context.cacheDir)
            val held = mutableListOf<Bitmap>()
            try {
                source.writeBytes(fixture(rotation))
                val mod = PageModification(0, rotationDegrees=90,
                    drawings=listOf(DrawingPath(points=listOf(DrawingPoint(.1f,.2f),DrawingPoint(.3f,.2f)),color=Color.RED,strokeWidth=20f)),
                    textAnnotations=listOf(TextAnnotation(text="TEXT", xRatio=.2f,yRatio=.4f,fontSize=28f,textColor=Color.BLUE)),
                    stamps=listOf(StampAnnotation(type=StampType.APPROVED,xRatio=.7f,yRatio=.7f,scale=2f,rotation=0f)),
                    redactions=if (redact) listOf(RedactionBox(0,RectF(.45f,.45f,.55f,.55f),overlayLabel="HIDDEN")) else emptyList())
                val base = PdfEditor.renderPageBitmap(context,Uri.fromFile(source),0,600)!!; held += base
                val overlay = AnnotationRenderer.render(mod,base.width,base.height)!!; held += overlay
                Canvas(base).drawBitmap(overlay,0f,0f,null)
                val expected = Bitmap.createBitmap(base,0,0,base.width,base.height,Matrix().apply { postRotate(90f) },true); held += expected
                assertTrue("Export rotation=$rotation redaction=$redact", PdfEditor.exportModifiedPdf(context,Uri.fromFile(source),Uri.fromFile(output),mapOf(0 to mod)).getOrThrow())
                val actual = PdfEditor.renderPageBitmap(context,Uri.fromFile(output),0,600)!!; held += actual
                assertEquals(expected.width.toDouble()/expected.height, actual.width.toDouble()/actual.height, .005)
                for (channel in 0..2) {
                    val a = centroid(actual,channel); val e = centroid(expected,channel)
                    assertEquals("x rotation=$rotation redact=$redact channel=$channel", e.first,a.first,.025)
                    assertEquals("y rotation=$rotation redact=$redact channel=$channel", e.second,a.second,.025)
                }
                // Test the opaque block away from its deliberately white label glyphs.
                if (redact) for (x in listOf(.46f,.54f)) for (y in listOf(.46f,.54f)) {
                    val pixel = actual.getPixel((actual.width*x).toInt(),(actual.height*y).toInt())
                    assertEquals("Opaque redaction rotation=$rotation", 255, Color.alpha(pixel))
                    // API 24 PdfRenderer maps embedded black to #020202. Require opaque
                    // near-black at every corner; retain the independent geometry checks.
                    assertTrue("Black redaction rotation=$rotation pixel=${Integer.toHexString(pixel)}",
                        Color.red(pixel) <= 3 && Color.green(pixel) <= 3 && Color.blue(pixel) <= 3)
                }
            } finally { held.distinct().forEach { it.recycle() }; source.delete(); output.delete() }
        }
    }
}
