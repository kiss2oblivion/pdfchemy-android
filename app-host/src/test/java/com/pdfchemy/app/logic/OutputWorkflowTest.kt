package com.pdfchemy.app.logic

import android.content.Context
import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutputWorkflowTest {
    private fun context(): Context {
        val application = ApplicationProvider.getApplicationContext<Context>()
        application.getSharedPreferences("pdfchemy_history", 0).edit().clear().commit()
        application.getSharedPreferences("shrinkpdf_settings", 0).edit().clear().putBoolean("history_enabled", true).commit()
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.getSharedPreferences(any(), any()) } answers { application.getSharedPreferences(firstArg(), secondArg()) }
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            MatrixCursor(arrayOf("_display_name", "_size")).apply { addRow(arrayOf(if (uri.lastPathSegment == "csv") "table.csv" else "My taxes 2026.pdf", 100)) }
        }
        every { resolver.getType(any()) } answers { if (firstArg<Uri>().lastPathSegment == "csv") "text/csv" else "application/pdf" }
        return context
    }

    @Test fun providerDisplayNameWinsOverOpaqueUriId() {
        assertEquals("My taxes 2026_compressed.pdf", FileUtil.generateSuggestedName(context(), Uri.parse("content://files/392"), "compressed"))
    }
    @Test fun collisionPolicyKeepsBothFilesAndPreservesExtension() {
        assertEquals("Report (3).pdf", OutputPolicy.uniqueName("Report.pdf", listOf("report.pdf", "Report (2).pdf")))
        assertEquals("a_b.pdf", OutputPolicy.safeName("folder/a:b.pdf"))
    }
    @Test fun historyUsesActualIdentityAndRemovesOnlyRequestedEntry() {
        val repo = HistoryRepository(context())
        val pdf = Uri.parse("content://files/pdf"); val csv = Uri.parse("content://files/csv")
        repo.addHistoryItem(pdf, "Generic result title", "Compress")
        repo.addHistoryItem(csv, "Generic result title", "Extract")
        val items = repo.getHistory()
        assertEquals("My taxes 2026.pdf", items.single { it.uriString == pdf.toString() }.name)
        assertEquals("application/pdf", items.single { it.uriString == pdf.toString() }.mimeType)
        assertEquals("text/csv", items.single { it.uriString == csv.toString() }.mimeType)
        repo.remove(pdf.toString())
        assertEquals(listOf(csv.toString()), repo.getHistory().map { it.uriString })
    }
    @Test fun disablingHistoryClearsAllSavedEntries() {
        val context = context(); val repo = HistoryRepository(context)
        repo.addHistoryItem(Uri.parse("content://files/pdf"), "title", "action")
        context.getSharedPreferences("shrinkpdf_settings", 0).edit().putBoolean("history_enabled", false).commit()
        assertTrue(repo.getHistory().isEmpty())
        assertEquals("[]", context.getSharedPreferences("pdfchemy_history", 0).getString("recent_files", "[]"))
    }
}
