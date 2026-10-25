package com.pdfchemy.app.jail.engines

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PdfLinearizeValidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun isLinearizedFileDetectsAbsenceAndPresenceOfLinearizationDictionary() {
        val nonLinearFile = tempFolder.newFile("non_linear.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(nonLinearFile)
        }
        assertFalse("Standard saved document must not be classified as linearized",
            PdfLinearizeEngineWorker.isLinearizedFile(nonLinearFile))

        val linearHeaderFile = tempFolder.newFile("linear_synthetic.pdf")
        linearHeaderFile.writeBytes("%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Linearized 1.0 /L 12345 /H [ 678 123 ] /O 11 /E 12345 /N 1 /T 12345 >>\nendobj\n".toByteArray(Charsets.US_ASCII))
        assertTrue("Synthetic linearized header must be detected",
            PdfLinearizeEngineWorker.isLinearizedFile(linearHeaderFile))
    }

    @Test
    fun unlinearizedOutputRejectionEnforcesLinearizationIntegrity() {
        val tempFile = tempFolder.newFile("saved_output.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.save(tempFile)
        }
        // Verify that standard PDFBox output fails the linearization check
        val isLinear = PdfLinearizeEngineWorker.isLinearizedFile(tempFile)
        assertFalse("PDFBox standard save cannot produce linearized PDFs", isLinear)
    }
}
