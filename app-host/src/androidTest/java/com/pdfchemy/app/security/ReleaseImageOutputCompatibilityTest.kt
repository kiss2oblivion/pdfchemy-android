package com.pdfchemy.app.security

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.logic.ImageCompressor
import com.pdfchemy.app.logic.ImageOutputFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReleaseImageOutputCompatibilityTest {
    @Test fun originalPngModeKeepsPngBytesMimeAndExtension() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("original_mode_", ".png", context.cacheDir)
        val output = File.createTempFile("original_mode_output_", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 256) for (x in 0 until 256) bitmap.setPixel(x, y, android.graphics.Color.rgb(x, y, (x + y) % 256))
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val analysis = ImageCompressor.analyzeImage(context, Uri.fromFile(source))
            val format = ImageCompressor.resolveOutputFormat(ImageOutputFormat.ORIGINAL, analysis.mimeType)
            assertEquals(ImageOutputFormat.PNG, format)
            assertEquals("png", format.extension)
            assertEquals("image/png", format.mimeType)
            val result = ImageCompressor.compressImage(context, Uri.fromFile(source), Uri.fromFile(output), targetFormat = format)
            assertTrue(result.error, result.success)
            assertArrayEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a), output.inputStream().use { input -> ByteArray(8).also { assertEquals(8, input.read(it)) } })
            assertEquals("image/png", ImageCompressor.analyzeImage(context, Uri.fromFile(output)).mimeType)
        } finally { bitmap.recycle(); source.delete(); output.delete() }
    }
}
