package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.pdfchemy.app.logic.VanguardThreatResult
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.json.JSONArray
import java.io.File

@RunWith(Parameterized::class)
class BenignPdfCompatibilityTest(private val filename: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<String>> {
            val files = InstrumentationRegistry.getInstrumentation().context.assets
                .list("vanguard-benign")!!.filter { it.endsWith(".pdf") }.sorted()
            require(files.size >= 14) { "Benign compatibility corpus is incomplete" }
            return files.map { arrayOf(it) }
        }
    }

    @Test(timeout = 120_000) fun auditsCleanPassesVanguardAndOpensInIsolatedRenderer() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("benign_corpus_", ".pdf", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("vanguard-benign/$filename").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            val uri = Uri.fromFile(source)
            val audit = PdfSanitizerEngine.auditDocumentThreats(context, uri)
            assertFalse("$filename parse failed", audit.parseFailed)
            assertFalse(audit.isEncrypted)
            assertEquals(0, audit.threatsFound)
            assertEquals(0, audit.jsCount)
            assertEquals(0, audit.launchActionsCount)
            assertEquals(0, audit.otherActionsCount)
            assertEquals(0, audit.untrustedUriCount)
            val manifest = InstrumentationRegistry.getInstrumentation().context.assets
                .open("vanguard-benign/manifest.json").bufferedReader().use { JSONArray(it.readText()) }
            val expected = (0 until manifest.length()).map { manifest.getJSONObject(it) }
                .single { it.getString("file") == filename }
            if (!expected.isNull("attachments")) {
                assertEquals("$filename logical attachments", expected.getInt("attachments"), audit.attachmentCount)
            }
            if (!expected.isNull("uris")) {
                assertEquals("$filename ordinary URI actions", expected.getInt("uris"), audit.uriCount)
            }
            assertTrue(audit.isClean)
            assertSame(VanguardThreatResult.Clean, PdfSanitizerEngine.checkVanguardThreat(context, uri))
            val staged = DocumentStager.stageDocumentCancellable(context, uri)
            try {
                val pages = context.contentResolver.openFileDescriptor(staged.uri, "r")!!.use {
                    NativeRendererCoordinator.getPageCount(context, it)
                }
                assertTrue("$filename did not open", pages != null && pages > 0)
                for (index in setOf(0, pages!! - 1)) {
                    val bitmap = NativeRendererCoordinator.renderUriToBitmap(context, staged.uri, index, 320)
                    assertNotNull("$filename page $index did not render", bitmap)
                    try { assertTrue(bitmap!!.width > 0 && bitmap.height > 0) }
                    finally { bitmap?.recycle() }
                }
            } finally { DocumentStager.release(staged) }
        } finally { source.delete() }
    }
}
