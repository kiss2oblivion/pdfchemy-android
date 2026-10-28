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
        assertFalse(tableContract.empty)

        val csvText = com.pdfchemy.app.jail.WorkerResponseValidator.parseCsvTableFile(outputFile)
        val parsed = com.pdfchemy.app.logic.CsvParser.parse(csvText)
        assertEquals(3, parsed.size)
        assertEquals(listOf("First Name", "Last Name", "Department"), parsed[0])
        assertEquals(listOf("Alice", "Smith", "Engineering"), parsed[1])
        assertEquals(listOf("Bob", "Jones", "Finance"), parsed[2])
    }
}
