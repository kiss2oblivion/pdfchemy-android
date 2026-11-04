package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OcrTextFailureIntegrityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun temporaryNames() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("ocr_") }.map { it.name }.toSet()
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun pdf(blank: Boolean): File {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("ocr-source-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(300f, 200f)); document.addPage(page)
            if (!blank) PDPageContentStream(document, page).use {
                it.beginText(); it.setFont(PDType1Font.HELVETICA, 24f)
                it.newLineAtOffset(20f, 120f); it.showText("PUBLIC OCR CHECK"); it.endText()
            }
            document.save(file)
        }
        return file
    }
    @Test fun malformedPdfReturnsFailureWithoutAnOcrTemporaryFile() = runBlocking {
        val file = File.createTempFile("ocr-malformed-", ".pdf", context.cacheDir)
        file.writeText("%PDF-1.7\nmalformed source preserved\n%%EOF")
        val original = digest(file); val before = temporaryNames()
        try {
            assertTrue(PdfTextExtractor.extractUsingOcr(context, Uri.fromFile(file)).isFailure)
            assertEquals(before, temporaryNames()); assertEquals(original, digest(file))
        } finally { file.delete() }
    }
    @Test fun genuineBlankPageIsEmptySuccessAndLeavesNoTemporaryFile() = runBlocking {
        val file = pdf(blank = true); val original = digest(file); val before = temporaryNames()
        try {
            val result = PdfTextExtractor.extractUsingOcr(context, Uri.fromFile(file))
            assertTrue(result.isSuccess); assertTrue(result.getOrThrow().isBlank())
            assertEquals(before, temporaryNames()); assertEquals(original, digest(file))
        } finally { file.delete() }
    }
    @Test fun actualOcrReturnsRecognizedTextAndPreservesSourceBytes() = runBlocking {
        val file = pdf(blank = false); val original = digest(file); val before = temporaryNames()
        try {
            val result = PdfTextExtractor.extractUsingOcr(context, Uri.fromFile(file))
            assertTrue(result.isSuccess); assertTrue(result.getOrThrow().contains("PUBLIC", ignoreCase = true))
            assertEquals(before, temporaryNames()); assertEquals(original, digest(file))
        } finally { file.delete() }
    }
}
