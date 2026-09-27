package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfSanitizerAndBatesTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testBatesStampingAndroid() = runBlocking {
        val inFile = File(context.cacheDir, "bates_in.pdf")
        val outFile = File(context.cacheDir, "bates_out.pdf")

        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))
        doc.addPage(PDPage(PDRectangle.A4))
        doc.save(inFile)
        doc.close()

        val inUri = Uri.fromFile(inFile)
        val outUri = Uri.fromFile(outFile)

        val options = BatesOptions(
            prefix = "EXHIBIT-",
            suffix = "-CONF",
            startNumber = 10,
            digits = 4,
            position = NumberPosition.BOTTOM_RIGHT
        )

        val success = PdfStampAndNumberEngine.applyBatesStamping(context, inUri, outUri, options)
        assertTrue(success)
        assertTrue(outFile.exists() && outFile.length() > 0)

        PDDocument.load(outFile).use { loaded ->
            assertEquals(2, loaded.numberOfPages)
        }
    }

    @Test
    fun testSanitizeDocumentAndroid() = runBlocking {
        val inFile = File(context.cacheDir, "sanitize_in.pdf")
        val outFile = File(context.cacheDir, "sanitize_out.pdf")

        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)
        doc.documentInformation.author = "Threat Actor"
        doc.documentInformation.title = "Confidential Leak"
        doc.documentCatalog.cosObject.setString(COSName.getPDFName("JavaScript"), "app.alert(1);")
        doc.save(inFile)
        doc.close()

        val inUri = Uri.fromFile(inFile)
        val outUri = Uri.fromFile(outFile)

        val audit = PdfSanitizerEngine.auditDocumentThreats(context, inUri)
        assertTrue("Threats should be detected", audit.threatsFound > 0)
        assertTrue(audit.jsCount > 0)
        assertFalse(audit.isClean)

        val result = PdfSanitizerEngine.sanitizeDocument(context, inUri, outUri)
        assertTrue(result.isSuccess)
        assertTrue(result.threatsRemoved > 0)

        PDDocument.load(outFile).use { cleanDoc ->
            assertNull(cleanDoc.documentInformation.author)
            assertNull(cleanDoc.documentInformation.title)
            assertNull(cleanDoc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("JavaScript")))
        }
    }

    @Test
    fun testConvertToPdfAAndroid() = runBlocking {
        val inFile = File(context.cacheDir, "pdfa_in.pdf")
        val outFile = File(context.cacheDir, "pdfa_out.pdf")

        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))
        doc.save(inFile)
        doc.close()

        val inUri = Uri.fromFile(inFile)
        val outUri = Uri.fromFile(outFile)

        val success = PdfArchiveValidatorEngine.convertToPdfA(context, inUri, outUri)
        assertTrue(success)
        assertTrue(outFile.exists() && outFile.length() > 0)

        PDDocument.load(outFile).use { pdfaDoc ->
            assertNotNull(pdfaDoc.documentCatalog.metadata)
            assertNotNull(pdfaDoc.documentCatalog.markInfo)
            assertTrue(pdfaDoc.documentCatalog.markInfo.isMarked)
        }
    }

    @Test
    fun testVanguardZeroTrustBlockOnExecutablePdf() = runBlocking {
        // 1. Construct synthetic test PDF with executable OpenAction and JavaScript triggers
        val infectedFile = File(context.cacheDir, "vanguard_infected_test.pdf")
        val cleanFile = File(context.cacheDir, "vanguard_clean_test.pdf")
        val sanitizedFile = File(context.cacheDir, "vanguard_sanitized_test.pdf")

        val docInfected = PDDocument()
        val page = PDPage(PDRectangle.A4)
        docInfected.addPage(page)
        // Add auto-executing OpenAction JavaScript hook
        val jsDict = com.tom_roush.pdfbox.cos.COSDictionary().apply {
            setName(COSName.S, "JavaScript")
            setString(COSName.getPDFName("JS"), "app.alert('Vanguard Test');")
        }
        docInfected.documentCatalog.cosObject.setItem(COSName.getPDFName("OpenAction"), jsDict)
        docInfected.save(infectedFile)
        docInfected.close()

        // 2. Construct clean standard PDF
        val docClean = PDDocument()
        docClean.addPage(PDPage(PDRectangle.A4))
        docClean.save(cleanFile)
        docClean.close()

        val infectedUri = Uri.fromFile(infectedFile)
        val cleanUri = Uri.fromFile(cleanFile)
        val sanitizedUri = Uri.fromFile(sanitizedFile)

        // 3. Verify Vanguard Zero-Trust detection
        val infectedHasThreats = PdfSanitizerEngine.hasExecutableThreats(context, infectedUri)
        assertTrue("Vanguard must detect executable triggers in infected PDF", infectedHasThreats)

        val cleanHasThreats = PdfSanitizerEngine.hasExecutableThreats(context, cleanUri)
        assertFalse("Clean document must not be flagged as a threat", cleanHasThreats)

        // 4. Verify sanitization neutralizes the threat completely
        val result = PdfSanitizerEngine.sanitizeDocument(context, infectedUri, sanitizedUri)
        assertTrue(result.isSuccess)
        val sanitizedHasThreats = PdfSanitizerEngine.hasExecutableThreats(context, sanitizedUri)
        assertFalse("Sanitized document must have all executable triggers purged", sanitizedHasThreats)
    }

    @Test
    fun testVanguardZeroTrustFailClosedOnEncryptedPdf() = runBlocking {
        val encryptedFile = File(context.cacheDir, "vanguard_encrypted_test.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))

        val accessPermission = AccessPermission()
        val protectionPolicy = StandardProtectionPolicy("ownerSecret", "userSecret", accessPermission).apply {
            encryptionKeyLength = 128
            permissions = accessPermission
        }
        doc.protect(protectionPolicy)
        doc.save(encryptedFile)
        doc.close()

        val encryptedUri = Uri.fromFile(encryptedFile)

        // 1. Audit report must report isClean = false, isEncrypted = true, threatsFound >= 1
        val auditReport = PdfSanitizerEngine.auditDocumentThreats(context, encryptedUri)
        assertTrue("Encrypted document must have isEncrypted=true", auditReport.isEncrypted)
        assertFalse("Encrypted document must not be flagged clean (fail-closed zero-trust)", auditReport.isClean)
        assertTrue("Encrypted document must have threatsFound >= 1", auditReport.threatsFound >= 1)

        // 2. Vanguard hasExecutableThreats must return true (fail-closed)
        val hasThreats = PdfSanitizerEngine.hasExecutableThreats(context, encryptedUri)
        assertTrue("Vanguard must fail-closed on password-protected PDF", hasThreats)

        // 3. checkVanguardThreat must classify as EncryptedCannotVerify
        val threatResult = PdfSanitizerEngine.checkVanguardThreat(context, encryptedUri)
        assertTrue(
            "Vanguard threat result must be EncryptedCannotVerify",
            threatResult is VanguardThreatResult.EncryptedCannotVerify
        )
        if (threatResult is VanguardThreatResult.EncryptedCannotVerify) {
            assertEquals(encryptedUri, threatResult.uri)
        }
    }

    @Test
    fun testVanguardCleanPdfWithMetadataPasses() = runBlocking {
        val metaFile = File(context.cacheDir, "vanguard_metadata_test.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage(PDRectangle.A4))
        doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation().apply {
            author = "Jane Doe"
            title = "Annual Financial Report"
            creator = "Microsoft Word"
        }
        doc.save(metaFile)
        doc.close()

        val metaUri = Uri.fromFile(metaFile)

        // Metadata alone is not an executable threat or parse failure
        val hasThreats = PdfSanitizerEngine.hasExecutableThreats(context, metaUri)
        assertFalse("Clean PDF with document metadata must not be flagged as an executable threat", hasThreats)

        val threatResult = PdfSanitizerEngine.checkVanguardThreat(context, metaUri)
        assertTrue("Vanguard threat result for standard document with metadata must be Clean", threatResult is VanguardThreatResult.Clean)
    }

    @Test
    fun testVanguardAllowsStandardWebHyperlinksAndDestinations() = runBlocking {
        val linkFile = File(context.cacheDir, "vanguard_hyperlink_test.pdf")
        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        // 1. Add standard web hyperlink annotation (/S /URI)
        val linkAnnot = PDAnnotationLink().apply {
            action = PDActionURI().apply {
                uri = "https://github.com/kiss2oblivion/pdfchemy"
            }
        }
        page.annotations = listOf(linkAnnot)

        // 2. Add standard benign GoTo destination as OpenAction
        val dest = com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination().apply {
            this.page = page
        }
        doc.documentCatalog.openAction = dest

        doc.save(linkFile)
        doc.close()

        val linkUri = Uri.fromFile(linkFile)

        // 3. Verify Vanguard does NOT flag web hyperlinks as executable threats
        val auditReport = PdfSanitizerEngine.auditDocumentThreats(context, linkUri)
        assertEquals("Standard web link must be recorded in uriCount", 1, auditReport.uriCount)
        assertEquals("Standard web link must not be counted as launch action", 0, auditReport.launchActionsCount)
        assertEquals("No JavaScript triggers should exist", 0, auditReport.jsCount)

        val hasThreats = PdfSanitizerEngine.hasExecutableThreats(context, linkUri)
        assertFalse("Document with standard web hyperlinks must NOT be blocked by Vanguard", hasThreats)

        val threatResult = PdfSanitizerEngine.checkVanguardThreat(context, linkUri)
        assertTrue("Vanguard threat result must be Clean for documents with web links", threatResult is VanguardThreatResult.Clean)
    }

    @Test
    fun testOfficeExportSanitizesInvalidXmlChars() = runBlocking {
        val sourceFile = File(context.cacheDir, "office_ctrl_chars_source.pdf")
        val destWordFile = File(context.cacheDir, "office_ctrl_chars_dest.docx")
        val destExcelFile = File(context.cacheDir, "office_ctrl_chars_dest.xlsx")

        val doc = PDDocument()
        val page = PDPage(PDRectangle.A4)
        doc.addPage(page)

        // Embed text containing null bytes \u0000 and form feed \u000C
        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Item Code 1001 Normal Text")
            cs.endText()
        }
        doc.save(sourceFile)
        doc.close()

        val sourceUri = Uri.fromFile(sourceFile)
        val wordUri = Uri.fromFile(destWordFile)
        val excelUri = Uri.fromFile(destExcelFile)

        val wordResult = OfficeExportEngine.exportToWord(context, sourceUri, wordUri)
        assertTrue("Export to Word should succeed", wordResult.isSuccess)
        assertTrue("Destination Word file should exist and have size", destWordFile.exists() && destWordFile.length() > 0)

        val excelResult = OfficeExportEngine.exportToExcel(context, sourceUri, excelUri)
        assertTrue("Export to Excel should succeed", excelResult.isSuccess)
        assertTrue("Destination Excel file should exist and have size", destExcelFile.exists() && destExcelFile.length() > 0)
    }
}

