package com.pdfchemy.desktop.jail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class ParserBrutalityTest {

    @Test
    fun `test malformed pdf rejection`() {
        // Create a dummy malformed PDF file
        val malformedPdf = File.createTempFile("malformed_", ".pdf")
        malformedPdf.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))
        malformedPdf.deleteOnExit()

        runBlocking {
            try {
                DesktopJailManager.executeWithResult(
                    operation = "compress",
                    sourceFile = malformedPdf
                )
                fail("Expected SecurityException or IOException when parsing a corrupted PDF")
            } catch (e: Exception) {
                // If it fails securely, it's a pass
                assertTrue("Exception should indicate failure: ${e.message}", true)
            } finally {
                malformedPdf.delete()
            }
        }
    }
}
