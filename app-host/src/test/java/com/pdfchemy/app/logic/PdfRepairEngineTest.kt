package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfRepairEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testDiagnoseAndRepairPdf() = runBlocking {
        // Create valid PDF and intentionally truncate EOF to simulate corruption
        val validFile = File(context.cacheDir, "sample_valid.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.save(validFile)
        doc.close()

        val validBytes = validFile.readBytes()
        // Truncate the last 20 bytes (strips %%EOF)
        val corruptedFile = File(context.cacheDir, "corrupted_test.pdf")
        val truncatedBytes = validBytes.copyOfRange(0, validBytes.size - 20)
        FileOutputStream(corruptedFile).use { it.write(truncatedBytes) }

        val corruptedUri = Uri.fromFile(corruptedFile)

        // Diagnose
        val diagResult = PdfRepairEngine.diagnosePdf(context, corruptedUri)
        assertTrue(diagResult.isSuccess)
        val diag = diagResult.getOrThrow()
        assertFalse("Truncated PDF should be detected as missing EOF", diag.hasValidEof)

        // Repair
        val destFile = File(context.cacheDir, "repaired_test_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val repairResult = PdfRepairEngine.repairPdf(context, corruptedUri, destUri)
        assertTrue(repairResult.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        // Verify repaired file loads cleanly
        val repairedDoc = PDDocument.load(destFile)
        assertTrue(repairedDoc.numberOfPages >= 1)
        repairedDoc.close()
    }
}
