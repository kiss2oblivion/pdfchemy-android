package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Security regression test for SC-12: Reader sharing capability and lifecycle isolation.
 * Verifies that staged reader snapshots resolve safely through FileProvider without root resolution errors,
 * recipient reads preserve exact bytes, and recipient reads succeed even after Reader navigation/lease release.
 */
@RunWith(AndroidJUnit4::class)
class ReaderShareSecurityTest {

    private val application: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(application)
        DocumentStager.releaseAll()
    }

    @After
    fun tearDown() {
        DocumentStager.releaseAll()
        File(application.cacheDir, "exports").listFiles()?.forEach {
            if (it.name.startsWith("shared_")) it.delete()
        }
    }

    private fun samplePdf(marker: String = "sc12_native_test"): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        val base = out.toByteArray()
        // Inject unique marker in bytes
        return (base.toString(Charsets.ISO_8859_1) + "\n%$marker\n").toByteArray(Charsets.ISO_8859_1)
    }

    @Test
    fun stagedReaderSnapshotSharesThroughFileProvider_andPreservesBytesAfterNavigation() {
        val originalBytes = samplePdf("sc12_isolated_share")
        val leased = originalBytes.inputStream().use {
            DocumentStager.stageStreamWithLease(application, it, "pdf")
        }

        try {
            val stagedUri = leased.document.uri
            assertEquals("file", stagedUri.scheme)
            assertTrue("Staged path must reside in internal staged directory", stagedUri.path!!.contains("staged"))

            // 1. Resolve shareable URI - must succeed and produce FileProvider content URI
            val shareUri = DocumentActions.getShareableContentUri(application, stagedUri, "AnnualReport.pdf")
            assertEquals("content", shareUri.scheme)
            assertEquals("${application.packageName}.fileprovider", shareUri.authority)

            // 2. Verify recipient reads exact bytes before navigation
            val initialBytes = application.contentResolver.openInputStream(shareUri)!!.use { it.readBytes() }
            assertArrayEquals("Recipient must read exact bytes", originalBytes, initialBytes)

            // 3. Simulate Reader navigation / exit: release lease and stager registration
            leased.lease.close()
            DocumentStager.release(leased.document)
            assertEquals("Leases must be zero after Reader exit", 0, DocumentStager.activeLeases(leased.document))

            // 4. Verify recipient reads exact bytes AFTER Reader exit / lease release
            val afterNavBytes = application.contentResolver.openInputStream(shareUri)!!.use { it.readBytes() }
            assertArrayEquals("Recipient must still read exact bytes after Reader lease release", originalBytes, afterNavBytes)

            // 5. Verify share action executes without throwing root resolution error
            DocumentActions.share(application, stagedUri, "AnnualReport.pdf")
        } finally {
            leased.lease.close()
            DocumentStager.release(leased.document)
        }
    }

    @Test
    fun exportsFileReusesDirectFileProviderUriWithoutRedundantCopy() {
        val exportsDir = File(application.cacheDir, "exports").apply { mkdirs() }
        val exportFile = File(exportsDir, "already_exported.pdf").apply {
            writeBytes(samplePdf("direct_export"))
        }
        val fileUri = Uri.fromFile(exportFile)

        val shareUri = DocumentActions.getShareableContentUri(application, fileUri, "Exported.pdf")
        assertEquals("content", shareUri.scheme)
        assertEquals("${application.packageName}.fileprovider", shareUri.authority)

        val readBytes = application.contentResolver.openInputStream(shareUri)!!.use { it.readBytes() }
        assertArrayEquals(samplePdf("direct_export"), readBytes)
    }

    @Test
    fun contentUriPassesThroughUnchanged() {
        val contentUri = Uri.parse("content://com.external.provider/document/456")
        val result = DocumentActions.getShareableContentUri(application, contentUri)
        assertEquals(contentUri, result)
    }
}
