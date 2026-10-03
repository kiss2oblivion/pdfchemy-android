package com.pdfchemy.app.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.WorkerResponseValidator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class HostResponseContractSecurityTest {

    private fun assertSecurityViolation(block: () -> Unit) {
        try {
            block()
            fail("Expected security rejection but succeeded")
        } catch (_: SecurityException) {
            // Expected fail-closed response
        } catch (_: IllegalArgumentException) {
            // Expected validation failure
        }
    }

    @Test
    fun typedContractsReturnedForHostEngines() {
        val metaJson = """{
            "title": "Doc Title", "author": "Author Name", "subject": "Quarterly", "keywords": "audit",
            "creator": "Host", "producer": "PDFchemy", "creationDate": "2026-10-01",
            "modificationDate": "2026-10-02", "pageCount": 10, "hasXmpMetadata": true, "isEncrypted": false
        }"""
        val metaContract = WorkerResponseValidator.validate("METADATA_READ", metaJson)
        assertTrue(metaContract is MetadataReadContract)
        assertEquals("Doc Title", (metaContract as MetadataReadContract).title)
        assertEquals(10, metaContract.pageCount)

        val pageCountJson = """{"pageCount":42}"""
        val pageContract = WorkerResponseValidator.validate("GET_PAGE_COUNT", pageCountJson)
        assertTrue(pageContract is PageCountContract)
        assertEquals(42, (pageContract as PageCountContract).pageCount)

        val compressJson = """{"size":500,"imagesProcessed":4,"hasSignatures":false}"""
        val compressContract = WorkerResponseValidator.validate("COMPRESS", compressJson)
        assertTrue(compressContract is CompressContract)
        assertEquals(500L, (compressContract as CompressContract).size)
        assertEquals(4, compressContract.imagesProcessed)

        val deskewJson = """{"success":true,"straightenedCount":3}"""
        val deskewContract = WorkerResponseValidator.validate("DESKEW", deskewJson)
        assertTrue(deskewContract is DeskewContract)
        assertEquals(3, (deskewContract as DeskewContract).straightenedCount)
        assertTrue(deskewContract.success)

        val officeJson = """{"success":true,"pageCount":5,"outputSizeBytes":2048,"itemsExtracted":10}"""
        val officeContract = WorkerResponseValidator.validate("OFFICE_WORD", officeJson)
        assertTrue(officeContract is OfficeExportContract)
        assertEquals(5, (officeContract as OfficeExportContract).pageCount)
        assertEquals(2048L, officeContract.outputSizeBytes)

        val cleanJson = """{"isSuccess":true,"threatsRemoved":2,"jsRemoved":1,"actionsRemoved":1,"metadataRemoved":true,"attachmentsRemoved":0}"""
        val cleanContract = WorkerResponseValidator.validate("SANITIZE_CLEAN", cleanJson)
        assertTrue(cleanContract is SanitizerCleanContract)
        assertEquals(2, (cleanContract as SanitizerCleanContract).threatsRemoved)
        assertTrue(cleanContract.metadataRemoved)

        val repairJson = """{"success":true,"recoveredPages":12}"""
        val repairContract = WorkerResponseValidator.validate("REPAIR_APPLY", repairJson)
        assertTrue(repairContract is RepairApplyContract)
        assertTrue((repairContract as RepairApplyContract).success)
        assertEquals(12, repairContract.recoveredPages)
    }

    @Test
    fun unknownOperationFailsClosedImmediately() {
        assertSecurityViolation {
            WorkerResponseValidator.validate("ARBITRARY_WORKER_OP", "{\"success\":true}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("INJECT_COMMAND", "{}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("", "{}")
        }
    }

    @Test
    fun trailingNonWhitespaceRejected() {
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\":5} ; DROP TABLE users;")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\":5} trailing")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\":5} {\"pageCount\":6}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\":5},")
        }
    }

    @Test
    fun excessiveNestingRejected() {
        val nested = "[[[[[[[[[\"overflow\"]]]]]]]]]"
        assertSecurityViolation {
            WorkerResponseValidator.validate("TEXT_PAGES", nested)
        }
    }

    @Test
    fun hostileWorkerErrorsFailClosed() {
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"error\":\"Access Denied\"}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"pageCount\":5,\"error\":\"partial failure\"}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"success\":false}")
        }
        assertSecurityViolation {
            WorkerResponseValidator.validate("GET_PAGE_COUNT", "{\"isSuccess\":false}")
        }
    }

    @Test
    fun oversizedPayloadThrows() {
        val hugePayload = "{\"data\":\"" + "A".repeat(1024 * 1024 + 50) + "\"}"
        assertSecurityViolation {
            WorkerResponseValidator.validate("READ_METADATA", hugePayload)
        }
    }

    @Test
    fun boundedOutlineReaderEnforcesQuotas() {
        val tempFile = File.createTempFile("host_outline_test", ".json")
        try {
            // Write overly deeply nested outline
            tempFile.writeText("[[[[[[[[{\"title\":\"Too Deep\"}]]]]]]]]")
            assertSecurityViolation {
                WorkerResponseValidator.parseOutlineFile(tempFile)
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun boundedCsvTableEnforcesQuotas() {
        val tempFile = File.createTempFile("host_csv_test", ".csv")
        try {
            // Write CSV with more than 128 columns
            val wideRow = (1..150).joinToString(",") { "col$it" }
            tempFile.writeText("$wideRow\n")
            assertSecurityViolation {
                WorkerResponseValidator.parseCsvTableFile(tempFile)
            }
        } finally {
            tempFile.delete()
        }
    }
}
