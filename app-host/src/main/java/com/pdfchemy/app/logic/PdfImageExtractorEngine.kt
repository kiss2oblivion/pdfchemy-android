package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PdfImageExtractorEngine {
    suspend fun extractImagesToZip(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String = withContext(Dispatchers.IO) {
        var extractedCount = 0
        FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
            val document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            FileOutputStream(destFd.fileDescriptor).use { destStream ->
                val zos = ZipOutputStream(destStream)
                for (pageIndex in 0 until document.numberOfPages) {
                    val page = document.getPage(pageIndex)
                    val resources = page.resources
                    if (resources != null) {
                        val xObjectNames = resources.xObjectNames
                        for (xObjectName in xObjectNames) {
                            val xObject = resources.getXObject(xObjectName)
                            if (xObject is PDImageXObject) {
                                try {
                                    val bitmap = xObject.image
                                    if (bitmap != null) {
                                        val entryName = "extracted_image_${System.currentTimeMillis()}_${extractedCount}.jpg"
                                        zos.putNextEntry(ZipEntry(entryName))
                                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, zos)
                                        zos.closeEntry()
                                        extractedCount++
                                    }
                                } catch (e: Exception) {
                                    // Ignore individual image errors
                                }
                            }
                        }
                    }
                }
                zos.finish()
            }
            document.close()
        }
        "{\"extractedCount\": $extractedCount}"
    }
}
