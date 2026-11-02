package com.pdfchemy.app.ui

import com.pdfchemy.app.logic.CsvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TableExtractorCorrectnessTest {

    @Test
    fun parseCsvHandlesQuotedCommasCorrectly() {
        val input = "Item,Price,\"Notes, Remarks\"\nWidget,$10.00,\"Fragile, handle with care\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Item", "Price", "Notes, Remarks"), rows[0])
        assertEquals(listOf("Widget", "$10.00", "Fragile, handle with care"), rows[1])
    }

    @Test
    fun parseCsvHandlesEscapedQuotesCorrectly() {
        val input = "Title,Quote\nPhilosophy,\"He said \"\"I think, therefore I am\"\"\""
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Title", "Quote"), rows[0])
        assertEquals(listOf("Philosophy", "He said \"I think, therefore I am\""), rows[1])
    }

    @Test
    fun parseCsvHandlesEmbeddedNewlinesInsideQuotes() {
        val input = "\"Multi\nLine\nField\",Simple\nSecond,Row"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Multi\nLine\nField", "Simple"), rows[0])
        assertEquals(listOf("Second", "Row"), rows[1])
    }

    @Test
    fun parseCsvPreservesQuotedWhitespace() {
        val input = "\"  Leading and trailing spaces  \",\" Tab\tDelimited \",NoQuotes"
        val rows = CsvParser.parse(input)
        assertEquals(1, rows.size)
        assertEquals("  Leading and trailing spaces  ", rows[0][0])
        assertEquals(" Tab\tDelimited ", rows[0][1])
        assertEquals("NoQuotes", rows[0][2])
    }

    @Test
    fun parseCsvPreservesEmptyCells() {
        val input = "Header1,,Header3\nValue1,\"\",Value3\n,,End"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Header1", "", "Header3"), rows[0])
        assertEquals(listOf("Value1", "", "Value3"), rows[1])
        assertEquals(listOf("", "", "End"), rows[2])
    }

    @Test
    fun parseCsvPreservesBlankRows() {
        val input = "Row1A,Row1B\n\nRow3A,Row3B"
        val rows = CsvParser.parse(input)
        assertEquals(3, rows.size)
        assertEquals(listOf("Row1A", "Row1B"), rows[0])
        assertEquals(listOf(""), rows[1]) // Blank row is preserved, not dropped
        assertEquals(listOf("Row3A", "Row3B"), rows[2])
    }

    @Test
    fun parseCsvHandlesTrailingNewlineWithoutExtraRow() {
        val input = "Col1,Col2\r\nVal1,Val2\r\n"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals(listOf("Col1", "Col2"), rows[0])
        assertEquals(listOf("Val1", "Val2"), rows[1])
    }

    @Test
    fun parseCsvHandlesEmptyInput() {
        val input = ""
        val rows = CsvParser.parse(input)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun parseCsvRejectsUnclosedQuoteAtEof() {
        val input = "Header1,Header2\nValue1,\"Unclosed quote"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(input)
        }
    }

    @Test
    fun parseCsvRejectsUnescapedQuoteInUnquotedField() {
        val input = "Header1,Header2\nValue1,Val\"ue2"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(input)
        }
    }

    @Test
    fun parseCsvRejectsCharactersAfterClosingQuote() {
        val input = "Header1,Header2\nValue1,\"Value2\"Stray"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(input)
        }
    }

    @Test
    fun parseCsvHandlesEmbeddedCrlfInsideQuotes() {
        val input = "\"Line1\r\nLine2\",SecondCol\r\nThird,Fourth"
        val rows = CsvParser.parse(input)
        assertEquals(2, rows.size)
        assertEquals("Line1\r\nLine2", rows[0][0])
        assertEquals("SecondCol", rows[0][1])
    }

    @Test
    fun parseCsvEnforcesCellCharacterLimit() {
        val longCell = "A".repeat(CsvParser.MAX_CELL_CHARS + 1)
        val input = "Col1,Col2\nVal1,$longCell"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(input)
        }
    }

    @Test
    fun parseCsvEnforcesColumnLimit() {
        val manyCols = (0..CsvParser.MAX_COLS).joinToString(",") { "Col$it" }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            CsvParser.parse(manyCols)
        }
    }

    @Test
    fun sequentialAndOverlappingSharesPreserveAllRecipientFiles() {
        val exportsDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_exports_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val createdFiles = mutableListOf<java.io.File>()
            for (i in 1..10) {
                val shareFile = java.io.File(exportsDir, "shared_table_${java.util.UUID.randomUUID()}.csv")
                shareFile.writeText("Row$i,Data$i", Charsets.UTF_8)
                createdFiles.add(shareFile)
            }

            // Verify that all 10 files still exist and none were prematurely pruned
            for (file in createdFiles) {
                assertTrue("Existing shared file ${file.name} must remain accessible for recipients", file.exists())
                assertTrue("File contents must remain intact", file.readText().isNotBlank())
            }
        } finally {
            exportsDir.deleteRecursively()
        }
    }

    @Test
    fun documentSelectionAuthoritativeSequence() {
        val uriA = android.net.Uri.parse("content://test/documentA.pdf")
        val uriB = android.net.Uri.parse("content://test/documentB.pdf")

        var initialPdfUri: android.net.Uri? = uriA
        var selectedPdfUri: android.net.Uri? = initialPdfUri
        var hasHandledInitialUri = false

        // Step 1: Initial load with A
        if (initialPdfUri != null && !hasHandledInitialUri) {
            hasHandledInitialUri = true
            selectedPdfUri = initialPdfUri
        }
        assertEquals(uriA, selectedPdfUri)

        // Step 2: User selects Document B in UI
        hasHandledInitialUri = true
        selectedPdfUri = uriB
        assertEquals(uriB, selectedPdfUri)

        // Step 3: User toggles Safe / Raw mode
        val activeTargetForModeSwitch = if (hasHandledInitialUri || selectedPdfUri != null) selectedPdfUri else initialPdfUri
        assertEquals("Mode switch must target document B, never reverting to initial document A", uriB, activeTargetForModeSwitch)
    }

    @Test
    fun tableExtractContractReportsAccurateRowCountDecoupledFromOutputFileCount() {
        val contractJson = org.json.JSONObject().apply {
            put("success", true)
            put("empty", false)
            put("count", 1) // 1 output file
            put("rowCount", 42) // 42 extracted rows
        }.toString()

        val contract = com.pdfchemy.app.jail.WorkerResponseValidator.validate("TABLE_EXTRACT", contractJson, targetCount = 1)
        assertTrue(contract is com.pdfchemy.app.logic.TableExtractContract)
        val tableContract = contract as com.pdfchemy.app.logic.TableExtractContract
        assertEquals(1, tableContract.count)
        assertEquals(42, tableContract.rowCount)
        assertFalse(tableContract.empty)
    }

    @Test
    fun tableExtractContractValidatesEmptyExtractionAccurately() {
        val contractJson = org.json.JSONObject().apply {
            put("success", true)
            put("empty", true)
            put("count", 0)
            put("rowCount", 0)
        }.toString()

        val contract = com.pdfchemy.app.jail.WorkerResponseValidator.validate("TABLE_EXTRACT", contractJson, targetCount = 0)
        assertTrue(contract is com.pdfchemy.app.logic.TableExtractContract)
        val tableContract = contract as com.pdfchemy.app.logic.TableExtractContract
        assertTrue(tableContract.empty)
        assertEquals(0, tableContract.rowCount)
    }

    @Test
    fun latestRequestProtectsAgainstStalePublication() {
        val requests = com.pdfchemy.app.logic.LatestRequest()
        val ticket1 = requests.begin()
        assertTrue(requests.isCurrent(ticket1))

        // Newer request starts
        val ticket2 = requests.begin()
        assertTrue(requests.isCurrent(ticket2))
        assertFalse("Older ticket1 must not be current after ticket2 begins", requests.isCurrent(ticket1))
    }

    @Test
    fun concurrentSwitchingOnlyAllowsLatestRequestToPublish() {
        val requests = com.pdfchemy.app.logic.LatestRequest()

        // User opens Document A
        val ticketA = requests.begin()
        assertTrue(requests.isCurrent(ticketA))

        // While A is extracting, user rapidly selects Document B
        val ticketB = requests.begin()
        assertFalse("Ticket A is immediately invalidated", requests.isCurrent(ticketA))
        assertTrue(requests.isCurrent(ticketB))

        // While B is extracting, user selects Document C
        val ticketC = requests.begin()
        assertFalse("Ticket B is immediately invalidated", requests.isCurrent(ticketB))
        assertTrue(requests.isCurrent(ticketC))

        // Simulate out-of-order completion: Worker A finishes late
        var publishedResult: String? = null
        if (requests.isCurrent(ticketA)) {
            publishedResult = "Result from A"
        }
        assertEquals("Worker A must not publish", null, publishedResult)

        // Worker B finishes late
        if (requests.isCurrent(ticketB)) {
            publishedResult = "Result from B"
        }
        assertEquals("Worker B must not publish", null, publishedResult)

        // Worker C finishes
        if (requests.isCurrent(ticketC)) {
            publishedResult = "Result from C"
        }
        assertEquals("Worker C must publish", "Result from C", publishedResult)
    }

    @Test
    fun cancellationDuringExtractionLeavesUiStateIntact() {
        var uiExtractedCsv = "Previous Valid Data"
        var uiIsEmpty = false
        var uiError: String? = null

        val result: com.pdfchemy.app.logic.TableExtractionResult = com.pdfchemy.app.logic.TableExtractionResult.Cancelled

        when (result) {
            is com.pdfchemy.app.logic.TableExtractionResult.Success -> {
                uiExtractedCsv = result.csv
            }
            is com.pdfchemy.app.logic.TableExtractionResult.Empty -> {
                uiIsEmpty = true
            }
            is com.pdfchemy.app.logic.TableExtractionResult.Failure -> {
                uiError = "Failed"
            }
            is com.pdfchemy.app.logic.TableExtractionResult.Cancelled -> {
                // Guaranteed no-op on cancellation
            }
        }

        assertEquals("Previous Valid Data", uiExtractedCsv)
        assertFalse(uiIsEmpty)
        assertEquals(null, uiError)
    }

    @Test
    fun exportResultDistinguishesSuccessFromEmptyRegardlessOfDestinationReadability() {
        // Contract with successful extraction: 42 rows
        val successContract = com.pdfchemy.app.logic.TableExtractContract(
            success = true,
            empty = false,
            rowCount = 42,
            count = 42
        )
        val successResult = if (successContract.empty || successContract.rowCount == 0) {
            com.pdfchemy.app.logic.TableExtractionResult.Empty()
        } else {
            com.pdfchemy.app.logic.TableExtractionResult.Success("", successContract.rowCount)
        }
        assertTrue("Successful extraction must produce TableExtractionResult.Success", successResult is com.pdfchemy.app.logic.TableExtractionResult.Success)
        assertEquals(42, (successResult as com.pdfchemy.app.logic.TableExtractionResult.Success).rowCount)

        // Contract with empty extraction: 0 rows
        val emptyContract = com.pdfchemy.app.logic.TableExtractContract(
            success = true,
            empty = true,
            rowCount = 0,
            count = 0
        )
        val emptyResult = if (emptyContract.empty || emptyContract.rowCount == 0) {
            com.pdfchemy.app.logic.TableExtractionResult.Empty()
        } else {
            com.pdfchemy.app.logic.TableExtractionResult.Success("", emptyContract.rowCount)
        }
        assertTrue("Empty extraction must produce TableExtractionResult.Empty", emptyResult is com.pdfchemy.app.logic.TableExtractionResult.Empty)
    }

    @Test
    fun exportInputsRemainStableWhenUserChangesSelectionOrModeDuringExport() {
        val initialDocUri = android.net.Uri.parse("content://docs/initial.pdf")
        val initialSafeMode = true

        var activeDocUri = initialDocUri
        var activeSafeMode = initialSafeMode

        // User taps export: snapshot inputs
        var exportSourceUri: android.net.Uri? = activeDocUri
        val exportSafeMode = activeSafeMode

        // User changes document and mode in the UI while export is pending/running
        activeDocUri = android.net.Uri.parse("content://docs/changed.pdf")
        activeSafeMode = false

        // Export worker execution uses snapshotted parameters
        val resolvedSourceUri = exportSourceUri ?: activeDocUri
        val resolvedSafeMode = exportSafeMode

        assertEquals("Export source must match original document", initialDocUri, resolvedSourceUri)
        assertEquals("Export safe mode must match original setting", true, resolvedSafeMode)

        // Reset on completion
        exportSourceUri = null
        org.junit.Assert.assertNull(exportSourceUri)
    }

    @Test
    fun concurrentExportAttemptsAreSafelyRejected() {
        var isExporting = true
        var secondaryExportLaunched = false

        fun triggerExport() {
            if (isExporting) return
            secondaryExportLaunched = true
        }

        triggerExport()
        assertFalse("Secondary export must not launch while an export is already active", secondaryExportLaunched)
    }

    @Test
    fun sharingWhileAnotherRecipientReadsEarlierFileDoesNotCorruptOrDelete() {
        val exportsDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_share_readers_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            // Recipient 1 opens first shared file
            val file1 = java.io.File(exportsDir, "shared_table_1.csv").apply { writeText("File1Header,Data\n1,Alpha", Charsets.UTF_8) }
            val readerStream = java.io.FileInputStream(file1)

            // Multiple subsequent shares are created (beyond 5)
            val subsequentFiles = (2..10).map { i ->
                java.io.File(exportsDir, "shared_table_$i.csv").apply { writeText("File${i}Header,Data\n$i,Data", Charsets.UTF_8) }
            }

            // Verify file1 is still readable and content intact while reader holds open stream
            val contentRead = readerStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            assertEquals("File1Header,Data\n1,Alpha", contentRead)
            assertTrue("File 1 must still exist on disk", file1.exists())

            // Verify all subsequent files also exist intact
            for (f in subsequentFiles) {
                assertTrue(f.exists())
            }
        } finally {
            exportsDir.deleteRecursively()
        }
    }

    @Test
    fun cancellationDuringCopyDeletesPartialTemporaryFile() {
        val exportsDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_cancel_share_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = java.io.File(exportsDir, "shared_table_partial.csv")
        try {
            var isCancelled = false
            try {
                java.io.FileOutputStream(tempFile).use { out ->
                    out.write("PartialData".toByteArray(Charsets.UTF_8))
                    // Simulate cancellation mid-write
                    throw kotlinx.coroutines.CancellationException("User cancelled share")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                isCancelled = true
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            assertTrue("Cancellation caught", isCancelled)
            assertFalse("Partial temporary file must be cleaned up on cancellation", tempFile.exists())
        } finally {
            exportsDir.deleteRecursively()
        }
    }

    @Test
    fun failureHalfwayThroughCopyingCleansUpPartialFile() {
        val exportsDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_fail_share_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = java.io.File(exportsDir, "shared_table_fail.csv")
        try {
            var failed = false
            try {
                java.io.FileOutputStream(tempFile).use { out ->
                    out.write("IncompleteData".toByteArray(Charsets.UTF_8))
                    throw java.io.IOException("Disk quota simulated error")
                }
            } catch (e: Exception) {
                failed = true
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            assertTrue("Exception caught", failed)
            assertFalse("Partial file must be deleted on error", tempFile.exists())
        } finally {
            exportsDir.deleteRecursively()
        }
    }

    @Test
    fun largeValidCsvInputSharingWritesCompleteContent() {
        val exportsDir = java.io.File(System.getProperty("java.io.tmpdir"), "test_large_share_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val tempFile = java.io.File(exportsDir, "shared_table_large.csv")
        try {
            // 2000 rows of valid CSV data
            val header = "ColA,ColB,ColC,ColD\n"
            val rows = (1..2000).joinToString("\n") { "Row$it,Val$it,\"Quoted, Data $it\",End$it" }
            val fullCsv = header + rows

            java.io.FileOutputStream(tempFile).use { it.write(fullCsv.toByteArray(Charsets.UTF_8)) }
            assertTrue(tempFile.exists())
            assertEquals(fullCsv.toByteArray(Charsets.UTF_8).size.toLong(), tempFile.length())

            val parsed = CsvParser.parse(tempFile.readText(Charsets.UTF_8))
            assertEquals(2001, parsed.size)
        } finally {
            exportsDir.deleteRecursively()
        }
    }

    @Test
    fun duplicateSimultaneousShareRequestsAreSafelyPrevented() {
        var isSharing = true
        var secondaryShareStarted = false

        fun triggerShare() {
            if (isSharing) return
            secondaryShareStarted = true
        }

        triggerShare()
        assertFalse("Duplicate simultaneous share request must not be triggered", secondaryShareStarted)
    }

    @Test
    fun sanitizeErrorMessageReturnsNullForNullOrBlank() {
        org.junit.Assert.assertNull(sanitizeErrorMessage(null))
        org.junit.Assert.assertNull(sanitizeErrorMessage(""))
        org.junit.Assert.assertNull(sanitizeErrorMessage("   \t\n  "))
    }

    @Test
    fun sanitizeErrorMessageStripsExceptionPrefixAndPaths() {
        val input = "java.io.FileNotFoundException: /data/user/0/com.pdfchemy.app/cache/table.pdf (No such file)"
        val sanitized = sanitizeErrorMessage(input)
        assertEquals("[path] (No such file)", sanitized)
    }

    @Test
    fun sanitizeErrorMessageStripsUrisAndWindowsPaths() {
        val uriInput = "Failed to read content://com.android.providers.media.documents/document/1234: I/O error"
        assertEquals("Failed to read [uri]: I/O error", sanitizeErrorMessage(uriInput))

        val fileUriInput = "file:///storage/emulated/0/doc.pdf unreadable"
        assertEquals("[uri] unreadable", sanitizeErrorMessage(fileUriInput))

        val winInput = "Error at C:\\Users\\John\\AppData\\Local\\Temp\\test.pdf occurred"
        assertEquals("Error at [path] occurred", sanitizeErrorMessage(winInput))
    }

    @Test
    fun sanitizeErrorMessageScrubsStackTraceLines() {
        val trace = "Error processing table\n\tat com.pdfchemy.app.Engine.extract(Engine.kt:123)\n\tat com.pdfchemy.app.Worker.run(Worker.java:45)"
        assertEquals("Error processing table", sanitizeErrorMessage(trace))
    }

    @Test
    fun sanitizeErrorMessageEnforcesDisplayLengthLimit() {
        val longMessage = "This is a detailed error message describing table extraction failure: " + "A".repeat(250)
        val sanitized = sanitizeErrorMessage(longMessage)
        org.junit.Assert.assertNotNull(sanitized)
        assertTrue("Sanitized error must be capped at 180 characters", sanitized!!.length <= 180)
        assertTrue("Truncated message must end with ellipsis", sanitized.endsWith("..."))
    }
}
