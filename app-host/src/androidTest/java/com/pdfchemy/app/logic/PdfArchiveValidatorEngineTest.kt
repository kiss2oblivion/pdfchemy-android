package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfArchiveValidatorEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_archive.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testInspectPdfACompliance() = runBlocking {
        val result = PdfArchiveValidatorEngine.inspectPdfACompliance(context, samplePdfUri)
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertTrue(report.checks.isNotEmpty())
        assertTrue(report.complianceScore in 0..100)
        assertFalse(report.isCompliant)
        assertEquals("NOT_CERTIFIED", report.certificationStatus)
        assertEquals("PRELIMINARY_PDF_A_CHECKS", report.assessmentScope)
    }

    @Test
    fun spoofedXmpWithMissingIccIsNeverCertified() = runBlocking {
        val fixture = File(context.cacheDir, "archive_spoof_${System.nanoTime()}.pdf")
        try {
            PDDocument.load(samplePdfFile).use { doc ->
                val metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(doc)
                metadata.importXMPMetadata("<not-a-valid-xmp-packet pdfaid:part=\"1\"/>".toByteArray())
                doc.documentCatalog.metadata = metadata
                doc.save(fixture)
            }
            val report = PdfArchiveValidatorEngine.inspectPdfACompliance(context, Uri.fromFile(fixture)).getOrThrow()
            println("CORE02_SPOOF compliant=${report.isCompliant} version=${report.pdfaVersionDetected} score=${report.complianceScore}")
            assertFalse("A metadata marker and a short checklist cannot certify PDF/A", report.isCompliant)
            assertEquals("Metadata declarations must not become a verified PDF/A classification",
                "Unknown (not certified)", report.pdfaVersionDetected)
        } finally { fixture.delete() }
    }

    @Test
    fun unsupportedConversionPreservesOldDestination() = runBlocking {
        val target = File(context.cacheDir, "archive_existing_${System.nanoTime()}.pdf")
        val originalSource = samplePdfFile.readBytes()
        val originalTarget = "existing private destination".toByteArray()
        target.writeBytes(originalTarget)
        try {
            val outcome = runCatching { PdfArchiveValidatorEngine.convertToPdfA(context, samplePdfUri, Uri.fromFile(target)) }
            println("CORE02_CONVERT result=$outcome targetChanged=${!originalTarget.contentEquals(target.readBytes())}")
            assertTrue("Conversion must explicitly reject unsupported generation: $outcome", outcome.exceptionOrNull() is UnsupportedOperationException)
            assertArrayEquals(originalTarget, target.readBytes())
            assertArrayEquals(originalSource, samplePdfFile.readBytes())
        } finally { target.delete() }
    }

    @Test fun missingIccProfileIsPreliminaryOnly() = runBlocking {
        val report = PdfArchiveValidatorEngine.inspectPdfACompliance(context, samplePdfUri).getOrThrow()
        assertFalse(report.checks.single { it.rule == "Output profile stream" }.isPassed)
        assertEquals("NOT_CERTIFIED", report.certificationStatus)
    }

    @Test fun nonembeddedFontDoesNotBecomeArchivalReady() = runBlocking {
        val fixture = File(context.cacheDir, "archive_font_${System.nanoTime()}.pdf")
        try {
            PDDocument.load(samplePdfFile).use { doc ->
                doc.getPage(0).resources = com.tom_roush.pdfbox.pdmodel.PDResources().apply {
                    put(com.tom_roush.pdfbox.cos.COSName.getPDFName("F1"), com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA)
                }
                doc.save(fixture)
            }
            val report = PdfArchiveValidatorEngine.inspectPdfACompliance(context, Uri.fromFile(fixture)).getOrThrow()
            assertFalse(report.checks.single { it.rule == "Referenced page-font embedding" }.isPassed)
            assertEquals("NOT_CERTIFIED", report.certificationStatus)
        } finally { fixture.delete() }
    }

    @Test fun forbiddenActionIsReportedWithoutCertification() = runBlocking {
        val fixture = File(context.cacheDir, "archive_action_${System.nanoTime()}.pdf")
        try {
            PDDocument.load(samplePdfFile).use { doc ->
                doc.documentCatalog.openAction = com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert('fixture')")
                doc.save(fixture)
            }
            val report = PdfArchiveValidatorEngine.inspectPdfACompliance(context, Uri.fromFile(fixture)).getOrThrow()
            assertFalse(report.checks.single { it.rule == "Catalog action entries" }.isPassed)
            assertEquals("NOT_CERTIFIED", report.certificationStatus)
        } finally { fixture.delete() }
    }

    @Test fun invalidStructureCannotProduceAReport() = runBlocking {
        val fixture = File(context.cacheDir, "archive_invalid_${System.nanoTime()}.pdf")
        try {
            fixture.writeText("%PDF-1.4\nnot a document")
            assertTrue(PdfArchiveValidatorEngine.inspectPdfACompliance(context, Uri.fromFile(fixture)).isFailure)
            PDDocument().use { it.save(fixture) }
            assertTrue(PdfArchiveValidatorEngine.inspectPdfACompliance(context, Uri.fromFile(fixture)).isFailure)
        } finally { fixture.delete() }
    }

    @Test fun actualIsolatedConversionCannotOpenProvider() = runBlocking {
        val id = "archive_${System.nanoTime()}"
        val target = Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id")
        context.contentResolver.call(target, "configure", id, Bundle())
        val result = runCatching { PdfGateway.executeEngine(context, "ARCHIVE_CONVERT", samplePdfUri, target, "{}") }
        assertTrue("Expected explicit worker rejection: $result", result.exceptionOrNull()?.message.orEmpty().contains("not supported"))
        val state = requireNotNull(context.contentResolver.call(target, "state", id, null))
        assertEquals(0, state.getInt("opens"))
        assertEquals("existing destination", String(state.getByteArray("bytes")!!))
    }

    @Test fun unsupportedConversionDoesNotCreateOutput() = runBlocking {
        val target = File(context.cacheDir, "archive_absent_${System.nanoTime()}.pdf")
        val result = runCatching { PdfArchiveValidatorEngine.convertToPdfA(context, samplePdfUri, Uri.fromFile(target)) }
        assertTrue(result.exceptionOrNull() is UnsupportedOperationException)
        assertFalse(target.exists())
    }

    @Test fun cancelledConversionPreservesExistingTarget() = runBlocking {
        val target = File(context.cacheDir, "archive_cancel_${System.nanoTime()}.pdf").apply { writeText("original") }
        try {
            var observed = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                try { PdfArchiveValidatorEngine.convertToPdfA(context, samplePdfUri, Uri.fromFile(target)); fail("Cancellation swallowed") }
                catch (_: CancellationException) { observed = true }
            }
            job.join()
            assertTrue(observed)
            assertEquals("original", target.readText())
        } finally { target.delete() }
    }
}
