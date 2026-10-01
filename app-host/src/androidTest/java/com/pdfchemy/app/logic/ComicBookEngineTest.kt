package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ComicBookEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun testCbzToPdf_createsPdfFromComicArchive() = runBlocking {
        // Create sample CBZ with 2 dummy image pages
        val sampleCbzFile = File(context.cacheDir, "sample_comic.cbz")
        ZipOutputStream(FileOutputStream(sampleCbzFile)).use { zip ->
            val bmp = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)

            zip.putNextEntry(ZipEntry("page_01.jpg"))
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, zip)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("page_02.jpg"))
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, zip)
            zip.closeEntry()

            bmp.recycle()
        }

        val destPdfFile = File(context.cacheDir, "comic_out.pdf")
        val destPdfUri = Uri.fromFile(destPdfFile)

        val result = ComicBookEngine.convertCbzToPdf(
            context = context,
            sourceCbzUri = Uri.fromFile(sampleCbzFile),
            destPdfUri = destPdfUri
        )

        assertTrue(result.isSuccess)
        assertTrue(destPdfFile.exists() && destPdfFile.length() > 0)

        val doc = PDDocument.load(destPdfFile)
        assertEquals(2, doc.numberOfPages)
        doc.close()
    }
}
