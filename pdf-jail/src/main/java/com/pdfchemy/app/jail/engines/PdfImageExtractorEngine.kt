package com.pdfchemy.app.jail.engines

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PdfImageExtractorEngine {
    suspend fun extractImagesFramed(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var count = 0
        FileInputStream(sourceFd.fileDescriptor).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
            java.io.DataOutputStream(com.pdfchemy.app.security.BoundedOutputStream(FileOutputStream(destFd.fileDescriptor))).use { output ->
                for (page in document.pages) {
                    val resources = page.resources ?: continue
                    for (name in resources.xObjectNames) {
                        val image = resources.getXObject(name) as? PDImageXObject ?: continue
                        com.pdfchemy.app.security.SecurityLimits.requirePixels(image.width, image.height)
                        check(++count <= com.pdfchemy.app.security.SecurityLimits.MAX_OUTPUT_FILES)
                        val bitmap = SafePdfImage.decode(image)
                        try {
                            val bytes = java.io.ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, com.pdfchemy.app.security.BoundedOutputStream(bytes, 16L * 1024 * 1024))
                            output.writeInt(bytes.size())
                            bytes.writeTo(output)
                        } finally { bitmap.recycle() }
                    }
                }
                output.writeInt(-1)
            }
        }
        return "{\"extractedCount\": $count}"
    }
    suspend fun extractImagesToZip(sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String = withContext(Dispatchers.IO) {
        var extractedCount = 0
        FileInputStream(sourceFd.fileDescriptor).use { inputStream ->
            val document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())
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
                                    val bitmap = SafePdfImage.decode(xObject)
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
