package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.StandardOutputContract
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FontExportRegistrationTest {
    private val valid = """{"success":true,"extractedCount":1,"fontFiles":["Fixture.ttf"]}"""

    @Test
    fun fontExtractionIsAdmittedWithItsTypedArchiveResult() {
        WorkerResponseValidator.validateOperation("FONT_EXTRACT")
        assertTrue(WorkerResponseValidator.validate("FONT_EXTRACT", valid, 1) is StandardOutputContract)
    }

    @Test
    fun fontMetadataCannotClaimMissingOrUnsafeFiles() {
        for (json in listOf(
            """{"success":true,"extractedCount":2,"fontFiles":["Fixture.ttf"]}""",
            """{"success":true,"extractedCount":1,"fontFiles":["../Fixture.ttf"]}""",
            """{"success":true,"extractedCount":2,"fontFiles":["Fixture.ttf","Fixture.ttf"]}"""
        )) {
            assertTrue(runCatching { WorkerResponseValidator.validate("FONT_EXTRACT", json, 1) }.isFailure)
        }
    }

    @Test
    fun fontExportRequiresExactlyOneOwnedOutput() {
        for (count in listOf(0, 2)) {
            assertTrue(runCatching { WorkerResponseValidator.validate("FONT_EXTRACT", valid, count) }.isFailure)
        }
    }
}
