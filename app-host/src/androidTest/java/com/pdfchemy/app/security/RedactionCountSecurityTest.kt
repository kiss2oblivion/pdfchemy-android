package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.RedactionBox
import com.pdfchemy.app.logic.RedactionConfig
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual public host -> isolated worker -> saved output count/publication contract. */
@RunWith(AndroidJUnit4::class)
class RedactionCountSecurityTest {
    private val left = "COUNT_PRIVATE_LEFT_482719"
    private val right = "COUNT_PRIVATE_RIGHT_482719"
    private val sentinel = "EXISTING_DESTINATION_MUST_SURVIVE".toByteArray()

    private fun fixture(): ByteArray {
        val text = "BT /F1 8 Tf 10 100 Td ($left) Tj ET\nBT /F1 8 Tf 160 100 Td ($right) Tj ET\n"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
            "<< /Length ${text.length} >>\nstream\n${text}endstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val output = ByteArrayOutputStream()
        fun emit(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        emit("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> output.size().also { emit("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = output.size()
        emit("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { emit(it.toString().padStart(10, '0') + " 00000 n \n") }
        emit("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    private fun verify(kind: String, boxes: List<RedactionBox>, expectedCount: Int?) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val phase = InstrumentationRegistry.getArguments().getString("redactionCountEvidenceRun", "probe")!!
        require(phase.matches(Regex("[a-zA-Z0-9_-]+")))
        val archive = File(context.getExternalFilesDir(null), "redaction-count/$phase/$kind-${UUID.randomUUID()}").apply { mkdirs() }
        val source = File.createTempFile("redaction_count_source_", ".pdf", context.cacheDir)
        val destination = File.createTempFile("redaction_count_output_", ".pdf", context.cacheDir)
        val input = fixture()
        val receipt = JSONObject().put("kind", kind).put("requested_boxes", boxes.size)
            .put("source_sha256", MessageDigest.getInstance("SHA-256").digest(input).joinToString("") { "%02x".format(it) })
        try {
            source.writeBytes(input)
            File(archive, "input.pdf").writeBytes(input)
            destination.writeBytes(sentinel)
            PDDocument.load(source).use {
                assertEquals(1, it.numberOfPages)
                val text = PDFTextStripper().getText(it)
                assertTrue(text.contains(left) && text.contains(right))
            }
            val result = PdfRedactionEngine.applyRedactions(context, Uri.fromFile(source), Uri.fromFile(destination), boxes,
                RedactionConfig(isBlackout = true, defaultOverlayText = "", forensicSanitize = true))
            receipt.put("success", result.isSuccess).put("returned_count", result.getOrNull() ?: JSONObject.NULL)
                .put("error_class", result.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("destination_unchanged", destination.readBytes().contentEquals(sentinel))
            destination.copyTo(File(archive, "destination.bin"))
            if (result.isSuccess) PDDocument.load(destination).use {
                assertEquals(1, it.numberOfPages)
                val text = PDFTextStripper().getText(it)
                receipt.put("saved_text_contains_left", text.contains(left)).put("saved_text_contains_right", text.contains(right))
                if (boxes.isEmpty()) assertTrue("No selected box must preserve the source text", text.contains(left) && text.contains(right))
                else assertFalse("Selected private text must be removed from saved output", text.contains(left) || text.contains(right))
            }
            if (expectedCount == null) {
                assertTrue("An invalid page must fail the whole export, not silently skip a requested redaction", result.isFailure)
                assertArrayEquals("Failed export must preserve an existing destination", sentinel, destination.readBytes())
            } else {
                assertTrue("Valid export must succeed: ${result.exceptionOrNull()}", result.isSuccess)
                assertEquals("Host must report the actual worker count, including zero", expectedCount, result.getOrThrow())
            }
        } finally {
            File(archive, "receipt.json").writeText(receipt.toString(2))
            source.delete()
            destination.delete()
        }
    }

    @Test fun twoAppliedBoxesReturnTwo() = verify("two", listOf(
        RedactionBox(0, 0f, 0f, 0.5f, 1f), RedactionBox(0, 0.5f, 0f, 0.5f, 1f)), 2)

    @Test fun zeroSelectedBoxesReturnZero() = verify("zero", emptyList(), 0)

    @Test fun invalidPageFailsWithoutPublishingPartialRedaction() = verify("invalid", listOf(
        RedactionBox(0, 0f, 0f, 1f, 1f), RedactionBox(42, 0f, 0f, 1f, 1f)), null)
}
