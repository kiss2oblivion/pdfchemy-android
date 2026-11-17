package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ComicArchiveAdmissionDeviceTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test(timeout=120000) fun unsupportedSignaturesReachTheSpoolGuardWithoutPublishing()=runBlocking<Unit> {
        for((header,label) in listOf(
            byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,0) to "RAR",
            byteArrayOf(0x52,0x61,0x72,0x21,0x1a,0x07,1,0) to "RAR",
            byteArrayOf(0x37,0x7a,0xbc.toByte(),0xaf.toByte(),0x27,0x1c) to "7-Zip")) {
            val source=File.createTempFile("unsupported-comic-",".cbz",context.cacheDir)
            val output=File.createTempFile("rejected-comic-",".pdf",context.cacheDir)
            try {
                val original=header+ByteArray(100);source.writeBytes(original)
                val result=ComicBookEngine.convertCbzToPdf(context,Uri.fromFile(source),Uri.fromFile(output))
                assertTrue(result.isFailure)
                val error=result.exceptionOrNull()!!.message.orEmpty()
                assertTrue("Unexpected rejection: $error",error.contains(label) && error.contains("ZIP (.cbz)"))
                assertEquals(0L,output.length());assertArrayEquals(original,source.readBytes())
            } finally {source.delete();output.delete()}
        }
    }
    @Test(timeout=60000) fun zipImageRemainsReadableAfterHeaderInspection()=runBlocking<Unit> {
        val source=File.createTempFile("accepted-comic-",".cbz",context.cacheDir)
        val output=File.createTempFile("accepted-comic-",".pdf",context.cacheDir)
        val image=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888)
        var rendered:Bitmap?=null
        try {
            image.eraseColor(Color.RED)
            ZipOutputStream(source.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("page001.png"))
                assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,archive));archive.closeEntry()
            }
            ComicBookEngine.convertCbzToPdf(context,Uri.fromFile(source),Uri.fromFile(output)).getOrThrow()
            assertEquals(1,PdfEditor.getPageCount(context,Uri.fromFile(output)))
            rendered=PdfEditor.renderPageBitmap(context,Uri.fromFile(output),0,200)!!
            val pixel=rendered!!.getPixel(rendered!!.width/2,rendered!!.height/2)
            assertTrue(Color.red(pixel)>200 && Color.blue(pixel)<30 && Color.green(pixel)<30)
        } finally {image.recycle();rendered?.recycle();source.delete();output.delete()}
    }
}
