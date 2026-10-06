package com.pdfchemy.app.jail

import com.pdfchemy.app.jail.engines.ActiveContentScrubber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class BenignPdfCorpusTest(private val fixture: File) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<File>> {
            val directory = File(requireNotNull(System.getProperty("benignPdfCorpus")))
            val files = directory.listFiles { file -> file.extension == "pdf" }!!.sortedBy { it.name }
            require(files.size >= 11) { "Benign compatibility corpus is missing fixtures" }
            return files.map { arrayOf(it) }
        }
    }

    @Test fun benignFileParsesAndHasNoExecutableThreats() {
        PDDocument.load(fixture).use { document ->
            assertFalse(document.isEncrypted)
            assertTrue("Fixture must contain readable pages", document.numberOfPages > 0)
            val audit = ActiveContentScrubber.inspect(document)
            assertEquals(0, audit.javascript)
            assertEquals(0, audit.launches)
            assertEquals(0, audit.actions)
            assertEquals(0, audit.untrustedUris)
            if (fixture.name.startsWith("06-") || fixture.name.startsWith("07-") || fixture.name.startsWith("10-")) {
                assertEquals(1, audit.attachments)
            }
        }
    }
}
