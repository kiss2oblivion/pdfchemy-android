package com.pdfchemy.app.security

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.sandbox.NativeRendererCoordinator
import com.pdfchemy.app.utils.DocumentStager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RendererStorageBoundarySecurityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private suspend fun fixture(block: suspend (ParcelFileDescriptor) -> Unit) {
        val source = File.createTempFile("renderer_boundary_", ".pdf", context.cacheDir)
        try {
            PDDocument().use { document -> document.addPage(PDPage(PDRectangle(300f, 200f))); document.save(source) }
            val staged = DocumentStager.stageDocumentCancellable(context, Uri.fromFile(source))
            try { requireNotNull(context.contentResolver.openFileDescriptor(staged.uri, "r")).use { block(it) } }
            finally { DocumentStager.release(staged) }
        } finally { source.delete() }
    }

    @Test fun bitmapReadbackDecodesCompletedHostPipeSnapshot() = runBlocking {
        fixture { input ->
            val bitmap = NativeRendererCoordinator.renderPageToBitmap(context, input, 0, 300)
            assertNotNull(bitmap)
            try { assertEquals(300, bitmap!!.width); assertEquals(200, bitmap.height) }
            finally { bitmap?.recycle() }
        }
    }

    @Test fun jpegReadbackCopiesCompletedSnapshotIntoHostDestination() = runBlocking {
        fixture { input ->
            val output = File.createTempFile("renderer_boundary_", ".jpg", context.cacheDir)
            try {
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use {
                    assertEquals(true, NativeRendererCoordinator.renderPageToJpeg(context, input, 0, it))
                }
                val bitmap = BitmapFactory.decodeFile(output.path)
                assertNotNull(bitmap)
                try { assertEquals(600, bitmap!!.width); assertEquals(400, bitmap.height) }
                finally { bitmap?.recycle() }
            } finally { output.delete() }
        }
    }

    @Test fun failedRendererCannotTouchHostJpegDestination() = runBlocking {
        fixture { input ->
            val output = File.createTempFile("renderer_boundary_", ".jpg", context.cacheDir)
            try {
                output.writeText("existing destination")
                ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE).use {
                    assertTrue(runCatching { NativeRendererCoordinator.renderPageToJpeg(context, input, 99, it) }.isFailure)
                }
                assertEquals("existing destination", output.readText())
            } finally { output.delete() }
        }
    }
}
