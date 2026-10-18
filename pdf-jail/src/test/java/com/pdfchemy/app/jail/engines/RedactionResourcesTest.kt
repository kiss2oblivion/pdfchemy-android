package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RedactionResourcesTest {
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }

    private fun secretForm(doc: PDDocument, secret: String): PDFormXObject = PDFormXObject(doc).apply {
        resources = PDResources()
        bBox = PDRectangle(200f, 100f)
        resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
        cosObject.createOutputStream().use { it.write("BT /F1 12 Tf 20 50 Td ($secret) Tj ET".toByteArray()) }
    }

    private fun replace(doc: PDDocument, page: PDPage) {
        RedactionResources.prepareForRedaction(doc)
        RedactionResources.replacePageContent(doc, page) { it.addRect(0f, 0f, 200f, 100f); it.fill() }
    }

    private fun assertAbsent(doc: PDDocument, secret: String, keep: String? = null) {
        RedactionResources.pruneUnused(doc)
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        PDDocument.load(bytes).use { saved ->
            val text = PDFTextStripper().getText(saved)
            assertFalse(text.contains(secret))
            if (keep != null) assertTrue("Unrelated vector text must survive", text.contains(keep))
            assertFalse("Original object metadata must be absent", String(bytes, Charsets.ISO_8859_1).contains(secret))
            for (obj in saved.document.objects) {
                val stream = obj.`object` as? COSStream ?: continue
                val decoded = stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
                assertFalse("Original decoded resource must be absent", decoded.contains(secret))
            }
        }
    }

    @Test fun replacingContentRemovesOriginalSensitiveForm() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            PDPageContentStream(doc, page).use { it.drawForm(secretForm(doc, "SECRET_FORM_731904")) }
            replace(doc, page)
            assertAbsent(doc, "SECRET_FORM_731904")
        }
    }

    @Test fun sharedStreamsPropagateRasterizationWithoutSelectingIndependentPages() {
        PDDocument().use { doc ->
            val pages = List(4) { PDPage().also(doc::addPage) }
            val private = secretForm(doc, "PRIVATE_SHARED_FORM")
            val second = secretForm(doc, "SECOND_SHARED_FORM")
            PDPageContentStream(doc, pages[0]).use { it.drawForm(private) }
            PDPageContentStream(doc, pages[1]).use { it.drawForm(private); it.drawForm(second) }
            PDPageContentStream(doc, pages[2]).use { it.drawForm(second) }
            PDPageContentStream(doc, pages[3]).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                it.showText("KEEP_INDEPENDENT_VECTOR"); it.endText()
            }
            val plan = RedactionResources.rasterizationPlan(doc, setOf(0), emptySet())
            assertEquals(setOf(0, 1, 2), plan.pages)
            for (index in plan.pages) replace(doc, pages[index])
            plan.requireOriginalsDetached(doc)
            assertAbsent(doc, "PRIVATE_SHARED_FORM", "KEEP_INDEPENDENT_VECTOR")
            assertAbsent(doc, "SECOND_SHARED_FORM")
        }
    }

    @Test fun originalStreamRetainedThroughCatalogMustFailClosed() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            val private = secretForm(doc, "PRIVATE_CATALOG_STREAM")
            PDPageContentStream(doc, page).use { it.drawForm(private) }
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("RetainedOriginal"), private)
            val plan = RedactionResources.rasterizationPlan(doc, setOf(0), emptySet())
            replace(doc, page)
            try {
                plan.requireOriginalsDetached(doc)
                fail("A catalog reference must not bypass stream removal")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("remains reachable"))
            }
        }
    }

    @Test fun noViewFieldMustNotBecomeVisibleSelectableTextDuringFlattening() {
        PDDocument().use { doc ->
            val target = PDPage().also(doc::addPage)
            val other = PDPage().also(doc::addPage)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
            field.partialName = "noViewField"
            form.fields.add(field)
            field.widgets.first().apply {
                rectangle = PDRectangle(10f, 50f, 180f, 100f)
                page = other
                isNoView = true
                other.annotations.add(this)
            }
            field.value = "PRIVATE_NO_VIEW_VALUE"
            replace(doc, target)
            assertAbsent(doc, "PRIVATE_NO_VIEW_VALUE")
        }
    }

    @Test fun redactedWidgetDoesNotRetainOriginalValueInDocumentFormTree() {
        PDDocument().use { doc ->
            val targetPage = PDPage().also(doc::addPage)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply {
                put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
            field.partialName = "privateField"
            form.fields.add(field)
            field.widgets.first().apply {
                rectangle = PDRectangle(0f, 0f, 200f, 100f)
                page = targetPage
                targetPage.annotations.add(this)
            }
            field.value = "SECRET_FORM_VALUE_159204"
            val otherPage = PDPage().also(doc::addPage)
            val other = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
            other.partialName = "publicField"
            form.fields.add(other)
            other.widgets.first().apply {
                rectangle = PDRectangle(10f, 50f, 180f, 100f)
                page = otherPage
                otherPage.annotations.add(this)
            }
            other.value = "KEEP_FORM_VALUE"
            val reset = COSDictionary().apply {
                setName(COSName.S, "ResetForm")
                setItem(COSName.FIELDS, COSArray().apply { add(field.cosObject) })
            }
            doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, reset)
            replace(doc, targetPage)
            assertAbsent(doc, "SECRET_FORM_VALUE_159204", "KEEP_FORM_VALUE")
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM))
        }
    }

    @Test fun xfaFormsAreRejectedBeforeAnyFormMutation() {
        PDDocument().use { doc ->
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.cosObject.setItem(COSName.XFA, COSString("PRIVATE_XFA_VALUE"))
            try {
                RedactionResources.prepareForRedaction(doc)
                fail("XFA redaction must fail closed")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("XFA"))
            }
            assertSame(form.cosObject, doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM))
        }
    }

    @Test fun cyclicFormHierarchyIsRejectedBeforeFlattening() {
        PDDocument().use { doc ->
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = COSDictionary()
            field.setItem(COSName.KIDS, COSArray().apply { add(field) })
            form.cosObject.setItem(COSName.FIELDS, COSArray().apply { add(field) })
            try {
                RedactionResources.prepareForRedaction(doc)
                fail("Cyclic fields must fail closed")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("hierarchy"))
            }
        }
    }

    @Test fun formFlatteningPreservesAnExistingCustomAppearance() {
        PDDocument().use { doc ->
            val target = PDPage().also(doc::addPage)
            val otherPage = PDPage().also(doc::addPage)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
            field.partialName = "customAppearance"
            form.fields.add(field)
            val widget = field.widgets.first()
            widget.rectangle = PDRectangle(10f, 50f, 180f, 100f)
            widget.page = otherPage
            otherPage.annotations.add(widget)
            field.value = "UNUSED_STRUCTURAL_VALUE"
            widget.cosObject.setItem(COSName.AP, COSDictionary().apply {
                setItem(COSName.N, secretForm(doc, "KEEP_CUSTOM_APPEARANCE"))
            })
            replace(doc, target)
            assertAbsent(doc, "UNUSED_STRUCTURAL_VALUE", "KEEP_CUSTOM_APPEARANCE")
        }
    }

    @Test fun missingVisibleFieldAppearanceIsCreatedBeforeFlattening() {
        PDDocument().use { doc ->
            val target = PDPage().also(doc::addPage)
            val otherPage = PDPage().also(doc::addPage)
            val form = com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val field = com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField(form)
            field.partialName = "missingAppearance"
            form.fields.add(field)
            val widget = field.widgets.first()
            widget.rectangle = PDRectangle(10f, 50f, 180f, 100f)
            widget.page = otherPage
            otherPage.annotations.add(widget)
            field.value = "KEEP_MISSING_APPEARANCE"
            widget.cosObject.removeItem(COSName.AP)
            replace(doc, target)
            RedactionResources.pruneUnused(doc)
            val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            PDDocument.load(bytes).use { saved ->
                assertTrue(PDFTextStripper().getText(saved).contains("KEEP_MISSING_APPEARANCE"))
                assertNull(saved.documentCatalog.getAcroForm(null))
            }
        }
    }

    @Test fun replacingScanRemovesOriginalImageResource() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            val image = PDImageXObject(doc, ByteArrayInputStream(byteArrayOf(127, 23, 65)), null,
                1, 1, 8, PDDeviceRGB.INSTANCE)
            image.cosObject.setString(COSName.getPDFName("AuditSensitive"), "SECRET_SCAN_842613")
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, 200f, 100f) }
            replace(doc, page)
            assertAbsent(doc, "SECRET_SCAN_842613")
        }
    }

    @Test fun inheritedSharedUnusedResourcesAreRemovedWithoutLosingUnrelatedText() {
        PDDocument().use { doc ->
            val target = PDPage().also(doc::addPage)
            val other = PDPage().also(doc::addPage)
            val shared = PDResources()
            shared.put(COSName.getPDFName("Sensitive"), secretForm(doc, "SECRET_SHARED_903417"))
            shared.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
            doc.pages.cosObject.setItem(COSName.RESOURCES, shared)
            target.resources = null
            other.resources = null
            PDPageContentStream(doc, target).use { it.drawForm(shared.getXObject(COSName.getPDFName("Sensitive")) as PDFormXObject) }
            PDPageContentStream(doc, other).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(30f, 500f); it.showText("KEEP_VECTOR_TEXT"); it.endText()
            }
            replace(doc, target)
            assertAbsent(doc, "SECRET_SHARED_903417", "KEEP_VECTOR_TEXT")
        }
    }

    @Test fun nestedUsedFormsKeepTextWhileUnusedSensitiveFormIsRemoved() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            val inner = secretForm(doc, "KEEP_NESTED_FORM")
            val outer = PDFormXObject(doc).apply { resources = PDResources(); bBox = PDRectangle(200f, 100f) }
            val usedName = outer.resources.add(inner)
            outer.resources.put(COSName.getPDFName("UnusedSecret"), secretForm(doc, "SECRET_UNUSED_NESTED"))
            outer.cosObject.createOutputStream().use { it.write("/${usedName.name} Do".toByteArray()) }
            PDPageContentStream(doc, page).use { it.drawForm(outer) }
            assertAbsent(doc, "SECRET_UNUSED_NESTED", "KEEP_NESTED_FORM")
        }
    }

    @Test fun resourceLessFormKeepsNamesResolvedInCallingPage() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            page.resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            val form = PDFormXObject(doc).apply { bBox = PDRectangle(200f, 100f) }
            form.cosObject.removeItem(COSName.RESOURCES)
            form.cosObject.createOutputStream().use {
                it.write("BT /F1 12 Tf 20 50 Td (KEEP_INHERITED_FORM) Tj ET".toByteArray())
            }
            PDPageContentStream(doc, page).use { it.drawForm(form) }
            assertAbsent(doc, "SECRET_NOT_IN_DOCUMENT", "KEEP_INHERITED_FORM")
        }
    }

    @Test fun implicitDefaultColorSpaceIsPreserved() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            page.resources = PDResources().apply { put(COSName.getPDFName("DefaultRGB"), PDDeviceRGB.INSTANCE) }
            PDPageContentStream(doc, page).use { it.setNonStrokingColor(1f, 0f, 0f); it.addRect(0f, 0f, 20f, 20f); it.fill() }
            RedactionResources.pruneUnused(doc)
            assertNotNull(page.resources.getColorSpace(COSName.getPDFName("DefaultRGB")))
        }
    }

    @Test fun annotationAppearanceCanResolveFontInPageResources() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            page.resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            val form = PDFormXObject(doc).apply { bBox = PDRectangle(200f, 100f) }
            form.cosObject.removeItem(COSName.RESOURCES)
            form.cosObject.createOutputStream().use { it.write("BT /F1 12 Tf (KEEP_APPEARANCE) Tj ET".toByteArray()) }
            page.annotations.add(com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink().apply {
                cosObject.setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, form) })
            })
            RedactionResources.pruneUnused(doc)
            assertNotNull(page.resources.getFont(COSName.getPDFName("F1")))
        }
    }

    @Test fun usedTilingPatternDoesNotRetainUnusedOriginalForm() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            val pattern = com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern().apply {
                resources = PDResources()
                bBox = PDRectangle(100f, 100f); xStep = 100f; yStep = 100f
            }
            pattern.resources.put(COSName.getPDFName("UnusedSecret"), secretForm(doc, "SECRET_PATTERN_RESOURCE"))
            pattern.contentStream.createOutputStream().use { it.write("0 0 10 10 re f".toByteArray()) }
            page.resources = PDResources().apply { put(COSName.getPDFName("P1"), pattern) }
            val content = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc)
            content.createOutputStream().use { it.write("/Pattern cs /P1 scn 0 0 100 100 re f".toByteArray()) }
            page.setContents(content)
            assertAbsent(doc, "SECRET_PATTERN_RESOURCE")
        }
    }

    @Test fun softMaskDoesNotRetainUnusedOriginalForm() {
        PDDocument().use { doc ->
            val page = PDPage().also(doc::addPage)
            val group = PDFormXObject(doc).apply { resources = PDResources(); bBox = PDRectangle(100f, 100f) }
            group.resources.put(COSName.getPDFName("UnusedSecret"), secretForm(doc, "SECRET_MASK_RESOURCE"))
            group.cosObject.createOutputStream().use { it.write("0 0 10 10 re f".toByteArray()) }
            val state = COSDictionary().apply {
                setItem(COSName.SMASK, COSDictionary().apply { setItem(COSName.G, group); setItem(COSName.S, COSName.LUMINOSITY) })
            }
            page.resources = PDResources(COSDictionary().apply {
                setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("GS1"), state) })
            })
            val content = com.tom_roush.pdfbox.pdmodel.common.PDStream(doc)
            content.createOutputStream().use { it.write("/GS1 gs 0 0 100 100 re f".toByteArray()) }
            page.setContents(content)
            assertAbsent(doc, "SECRET_MASK_RESOURCE")
        }
    }
}
