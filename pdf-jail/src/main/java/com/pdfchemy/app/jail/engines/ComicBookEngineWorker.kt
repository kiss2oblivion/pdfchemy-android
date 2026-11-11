package com.pdfchemy.app.jail.engines

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import com.pdfchemy.app.jail.capabilityInput as FileInputStream
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import com.pdfchemy.app.jail.FdZipFile as ZipFile

object ComicBookEngineWorker {

    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            val action = params.optString("action", "pdf2cbz")

            if (action == "cbz2pdf") {
                convertCbzToPdf(context, sourceFd!!, destFd!!)
            } else if (action == "pdf2cbz") {
                convertPdfToCbz(context, sourceFd!!, destFd!!)
            } else {
                JSONObject().put("error", "Unsupported action in worker: $action").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "Comic book engine error").toString()
        }
    }

    private fun convertPdfToCbz(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        var dupPfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var zos: ZipOutputStream? = null
        try {
            dupPfd = try {
                android.system.Os.lseek(sourceFd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                sourceFd.dup()
            } catch (_: Exception) {
                null
            }

            renderer = PdfRenderer(dupPfd ?: sourceFd)
            val pageCount = renderer.pageCount
            if (pageCount == 0) {
                throw IllegalArgumentException("PDF contains 0 pages.")
            }

            zos = ZipOutputStream(BufferedOutputStream(FileOutputStream(destFd.fileDescriptor)))
            var totalBytesWritten = 0L

            for (pageIndex in 0 until pageCount) {
                var page: PdfRenderer.Page? = null
                var bitmap: Bitmap? = null
                try {
                    page = renderer.openPage(pageIndex)
                    val scale = 2.0f
                    val renderW = (page.width * scale).toInt().coerceIn(1, 4096)
                    val renderH = (page.height * scale).toInt().coerceIn(1, 4096)
                    bitmap = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                    val entryName = String.format(Locale.US, "page_%04d.jpg", pageIndex + 1)
                    val entry = ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    val bos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                    val bytes = bos.toByteArray()
                    totalBytesWritten += bytes.size
                    if (totalBytesWritten > JailQuotas.MAX_ARCHIVE_BYTES_READ) {
                        throw SecurityException("CBZ generation exceeded quota limit of ${JailQuotas.MAX_ARCHIVE_BYTES_READ} bytes.")
                    }
                    zos.write(bytes)
                    zos.closeEntry()
                } finally {
                    bitmap?.recycle()
                    page?.close()
                }
            }
            zos.finish()
            return JSONObject().put("success", true).put("pages", pageCount).toString()
        } finally {
            try { zos?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { dupPfd?.close() } catch (_: Exception) {}
        }
    }

    private fun convertCbzToPdf(context: Context, sourceFd: ParcelFileDescriptor, destFd: ParcelFileDescriptor): String {
        // Fast pre-flight magic byte inspection before scratch allocation
        checkArchiveFormat(sourceFd)

        var tempFile: File? = null
        var zip: ZipFile? = null
        var document: PDDocument? = null
        
        try {
            tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("cbz_worker_", ".cbz", context.cacheDir)
            
            // Inspect archive magic bytes and copy safely
            var magicBytesChecked = false
            FileInputStream(sourceFd.fileDescriptor).use { input ->
                FileOutputStream(tempFile).use { output ->
                    var totalRead = 0L
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        if (!magicBytesChecked && read >= 4) {
                            magicBytesChecked = true
                            // RAR magic check: 0x52 0x61 0x72 0x21 ("Rar!")
                            if (buffer[0] == 0x52.toByte() && buffer[1] == 0x61.toByte() && buffer[2] == 0x72.toByte() && buffer[3] == 0x21.toByte()) {
                                throw IllegalArgumentException("RAR-compressed archive detected (.cbr). RAR format cannot be unpacked directly on-device. Please convert the comic archive to standard ZIP format (.cbz) or unpack its image pages first.")
                            }
                            // 7-Zip magic check: 0x37 0x7A 0xBC 0xAF 0x27 0x1C ('7z...')
                            if (read >= 6 && buffer[0] == 0x37.toByte() && buffer[1] == 0x7A.toByte() && buffer[2] == 0xBC.toByte() && buffer[3] == 0xAF.toByte() && buffer[4] == 0x27.toByte() && buffer[5] == 0x1C.toByte()) {
                                throw IllegalArgumentException("7-Zip compressed archive detected (.cb7). Please convert the archive to standard ZIP format (.cbz) first.")
                            }
                        }
                        totalRead += read
                        if (totalRead > JailQuotas.MAX_ARCHIVE_BYTES_READ) {
                            throw SecurityException("CBZ archive exceeded quota limit of ${JailQuotas.MAX_ARCHIVE_BYTES_READ} bytes.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            try {
                zip = ZipFile(tempFile)
            } catch (e: ZipException) {
                throw IllegalArgumentException("Invalid or unsupported comic archive. The file is not a standard ZIP (.cbz) container: ${e.message}")
            }
            document = PDDocument(com.pdfchemy.app.jail.JailMemory.settings())
            
            val validExtensions = setOf(".jpg", ".jpeg", ".png", ".webp")
            
            // Limit the enumeration count
            val imageEntries = zip.entries().asSequence()
                .take(JailQuotas.MAX_ARCHIVE_FILE_COUNT)
                .filter { !it.isDirectory }
                .filter { entry -> validExtensions.any { ext -> entry.name.endsWith(ext, ignoreCase = true) } }
                .sortedBy { it.name }
                .toList()

            if (imageEntries.isEmpty()) {
                throw IllegalArgumentException("No valid image pages (JPG, PNG, WEBP) found in CBZ archive.")
            }
            
            var aggregateUncompressedBytes = 0L

            for (entry in imageEntries) {
                zip.getInputStream(entry).use { inputStream ->
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    
                    val tempImgFile = com.pdfchemy.app.jail.JailScratch.createTempFile("cbz_img_", ".tmp", context.cacheDir)
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
                        
                        com.pdfchemy.app.jail.CapabilityIo.fd(tempImgFile).use { android.system.Os.lseek(it.fileDescriptor, 0, android.system.OsConstants.SEEK_SET); BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
                        
                        if (options.outWidth > 8192 || options.outHeight > 8192) {
                            throw SecurityException("Image dimensions (${options.outWidth}x${options.outHeight}) exceed safe maximums in CBZ.")
                        }
                        
                        // Parse actual image
                        val bitmap = com.pdfchemy.app.jail.CapabilityIo.fd(tempImgFile).use(SafeImageDecoder::decode)
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

            FileOutputStream(destFd.fileDescriptor).use { document.save(it) }
            return JSONObject().put("success", true).put("pages", imageEntries.size).toString()
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { zip?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private fun checkArchiveFormat(sourceFd: ParcelFileDescriptor) {
        val header = ByteArray(8)
        var bytesRead = -1
        try {
            bytesRead = android.system.Os.pread(sourceFd.fileDescriptor, header, 0, 8, 0L)
        } catch (_: Throwable) {
            try {
                sourceFd.dup().use { dup ->
                    java.io.FileInputStream(dup.fileDescriptor).use { fis ->
                        bytesRead = fis.read(header)
                    }
                }
            } catch (_: Throwable) {}
        }

        if (bytesRead >= 4) {
            // RAR check: 0x52 0x61 0x72 0x21 ("Rar!")
            if (header[0] == 0x52.toByte() && header[1] == 0x61.toByte() && header[2] == 0x72.toByte() && header[3] == 0x21.toByte()) {
                throw IllegalArgumentException("RAR-compressed archive detected (.cbr). RAR format cannot be unpacked directly on-device. Please convert the comic archive to standard ZIP format (.cbz) or unpack its image pages first.")
            }
        }
        if (bytesRead >= 6) {
            // 7-Zip check: 0x37 0x7A 0xBC 0xAF 0x27 0x1C ('7z...')
            if (header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() && header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() && header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()) {
                throw IllegalArgumentException("7-Zip compressed archive detected (.cb7). Please convert the archive to standard ZIP format (.cbz) first.")
            }
        }
    }
}

