package com.pdfchemy.app.jail.engines

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfArchivePreliminaryChecksTest {
    @get:Rule val temp = TemporaryFolder()
    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }
    private fun inspect(configure: (PDDocument) -> Unit = {}): JSONObject {
        val file = temp.newFile()
        PDDocument().use { doc -> doc.addPage(PDPage()); configure(doc); doc.save(file) }
        val result = PDDocument.load(file).use { JSONObject(PdfArchiveValidatorEngineWorker.inspectPreliminaryChecks(it)) }
        assertFalse(result.getBoolean("isCompliant"))
        assertEquals("NOT_CERTIFIED", result.getString("certificationStatus"))
        assertEquals("PRELIMINARY_PDF_A_CHECKS", result.getString("assessmentScope"))
        assertEquals("Unknown (not certified)", result.getString("pdfaVersionDetected"))
        return result
    }
    private fun passed(result: JSONObject, rule: String): Boolean {
        val checks = result.getJSONArray("checks")
        return (0 until checks.length()).map { checks.getJSONObject(it) }.single { it.getString("rule") == rule }.getBoolean("isPassed")
    }
    @Test fun missingIccStreamIsReportedWithoutCertification() {
        assertFalse(passed(inspect(), "Output profile stream"))
    }
    @Test fun nonembeddedPageFontIsReported() {
        val result = inspect { doc ->
            val resources = PDResources()
            resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
            doc.getPage(0).resources = resources
        }
        assertFalse(passed(result, "Referenced page-font embedding"))
    }
    @Test fun spoofedPdfaidMetadataCannotCertify() {
        val result = inspect { doc ->
            doc.documentCatalog.metadata = PDMetadata(doc).apply {
                importXMPMetadata("<fake pdfaid:part=\"1\" pdfaid:conformance=\"B\"/>".toByteArray())
            }
        }
        assertTrue("Presence is distinct from validity", passed(result, "Metadata stream"))
    }
    @Test fun evenAllPresenceChecksPassingCannotCertify() {
        val result = inspect { doc ->
            doc.documentCatalog.metadata = PDMetadata(doc).apply { importXMPMetadata("not valid XMP".toByteArray()) }
            val profile = doc.document.createCOSStream()
            profile.createOutputStream().use { it.write("not an ICC profile".toByteArray()) }
            val intent = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.getPDFName("OutputIntent"))
                setItem(COSName.getPDFName("DestOutputProfile"), profile)
            }
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("OutputIntents"), COSArray().apply { add(intent) })
        }
        assertEquals("The legacy score describes presence checks, never compliance", 100, result.getInt("complianceScore"))
        assertFalse(result.getBoolean("isCompliant"))
    }
    @Test fun forbiddenCatalogActionIsReported() {
        val result = inspect { it.documentCatalog.openAction = PDActionJavaScript("app.alert('fixture')") }
        assertFalse(passed(result, "Catalog action entries"))
    }
    @Test fun zeroPageStructureIsRejected() {
        PDDocument().use { document ->
            try { PdfArchiveValidatorEngineWorker.inspectPreliminaryChecks(document); fail("Invalid structure accepted") }
            catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun unsupportedConversionPreservesBothDescriptorsWithoutScratch() {
        val source = temp.newFile().apply { writeText("invalid input with pdfaid markers") }
        val target = temp.newFile().apply { writeText("original target") }
        val files = temp.root.list()!!.toSet()
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                try { PdfArchiveValidatorEngineWorker.convertToPdfA(input, output); fail("Unsupported conversion succeeded") }
                catch (e: UnsupportedOperationException) { assertTrue(e.message!!.contains("not supported")) }
            }
        }
        assertEquals("original target", target.readText())
        assertEquals("invalid input with pdfaid markers", source.readText())
        assertEquals(files, temp.root.list()!!.toSet())
    }
}
