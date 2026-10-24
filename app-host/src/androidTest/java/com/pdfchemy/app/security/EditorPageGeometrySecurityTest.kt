package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

/** Actual isolated Editor exports: PDF coordinates, rendered alignment and serialized privacy. */
@RunWith(AndroidJUnit4::class)
class EditorPageGeometrySecurityTest {
    private val secret = "EDITOR_GEOMETRY_PRIVATE_381729"
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun points300By200StayPoints() = verify(PDRectangle(300f, 200f))
    @Test fun a4KeepsOriginalBoxes() = verify(PDRectangle.A4)
    @Test fun letterKeepsOriginalBoxes() = verify(PDRectangle.LETTER)
    @Test fun fractionalPointsAreNotRoundedToPixels() = verify(PDRectangle(300.5f, 200.25f))
    @Test fun nonzeroMediaAndCropOffsetsSurvive() = verify(offsetMedia(), offsetCrop())
    @Test fun rotated90KeepsCoordinatesAndAlignment() = verify(offsetMedia(), offsetCrop(), 90)
    @Test fun rotated180KeepsCoordinatesAndAlignment() = verify(offsetMedia(), offsetCrop(), 180)
    @Test fun rotated270KeepsCoordinatesAndAlignment() = verify(offsetMedia(), offsetCrop(), 270)
    @Test fun requestedRotationIsAddedWithoutRebasingBoxes() = verify(offsetMedia(), offsetCrop(), 270, 90)

    private fun offsetMedia() = PDRectangle(-30f, 40f, 600f, 800f)
    private fun offsetCrop() = PDRectangle(50f, 100f, 300f, 200f)

    private fun inDisplayPlane(cs: PDPageContentStream, crop: PDRectangle, rotation: Int) {
        val x = crop.lowerLeftX; val y = crop.lowerLeftY; val w = crop.width; val h = crop.height
        cs.transform(when (rotation) {
            90 -> Matrix(0f, 1f, -1f, 0f, x+w, y)
            180 -> Matrix(-1f, 0f, 0f, -1f, x+w, y+h)
            270 -> Matrix(0f, -1f, 1f, 0f, x, y+h)
            else -> Matrix(1f, 0f, 0f, 1f, x, y)
        })
    }

