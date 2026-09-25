package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

object ComicBookEngineWorker {

    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            val action = params.optString("action", "pdf2cbz")

            if (action == "cbz2pdf") {
                convertCbzToPdf(context, sourceFd!!, destFd!!)
            } else {
                JSONObject().put("error", "Unsupported action in worker").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    private fun convertCbzToPdf(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var tempFile: File? = null
        var zip: ZipFile? = null
        var document: PDDocument? = null
        
        try {
            tempFile = File.createTempFile("cbz_worker_", ".cbz", context.cacheDir)
            FileInputStream(sourceFd.fileDescriptor).use { input ->
                FileOutputStream(tempFile).use { output ->
                    var totalRead = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalRead += read
                        if (totalRead > JailQuotas.MAX_ARCHIVE_BYTES_READ) {
                            throw SecurityException("CBZ archive exceeded quota limit of ${JailQuotas.MAX_ARCHIVE_BYTES_READ} bytes.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            zip = ZipFile(tempFile)
            document = PDDocument()
            
            val validExtensions = setOf(".jpg", ".jpeg", ".png", ".webp")
            
            // Limit the enumeration count
            val imageEntries = zip.entries().asSequence()
                .take(JailQuotas.MAX_ARCHIVE_FILE_COUNT)
                .filter { !it.isDirectory }
                .filter { entry -> validExtensions.any { ext -> entry.name.endsWith(ext, ignoreCase = true) } }
                .sortedBy { it.name }
                .toList()

            if (imageEntries.isEmpty()) {
                throw IllegalArgumentException("No valid images found in CBZ.")
            }
            
            var aggregateUncompressedBytes = 0L

            for (entry in imageEntries) {
                // If entry size is known and crazy, reject early. But wait, zip slip / zip bomb?
                // Also zip bomb prevention: bounded total uncompressed bytes.
                zip.getInputStream(entry).use { inputStream ->
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    
                    // We must buffer the stream because decoding bounds consumes it if not mark-supported
                    val tempImgFile = File.createTempFile("cbz_img_", ".tmp", context.cacheDir)
                    try {
                        FileOutputStream(tempImgFile).use { output ->
                            var entryRead = 0L
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (inputStream.read(buffer).also { read = it } != -1) {
                                aggregateUncompressedBytes += read
                                entryRead += read
                                if (aggregateUncompressedBytes > JailQuotas.MAX_ARCHIVE_BYTES_READ) {
                                    throw SecurityException("CBZ extraction exceeded aggregate quota limit of ${JailQuotas.MAX_ARCHIVE_BYTES_READ} bytes.")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        
                        BitmapFactory.decodeFile(tempImgFile.absolutePath, options)
                        
                        if (options.outWidth > 8192 || options.outHeight > 8192) {
                            throw SecurityException("Image dimensions (${options.outWidth}x${options.outHeight}) exceed safe maximums in CBZ.")
                        }
                        
                        // Parse actual image
                        val bitmap = BitmapFactory.decodeFile(tempImgFile.absolutePath) ?: return@use
                        try {
                            val pdImage = if (options.outMimeType == "image/jpeg") {
                                JPEGFactory.createFromImage(document, bitmap)
                            } else {
                                LosslessFactory.createFromImage(document, bitmap)
                            }
                            
                            val page = PDPage(PDRectangle(pdImage.width.toFloat(), pdImage.height.toFloat()))
                            document.addPage(page)
                            
                            PDPageContentStream(document, page).use { cs ->
                                cs.drawImage(pdImage, 0f, 0f, pdImage.width.toFloat(), pdImage.height.toFloat())
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        tempImgFile.delete()
                    }
                }
            }

            document.save(FileOutputStream(destFd.fileDescriptor))
            return JSONObject().put("success", true).put("pages", imageEntries.size).toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { zip?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }
}

