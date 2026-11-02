package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.RedactionBox
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorRedactionResourceSecurityTest {
    private fun runFixture(scan: Boolean, inherited: Boolean, formField: Boolean = false, documentCarriers: Boolean = false) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc01_input_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc01_output_", ".pdf", context.cacheDir)
        val secret = "SC01_ORIGINAL_PRIVATE_RESOURCE_731904"
        val pixels = ByteArray(4 * 4 * 3) { ((it * 37 + 19) % 256).toByte() }
        try {
            PDDocument().use { doc ->
                val target = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                if (documentCarriers) {
                    doc.documentInformation.subject = secret
                    doc.documentCatalog.metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(doc).apply {
                        importXMPMetadata(("<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF " +
                            "xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description " +
                            "xmlns:dc='http://purl.org/dc/elements/1.1/' dc:description='$secret'/>" +
                            "</rdf:RDF></x:xmpmeta>").toByteArray())
                    }
                    doc.documentCatalog.cosObject.setItem(COSName.getPDFName("RetainedMetadata"), doc.documentCatalog.metadata)
                    val original = doc.document.createCOSStream().apply {
                        setName(COSName.TYPE, "EmbeddedFile")
                        createOutputStream().use { it.write(secret.toByteArray()) }
                    }
                    val specification = COSDictionary().apply {
                        setName(COSName.TYPE, "Filespec"); setString(COSName.F, "original.pdf")
                        setName(COSName.getPDFName("AFRelationship"), "Source")
                        setItem(COSName.getPDFName("EF"), COSDictionary().apply { setItem(COSName.F, original) })
                    }
                    doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply {
                        setItem(COSName.getPDFName("EmbeddedFiles"), COSDictionary().apply {
                            setItem(COSName.NAMES, COSArray().apply { add(COSString("original.pdf")); add(specification) })
                        })
                    })
                    doc.documentCatalog.cosObject.setItem(COSName.getPDFName("AF"), COSArray().apply { add(specification) })
                    doc.documentCatalog.cosObject.setItem(COSName.getPDFName("RetainedEmbeddedStream"), original)
                    val structure = COSDictionary().apply { setName(COSName.TYPE, "StructTreeRoot") }
                    val element = COSDictionary().apply {
                        setName(COSName.TYPE, "StructElem"); setName(COSName.S, "Span")
                        setItem(COSName.P, structure); setItem(COSName.getPDFName("Pg"), target)
                        setInt(COSName.K, 0); setString(COSName.getPDFName("ActualText"), secret)
                    }
                    structure.setItem(COSName.K, COSArray().apply { add(element) })
                    structure.setItem(COSName.getPDFName("ParentTree"), COSDictionary().apply {
                        setItem(COSName.getPDFName("Nums"), COSArray().apply {
                            add(COSInteger.ZERO); add(COSArray().apply { add(element) })
                        })
                    })
                    structure.setInt(COSName.getPDFName("ParentTreeNextKey"), 1)
                    target.cosObject.setInt(COSName.getPDFName("StructParents"), 0)
                    doc.documentCatalog.cosObject.setItem(COSName.getPDFName("StructTreeRoot"), structure)
                    doc.documentCatalog.cosObject.setItem(COSName.getPDFName("MarkInfo"), COSDictionary().apply {
                        setBoolean(COSName.getPDFName("Marked"), true)
                    })
                    val outlines = COSDictionary().apply { setName(COSName.TYPE, "Outlines"); setInt(COSName.COUNT, 1) }
                    val bookmark = COSDictionary().apply {
                        setString(COSName.TITLE, "Keep navigation"); setItem(COSName.PARENT, outlines)
                        setItem(COSName.DEST, COSArray().apply { add(target); add(COSName.getPDFName("Fit")) })
                        setItem(COSName.getPDFName("SE"), element)
                    }
                    outlines.setItem(COSName.FIRST, bookmark); outlines.setItem(COSName.LAST, bookmark)
                    doc.documentCatalog.cosObject.setItem(COSName.OUTLINES, outlines)
                }
                val resources = PDResources()
                if (inherited) doc.pages.cosObject.setItem(COSName.RESOURCES, resources)
                else target.resources = resources
                if (formField) {
                    val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc).apply {
                        defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
                        defaultAppearance = "/Helv 12 Tf 0 g"
                    }
                    doc.documentCatalog.acroForm = form
                    fun addField(onPage: PDPage, name: String, value: String): com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField {
                        val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
                        field.partialName = name
                        form.fields.add(field)
                        field.widgets.first().apply {
                            rectangle = PDRectangle(10f, 50f, 180f, 100f)
                            page = onPage
                            onPage.annotations.add(this)
                        }
                        field.value = value
                        return field
                    }
                    val privateField = addField(target, "privateField", secret)
                    doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, COSDictionary().apply {
                        setName(COSName.S, "ResetForm")
                        setItem(COSName.FIELDS, COSArray().apply { add(privateField.cosObject) })
                    })
                    val other = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                    addField(other, "publicField", "KEEP_FORM_VALUE")
                } else if (scan) {
                    val image = PDImageXObject(doc, ByteArrayInputStream(pixels), null, 4, 4, 8, PDDeviceRGB.INSTANCE)
                    image.cosObject.setString(COSName.getPDFName("AuditOriginal"), secret)
                    PDPageContentStream(doc, target).use { it.drawImage(image, 0f, 0f, 200f, 200f) }
                } else {
                    val form = PDFormXObject(doc).apply {
                        this.resources = PDResources()
                        bBox = PDRectangle(200f, 200f)
                    }
                    form.resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                    form.cosObject.createOutputStream().use {
                        it.write("BT /F1 10 Tf 10 100 Td ($secret) Tj ET".toByteArray(Charsets.US_ASCII))
                    }
                    PDPageContentStream(doc, target).use {
                        if (documentCarriers) it.beginMarkedContent(COSName.getPDFName("Span"),
                            com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(
                                COSDictionary().apply { setInt(COSName.getPDFName("MCID"), 0) }))
                        it.drawForm(form)
                        if (documentCarriers) it.endMarkedContent()
                    }
                }
                if (inherited) {
                    val other = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                    PDPageContentStream(doc, other).use {
                        it.beginText(); it.setFont(PDType1Font.HELVETICA, 10f)
                        it.newLineAtOffset(10f, 100f); it.showText("KEEP_VECTOR_PAGE"); it.endText()
                    }
                }
                doc.save(source)
            }
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
            assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).getOrThrow())
            PDDocument.load(output).use { saved ->
                val text = PDFTextStripper().getText(saved)
                assertFalse(text.contains(secret))
                if (inherited) assertTrue(text.contains("KEEP_VECTOR_PAGE"))
                assertFalse(String(output.readBytes(), Charsets.ISO_8859_1).contains(secret))
                for (obj in saved.document.objects) {
                    val stream = obj.`object` as? COSStream ?: continue
                    val bytes = stream.createInputStream().use { it.readBytes() }
                    assertFalse("Original decoded Form must be absent", String(bytes, Charsets.ISO_8859_1).contains(secret))
                    if (scan) assertFalse("Original scan pixels must be absent", bytes.contentEquals(pixels))
                }
                if (formField) {
                    assertTrue("Unredacted field appearance must survive as visible text", text.contains("KEEP_FORM_VALUE"))
                    assertNull("Redacted exports must not retain interactive form values", saved.documentCatalog.getAcroForm(null))
                }
                if (documentCarriers) {
                    val outlines = saved.documentCatalog.cosObject.getDictionaryObject(COSName.OUTLINES) as COSDictionary
                    val bookmark = outlines.getDictionaryObject(COSName.FIRST) as COSDictionary
                    assertEquals("Keep navigation", bookmark.getString(COSName.TITLE))
                    assertNull(bookmark.getDictionaryObject(COSName.getPDFName("SE")))
                    val destination = bookmark.getDictionaryObject(COSName.DEST) as COSArray
                    assertSame("Bookmark must still target the sanitized page", saved.getPage(0).cosObject, destination.getObject(0))
                }
            }
            val bitmap = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 0, 200))
            try {
                val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                assertTrue("Redaction must remain opaque black", android.graphics.Color.alpha(center) == 255 &&
                    android.graphics.Color.red(center) <= 3 && android.graphics.Color.green(center) <= 3 && android.graphics.Color.blue(center) <= 3)
            } finally { bitmap.recycle() }
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout = 180000) fun formCannotSurviveRealEditorRedactionExport() = runFixture(false, false)
    @Test(timeout = 180000) fun originalScanCannotSurviveRealEditorRedactionExport() = runFixture(true, false)
    @Test(timeout = 180000) fun inheritedUnusedFormIsRemovedAndOtherVectorPageSurvives() = runFixture(false, true)
    @Test(timeout = 180000) fun formValuesCannotSurviveRealEditorRedactionExport() = runFixture(false, false, true)
    @Test(timeout = 180000) fun documentMetadataAndAccessibilityTextCannotSurviveRedaction() = runFixture(false, false, documentCarriers = true)

    @Test(timeout = 180000) fun unsupportedXfaCannotOverwriteAnExistingDestination() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc01_xfa_input_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc01_xfa_output_", ".pdf", context.cacheDir)
        val existing = "EXISTING_DOCUMENT_MUST_SURVIVE".toByteArray()
        try {
            PDDocument().use { doc ->
                doc.addPage(PDPage(PDRectangle(200f, 200f)))
                val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
                doc.documentCatalog.acroForm = form
                val xfa = doc.document.createCOSStream()
                xfa.createOutputStream().use {
                    it.write(("<?xml version=\"1.0\"?><xfa:datasets xmlns:xfa=\"http://www.xfa.org/schema/xfa-data/1.0/\">" +
                        "<xfa:data><privateValue>PRIVATE_XFA_VALUE</privateValue></xfa:data></xfa:datasets>").toByteArray())
                }
                form.cosObject.setItem(COSName.XFA, xfa)
                doc.save(source)
            }
            output.writeBytes(existing)
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
            val result = PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod))
            assertTrue("Unsupported XFA must abort secure redaction", result.isFailure || result.getOrNull() != true)
            assertArrayEquals("An unsuccessful export must preserve the existing bytes", existing, output.readBytes())
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout = 180000) fun clippedSharedFormCannotRetainTheHiddenOriginalText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc01_shared_clip_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc01_shared_clip_output_", ".pdf", context.cacheDir)
        val secret = "SC01_SHARED_CLIPPED_PRIVATE_571906"
        try {
            PDDocument().use { doc ->
                val first = PDPage(PDRectangle(200f, 100f)).also(doc::addPage)
                val second = PDPage(PDRectangle(200f, 100f)).also(doc::addPage)
                val third = PDPage(PDRectangle(200f, 100f)).also(doc::addPage)
                val shared = PDFormXObject(doc).apply {
                    resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                    bBox = PDRectangle(200f, 100f)
                }
                shared.cosObject.createOutputStream().use {
                    it.write(("BT /F1 3 Tf 5 50 Td ($secret) Tj ET " +
                        "BT /F1 10 Tf 110 50 Td (KEEP_VISIBLE) Tj ET").toByteArray())
                }
                PDPageContentStream(doc, first).use { it.drawForm(shared) }
                PDPageContentStream(doc, second).use {
                    it.addRect(100f, 0f, 100f, 100f); it.clip(); it.drawForm(shared)
                }
                PDPageContentStream(doc, third).use {
                    it.beginText(); it.setFont(PDType1Font.HELVETICA, 10f)
                    it.newLineAtOffset(5f, 50f); it.showText("KEEP_UNRELATED_VECTOR"); it.endText()
                }
                val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc).apply {
                    defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
                    defaultAppearance = "/Helv 8 Tf 0 g"
                }
                doc.documentCatalog.acroForm = form
                val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
                field.partialName = "missingDependentAppearance"
                form.fields.add(field)
                field.widgets.first().apply {
                    rectangle = PDRectangle(110f, 10f, 80f, 20f)
                    page = second
                    second.annotations.add(this)
                }
                field.value = "KEEP_NEW_FIELD"
                field.widgets.first().cosObject.removeItem(COSName.AP)
                doc.save(source)
            }
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
            assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod)).getOrThrow())
            PDDocument.load(output).use { saved ->
                for (obj in saved.document.objects) {
                    val stream = obj.`object` as? COSStream ?: continue
                    val decoded = stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    assertFalse("Clipping must not leave private shared stream data", decoded.contains(secret))
                }
                assertTrue(PDFTextStripper().getText(saved).contains("KEEP_UNRELATED_VECTOR"))
            }
            val bitmap = requireNotNull(PdfEditor.renderPageBitmap(context, Uri.fromFile(output), 1, 400))
            try {
                var ink = 0
                for (y in 0 until bitmap.height) for (x in bitmap.width / 2 until bitmap.width) {
                    val color = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(color) < 100 && android.graphics.Color.green(color) < 100 &&
                        android.graphics.Color.blue(color) < 100) ink++
                }
                assertTrue("Visible content on the dependent page must survive rasterization", ink > 10)
                var fieldInk = 0
                for (y in bitmap.height * 7 / 10 until bitmap.height * 9 / 10) {
                    for (x in bitmap.width * 55 / 100 until bitmap.width * 95 / 100) {
                        val color = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(color) < 100 && android.graphics.Color.green(color) < 100 &&
                            android.graphics.Color.blue(color) < 100) fieldInk++
                    }
                }
                assertTrue("Generated form appearances must survive dependent-page rasterization", fieldInk > 10)
            } finally { bitmap.recycle() }
        } finally { source.delete(); output.delete() }
    }

    @Test(timeout = 180000) fun catalogReferenceToOriginalStreamCannotOverwriteExistingDestination() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("sc01_catalog_", ".pdf", context.cacheDir)
        val output = File.createTempFile("sc01_catalog_output_", ".pdf", context.cacheDir)
        val existing = "EXISTING_CATALOG_DESTINATION".toByteArray()
        try {
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(200f, 100f)).also(doc::addPage)
                val form = PDFormXObject(doc).apply {
                    resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                    bBox = PDRectangle(200f, 100f)
                }
                form.cosObject.createOutputStream().use {
                    it.write("BT /F1 12 Tf 5 50 Td (PRIVATE_CATALOG_STREAM) Tj ET".toByteArray())
                }
                PDPageContentStream(doc, page).use { it.drawForm(form) }
                doc.documentCatalog.cosObject.setItem(COSName.getPDFName("RetainedOriginal"), form)
                doc.save(source)
            }
            output.writeBytes(existing)
            val mod = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
            val result = PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to mod))
            assertTrue("Retained originals must abort export", result.isFailure || result.getOrNull() != true)
            assertArrayEquals("An unsuccessful export must preserve existing bytes", existing, output.readBytes())
        } finally { source.delete(); output.delete() }
    }
}
