package com.pdfchemy.app.security

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.DocumentActions
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Vulnerability-confirming native component probe; no Reader UI/recipient automation. */
@RunWith(AndroidJUnit4::class)
class FreshReaderShareProbeTest {
    @Test fun readerSnapshotFailsConfiguredRootWhileExportControlWorks_probe() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(app)
        val bytes = PDDocument().use { doc -> doc.addPage(PDPage()); ByteArrayOutputStream().also(doc::save).toByteArray() }
        val leased = bytes.inputStream().use { DocumentStager.stageStreamWithLease(app, it, "pdf") }
        val control = File.createTempFile("audit_share_", ".pdf", File(app.cacheDir, "exports").apply { mkdirs() })
        try {
            control.writeBytes(bytes)
            val accepted = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", control)
            assertEquals("content", accepted.scheme)
            assertArrayEquals(bytes, app.contentResolver.openInputStream(accepted)!!.use { it.readBytes() })
            val error = runCatching { DocumentActions.share(app, leased.document.uri) }.exceptionOrNull()
            assertTrue("Sharing must fail specifically at staging root resolution: $error", error is IllegalArgumentException)
            assertTrue(error!!.message.orEmpty().contains("configured root", ignoreCase = true))
        } finally { control.delete(); leased.close(); DocumentStager.release(leased.document) }
    }
}
