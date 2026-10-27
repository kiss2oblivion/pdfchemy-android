package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeskewPublicationIntegrityTest {
    @Test fun existingProviderDestinationIsRejectedBeforeAnyTruncatingOpen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val input = File.createTempFile("deskew_publication_", ".pdf", context.cacheDir)
        PDDocument().use { doc -> doc.addPage(PDPage()); doc.save(input) }
        val source = input.readBytes()
        val id = "deskew_publication_${System.nanoTime()}"
        val target = Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id")
        context.contentResolver.call(target, "configure", id, Bundle().apply { putBoolean("block", true) })
        try {
            assertTrue(runCatching { PdfDeskewEngine.deskewDocument(context, Uri.fromFile(input), target) }.exceptionOrNull() is UnsupportedOperationException)
            val state = context.contentResolver.call(target, "state", id, null)!!
            assertEquals("Existing destinations must never enter publication", 0, state.getInt("opens"))
            assertArrayEquals("existing destination".toByteArray(), state.getByteArray("bytes"))
            assertArrayEquals(source, input.readBytes())
        } finally {
            context.contentResolver.call(target, "release", id, null)
            input.delete()
        }
    }
}
