package com.pdfchemy.app.jail.engines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FormValueAliasTest {
    private val marker = "PRIVATE_FORM_ALIAS_720194"
    private val alias = COSName.getPDFName("AlternatePayload")
    @Before fun initialize() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>()) }

    private fun field(document: PDDocument): PDTextField {
        val page = PDPage().also(document::addPage)
        val form = PDAcroForm(document)
        document.documentCatalog.acroForm = form
        form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
        form.defaultAppearance = "/Helv 12 Tf 0 g"
        return PDTextField(form).also { field ->
            field.partialName = "privateField"
            form.fields.add(field)
            field.widgets.single().apply {
                rectangle = PDRectangle(10f, 50f, 180f, 30f)
                this.page = page
                isNoView = true
                page.annotations.add(this)
            }
        }
    }

    private fun sanitized(document: PDDocument, secret: String = marker): ByteArray {
        RedactionResources.prepareForRedaction(document)
        RedactionResources.replacePageContent(document, document.getPage(0)) {
            it.addRect(0f, 0f, 300f, 300f); it.fill()
        }
        RedactionResources.pruneUnused(document)
        val bytes = ByteArrayOutputStream().also(document::save).toByteArray()
        assertFalse("Private field payload survives in saved bytes", String(bytes, Charsets.ISO_8859_1).contains(secret))
        PDDocument.load(bytes).use { saved ->
            assertNull("Private value alias must be detached", saved.documentCatalog.cosObject.getItem(alias))
            assertNull(saved.documentCatalog.cosObject.getItem(COSName.ACRO_FORM))
        }
        return bytes
    }

    @Test fun serializedIndirectValueFixtureLosesAlias() {
        val input = javaClass.getResourceAsStream("/redaction-form-value-alias/no-view-indirect-value.pdf")!!.use { it.readBytes() }
        PDDocument.load(input).use { document ->
            val value = document.documentCatalog.getAcroForm(null)!!.fields.single().cosObject.getItem(COSName.V)
            assertTrue(value is COSObject)
            assertSame((value as COSObject).`object`, document.documentCatalog.cosObject.getDictionaryObject(alias))
            sanitized(document, "FOLLOW10_PRIVATE_VALUE_482719")
        }
    }

    @Test fun defaultValueStringAliasIsRemoved() {
        PDDocument().use { document ->
            val value = COSString(marker)
            field(document).cosObject.setItem(COSName.DV, value)
            document.documentCatalog.cosObject.setItem(alias, value)
            sanitized(document)
        }
    }

    @Test fun richTextStreamAliasIsRemoved() {
        PDDocument().use { document ->
            val privateField = field(document)
            val value = document.document.createCOSStream().apply {
                createOutputStream().use { it.write(marker.toByteArray()) }
            }
            privateField.cosObject.setItem(COSName.getPDFName("RV"), value)
            document.documentCatalog.cosObject.setItem(alias, value)
            sanitized(document)
        }
    }

    @Test fun nestedOptionDescendantAliasIsRemoved() {
        PDDocument().use { document ->
            val value = COSString(marker)
            field(document).cosObject.setItem(COSName.getPDFName("Opt"), COSArray().apply {
                add(COSArray().apply { add(value); add(COSString("Display choice")) })
            })
            document.documentCatalog.cosObject.setItem(alias, value)
            sanitized(document)
        }
    }

    @Test fun cyclicValueGraphDoesNotLeaveItsStringAlias() {
        PDDocument().use { document ->
            val value = COSString(marker)
            val graph = COSDictionary().apply { setItem(COSName.getPDFName("Secret"), value) }
            graph.setItem(COSName.getPDFName("Cycle"), graph)
            field(document).cosObject.setItem(COSName.V, graph)
            document.documentCatalog.cosObject.setItem(alias, value)
            sanitized(document)
        }
    }

    @Test fun privateValueAliasToRetainedFontRejects() {
        PDDocument().use { document ->
            val privateField = field(document)
            val font = PDType1Font.HELVETICA
            document.getPage(0).resources = PDResources().apply { put(COSName.getPDFName("F1"), font) }
            privateField.cosObject.setItem(COSName.getPDFName("RV"), font.cosObject)
            assertThrows(IllegalArgumentException::class.java) { RedactionResources.prepareForRedaction(document) }
        }
    }

    @Test fun privateValueAliasToDocumentHierarchyRejects() {
        PDDocument().use { document ->
            field(document).cosObject.setItem(COSName.V, document.documentCatalog.cosObject)
            assertThrows(IllegalArgumentException::class.java) { RedactionResources.prepareForRedaction(document) }
        }
    }

    @Test fun unrelatedVisibleFormValueRemainsSelectable() {
        PDDocument().use { document ->
            val privateField = field(document)
            val privateValue = COSString(marker)
            privateField.cosObject.setItem(COSName.V, privateValue)
            document.documentCatalog.cosObject.setItem(alias, privateValue)
            val otherPage = PDPage().also(document::addPage)
            val form = document.documentCatalog.getAcroForm(null)!!
            val publicField = PDTextField(form)
            publicField.partialName = "publicField"
            form.fields.add(publicField)
            publicField.widgets.single().apply {
                rectangle = PDRectangle(10f, 100f, 220f, 40f)
                page = otherPage
                otherPage.annotations.add(this)
            }
            publicField.value = "KEEP_PUBLIC_FORM_VALUE"
            PDDocument.load(sanitized(document)).use { saved ->
                assertTrue(PDFTextStripper().getText(saved).contains("KEEP_PUBLIC_FORM_VALUE"))
            }
        }
    }
}
