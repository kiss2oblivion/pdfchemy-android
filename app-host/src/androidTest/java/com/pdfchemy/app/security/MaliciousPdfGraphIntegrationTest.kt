package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/** Serialized indirect-object graphs exercise the actual isolated parser and exported PDF. */
@RunWith(AndroidJUnit4::class)
class MaliciousPdfGraphIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun graph(depth: Int, cycle: Boolean, directDepth: Int = 0): ByteArray {
        val lastAction = 4 + depth
        val metadata = lastAction + 2
        var nested = "<< /End true >>"
        repeat(directDepth) { nested = "<< /Child $nested >>" }
        val direct = if (directDepth > 0) " /Deep $nested" else ""
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R /OpenAction 5 0 R /Metadata $metadata 0 R$direct >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources <<>> /Contents 4 0 R >>",
            "<< /Length 3 >>\nstream\nq Q\nendstream"
        )
        repeat(depth) { i ->
            val number = 5 + i
            objects += if (number == lastAction) {
                "<< /S /JavaScript /JS (GRAPH_SCRIPT_SENTINEL) ${if (cycle) "/Next 5 0 R" else ""} >>"
            } else "<< /Next ${number + 1} 0 R >>"
        }
        objects += "<< /Title (GRAPH_METADATA_SENTINEL) /Author (private fixture) >>"
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><private>GRAPH_METADATA_SENTINEL</private></x:xmpmeta>"
        objects += "<< /Type /Metadata /Subtype /XML /Length ${xmp.length} >>\nstream\n$xmp\nendstream"
        val bytes = ByteArrayOutputStream()
        fun emit(s: String) { bytes.write(s.toByteArray(Charsets.US_ASCII)) }
        emit("%PDF-1.7\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { i, value -> offsets += bytes.size(); emit("${i + 1} 0 obj\n$value\nendobj\n") }
        val xref = bytes.size()
        emit("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { emit(String.format(Locale.US, "%010d 00000 n \n", it)) }
        emit("trailer\n<< /Size ${offsets.size} /Root 1 0 R /Info ${lastAction + 1} 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return bytes.toByteArray()
    }

    @Test fun recursiveActionIsRemovedAndExportedPdfReopensWithoutMetadata() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File.createTempFile("recursive_graph_", ".pdf", context.cacheDir)
        val output = File.createTempFile("recursive_clean_", ".pdf", context.cacheDir)
        val original = graph(1, cycle = true)
        source.writeBytes(original)
        try {
            val before = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(source))
            assertFalse(before.parseFailed); assertFalse(before.isClean); assertTrue(before.jsCount > 0)
            val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(output))
            assertTrue(result.isSuccess); assertTrue(result.metadataRemoved); assertTrue(result.jsRemoved > 0)
            val after = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(output))
            assertTrue(after.isClean); assertFalse(after.parseFailed); assertEquals(0, after.jsCount)
            PDDocument.load(output).use { pdf ->
                assertEquals(1, pdf.numberOfPages)
                assertEquals(300f, pdf.getPage(0).mediaBox.width, 0f)
                assertEquals(200f, pdf.getPage(0).mediaBox.height, 0f)
                assertNull(pdf.documentInformation.title)
                assertNull(pdf.documentCatalog.metadata)
            }
            assertArrayEquals("Sanitization must preserve the original", original, source.readBytes())
        } finally { source.delete(); output.delete() }
    }

    @Test fun excessiveDirectDepthFailsClosedAndPreservesBothDocuments() = runBlocking {
        val source = File.createTempFile("deep_graph_", ".pdf", context.cacheDir)
        val output = File.createTempFile("deep_old_target_", ".pdf", context.cacheDir)
        val healthy = File.createTempFile("deep_recovery_", ".pdf", context.cacheDir)
        val original = graph(1, cycle = false, directDepth = SecurityLimits.MAX_GRAPH_DEPTH + 8)
        val oldTarget = "existing destination must survive".toByteArray()
        source.writeBytes(original); output.writeBytes(oldTarget)
        try {
            val audit = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(source))
            assertFalse(audit.isClean); assertTrue(audit.parseFailed)
            val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(output))
            assertFalse(result.isSuccess)
            assertArrayEquals(original, source.readBytes()); assertArrayEquals(oldTarget, output.readBytes())
            InstrumentationRegistry.getInstrumentation().context.assets.open("vanguard-benign/01-metadata-xmp.pdf")
                .use { input -> healthy.outputStream().use(input::copyTo) }
            assertTrue("The next valid document must remain usable",
                PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(healthy)).isClean)
        } finally { source.delete(); output.delete(); healthy.delete() }
    }

    @Test fun deepIndirectActionsCannotEscapeAuditOrSanitization() = runBlocking {
        val source = File.createTempFile("indirect_graph_", ".pdf", context.cacheDir)
        val output = File.createTempFile("indirect_old_", ".pdf", context.cacheDir)
        val original = graph(SecurityLimits.MAX_GRAPH_DEPTH + 8, cycle = false)
        val oldTarget = "previous destination".toByteArray()
        source.writeBytes(original); output.writeBytes(oldTarget)
        try {
            val audit = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(source))
            assertFalse("Indirect references must never hide active content", audit.isClean)
            assertTrue(audit.parseFailed || audit.jsCount > 0)
            val result = PdfSanitizerEngine.sanitizeDocument(context, Uri.fromFile(source), Uri.fromFile(output))
            if (result.isSuccess) {
                assertTrue(result.jsRemoved > 0)
                val after = PdfSanitizerEngine.auditDocumentThreats(context, Uri.fromFile(output))
                assertTrue(after.isClean); assertFalse(after.parseFailed)
            } else {
                assertArrayEquals("A rejected graph must preserve the destination", oldTarget, output.readBytes())
            }
            assertArrayEquals(original, source.readBytes())
        } finally { source.delete(); output.delete() }
    }
}
