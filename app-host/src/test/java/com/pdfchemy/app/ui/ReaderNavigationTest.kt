package com.pdfchemy.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfchemy.app.Screen
import com.pdfchemy.app.logic.OfficeFormat
import com.pdfchemy.app.logic.ReaderNavigationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReaderNavigationTest {

    @Test
    fun resolveDocumentEntryScreen_routesSupportedPdfsDirectlyToPdfReader() {
        val pdfUri = Uri.parse("content://com.android.providers.downloads/document/42")
        val stagedUri = Uri.parse("file:///data/user/0/com.pdfchemy.app/cache/staged/snapshot_101.pdf")

        assertEquals(
            Screen.PdfReader(initialPdfUri = pdfUri),
            ReaderNavigationCoordinator.resolveDocumentEntryScreen(pdfUri, "Contract_2026.PDF")
        )
        assertEquals(
            Screen.PdfReader(initialPdfUri = stagedUri),
            ReaderNavigationCoordinator.resolveDocumentEntryScreen(stagedUri, null)
        )
    }

    @Test
    fun resolveDocumentEntryScreen_preservesSpecializedEpubAndArchiveRoutes() {
        val epubUri = Uri.parse("content://provider/books/novel.epub")
        val cbzUri = Uri.parse("content://provider/comics/issue1.cbz")

        assertEquals(
            Screen.ReflowReader(initialUri = epubUri),
            ReaderNavigationCoordinator.resolveDocumentEntryScreen(epubUri, "novel.epub")
        )
        assertEquals(
            Screen.EbookConverter,
            ReaderNavigationCoordinator.resolveDocumentEntryScreen(cbzUri, "issue1.cbz")
        )
    }

    @Test
    fun extractIncomingDocumentUri_handlesActionViewSendAndMultiple() {
        val viewUri = Uri.parse("content://external/doc/view.pdf")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply { data = viewUri }
        assertEquals(viewUri, ReaderNavigationCoordinator.extractIncomingDocumentUri(viewIntent))

        val sendUri = Uri.parse("content://external/doc/send.pdf")
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, sendUri)
        }
        assertEquals(sendUri, ReaderNavigationCoordinator.extractIncomingDocumentUri(sendIntent))

        val multiUri1 = Uri.parse("content://external/doc/multi1.pdf")
        val multiUri2 = Uri.parse("content://external/doc/multi2.pdf")
        val multiIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(multiUri1, multiUri2))
        }
        assertEquals(multiUri1, ReaderNavigationCoordinator.extractIncomingDocumentUri(multiIntent))

        val clipUri = Uri.parse("content://external/doc/clip.pdf")
        val clipIntent = Intent(Intent.ACTION_VIEW).apply {
            clipData = ClipData.newRawUri("pdf", clipUri)
        }
        assertEquals(clipUri, ReaderNavigationCoordinator.extractIncomingDocumentUri(clipIntent))
    }

    @Test
    fun viewModel_navigatingFromReaderToToolAndReturningPreservesSelectedDocument() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(app)
        val docUri = Uri.parse("file:///data/user/0/com.pdfchemy.app/cache/staged/snapshot_reader.pdf")

        viewModel.readerNavigation.onEnterReader(docUri)

        // Navigate from Reader to Compress tool
        val targetTool = viewModel.navigateFromReaderToTool(docUri, Screen.CompressPdf)
        assertEquals(Screen.CompressPdf, targetTool)
        assertEquals(docUri, viewModel.continuityDocumentUri.value)
        assertEquals(docUri, viewModel.readerNavigation.readerReturnUri)

        // Pressing Back from Compress tool returns directly to PdfReader with the same document URI
        val returnedScreen = viewModel.returnFromTool(Screen.CompressCategory)
        assertEquals(Screen.PdfReader(initialPdfUri = docUri), returnedScreen)
        assertEquals(docUri, viewModel.continuityDocumentUri.value)

        // Navigating from Reader to PdfEditor populates initialPdfUri automatically
        val editorTarget = viewModel.navigateFromReaderToTool(docUri, Screen.PdfEditor())
        assertEquals(Screen.PdfEditor(initialPdfUri = docUri), editorTarget)

        val returnedFromEditor = viewModel.returnFromTool(Screen.OrganizeCategory)
        assertEquals(Screen.PdfReader(initialPdfUri = docUri), returnedFromEditor)

        // Subsequent back press when not returning from a Reader-launched tool falls back to category and clears continuity
        val categoryFallback = viewModel.returnFromTool(Screen.OrganizeCategory)
        assertEquals(Screen.OrganizeCategory, categoryFallback)
        assertNull(viewModel.continuityDocumentUri.value)
    }

    @Test
    fun toolRegistry_includesPdfReaderEntryPoint() {
        assertTrue(
            "ToolRegistry must include Screen.PdfReader entry point for search discovery",
            ToolRegistry.allTools.any { it.screen is Screen.PdfReader }
        )
    }
}

