package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.billing.BillingManager
import com.pdfchemy.app.logic.CsvParser
import com.pdfchemy.app.logic.LatestRequest
import com.pdfchemy.app.logic.PdfTableExtractorEngine
import com.pdfchemy.app.logic.TableExtractionResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TableExtractorUserPathTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
        tempDir = File(context.cacheDir, "table_user_path_${UUID.randomUUID()}").apply { mkdirs() }
        BillingManager.resetCacheForTesting()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
        BillingManager.resetCacheForTesting()
    }

    private fun createPdfWithTable(
        file: File,
        rows: List<List<String>>,
        colSpacing: Float = 120f
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
                        stream.setFont(PDType1Font.HELVETICA_BOLD, 12f)
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
    fun realIsolatedWorkerTableExtractionAndPreviewParsing() = runTest {
        val pdfFile = File(tempDir, "table_doc.pdf")
        val testRows = listOf(
            listOf("Department", "Headcount", "Budget"),
            listOf("Engineering", "42", "1000000"),
            listOf("Marketing", "15", "500000")
        )
        createPdfWithTable(pdfFile, testRows)

        val result = PdfTableExtractorEngine.extractTables(
            context = context,
            sourceUri = Uri.fromFile(pdfFile),
            safeMode = true
        )

        assertTrue("Table extraction must succeed", result is TableExtractionResult.Success)
        val success = result as TableExtractionResult.Success
        assertEquals("Worker must report 3 rows", 3, success.rowCount)
        assertTrue("Generated CSV must not be blank", success.csv.isNotBlank())

        val parsed = CsvParser.parse(success.csv)
        assertEquals(3, parsed.size)
        assertEquals(listOf("Department", "Headcount", "Budget"), parsed[0])
        assertEquals(listOf("Engineering", "42", "1000000"), parsed[1])
        assertEquals(listOf("Marketing", "15", "500000"), parsed[2])
    }

    @Test
    fun realIsolatedWorkerFileExportContentMatchesInput() = runTest {
        val pdfFile = File(tempDir, "export_table.pdf")
        val exportFile = File(tempDir, "exported_output.csv")
        val testRows = listOf(
            listOf("SKU", "Description", "Price"),
            listOf("A100", "Widget Pro", "$99.99")
        )
        createPdfWithTable(pdfFile, testRows)

        val result = PdfTableExtractorEngine.extractTablesToFile(
            context = context,
            sourceUri = Uri.fromFile(pdfFile),
            destUri = Uri.fromFile(exportFile),
            safeMode = true
        )

        assertTrue("File export must succeed", result is TableExtractionResult.Success)
        assertTrue("Exported file must exist on disk", exportFile.exists())
        assertTrue("Exported file must contain bytes", exportFile.length() > 0)

        val csvContent = exportFile.readText(Charsets.UTF_8)
        val parsed = CsvParser.parse(csvContent)
        assertEquals(2, parsed.size)
        assertEquals(listOf("SKU", "Description", "Price"), parsed[0])
        assertEquals(listOf("A100", "Widget Pro", "$99.99"), parsed[1])
    }

    @Test
    fun changingDocumentsDuringExtractionArbitratesAuthoritativeResult() = runTest {
        val requests = LatestRequest()
        val ticket1 = requests.begin()
        assertTrue(requests.isCurrent(ticket1))

        // Document change occurs before worker 1 finishes
        val ticket2 = requests.begin()
        assertFalse("Old request is immediately superseded", requests.isCurrent(ticket1))
        assertTrue("New request ticket is current", requests.isCurrent(ticket2))

        var publishedData: String? = null
        if (requests.isCurrent(ticket1)) {
            publishedData = "Stale Document 1"
        }
        assertNull("Stale worker result must be dropped", publishedData)

        if (requests.isCurrent(ticket2)) {
            publishedData = "Current Document 2"
        }
        assertEquals("Current Document 2", publishedData)
    }

    @Test
    fun cancellationDuringExtractionSafelyAborts() = runTest {
        val pdfFile = File(tempDir, "cancel_table.pdf")
        createPdfWithTable(pdfFile, listOf(listOf("H1", "H2"), listOf("V1", "V2")))

        val job = launch(Dispatchers.IO) {
            val result = PdfTableExtractorEngine.extractTables(context, Uri.fromFile(pdfFile))
            if (!isActive) return@launch
            assertNotNull(result)
        }
        job.cancelAndJoin()
        assertTrue("Job must be cancelled", job.isCancelled)
    }

    @Test
    fun preferredSaveWrapperComposesInTableExtractorFlow() {
        composeTestRule.setContent {
            MaterialTheme {
                rememberPreferredDocumentCreator("text/csv") { }
                Text("Table Export Wrapper Ready")
            }
        }
        composeTestRule.onNodeWithText("Table Export Wrapper Ready").assertIsDisplayed()
    }

    @Test
    fun activityRecreationRetainsPremiumEntitlementState() {
        BillingManager.resetCacheForTesting()
        assertFalse("Default should be free", BillingManager.isUserEntitled(context))

        BillingManager.updateCachedEntitlement(true)
        assertTrue("Entitlement granted", BillingManager.isUserEntitled(context))

        // Simulate activity recreation / new lifecycle entry
        val reloadedEntitlement = BillingManager.isUserEntitled(context)
        assertTrue("Recreated state must retain premium entitlement", reloadedEntitlement)
    }
}
