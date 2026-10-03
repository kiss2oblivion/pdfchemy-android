package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.*
import com.pdfchemy.app.security.SecurityLimits
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class WorkerResponseValidatorTest {

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection but succeeded")
        } catch (_: IllegalArgumentException) {
            // expected
        } catch (_: SecurityException) {
            // expected
        }
    }

    @Test
    fun oversizedPayloadThrows() {
        val large = "{\"test\":\"" + "a".repeat(1024 * 1024 + 10) + "\"}"
        assertRejected { WorkerResponseValidator.validate("DEBUG_IDENTITY", large) }
    }

    @Test
    fun nestingQuotaEnforced() {
        // Depth 9 exceeds MAX_REQUEST_DEPTH (8)
        val deep = "[[[[[[[[[\"deep\"]]]]]]]]]"
        assertRejected { WorkerResponseValidator.validate("TEXT_PAGES", deep) }

        // Depth 1 is valid
        val valid = "[\"page1\", \"page2\"]"
        val contract = WorkerResponseValidator.validate("TEXT_PAGES", valid)
        assertTrue(contract is TextPagesContract)
        assertEquals(2, (contract as TextPagesContract).pages.size)
    }

    @Test
    fun unbalancedDelimitersThrow() {
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5}}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": \"5}") }
    }

    @Test
    fun singleRootEnforcedAndTrailingNonWhitespaceRejected() {
        // Trailing ASCII whitespace is permitted
        val validWithWhitespace = "{\"pageCount\": 5}   \n\t  "
        val c1 = WorkerResponseValidator.validate("GET_PAGE_COUNT", validWithWhitespace)
        assertEquals(5, (c1 as PageCountContract).pageCount)

        // Trailing non-whitespace rejected
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5} extra") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5};") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5}{\"pageCount\": 6}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5},") }
    }

    @Test
    fun nodeLimitEnforced() {
        // Exceed MAX_TOTAL_NODES (5000)
        val sb = StringBuilder("[")
        for (i in 0..5005) {
            if (i > 0) sb.append(",")
            sb.append("\"item$i\"")
        }
        sb.append("]")
        assertRejected { WorkerResponseValidator.validate("TEXT_PAGES", sb.toString()) }
    }

    @Test
    fun errorPresenceRejected() {
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"error\": \"Worker crashed\"}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 5, \"error\": \"bad\"}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"success\": false}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"isSuccess\": false}") }
    }

    @Test
    fun unknownOperationFailsClosed() {
        assertRejected { WorkerResponseValidator.validate("UNRECOGNIZED_ENGINE", "{\"success\": true}") }
        assertRejected { WorkerResponseValidator.validate("ESCAPE_HATCH", "{}") }
    }

    @Test
    fun sanitizeAuditRejectsInsecureDefaults() {
        // Empty JSON defaulting to clean rejected
        assertRejected { WorkerResponseValidator.validate("SANITIZE_AUDIT", "{}") }

        // threatsFound > 0 reporting isClean=true rejected
        val badThreat = """{
            "threatsFound": 2, "isClean": true, "jsCount": 2, "launchActionsCount": 0,
            "attachmentCount": 0, "uriCount": 0, "hasMetadata": false, "isEncrypted": false, "parseFailed": false
        }"""
        assertRejected { WorkerResponseValidator.validate("SANITIZE_AUDIT", badThreat) }

        // parseFailed=true reporting isClean=true rejected
        val badParse = """{
            "threatsFound": 1, "isClean": true, "isEncrypted": false, "parseFailed": true
        }"""
        assertRejected { WorkerResponseValidator.validate("SANITIZE_AUDIT", badParse) }

        // Valid clean audit
        val clean = """{
            "threatsFound": 0, "isClean": true, "jsCount": 0, "launchActionsCount": 0,
            "attachmentCount": 0, "uriCount": 0, "hasMetadata": false, "isEncrypted": false, "parseFailed": false
        }"""
        val c = WorkerResponseValidator.validate("SANITIZE_AUDIT", clean) as SanitizeAuditContract
        assertTrue(c.isClean)
        assertEquals(0, c.threatsFound)
        assertFalse(c.isEncrypted)
        assertFalse(c.parseFailed)

        // Valid canonical encrypted audit
        val encrypted = """{
            "threatsFound": 1, "isClean": false, "jsCount": 0, "launchActionsCount": 0,
            "attachmentCount": 0, "uriCount": 0, "hasMetadata": false, "isEncrypted": true, "parseFailed": false
        }"""
        val cEncrypted = WorkerResponseValidator.validate("SANITIZE_AUDIT", encrypted) as SanitizeAuditContract
        assertFalse(cEncrypted.isClean)
        assertTrue(cEncrypted.isEncrypted)
        assertFalse(cEncrypted.parseFailed)
        assertEquals(1, cEncrypted.threatsFound)

        // isEncrypted=true reporting isClean=true rejected fail-closed
        val badEncryptedClean = """{
            "threatsFound": 1, "isClean": true, "jsCount": 0, "launchActionsCount": 0,
            "attachmentCount": 0, "uriCount": 0, "hasMetadata": false, "isEncrypted": true, "parseFailed": false
        }"""
        assertRejected { WorkerResponseValidator.validate("SANITIZE_AUDIT", badEncryptedClean) }

        // Missing required field in canonical schema rejected
        val missingField = """{
            "threatsFound": 1, "isClean": false, "isEncrypted": true, "parseFailed": false
        }"""
        assertRejected { WorkerResponseValidator.validate("SANITIZE_AUDIT", missingField) }
    }

    @Test
    fun imageAnalyzeEnforcesCanonicalSchemaAndBounds() {
        // Valid supported allowed image with canonical enum-string qualityLoss
        val validSupported = """{
            "width": 1600, "height": 1200, "originalSizeBytes": 500000,
            "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
            "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
            "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50,
            "qualityLoss": "MINIMAL"
        }"""
        val c = WorkerResponseValidator.validate("IMAGE_ANALYZE", validSupported) as ImageAnalysisContract
        assertTrue(c.analysis.isSupported)
        assertEquals(1600, c.analysis.width)
        assertEquals(1200, c.analysis.height)
        assertEquals(PerceivedQualityLoss.MINIMAL, c.analysis.qualityLoss)

        // Supported allowed image with 0x0 dimensions rejected
        val badZeroDimensionSupported = """{
            "width": 0, "height": 0, "originalSizeBytes": 500000,
            "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
            "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
            "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50,
            "qualityLoss": "MINIMAL"
        }"""
        assertRejected { WorkerResponseValidator.validate("IMAGE_ANALYZE", badZeroDimensionSupported) }

        // Denied/corrupt/unsupported image permits 0x0 dimensions
        val validDeniedCorrupt = """{
            "width": 0, "height": 0, "originalSizeBytes": 100,
            "mimeType": "unknown", "formatName": "Corrupt/Unknown", "isSupported": false,
            "validationStatus": "DENIED_CORRUPT", "validationMessage": "corrupt", "alternativeSuggestion": "use jpg",
            "estimatedCompressedBytes": 100, "estimatedSavingsPercent": 0,
            "qualityLoss": "NEGLIGIBLE"
        }"""
        val cDenied = WorkerResponseValidator.validate("IMAGE_ANALYZE", validDeniedCorrupt) as ImageAnalysisContract
        assertFalse(cDenied.analysis.isSupported)
        assertEquals(0, cDenied.analysis.width)
        assertEquals(0, cDenied.analysis.height)
        assertEquals(ImageValidationStatus.DENIED_CORRUPT, cDenied.analysis.validationStatus)

        // Contradictory states in ImageAnalysis semantic matrix must be rejected
        // 1. isSupported=false + ALLOWED
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 1600, "height": 1200, "originalSizeBytes": 500000,
                "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": false,
                "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
                "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50, "qualityLoss": "MINIMAL"
            }""")
        }

        // 2. isSupported=true + DENIED_CORRUPT
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 0, "height": 0, "originalSizeBytes": 100,
                "mimeType": "unknown", "formatName": "Corrupt/Unknown", "isSupported": true,
                "validationStatus": "DENIED_CORRUPT", "validationMessage": "corrupt", "alternativeSuggestion": "use jpg",
                "estimatedCompressedBytes": 100, "estimatedSavingsPercent": 0, "qualityLoss": "NEGLIGIBLE"
            }""")
        }

        // 3. isSupported=true + DENIED_UNSUPPORTED_FORMAT
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 0, "height": 0, "originalSizeBytes": 100,
                "mimeType": "image/svg+xml", "formatName": "SVG", "isSupported": true,
                "validationStatus": "DENIED_UNSUPPORTED_FORMAT", "validationMessage": "vector", "alternativeSuggestion": null,
                "estimatedCompressedBytes": 100, "estimatedSavingsPercent": 0, "qualityLoss": "NEGLIGIBLE"
            }""")
        }

        // 4. DENIED_UNSUPPORTED_FORMAT with positive dimensions (must be 0x0)
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 100, "height": 100, "originalSizeBytes": 100,
                "mimeType": "image/svg+xml", "formatName": "SVG", "isSupported": false,
                "validationStatus": "DENIED_UNSUPPORTED_FORMAT", "validationMessage": "vector", "alternativeSuggestion": null,
                "estimatedCompressedBytes": 100, "estimatedSavingsPercent": 0, "qualityLoss": "NEGLIGIBLE"
            }""")
        }

        // 5. DENIED_TOO_SMALL with 0x0 dimensions (must have positive dimensions)
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 0, "height": 0, "originalSizeBytes": 100,
                "mimeType": "image/png", "formatName": "PNG", "isSupported": false,
                "validationStatus": "DENIED_TOO_SMALL", "validationMessage": "too small", "alternativeSuggestion": null,
                "estimatedCompressedBytes": 100, "estimatedSavingsPercent": 0, "qualityLoss": "NEGLIGIBLE"
            }""")
        }

        // 6. DENIED_TOO_SMALL valid combination (isSupported=false + positive dimensions)
        val validTooSmall = """{
            "width": 64, "height": 64, "originalSizeBytes": 800,
            "mimeType": "image/png", "formatName": "PNG", "isSupported": false,
            "validationStatus": "DENIED_TOO_SMALL", "validationMessage": "too small", "alternativeSuggestion": null,
            "estimatedCompressedBytes": 800, "estimatedSavingsPercent": 0, "qualityLoss": "NEGLIGIBLE"
        }"""
        val cTooSmall = WorkerResponseValidator.validate("IMAGE_ANALYZE", validTooSmall) as ImageAnalysisContract
        assertFalse(cTooSmall.analysis.isSupported)
        assertEquals(64, cTooSmall.analysis.width)
        assertEquals(64, cTooSmall.analysis.height)
        assertEquals(ImageValidationStatus.DENIED_TOO_SMALL, cTooSmall.analysis.validationStatus)

        // 7. WARNING_ALREADY_COMPRESSED valid combination (isSupported=true + positive dimensions)
        val validWarning = """{
            "width": 1920, "height": 1080, "originalSizeBytes": 120000,
            "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
            "validationStatus": "WARNING_ALREADY_COMPRESSED", "validationMessage": "already compressed", "alternativeSuggestion": null,
            "estimatedCompressedBytes": 115000, "estimatedSavingsPercent": 4, "qualityLoss": "NEGLIGIBLE"
        }"""
        val cWarning = WorkerResponseValidator.validate("IMAGE_ANALYZE", validWarning) as ImageAnalysisContract
        assertTrue(cWarning.analysis.isSupported)
        assertEquals(1920, cWarning.analysis.width)
        assertEquals(1080, cWarning.analysis.height)
        assertEquals(ImageValidationStatus.WARNING_ALREADY_COMPRESSED, cWarning.analysis.validationStatus)

        // Non-enum string or object-based qualityLoss rejected (only canonical enum string accepted)
        val badObjectQl = """{
            "width": 1600, "height": 1200, "originalSizeBytes": 500000,
            "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
            "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
            "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50,
            "qualityLoss": {"level": "Minimal", "stars": 4}
        }"""
        assertRejected { WorkerResponseValidator.validate("IMAGE_ANALYZE", badObjectQl) }

        val badInvalidStringQl = """{
            "width": 1600, "height": 1200, "originalSizeBytes": 500000,
            "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
            "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
            "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50,
            "qualityLoss": "INVALID_QUALITY_STRING"
        }"""
        assertRejected { WorkerResponseValidator.validate("IMAGE_ANALYZE", badInvalidStringQl) }
    }

    @Test
    fun integerValidationRejectsFractionalNumbers() {
        // 1. Fractional width/height in IMAGE_ANALYZE
        assertRejected {
            WorkerResponseValidator.validate("IMAGE_ANALYZE", """{
                "width": 1600.9, "height": 1200, "originalSizeBytes": 500000,
                "mimeType": "image/jpeg", "formatName": "JPEG", "isSupported": true,
                "validationStatus": "ALLOWED", "validationMessage": null, "alternativeSuggestion": null,
                "estimatedCompressedBytes": 250000, "estimatedSavingsPercent": 50, "qualityLoss": "MINIMAL"
            }""")
        }

        // 2. Fractional pageCount in GET_PAGE_COUNT
        assertRejected {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": 3.14}")
        }

        // 3. Fractional threatsFound in SANITIZE_AUDIT
        assertRejected {
            WorkerResponseValidator.validate("SANITIZE_AUDIT", """{
                "threatsFound": 1.5, "isClean": false, "isEncrypted": false, "parseFailed": false,
                "jsCount": 0, "launchActionsCount": 0, "attachmentCount": 0, "uriCount": 0, "hasMetadata": false
            }""")
        }

        // 4. Fractional renderedCount in ROTATE
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"renderedCount\": 1.5}")
        }

        // 5. Fractional size in ROTATE object
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"size\": 1024.5}")
        }

        // 6. Fractional root Number in ROTATE
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "1.9")
        }

        // 7. Fractional root Number in REPLACE_ALL
        assertRejected {
            WorkerResponseValidator.validate("REPLACE_ALL", "1.9")
        }

        // 8. Negative root Number in REPLACE_ALL
        assertRejected {
            WorkerResponseValidator.validate("REPLACE_ALL", "-1")
        }

        // 9. Root Number exceeding Int.MAX_VALUE in REPLACE_ALL
        assertRejected {
            WorkerResponseValidator.validate("REPLACE_ALL", "${Int.MAX_VALUE.toLong() + 1L}")
        }

        // 10. Valid non-fractional integers in REPLACE_ALL
        val cReplace0 = WorkerResponseValidator.validate("REPLACE_ALL", "0") as ReplaceAllContract
        assertEquals(0, cReplace0.replacementsCount)

        val cReplace1 = WorkerResponseValidator.validate("REPLACE_ALL", "1") as ReplaceAllContract
        assertEquals(1, cReplace1.replacementsCount)

        val cReplaceMax = WorkerResponseValidator.validate("REPLACE_ALL", "${Int.MAX_VALUE}") as ReplaceAllContract
        assertEquals(Int.MAX_VALUE, cReplaceMax.replacementsCount)

        val cReplaceObj = WorkerResponseValidator.validate("REPLACE_ALL", "{\"count\": 42}") as ReplaceAllContract
        assertEquals(42, cReplaceObj.replacementsCount)

        // 11. Non-fractional integers must pass cleanly in ROTATE
        val c = WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"size\": 2048, \"renderedCount\": 2}", targetCount = 2) as StandardOutputContract
        assertTrue(c.success)
        assertEquals(2048L, c.size)
    }

    @Test
    fun numericLongBoundaryValidationEnforced() {
        // Long.MAX_VALUE represented safely as Long in object field
        val validMaxLong = "{\"success\": true, \"size\": ${Long.MAX_VALUE}}"
        val cMax = WorkerResponseValidator.validate("ROTATE", validMaxLong) as StandardOutputContract
        assertEquals(Long.MAX_VALUE, cMax.size)

        // Double with 2^63 (9223372036854775808.0) > Long.MAX_VALUE rejected
        val overflowDouble = "{\"success\": true, \"size\": 9223372036854775808.0}"
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", overflowDouble)
        }

        // Large Double > Long.MAX_VALUE rejected in root number
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "9223372036854775808.0")
        }

        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "1e20")
        }

        // Negative value in non-negative Long field (size) rejected
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"size\": -1}")
        }

        // Double with value < Long.MIN_VALUE rejected
        assertRejected {
            WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"size\": -9223372036854775809.0}")
        }
    }

    @Test
    fun standardOutputRejectsUnsupportedRootShapes() {
        // Valid root shapes
        val cObj = WorkerResponseValidator.validate("ROTATE", "{\"success\": true, \"size\": 1024}") as StandardOutputContract
        assertTrue(cObj.success)
        assertEquals(1024L, cObj.size)

        val cBool = WorkerResponseValidator.validate("ROTATE", "true") as StandardOutputContract
        assertTrue(cBool.success)

        val cNum = WorkerResponseValidator.validate("ROTATE", "2048") as StandardOutputContract
        assertTrue(cNum.success)
        assertEquals(2048L, cNum.size)

        // Unexpected root shapes must be rejected
        assertRejected { WorkerResponseValidator.validate("ROTATE", "\"unexpected_root_string\"") }
        assertRejected { WorkerResponseValidator.validate("ROTATE", "[\"unexpected\", \"array\"]") }
        assertRejected { WorkerResponseValidator.validate("ROTATE", "null") }
    }

    @Test
    fun metadataReadValidatedStrictly() {
        val json = """{
            "title": "Report", "author": "Alice", "subject": "Quarterly", "keywords": "audit",
            "creator": "Host", "producer": "PDFchemy", "creationDate": "2026-10-01",
            "modificationDate": "2026-10-02", "pageCount": 42, "hasXmpMetadata": true, "isEncrypted": false
        }"""
        val contract = WorkerResponseValidator.validate("METADATA_READ", json) as MetadataReadContract
        assertEquals("Report", contract.title)
        assertEquals(42, contract.pageCount)
        assertTrue(contract.hasXmpMetadata)
        assertFalse(contract.isEncrypted)
    }

    @Test
    fun pageCountEnforcesLimits() {
        val valid = "{\"pageCount\": 10}"
        val c = WorkerResponseValidator.validate("GET_PAGE_COUNT", valid) as PageCountContract
        assertEquals(10, c.pageCount)

        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": -1}") }
        assertRejected { WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\": ${SecurityLimits.MAX_PAGE_COUNT + 1}}") }
    }

    @Test
    fun searchRedactValidatesBoundingBoxes() {
        // Valid box
        val valid = """{
            "boxes": [
                {"pageIndex": 0, "left": 0.1, "top": 0.2, "right": 0.5, "bottom": 0.6, "overlayLabel": "REDACTED"}
            ]
        }"""
        val c = WorkerResponseValidator.validate("SEARCH_REDACT", valid) as SearchRedactContract
        assertEquals(1, c.boxes.size)
        assertEquals(0.4f, c.boxes[0].widthRatio, 0.001f)
        assertEquals(0.4f, c.boxes[0].heightRatio, 0.001f)

        // Invalid bounds (right < left)
        val inverted = """{
            "boxes": [
                {"pageIndex": 0, "left": 0.8, "top": 0.2, "right": 0.5, "bottom": 0.6, "overlayLabel": "REDACTED"}
            ]
        }"""
        assertRejected { WorkerResponseValidator.validate("SEARCH_REDACT", inverted) }
    }

    @Test
    fun deskewContractParsed() {
        val valid = "{\"success\": true, \"straightenedCount\": 3}"
        val c = WorkerResponseValidator.validate("DESKEW", valid) as DeskewContract
        assertTrue(c.success)
        assertEquals(3, c.straightenedCount)
    }

    @Test
    fun repairApplyContractParsed() {
        val valid = "{\"success\": true, \"recoveredPages\": 12}"
        val c = WorkerResponseValidator.validate("REPAIR_APPLY", valid) as RepairApplyContract
        assertTrue(c.success)
        assertEquals(12, c.recoveredPages)
    }

    @Test
    fun officeExportContractParsed() {
        val valid = "{\"success\": true, \"pageCount\": 5, \"outputSizeBytes\": 10240, \"itemsExtracted\": 50}"
        val c = WorkerResponseValidator.validate("OFFICE_WORD", valid) as OfficeExportContract
        assertTrue(c.success)
        assertEquals(5, c.pageCount)
        assertEquals(10240L, c.outputSizeBytes)
        assertEquals(50, c.itemsExtracted)
    }

    @Test
    fun standardOutputRespectsTargetCount() {
        val valid = "{\"success\": true, \"renderedCount\": 2, \"size\": 1234}"
        val c = WorkerResponseValidator.validate("ROTATE", valid, targetCount = 2) as StandardOutputContract
        assertTrue(c.success)
        assertEquals(1234L, c.size)

        // Count mismatch throws
        assertRejected { WorkerResponseValidator.validate("ROTATE", valid, targetCount = 3) }
    }

    @Test
    fun outlineFileEnforcesLimits() {
        val temp = File.createTempFile("test_outline_", ".json")
        try {
            // Valid outline
            val valid = """{
                "sections": [
                    {"pageNumber": 1, "title": "Intro", "paragraphs": ["First line", "Second line"]}
                ],
                "bookmarks": [
                    {"title": "Chapter 1", "pageNumber": 1, "children": []}
                ],
                "isScannedOnly": false,
                "totalPages": 1
            }"""
            temp.writeText(valid)
            val contract = WorkerResponseValidator.parseOutlineFile(temp)
            assertEquals(1, contract.sections.size)
            assertEquals("Intro", contract.sections[0].title)
            assertEquals(1, contract.bookmarks.size)

            // Trailing garbage
            temp.writeText(valid + " extra_garbage")
            assertRejected { WorkerResponseValidator.parseOutlineFile(temp) }

            // Deeply nested bookmarks exceeding quota
            val deep = StringBuilder("{\"title\":\"root\",\"pageNumber\":1,\"children\":[")
            repeat(10) { deep.append("{\"title\":\"child\",\"pageNumber\":1,\"children\":[") }
            repeat(10) { deep.append("]}") }
            deep.append("]}")
            val invalidDeepOutline = "{\"sections\":[],\"bookmarks\":[$deep],\"isScannedOnly\":false,\"totalPages\":1}"
            temp.writeText(invalidDeepOutline)
            assertRejected { WorkerResponseValidator.parseOutlineFile(temp) }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun csvTableEnforcesBounds() {
        // Valid CSV
        val valid = "Header1,Header2,Header3\nVal1,Val2,Val3\n"
        assertEquals(valid, WorkerResponseValidator.validateCsvTable(valid))

        // Exceeds column limit (128)
        val wideRow = (0..130).joinToString(",") { "col$it" }
        assertRejected { WorkerResponseValidator.validateCsvTable(wideRow) }

        // Exceeds cell character limit (1024)
        val longCell = "a".repeat(1025)
        assertRejected { WorkerResponseValidator.validateCsvTable("H1,H2\nval,$longCell\n") }

        // Exceeds row limit (5000)
        val sb = StringBuilder("H1,H2\n")
        repeat(5005) { sb.append("a,b\n") }
        assertRejected { WorkerResponseValidator.validateCsvTable(sb.toString()) }
    }
}
