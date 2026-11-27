package com.pdfchemy.app.logic

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfCompareLazyPreviewTest {
    @Test fun previewUsesLeasedSnapshotsAndSessionCloseReleasesOwnership() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val staged = assets.open("vanguard-benign/01-metadata-xmp.pdf").use {
            DocumentStager.stageStreamWithLease(context, it)
        }
        try {
            val uri = staged.document.uri
            val originalLeases = DocumentStager.activeLeases(uri)
            val summary = PdfDiffEngine.compareDocuments(context, uri, uri)
            try {
                assertEquals(summary.pageDiffs.size, summary.identicalPages)
                assertEquals(0, summary.modifiedPages)
                assertEquals(originalLeases + 2, DocumentStager.activeLeases(uri))
                val preview = summary.renderPreview(context, 0)
                try {
                    assertTrue(preview.width > 0 && preview.height > 0)
                } finally { preview.recycle() }
                assertEquals(originalLeases + 2, DocumentStager.activeLeases(uri))
            } finally { summary.close() }
            assertEquals(originalLeases, DocumentStager.activeLeases(uri))
            summary.close() // idempotent
            assertEquals(originalLeases, DocumentStager.activeLeases(uri))
            try {
                summary.renderPreview(context, 0).recycle()
                fail("Closed session must not render another preview")
            } catch (_: IllegalStateException) { /* expected */ }
        } finally { staged.discard() }
    }
}
