package com.pdfchemy.app.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.pdfchemy.app.utils.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class EmbeddedImageInfo(
    val id: String,
    val pageIndex: Int,
    val resourceName: String,
    val width: Int,
    val height: Int,
    val format: String,
    val thumbnailBitmap: Bitmap?
)

object PdfImageReplacerEngine {

    /**
     * Inspects the PDF and lists all embedded image XObjects across all pages.
     */
    suspend fun listEmbeddedImages(context: Context, pdfUri: Uri): List<EmbeddedImageInfo> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        val imageList = mutableListOf<EmbeddedImageInfo>()

        var inputStream: InputStream? = null
        var document: PDDocument? = null
        try {
            inputStream = context.contentResolver.openInputStream(pdfUri)
                ?: return@withContext emptyList()
            document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            for (pageIndex in 0 until document.numberOfPages) {
                val page = document.getPage(pageIndex)
                val resources = page.resources ?: continue
                val xObjectNames = resources.xObjectNames ?: continue

                for (cosName in xObjectNames) {
                    try {
                        val xObject = resources.getXObject(cosName)
                        if (xObject is PDImageXObject) {
                            val width = xObject.width
                            val height = xObject.height
                            val suffix = xObject.suffix ?: "png"

                            // Extract a scaled down thumbnail for memory safety
                            var thumb: Bitmap? = null
                            try {
                                val fullImage = xObject.image
                                if (fullImage != null) {
                                    val scale = calculateThumbnailScale(width, height, maxDim = 300)
                                    thumb = if (scale < 1.0f) {
                                        Bitmap.createScaledBitmap(
                                            fullImage,
                                            (width * scale).toInt().coerceAtLeast(1),
                                            (height * scale).toInt().coerceAtLeast(1),
                                            true
                                        )
                                    } else {
                                        fullImage
                                    }
                                }
                            } catch (e: Throwable) {
                                AppLogger.w("PdfImageReplacerEngine: Failed to extract thumbnail for $cosName: ${e.message}")
                            }

                            imageList.add(
                                EmbeddedImageInfo(
                                    id = "${pageIndex}_${cosName.name}",
                                    pageIndex = pageIndex,
                                    resourceName = cosName.name,
                                    width = width,
                                    height = height,
                                    format = suffix.uppercase(),
                                    thumbnailBitmap = thumb
                                )
                            )
                        }
                    } catch (e: Exception) {
                        AppLogger.w("PdfImageReplacerEngine: Skipping XObject $cosName: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.e("PdfImageReplacerEngine: Failed to list embedded images", e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
        }

        return@withContext imageList
    }

    /**
     * Swaps an existing PDImageXObject with a new Bitmap while maintaining identical layout.
     */
    suspend fun replaceEmbeddedImage(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        pageIndex: Int,
        resourceName: String,
        replacementBitmap: Bitmap,
        isLossless: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context)
        var inputStream: InputStream? = null
        var document: PDDocument? = null
        var tempFile: File? = null

        return@withContext try {
            inputStream = context.contentResolver.openInputStream(sourcePdfUri)
                ?: throw IllegalArgumentException("Cannot open source PDF")
            document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())

            if (pageIndex !in 0 until document.numberOfPages) {
                throw IndexOutOfBoundsException("Invalid page index: $pageIndex")
            }

            val page = document.getPage(pageIndex)
            val resources = page.resources
                ?: throw IllegalStateException("Page $pageIndex has no resources dictionary")

            val cosName = COSName.getPDFName(resourceName)
            val existingXObject = resources.getXObject(cosName)
            if (existingXObject !is PDImageXObject) {
                throw IllegalArgumentException("Resource $resourceName on page $pageIndex is not an image XObject")
            }

            // Create replacement PDImageXObject
            val newImageXObject = if (isLossless) {
                LosslessFactory.createFromImage(document, replacementBitmap)
            } else {
                JPEGFactory.createFromImage(document, replacementBitmap, 0.90f)
            }

            // Replace the object in the page resources dictionary
            resources.put(cosName, newImageXObject)

            // Write updated document to temporary file then copy to destination URI
            tempFile = File(context.cacheDir, "replaced_image_tmp_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { outStream ->
                document.save(outStream)
            }

            context.contentResolver.openOutputStream(destPdfUri)?.use { destStream ->
                tempFile.inputStream().use { tempIn ->
                    tempIn.copyTo(destStream)
                }
            } ?: throw IllegalStateException("Cannot open destination output stream")

            tempFile.delete()
            tempFile = null

            // Record to history
            val historyRepo = HistoryRepository(context)
            historyRepo.addHistoryItem(destPdfUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "image_swapped.pdf", "Image Replaced PDF")

            Result.success(true)
        } catch (e: Exception) {
            AppLogger.e("PdfImageReplacerEngine: Error replacing image", e)
            Result.failure(e)
        } finally {
            try { document?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private fun calculateThumbnailScale(width: Int, height: Int, maxDim: Int): Float {
        val maxOriginal = maxOf(width, height)
        return if (maxOriginal > maxDim) {
            maxDim.toFloat() / maxOriginal.toFloat()
        } else {
            1.0f
        }
    }
}
