package com.pdfchemy.app.security

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Exercises the shipped JNI, deliberately bypassing ReadFile.readMem's platform decoder. */
@RunWith(AndroidJUnit4::class)
class NativeOcrArtifactCompatibilityTest {
    private val tessClass get() = Class.forName("com.googlecode.tesseract.android.TessBaseAPI")
    private val pixClass get() = Class.forName("com.googlecode.leptonica.android.Pix")

    @Test fun shippedEngineReportsTheReviewedVersion() {
        val type = tessClass
        val engine = type.getConstructor().newInstance()
        try {
            assertEquals("5.5.4", type.getMethod("getVersion").invoke(engine))
        } finally {
            type.getMethod("recycle").invoke(engine)
        }
    }

    @Test fun customInMemoryInitializationRejectsAnInvalidModelWithoutCrashing() {
        val type = tessClass
        val engine = type.getConstructor().newInstance()
        try {
            val init = type.getMethod("init", ByteArray::class.java, String::class.java, Int::class.javaPrimitiveType)
            assertEquals(false, init.invoke(engine, ByteArray(32), "eng", 1))
        } finally {
            type.getMethod("recycle").invoke(engine)
        }
    }

    @Test fun nativePngAndJpegDecodersRetainImageDimensions() {
        for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG)) {
            assertDimensions(encodedImage(format))
        }
    }

    @Test fun truncatedPngIsRejectedAndTheDecoderRemainsUsable() {
        val png = encodedImage(Bitmap.CompressFormat.PNG)
        assertEquals(0L, decodeNative(png.copyOf(24)))
        assertDimensions(png)
    }

    private fun encodedImage(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(37, 23, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            bitmap.setPixel(10, 10, Color.BLACK)
            return ByteArrayOutputStream().use { bytes ->
                assertTrue(bitmap.compress(format, 95, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeNative(bytes: ByteArray): Long {
        val read = Class.forName("com.googlecode.leptonica.android.ReadFile")
            .getDeclaredMethod("nativeReadMem", ByteArray::class.java, Int::class.javaPrimitiveType)
        read.isAccessible = true
        return read.invoke(null, bytes, bytes.size) as Long
    }

    private fun assertDimensions(bytes: ByteArray) {
        val pointer = decodeNative(bytes)
        assertTrue("Native decoder must produce a Pix", pointer != 0L)
        val type = pixClass
        val pix = type.getConstructor(Long::class.javaPrimitiveType).newInstance(pointer)
        try {
            assertEquals(37, type.getMethod("getWidth").invoke(pix))
            assertEquals(23, type.getMethod("getHeight").invoke(pix))
        } finally {
            type.getMethod("recycle").invoke(pix)
        }
    }
}
