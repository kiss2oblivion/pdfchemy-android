package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfTableExtractorWorkerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun initialize() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())
    }

    private fun createTestPdf(
        file: File,
        rows: List<List<String>>,
        colSpacing: Float = 100f
    ) {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                var currentY = 700f
                for (row in rows) {
                    var currentX = 50f
                    for (cell in row) {
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(currentX, currentY)
                        stream.showText(cell)
                        stream.endText()
                        currentX += colSpacing
                    }
                    currentY -= 25f
                }
            }
            doc.save(file)
        }
    }

    @Test
    fun extractTextExtractsTabularDataAndDefaultsToSafeMode() {
        val pdfFile = tempFolder.newFile("table_test.pdf")
        val outputFile = tempFolder.newFile("output.csv")
        createTestPdf(
            pdfFile,
            listOf(
                listOf("Product", "Price", "Formula"),
                listOf("Gadget", "$19.99", "=1+2")
            )
        )

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val resultJson = PdfTableExtractorWorker.extractText(sourceFd, targetFd, "{}")
        sourceFd.close()
        targetFd.close()

        val json = JSONObject(resultJson)
        assertTrue("Extraction should be successful", json.getBoolean("success"))
        assertFalse("Extraction should not be empty", json.getBoolean("empty"))
        assertEquals(2, json.getInt("rowCount"))

        val csvContent = outputFile.readText(Charsets.UTF_8)
        assertTrue("Header Product should be in CSV", csvContent.contains("Product"))
        assertTrue("Price should be in CSV", csvContent.contains("$19.99"))
        // In safeMode (default true), formula starting with '=' must be prefixed with single quote
        assertTrue("Formula should be sanitized with leading single quote: $csvContent", csvContent.contains("'=1+2"))
    }

    @Test
    fun extractTextPreservesRawDataWhenSafeModeIsFalse() {
        val pdfFile = tempFolder.newFile("raw_test.pdf")
        val outputFile = tempFolder.newFile("raw_output.csv")
        createTestPdf(
            pdfFile,
            listOf(
                listOf("Header", "Calc"),
                listOf("Data", "=SUM(A1:B1)")
            )
        )

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val params = JSONObject().apply { put("safeMode", false) }
        val resultJson = PdfTableExtractorWorker.extractText(sourceFd, targetFd, params.toString())
        sourceFd.close()
        targetFd.close()

        val json = JSONObject(resultJson)
        assertTrue(json.getBoolean("success"))
        assertFalse(json.getBoolean("empty"))

        val csvContent = outputFile.readText(Charsets.UTF_8)
        assertTrue("Raw formula must not have single quote prefix: $csvContent", csvContent.contains("=SUM(A1:B1)"))
        assertFalse("Must not have sanitized single quote prefix", csvContent.contains("'=SUM(A1:B1)"))
    }

    @Test
    fun extractTextRejectsNegativePageIndexStrictly() {
        val pdfFile = tempFolder.newFile("negative_page.pdf")
        val outputFile = tempFolder.newFile("out_negative.csv")
        createTestPdf(pdfFile, listOf(listOf("Col1", "Col2")))

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val params = JSONObject().apply { put("pageIndex", -1) }
        try {
            PdfTableExtractorWorker.extractText(sourceFd, targetFd, params.toString())
            fail("Expected IllegalArgumentException for negative pageIndex")
        } catch (e: IllegalArgumentException) {
            assertTrue("Exception should explain invalid page index: ${e.message}", e.message!!.contains("Invalid pageIndex -1"))
        } finally {
            sourceFd.close()
            targetFd.close()
        }
    }

    @Test
    fun extractTextRejectsOutOfBoundsPageIndexStrictly() {
        val pdfFile = tempFolder.newFile("oob_page.pdf")
        val outputFile = tempFolder.newFile("out_oob.csv")
        createTestPdf(pdfFile, listOf(listOf("Col1", "Col2")))

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val params = JSONObject().apply { put("pageIndex", 5) } // Document has only 1 page (index 0)
        try {
            PdfTableExtractorWorker.extractText(sourceFd, targetFd, params.toString())
            fail("Expected IllegalArgumentException for out-of-bounds pageIndex")
        } catch (e: IllegalArgumentException) {
            assertTrue("Exception should explain out-of-bounds page index: ${e.message}", e.message!!.contains("Invalid pageIndex 5"))
        } finally {
            sourceFd.close()
            targetFd.close()
        }
    }

    @Test
    fun extractTextReturnsEmptyResultForBlankDocument() {
        val pdfFile = tempFolder.newFile("blank.pdf")
        val outputFile = tempFolder.newFile("out_blank.csv")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(pdfFile)
        }

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val resultJson = PdfTableExtractorWorker.extractText(sourceFd, targetFd, "{}")
        sourceFd.close()
        targetFd.close()

        val json = JSONObject(resultJson)
        assertTrue("Worker must report success=true for empty tables", json.getBoolean("success"))
        assertTrue("Worker must report empty=true for empty tables", json.getBoolean("empty"))
        assertEquals(0, json.getInt("rowCount"))
        assertEquals(0, json.getInt("count"))
    }

    @Test
    fun workerExtractedCsvValidatesAndParsesThroughCsvParser() {
        val pdfFile = tempFolder.newFile("e2e_table.pdf")
        val outputFile = tempFolder.newFile("e2e_output.csv")
        createTestPdf(
            pdfFile,
            listOf(
                listOf("First Name", "Last Name", "Department"),
                listOf("Alice", "Smith", "Engineering"),
                listOf("Bob", "Jones", "Finance")
            )
        )

        val sourceFd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFd = ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_WRITE)

        val resultJson = PdfTableExtractorWorker.extractText(sourceFd, targetFd, "{}")
        sourceFd.close()
        targetFd.close()

        val contract = com.pdfchemy.app.jail.WorkerResponseValidator.validate("TABLE_EXTRACT", resultJson, targetCount = 1)
        assertTrue(contract is com.pdfchemy.app.logic.TableExtractContract)
        val tableContract = contract as com.pdfchemy.app.logic.TableExtractContract
        assertEquals(3, tableContract.rowCount)
        assertEquals("count must equal rowCount for TABLE_EXTRACT", 3, tableContract.count)
        assertFalse(tableContract.empty)

        val csvText = com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(outputFile)
        val parsed = com.pdfchemy.app.logic.CsvParser.parse(csvText)
        assertEquals(3, parsed.size)
        assertEquals(tableContract.rowCount, parsed.size)
        assertEquals(listOf("First Name", "Last Name", "Department"), parsed[0])
        assertEquals(listOf("Alice", "Smith", "Engineering"), parsed[1])
        assertEquals(listOf("Bob", "Jones", "Finance"), parsed[2])
    }

    @Test
    fun spreadsheetSafeSanitizationCoversFormulaTriggersAndLeadingWhitespace() {
        // Direct trigger characters
        assertEquals("'=SUM(A1:A5)", PdfTableExtractorWorker.sanitizeCell("=SUM(A1:A5)", safeMode = true))
        assertEquals("'+1234", PdfTableExtractorWorker.sanitizeCell("+1234", safeMode = true))
        assertEquals("'-50.00", PdfTableExtractorWorker.sanitizeCell("-50.00", safeMode = true))
        assertEquals("'@SUM", PdfTableExtractorWorker.sanitizeCell("@SUM", safeMode = true))
        assertEquals("'\tcommand", PdfTableExtractorWorker.sanitizeCell("\tcommand", safeMode = true))
        assertEquals("'\rdata", PdfTableExtractorWorker.sanitizeCell("\rdata", safeMode = true))

        // Leading whitespace before formula triggers
        assertEquals("'   =1+1", PdfTableExtractorWorker.sanitizeCell("   =1+1", safeMode = true))
        assertEquals("' \t+42", PdfTableExtractorWorker.sanitizeCell(" \t+42", safeMode = true))
        assertEquals("'  -calc", PdfTableExtractorWorker.sanitizeCell("  -calc", safeMode = true))
        assertEquals("'   @admin", PdfTableExtractorWorker.sanitizeCell("   @admin", safeMode = true))

        // Safe cell values without formula triggers
        assertEquals("Normal Text", PdfTableExtractorWorker.sanitizeCell("Normal Text", safeMode = true))
        assertEquals("   Indented Text", PdfTableExtractorWorker.sanitizeCell("   Indented Text", safeMode = true))
        assertEquals("", PdfTableExtractorWorker.sanitizeCell("", safeMode = true))
    }

    @Test
    fun rawModePreservesCellValuesVerbatim() {
        assertEquals("=SUM(A1:A5)", PdfTableExtractorWorker.sanitizeCell("=SUM(A1:A5)", safeMode = false))
        assertEquals("+1234", PdfTableExtractorWorker.sanitizeCell("+1234", safeMode = false))
        assertEquals("-50.00", PdfTableExtractorWorker.sanitizeCell("-50.00", safeMode = false))
        assertEquals("@SUM", PdfTableExtractorWorker.sanitizeCell("@SUM", safeMode = false))
        assertEquals("   =1+1", PdfTableExtractorWorker.sanitizeCell("   =1+1", safeMode = false))
        assertEquals("  -calc", PdfTableExtractorWorker.sanitizeCell("  -calc", safeMode = false))
        assertEquals("Normal Text", PdfTableExtractorWorker.sanitizeCell("Normal Text", safeMode = false))
    }

    @Test
    fun endToEndExtractionDefaultsToSafeModeAndRespectsRawMode() {
        val pdfFile = tempFolder.newFile("formula_table.pdf")
        val safeCsvFile = tempFolder.newFile("safe_output.csv")
        val rawCsvFile = tempFolder.newFile("raw_output.csv")

        createTestPdf(
            pdfFile,
            listOf(
                listOf("Formula", "Payload"),
                listOf("=cmd", "-calc")
            )
        )

        // 1. Default safe mode (safeMode not passed)
        val sourceFdSafe = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFdSafe = ParcelFileDescriptor.open(safeCsvFile, ParcelFileDescriptor.MODE_READ_WRITE)
        val safeResultJson = PdfTableExtractorWorker.extractText(sourceFdSafe, targetFdSafe, "{}")
        sourceFdSafe.close()
        targetFdSafe.close()

        val safeContract = com.pdfchemy.app.jail.WorkerResponseValidator.validate("TABLE_EXTRACT", safeResultJson, targetCount = 1)
        val safeTable = safeContract as com.pdfchemy.app.logic.TableExtractContract
        assertEquals(2, safeTable.rowCount)
        assertEquals(2, safeTable.count)

        val safeCsvText = com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(safeCsvFile)
        val safeParsed = com.pdfchemy.app.logic.CsvParser.parse(safeCsvText)
        assertEquals(2, safeParsed.size)
        assertEquals(listOf("Formula", "Payload"), safeParsed[0])
        assertEquals(listOf("'=cmd", "'-calc"), safeParsed[1])

        // 2. Explicit Raw mode (safeMode = false)
        val sourceFdRaw = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val targetFdRaw = ParcelFileDescriptor.open(rawCsvFile, ParcelFileDescriptor.MODE_READ_WRITE)
        val rawParams = JSONObject().apply { put("safeMode", false) }.toString()
        val rawResultJson = PdfTableExtractorWorker.extractText(sourceFdRaw, targetFdRaw, rawParams)
        sourceFdRaw.close()
        targetFdRaw.close()

        val rawContract = com.pdfchemy.app.jail.WorkerResponseValidator.validate("TABLE_EXTRACT", rawResultJson, targetCount = 1)
        val rawTable = rawContract as com.pdfchemy.app.logic.TableExtractContract
        assertEquals(2, rawTable.rowCount)
        assertEquals(2, rawTable.count)

        val rawCsvText = com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(rawCsvFile)
        val rawParsed = com.pdfchemy.app.logic.CsvParser.parse(rawCsvText)
        assertEquals(2, rawParsed.size)
        assertEquals(listOf("Formula", "Payload"), rawParsed[0])
        assertEquals(listOf("=cmd", "-calc"), rawParsed[1])
    }
}
