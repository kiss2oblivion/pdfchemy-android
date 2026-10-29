package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfLinearizeValidationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun inspect(configure: (PDDocument) -> Unit = {}): JSONObject {
        val file = temp.newFile()
        PDDocument().use { doc -> doc.addPage(PDPage()); configure(doc); doc.save(file) }
        return PDDocument.load(file).use { JSONObject(PdfLinearizeEngineWorker.inspectDocument(it)) }
    }
    private fun assertUnverified(result: JSONObject) {
        assertFalse(result.getBoolean("isLinearized"))
        assertEquals("NOT_VERIFIED", result.getString("verificationStatus"))
        assertEquals(1, result.getInt("pageCount"))
    }
    @Test fun ordinarySerializedPdfIsNotCertified() = assertUnverified(inspect())
    @Test fun metadataMarkerIsNotCertification() = assertUnverified(inspect {
        it.documentInformation.subject = "A mention of /Linearized is not a validation result"
    })
    @Test fun forgedCatalogDictionaryIsNotCertification() = assertUnverified(inspect {
        it.documentCatalog.cosObject.setInt(COSName.getPDFName("Linearized"), 1)
    })
    @Test fun zeroPageDocumentCannotProduceDiagnosticSuccess() {
        PDDocument().use { doc ->
            try { PdfLinearizeEngineWorker.inspectDocument(doc); fail("Expected rejection") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun unsupportedWorkerDoesNotReadOrModifyEitherDescriptor() {
        val input = temp.newFile().apply { writeText("not even a PDF /Linearized") }
        val target = temp.newFile().apply { writeText("existing destination") }
        val before = temp.root.list()!!.toSet()
        ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use { source ->
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { dest ->
                try { PdfLinearizeEngineWorker.optimizeFastWebView(source, dest); fail("Expected unsupported") }
                catch (e: UnsupportedOperationException) { assertTrue(e.message!!.contains("not supported")) }
            }
        }
        assertEquals("existing destination", target.readText())
        assertEquals("not even a PDF /Linearized", input.readText())
        assertEquals("No scratch file may be created", before, temp.root.list()!!.toSet())
    }
}
