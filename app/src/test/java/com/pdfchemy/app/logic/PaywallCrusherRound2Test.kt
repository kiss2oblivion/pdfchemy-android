package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaywallCrusherRound2Test {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testPdfTableExtractorEngine() = runBlocking {
        val testFile = File(context.cacheDir, "table_test.pdf")
        val doc = PDDocument()
        val page = PDPage()
        doc.addPage(page)

        PDPageContentStream(doc, page).use { cs ->
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
            cs.newLineAtOffset(50f, 700f)
            cs.showText("Product")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Qty")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("Total")
            cs.endText()

            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA, 12f)
            cs.newLineAtOffset(50f, 680f)
            cs.showText("Enterprise License")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("5")
            cs.newLineAtOffset(150f, 0f)
            cs.showText("$1500")
            cs.endText()
        }
        doc.save(testFile)
        doc.close()

        val csv = PdfTableExtractorEngine.extractTablesToCsv(context, Uri.fromFile(testFile))
        assertTrue("CSV should not be blank", csv.isNotBlank())
        assertTrue("Should contain Product", csv.contains("Product"))
        assertTrue("Should contain Enterprise License", csv.contains("Enterprise License"))
        assertTrue("Should contain 1500", csv.contains("1500"))
    }

    @Test
    fun testPdfDeskewAngleDetection() {
        // Create a synthetic horizontal line bitmap directly setting pixels (Robolectric safe)
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        for (x in 20 until 180) {
            for (y in 95 until 105) {
                bmp.setPixel(x, y, Color.BLACK)
            }
        }

        val angle = PdfDeskewEngine.detectSkewAngle(bmp)
        assertTrue("Skew angle of horizontal line should be close to 0 (was $angle)", Math.abs(angle) < 1.0f)
        bmp.recycle()
    }
}
