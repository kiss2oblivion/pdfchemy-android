package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfBookmarkEngineTest {

    private lateinit var context: Context
    private lateinit var samplePdfFile: File
    private lateinit var samplePdfUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)

        samplePdfFile = File(context.cacheDir, "sample_book.pdf")
        val doc = PDDocument()
        doc.addPage(PDPage())
        doc.addPage(PDPage())
        doc.addPage(PDPage())
        doc.save(samplePdfFile)
        doc.close()
        samplePdfUri = Uri.fromFile(samplePdfFile)
    }

    @Test
    fun testWriteAndReadBookmarks() = runBlocking {
        val destFile = File(context.cacheDir, "bookmarked_out.pdf")
        val destUri = Uri.fromFile(destFile)

        val newBookmarks = listOf(
            BookmarkItem(title = "Chapter 1: Overview", pageIndex = 0),
            BookmarkItem(title = "Chapter 2: Installation", pageIndex = 1),
            BookmarkItem(title = "Chapter 3: Reference", pageIndex = 2)
        )

        val writeResult = PdfBookmarkEngine.writeBookmarks(context, samplePdfUri, destUri, newBookmarks)
        assertTrue(writeResult.isSuccess)
        assertTrue(destFile.exists() && destFile.length() > 0)

        val readResult = PdfBookmarkEngine.readBookmarks(context, destUri)
        assertTrue(readResult.isSuccess)
        val readList = readResult.getOrThrow()
        assertEquals(3, readList.size)
        assertEquals("Chapter 1: Overview", readList[0].title)
        assertEquals("Chapter 2: Installation", readList[1].title)
    }
}
