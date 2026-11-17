package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class FontExportDeviceTest {
    @Test(timeout = 60000)
    fun embeddedFontExportIsAdmittedAndDeduplicatesSharedPrograms() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val fontFile = listOf("Roboto-Regular.ttf", "RobotoStatic-Regular.ttf", "DroidSansMono.ttf")
            .map { File("/system/fonts", it) }
            .firstOrNull { it.isFile && it.length() in 1..4_000_000L }
            ?: error("No supported system font fixture")
        val expected = fontFile.readBytes()
        val source = File.createTempFile("embedded-font-", ".pdf", context.cacheDir)
        val output = File.createTempFile("exported-font-", ".zip", context.cacheDir)
        try {
            PDDocument().use { document ->
                val font = fontFile.inputStream().use { PDType0Font.load(document, it, false) }
                repeat(2) {
                    val page = PDPage()
                    document.addPage(page)
                    PDPageContentStream(document, page).use { content ->
                        content.beginText()
                        content.setFont(font, 18f)
                        content.newLineAtOffset(50f, 700f)
                        content.showText("Font fixture")
                        content.endText()
                    }
                }
                document.save(source)
            }
            assertTrue(
                PdfFontInspectorEngine.extractEmbeddedFonts(
                    context,
                    Uri.fromFile(source),
                    Uri.fromFile(output)
                ).getOrThrow()
            )
            ZipInputStream(output.inputStream()).use { archive ->
                val entry = requireNotNull(archive.nextEntry)
                assertTrue(entry.name.matches(Regex("[A-Za-z0-9_.-]+[.]ttf")))
                assertArrayEquals(expected, archive.readBytes())
                archive.closeEntry()
                assertNull(archive.nextEntry)
            }
        } finally {
            source.delete()
            output.delete()
        }
    }
}
