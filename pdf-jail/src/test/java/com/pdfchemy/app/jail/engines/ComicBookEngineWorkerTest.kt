package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ComicBookEngineWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testRarArchiveDetected_returnsClearActionableError() {
        val rarFile = File(context.cacheDir, "test.cbr")
        FileOutputStream(rarFile).use { out ->
            // RAR 4.x / 5.0 magic header
            out.write(byteArrayOf(0x52.toByte(), 0x61.toByte(), 0x72.toByte(), 0x21.toByte(), 0x1A.toByte(), 0x07.toByte(), 0x00.toByte()))
            out.write(ByteArray(100))
        }

        val destFile = File(context.cacheDir, "out_from_rar.pdf")
        val sourcePfd = ParcelFileDescriptor.open(rarFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val destPfd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)

        val params = JSONObject().put("action", "cbz2pdf").toString()
        val response = ComicBookEngineWorker.execute(context, sourcePfd, destPfd, params)

        sourcePfd.close()
        destPfd.close()

        val json = JSONObject(response)
        assertTrue("Expected error key, got: $response", json.has("error"))
        val errorMsg = json.getString("error")
        assertTrue("Expected RAR message, got: $errorMsg", errorMsg.contains("RAR-compressed archive detected"))
        assertFalse(errorMsg.contains("ZipException"))
    }

    @Test
    fun test7zArchiveDetected_returnsClearActionableError() {
        val sevenZipFile = File(context.cacheDir, "test.cb7")
        FileOutputStream(sevenZipFile).use { out ->
            // 7-Zip magic header
            out.write(byteArrayOf(0x37.toByte(), 0x7A.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27.toByte(), 0x1C.toByte()))
            out.write(ByteArray(100))
        }

        val destFile = File(context.cacheDir, "out_from_7z.pdf")
        val sourcePfd = ParcelFileDescriptor.open(sevenZipFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val destPfd = ParcelFileDescriptor.open(destFile, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY)

        val params = JSONObject().put("action", "cbz2pdf").toString()
        val response = ComicBookEngineWorker.execute(context, sourcePfd, destPfd, params)

        sourcePfd.close()
        destPfd.close()

        val json = JSONObject(response)
        assertTrue("Expected error key, got: $response", json.has("error"))
        val errorMsg = json.getString("error")
        assertTrue("Expected 7z message, got: $errorMsg", errorMsg.contains("7-Zip compressed archive detected"))
    }
}
