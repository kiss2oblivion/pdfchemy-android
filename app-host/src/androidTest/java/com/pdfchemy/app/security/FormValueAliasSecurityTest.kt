package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PageModification
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactionConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** FOLLOW-10: serialized field-value identity aliases must not bypass forensic export. */
@RunWith(AndroidJUnit4::class)
class FormValueAliasSecurityTest {
    private val secret = "FOLLOW10_PRIVATE_VALUE_482719"
    private val alias = COSName.getPDFName("AlternatePayload")

    private fun fixture(): ByteArray {
        val contents = "BT /F1 12 Tf 20 110 Td ($secret) Tj ET\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R /AcroForm 5 0 R /AlternatePayload 7 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 8 0 R >> >> /Annots [9 0 R] >>",
            "<< /Length ${contents.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${contents}endstream",
            "<< /Fields [6 0 R] /NeedAppearances false /DA (/F1 12 Tf 0 g) /DR << /Font << /F1 8 0 R >> >> >>",
            "<< /FT /Tx /T (privateValue) /V 7 0 R /Kids [9 0 R] >>",
            "($secret)",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
            "<< /Type /Annot /Subtype /Widget /Parent 6 0 R /Rect [20 90 280 110] /F 32 /P 3 0 R >>"
        )
        val bytes = ByteArrayOutputStream()
        fun emit(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        emit("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body ->
            bytes.size().also { emit("${index + 1} 0 obj\n$body\nendobj\n") }
        }
        val xref = bytes.size()
        emit("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { emit(it.toString().padStart(10, '0') + " 00000 n \n") }
        emit("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return bytes.toByteArray()
    }

    private fun verify(search: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val route = if (search) "search" else "editor"
        val run = InstrumentationRegistry.getArguments().getString("follow10EvidenceRun", "probe")!!
        require(run.matches(Regex("[a-zA-Z0-9_-]+")))
        val archive = File(context.getExternalFilesDir(null), "follow10/$run").apply { mkdirs() }
        val source = File.createTempFile("follow10_source_", ".pdf", context.cacheDir)
        val output = File.createTempFile("follow10_output_", ".pdf", context.cacheDir)
        try {
            source.writeBytes(fixture())
            source.copyTo(File(archive, "$route-input.pdf"))
            PDDocument.load(source).use { document ->
                val catalog = document.documentCatalog
                val field = catalog.getAcroForm(null)!!.fields.single()
                val fieldReference = field.cosObject.getItem(COSName.V)
                val aliasReference = catalog.cosObject.getItem(alias)
                assertTrue("Fixture /V must be serialized as an indirect reference", fieldReference is COSObject)
                assertTrue("Fixture alias must be serialized as an indirect reference", aliasReference is COSObject)
                assertEquals((fieldReference as COSObject).objectNumber, (aliasReference as COSObject).objectNumber)
                val value = field.cosObject.getDictionaryObject(COSName.V)
                assertSame("Serialized/reloaded form value and alias must share object identity", value, catalog.cosObject.getDictionaryObject(alias))
                assertEquals(secret, (value as COSString).string)
                assertTrue("Fixture must exercise the NoView path", field.widgets.single().isNoView)
                assertTrue(PDFTextStripper().getText(document).contains(secret))
            }

            if (search) {
                val targets = PdfRedactionEngine.searchRedactionTargets(context, Uri.fromFile(source), secret).getOrThrow()
                assertEquals("Real worker search must find the visible secret", 1, targets.size)
                assertTrue(PdfRedactionEngine.applyRedactions(context, Uri.fromFile(source), Uri.fromFile(output), targets,
                    RedactionConfig(isBlackout = true, defaultOverlayText = "", forensicSanitize = true)).getOrThrow() > 0)
            } else {
                val page = PageModification(0, redactions = listOf(RedactionBox(0, 0f, 0f, 1f, 1f, overlayLabel = "")))
                assertTrue(PdfEditor.exportModifiedPdf(context, Uri.fromFile(source), Uri.fromFile(output), mapOf(0 to page)).getOrThrow())
            }
            PDDocument.load(output).use { saved ->
                assertNull("Export must detach AcroForm", saved.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM))
                assertFalse("Selected visible secret must be redacted", PDFTextStripper().getText(saved).contains(secret))
                assertNull("FOLLOW-10: $route export retained the private form value through its catalog alias",
                    saved.documentCatalog.cosObject.getDictionaryObject(alias))
            }
            assertFalse("Private value must not remain in serialized $route output",
                output.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        } finally {
            if (output.length() > 0) output.copyTo(File(archive, "$route-output.pdf"))
            source.delete()
            output.delete()
        }
    }

    @Test fun editorRemovesNoViewFieldValueCatalogAlias() = verify(search = false)
    @Test fun secureSearchRemovesNoViewFieldValueCatalogAlias() = verify(search = true)
}