    private fun fixture(file: File, media: PDRectangle, crop: PDRectangle?, rotation: Int) {
        PDFBoxResourceLoader.init(context)
        PDDocument().use { doc ->
            val page = PDPage(media).also(doc::addPage)
            if (crop != null) page.cropBox = crop
            page.rotation = rotation
            val visible = crop ?: media
            val w = if (rotation % 180 == 0) visible.width else visible.height
            val h = if (rotation % 180 == 0) visible.height else visible.width
            PDPageContentStream(doc, page).use { cs ->
                inDisplayPlane(cs, visible, rotation)
                // Public registration mark must survive at the same rendered coordinates.
                cs.setNonStrokingColor(0, 0, 255)
                cs.addRect(w*.08f, h*.8f, w*.08f, h*.08f); cs.fill()
                cs.setNonStrokingColor(0, 0, 0)
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, w*.018f)
                cs.newLineAtOffset(w*.3f, h*.5f); cs.showText(secret); cs.endText()
            }
            // A compressed private stream has two references; both must be detached.
            val metadata = PDMetadata(doc)
            metadata.cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(secret.toByteArray()) }
            doc.documentCatalog.metadata = metadata
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("RetainedMetadata"), metadata)
            doc.save(file)
        }
    }

    private fun assertBox(label: String, expected: PDRectangle, actual: PDRectangle) {
        assertEquals("$label left", expected.lowerLeftX, actual.lowerLeftX, .001f)
        assertEquals("$label bottom", expected.lowerLeftY, actual.lowerLeftY, .001f)
        assertEquals("$label right", expected.upperRightX, actual.upperRightX, .001f)
        assertEquals("$label top", expected.upperRightY, actual.upperRightY, .001f)
    }

    private fun assertPrivatePayloadAbsent(saved: PDDocument, bytes: ByteArray) {
        assertFalse(PDFTextStripper().getText(saved).contains(secret))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains(secret))
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun scan(value: COSBase?) {
            if (value == null || !seen.add(value)) return
            when (value) {
                is COSObject -> scan(value.`object`)
                is COSString -> assertFalse("Private string remains recoverable", value.string.contains(secret))
                is COSStream -> {
                    val decoded = value.createInputStream().use { it.readBytes() }
                    assertFalse("Private decoded stream remains recoverable", decoded.toString(Charsets.ISO_8859_1).contains(secret))
                    value.keySet().forEach { scan(value.getItem(it)) }
                }
                is COSDictionary -> value.keySet().forEach { scan(value.getItem(it)) }
                is COSArray -> (0 until value.size()).forEach { scan(value.get(it)) }
            }
        }
        scan(saved.document.trailer)
        saved.document.objects.forEach { scan(it) }
        assertNull(saved.documentCatalog.metadata)
        assertNull(saved.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("RetainedMetadata")))
    }

    private fun verify(media: PDRectangle, crop: PDRectangle? = null, rotation: Int = 0,
                       addedRotation: Int = 0) = runBlocking<Unit> {
        val source = File.createTempFile("editor_geometry_", ".pdf", context.cacheDir)
        val output = File.createTempFile("editor_geometry_export_", ".pdf", context.cacheDir)
        try {
            fixture(source, media, crop, rotation)
            val original = source.readBytes()
            PDDocument.load(source).use { input ->
                assertTrue("Positive text privacy control", PDFTextStripper().getText(input).contains(secret))
                assertTrue("Positive compressed private stream control", input.documentCatalog.metadata.cosObject
                    .createInputStream().use { it.readBytes().toString(Charsets.ISO_8859_1) }.contains(secret))
            }
            val mod = PageModification(0, rotationDegrees = addedRotation,
                drawings = listOf(DrawingPath(points = listOf(DrawingPoint(.1f,.8f), DrawingPoint(.3f,.8f)),
                    color = Color.GREEN, strokeWidth = 12f)),
                redactions = listOf(RedactionBox(0, .2f, .35f, .7f, .3f, overlayLabel = "")))
            assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).getOrThrow())
            assertArrayEquals(original, source.readBytes())
            PDDocument.load(output).use { saved ->
                assertEquals(1, saved.numberOfPages)
                val page = saved.getPage(0)
                assertBox("MediaBox", media, page.mediaBox)
                assertBox("CropBox", crop ?: media, page.cropBox)
                assertEquals((rotation + addedRotation) % 360, page.rotation)
                val images = page.resources.xObjectNames.map { page.resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
                assertEquals("Exercise real raster replacement", 1, images.size)
                assertTrue("Resolution remains independent of point dimensions", images.single().width > 400)
                assertPrivatePayloadAbsent(saved, output.readBytes())
            }
            val before = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(source), 0, 600))
            val after = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 600))
            val expected = android.graphics.Bitmap.createBitmap(before, 0, 0, before.width, before.height,
                android.graphics.Matrix().apply { postRotate(addedRotation.toFloat()) }, true)
            try {
                assertEquals(expected.width.toDouble()/expected.height, after.width.toDouble()/after.height, .005)
                fun centroid(bitmap: android.graphics.Bitmap, blue: Boolean): Pair<Double,Double> {
                    var x = 0L; var y = 0L; var n = 0L
                    for (py in 0 until bitmap.height) for (px in 0 until bitmap.width) {
                        val c = bitmap.getPixel(px,py)
                        if (if (blue) Color.blue(c)>120 && Color.red(c)<60 && Color.green(c)<60
                            else Color.green(c)>120 && Color.red(c)<60 && Color.blue(c)<60) { x+=px; y+=py; n++ }
                    }
                    assertTrue("Registration/annotation mark missing", n>20)
                    return Pair(x.toDouble()/n/bitmap.width, y.toDouble()/n/bitmap.height)
                }
                val publicBefore = centroid(expected, true); val publicAfter = centroid(after, true)
                assertEquals(publicBefore.first, publicAfter.first, .015)
                assertEquals(publicBefore.second, publicAfter.second, .015)
                // Added quarter-turn rotates normalized display coordinates clockwise.
                fun rotated(x: Float,y: Float) = if (addedRotation == 90) Pair(1-y,x) else Pair(x,y)
                val green = centroid(after, false); val expectedGreen = rotated(.2f,.8f)
                assertEquals(expectedGreen.first.toDouble(), green.first, .025)
                assertEquals(expectedGreen.second.toDouble(), green.second, .025)
                for (x in listOf(.22f,.5f,.88f)) for (y in listOf(.37f,.5f,.63f)) {
                    val point = rotated(x,y)
                    val color = after.getPixel((after.width*point.first).toInt(),(after.height*point.second).toInt())
                    assertEquals(255, Color.alpha(color))
                    assertTrue("Opaque redaction is misplaced: rotation=$rotation added=$addedRotation",
                        Color.red(color)<=3 && Color.green(color)<=3 && Color.blue(color)<=3)
                }
            } finally { if (expected !== before) expected.recycle(); before.recycle(); after.recycle() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun rasterQuotaFailurePreservesExistingDestination() = runBlocking<Unit> {
        val source = File.createTempFile("editor_unrenderable_", ".pdf", context.cacheDir)
        val output = File.createTempFile("editor_existing_", ".pdf", context.cacheDir)
        val existing = "EXISTING_DESTINATION_MUST_SURVIVE".toByteArray()
        try {
            // Extreme aspect ratio yields a zero-height bitmap at the bounded resolution.
            // It must fail the raster quota, never fall back to recoverable vector content.
            fixture(source, PDRectangle(100000f, 1f), null, 0)
            val original = source.readBytes()
            output.writeBytes(existing)
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel="")))
            assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).isFailure)
            assertArrayEquals(existing, output.readBytes())
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete(); output.delete() }
    }

    @Test fun compressedObjectStreamFormAliasCannotSurviveExport() = runBlocking<Unit> {
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("editor_objstm_", ".pdf", context.cacheDir)
        val output = File.createTempFile("editor_objstm_export_", ".pdf", context.cacheDir)
        try {
            // /V and a catalog alias share a string stored inside a real PDF 1.5 ObjStm.
            val bytes = java.io.ByteArrayOutputStream()
            fun emit(value: String) { bytes.write(value.toByteArray(Charsets.ISO_8859_1)) }
            val offsets = IntArray(12)
            emit("%PDF-1.5\n")
            fun obj(number: Int, body: String) { offsets[number]=bytes.size(); emit("$number 0 obj\n$body\nendobj\n") }
            val content = "BT /F1 8 Tf 30 100 Td ($secret) Tj ET\n"
            obj(1,"<< /Type /Catalog /Pages 2 0 R /AcroForm 5 0 R /AlternatePayload 7 0 R >>")
            obj(2,"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            obj(3,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 8 0 R >> >> /Annots [9 0 R] >>")
            obj(4,"<< /Length ${content.length} >>\nstream\n${content}endstream")
            obj(5,"<< /Fields [6 0 R] /NeedAppearances false /DA (/F1 8 Tf 0 g) /DR << /Font << /F1 8 0 R >> >> >>")
            obj(6,"<< /FT /Tx /T (private) /V 7 0 R /Kids [9 0 R] >>")
            obj(8,"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
            obj(9,"<< /Type /Annot /Subtype /Widget /Parent 6 0 R /Rect [20 90 280 110] /F 32 /P 3 0 R >>")
            val compressed = java.io.ByteArrayOutputStream().also { target ->
                java.util.zip.DeflaterOutputStream(target).use { it.write("7 0 ($secret)".toByteArray()) }
            }.toByteArray()
            offsets[10]=bytes.size()
            emit("10 0 obj\n<< /Type /ObjStm /N 1 /First 4 /Filter /FlateDecode /Length ${compressed.size} >>\nstream\n")
            bytes.write(compressed); emit("\nendstream\nendobj\n")
            offsets[11]=bytes.size()
            val xref = java.io.ByteArrayOutputStream()
            java.io.DataOutputStream(xref).use { data ->
                for (number in 0..11) {
                    data.writeByte(if (number == 0) 0 else if (number == 7) 2 else 1)
                    data.writeInt(if (number == 7) 10 else offsets[number])
                    data.writeShort(if (number == 0) 65535 else 0)
                }
            }
            emit("11 0 obj\n<< /Type /XRef /Size 12 /Root 1 0 R /W [1 4 2] /Length ${xref.size()} >>\nstream\n")
            bytes.write(xref.toByteArray()); emit("\nendstream\nendobj\nstartxref\n${offsets[11]}\n%%EOF\n")
            source.writeBytes(bytes.toByteArray())
            PDDocument.load(source).use { input ->
                assertTrue(PDFTextStripper().getText(input).contains(secret))
                assertEquals(secret, (input.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AlternatePayload")) as COSString).string)
                assertTrue(input.document.objects.any { (it.`object` as? COSStream)?.getCOSName(COSName.TYPE) == COSName.OBJ_STM })
            }
            val mod = PageModification(0, redactions=listOf(RedactionBox(0,0f,0f,1f,1f,overlayLabel="")))
            assertTrue(PdfEditor.exportModifiedPdf(context,Uri.fromFile(source),Uri.fromFile(output),mapOf(0 to mod)).getOrThrow())
            PDDocument.load(output).use { saved ->
                assertBox("MediaBox", PDRectangle(300f,200f), saved.getPage(0).mediaBox)
                assertNull(saved.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM))
                assertNull(saved.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("AlternatePayload")))
                assertPrivatePayloadAbsent(saved,output.readBytes())
            }
        } finally { source.delete(); output.delete() }
    }
}
