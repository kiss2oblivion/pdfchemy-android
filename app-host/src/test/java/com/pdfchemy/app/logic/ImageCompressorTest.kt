package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ImageCompressorTest {

    private lateinit var context: Context
    private lateinit var testJpegFile: File
    private lateinit var testJpegUri: Uri

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testJpegFile = createSampleBitmapFile("test_sample.jpg", 1000, 1000, 100)
        testJpegUri = Uri.fromFile(testJpegFile)
    }

    @Test
    fun testCompressJpegReducesFileSize() = runBlocking {
        val destFile = File(context.cacheDir, "compressed_sample.jpg")
        val destUri = Uri.fromFile(destFile)

        val result = ImageCompressor.compressImage(
            context = context,
            sourceUri = testJpegUri,
            destUri = destUri,
            quality = 30,
            targetFormat = ImageOutputFormat.JPEG
        )

        assertTrue("Compression should succeed", result.success)
        assertTrue("Dest file should exist", destFile.exists())
        assertTrue("Compressed size should be > 0", result.compressedSize > 0)
        assertTrue("Original size should be greater than compressed", result.originalSize > result.compressedSize)
        assertTrue("Percent saved should be > 0", result.percentSaved > 0)
    }

    @Test
    fun testCompressToTargetSize() = runBlocking {
        val destFile = File(context.cacheDir, "target_size_sample.jpg")
        val destUri = Uri.fromFile(destFile)
        val targetLimit = 150_000L // 150 KB

        val result = ImageCompressor.compressToTargetSize(
            context = context,
            sourceUri = testJpegUri,
            destUri = destUri,
            targetSizeBytes = targetLimit,
            targetFormat = ImageOutputFormat.JPEG
        )

        assertTrue("Target size compression should succeed", result.success)
        assertTrue("Dest file should exist", destFile.exists())
        assertTrue("Result size should be <= targetLimit or very close", result.compressedSize <= targetLimit + 20_000L)
    }

    @Test
    fun testImageAnalysisAndEstimator() {
        val analysis = ImageCompressor.analyzeImage(
            context = context,
            uri = testJpegUri,
            quality = 65,
            targetFormat = ImageOutputFormat.ORIGINAL
        )

        assertTrue("Analysis should support standard JPEG", analysis.isSupported)
        assertEquals(ImageValidationStatus.ALLOWED, analysis.validationStatus)
        assertEquals(1000, analysis.width)
        assertEquals(1000, analysis.height)
        assertTrue("Original size should be > 0", analysis.originalSizeBytes > 0)
        assertTrue("Estimated bytes should be less than original", analysis.estimatedCompressedBytes < analysis.originalSizeBytes)
        assertTrue("Estimated savings should be > 0", analysis.estimatedSavingsPercent > 0)
        assertEquals(PerceivedQualityLoss.MINIMAL, analysis.qualityLoss)
    }

    @Test
    fun testValidationGuardrailDeniesTinyFile() {
        val tinyFile = File(context.cacheDir, "tiny.jpg")
        FileOutputStream(tinyFile).use { out ->
            out.write(ByteArray(5000)) // 5 KB
        }
        val tinyUri = Uri.fromFile(tinyFile)

        val analysis = ImageCompressor.analyzeImage(
            context = context,
            uri = tinyUri
        )

        assertFalse("Analysis should flag tiny files as unsupported for compression", analysis.isSupported)
        assertEquals(ImageValidationStatus.DENIED_TOO_SMALL, analysis.validationStatus)
        assertNotNull("Should provide alternative suggestion", analysis.alternativeSuggestion)
    }

    @Test
    fun testResolutionDownscaling() = runBlocking {
        val destFile = File(context.cacheDir, "scaled_sample.jpg")
        val destUri = Uri.fromFile(destFile)

        val result = ImageCompressor.compressImage(
            context = context,
            sourceUri = testJpegUri,
            destUri = destUri,
            quality = 80,
            targetFormat = ImageOutputFormat.JPEG,
            maxDimension = 500
        )

        assertTrue("Compression should succeed", result.success)
        assertTrue("Width should be <= 500", result.width <= 500)
        assertTrue("Height should be <= 500", result.height <= 500)
    }

    @Test
    fun testCalculateInSampleSize() {
        assertEquals(1, ImageCompressor.calculateInSampleSize(1000, 1000, 0))
        assertEquals(1, ImageCompressor.calculateInSampleSize(1000, 1000, 1200))
        assertEquals(2, ImageCompressor.calculateInSampleSize(4000, 3000, 1920))
        assertEquals(4, ImageCompressor.calculateInSampleSize(8000, 6000, 1920))
    }

    @Test
    fun testDecodeImageBounds() {
        val bounds = ImageCompressor.decodeImageBounds(context, testJpegUri)
        assertNotNull("Bounds should not be null", bounds)
        assertEquals(1000, bounds!!.outWidth)
        assertEquals(1000, bounds.outHeight)
    }

    private fun createSampleBitmapFile(name: String, width: Int, height: Int, quality: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        for (i in 0 until 10) {
            paint.color = if (i % 2 == 0) Color.RED else Color.BLUE
            canvas.drawRect(
                (i * width / 10).toFloat(), 0f,
                ((i + 1) * width / 10).toFloat(), height.toFloat(),
                paint
            )
        }

        val file = File(context.cacheDir, name)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.flush()
        }
        bitmap.recycle()
        return file
    }
}
