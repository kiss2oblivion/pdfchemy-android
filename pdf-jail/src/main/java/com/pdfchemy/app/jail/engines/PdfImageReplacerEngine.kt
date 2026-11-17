package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.pdfchemy.app.jail.AppLogger
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.pdfchemy.app.jail.boundedFileOutput as FileOutputStream
import java.io.InputStream

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
            inputStream = com.pdfchemy.app.jail.CapabilityIo.input(context, pdfUri)
                ?: return@withContext emptyList()
            document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())

            fun collectImages(pageIndex: Int, resources: PDResources?, pathPrefix: String, depth: Int) {
                if (resources == null || depth > 5) return
                val xObjectNames = resources.xObjectNames ?: return

                for (cosName in xObjectNames) {
                    if (imageList.size >= com.pdfchemy.app.security.SecurityLimits.MAX_OUTPUT_FILES) break
                    try {
                        val xObject = resources.getXObject(cosName)
                        if (xObject is PDImageXObject) {
                            com.pdfchemy.app.security.SecurityLimits.requireImageDecodePixels(xObject.width, xObject.height)
                            val fullResourceName = if (pathPrefix.isEmpty()) cosName.name else "$pathPrefix/${cosName.name}"
                            val width = xObject.width
                            val height = xObject.height
                            val suffix = xObject.suffix ?: "png"

                            // Extract a scaled down thumbnail for memory safety
                            var fullImage: Bitmap? = null
                            var thumb: Bitmap? = null
                            try {
                                fullImage = SafePdfImage.decode(xObject)
                                val scale = calculateThumbnailScale(width, height, maxDim = 300)
                                thumb = if (scale < 1.0f) {
                                    fullImage.scale(
                                        (width * scale).toInt().coerceAtLeast(1),
                                        (height * scale).toInt().coerceAtLeast(1)
                                    )
                                } else {
                                    fullImage
                                }
                            } catch (e: Throwable) {
                                AppLogger.w("PdfImageReplacerEngine: Failed to extract thumbnail for $cosName: ${e.message}")
                            } finally {
                                if (fullImage != null && fullImage !== thumb && !fullImage.isRecycled) {
                                    fullImage.recycle()
                                }
                            }

                            imageList.add(
                                EmbeddedImageInfo(
                                    id = "${pageIndex}_$fullResourceName",
                                    pageIndex = pageIndex,
                                    resourceName = fullResourceName,
                                    width = width,
                                    height = height,
                                    format = suffix.uppercase(),
                                    thumbnailBitmap = thumb
                                )
                            )
                        } else if (xObject is PDFormXObject) {
                            val nextPrefix = if (pathPrefix.isEmpty()) cosName.name else "$pathPrefix/${cosName.name}"
                            collectImages(pageIndex, xObject.resources, nextPrefix, depth + 1)
                        }
                    } catch (e: Exception) {
                        AppLogger.w("PdfImageReplacerEngine: Skipping XObject $cosName: ${e.message}")
                    }
                }
            }

            for (pageIndex in 0 until document.numberOfPages) {
                if (imageList.size >= com.pdfchemy.app.security.SecurityLimits.MAX_OUTPUT_FILES) break
                val page = document.getPage(pageIndex)
                collectImages(pageIndex, page.resources, "", 0)
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
            inputStream = com.pdfchemy.app.jail.CapabilityIo.input(context, sourcePdfUri)
                ?: throw IllegalArgumentException("Cannot open source PDF")
            document = PDDocument.load(inputStream, com.pdfchemy.app.jail.JailMemory.settings())

            if (pageIndex !in 0 until document.numberOfPages) {
                throw IndexOutOfBoundsException("Invalid page index: $pageIndex")
            }

            val page = document.getPage(pageIndex)
            val resources = page.resources
                ?: throw IllegalStateException("Page $pageIndex has no resources dictionary")

            fun findTargetImageAndResources(pageResources: PDResources, resourcePath: String): Pair<PDResources, Pair<COSName, PDImageXObject>>? {
                if (resourcePath.contains("/")) {
                    val parts = resourcePath.split("/")
                    var currentRes: PDResources = pageResources
                    for (i in 0 until parts.size - 1) {
                        val formName = COSName.getPDFName(parts[i])
                        val formXObject = currentRes.getXObject(formName) as? PDFormXObject ?: return null
                        currentRes = formXObject.resources ?: return null
                    }
                    val imgName = COSName.getPDFName(parts.last())
                    val imgXObject = currentRes.getXObject(imgName) as? PDImageXObject ?: return null
                    return Pair(currentRes, Pair(imgName, imgXObject))
                } else {
                    val directName = COSName.getPDFName(resourcePath)
                    val directObj = pageResources.getXObject(directName)
                    if (directObj is PDImageXObject) {
                        return Pair(pageResources, Pair(directName, directObj))
                    }
                    fun searchRecursively(res: PDResources, depth: Int): Pair<PDResources, Pair<COSName, PDImageXObject>>? {
                        if (depth > 5) return null
                        val names = res.xObjectNames ?: return null
                        for (name in names.toList()) {
                            val xObj = res.getXObject(name)
                            if (xObj is PDFormXObject) {
                                val childRes = xObj.resources ?: continue
                                val targetImg = childRes.getXObject(directName) as? PDImageXObject
                                if (targetImg != null) {
                                    return Pair(childRes, Pair(directName, targetImg))
                                }
                                val nested = searchRecursively(childRes, depth + 1)
                                if (nested != null) return nested
                            }
                        }
                        return null
                    }
                    return searchRecursively(pageResources, 1)
                }
            }

            val targetPair = findTargetImageAndResources(resources, resourceName)
                ?: throw IllegalArgumentException("Resource $resourceName on page $pageIndex not found as an image XObject")

            val targetResources = targetPair.first
            val cosName = targetPair.second.first
            val existingXObject = targetPair.second.second

            // Aspect ratio fitting: prevent anamorphic stretching by letterboxing if aspect ratios differ
            val origW = existingXObject.width
            val origH = existingXObject.height
            val origAR = origW.toFloat() / origH.toFloat().coerceAtLeast(1f)
            val repAR = replacementBitmap.width.toFloat() / replacementBitmap.height.toFloat().coerceAtLeast(1f)

            var fittedBitmap = replacementBitmap
            var isTemporaryBitmap = false

            if (kotlin.math.abs(origAR - repAR) > 0.01f && origW in 1..4096 && origH in 1..4096) {
                com.pdfchemy.app.security.SecurityLimits.requirePixels(origW, origH)
                val canvasBmp = createBitmap(origW, origH)
                val canvas = android.graphics.Canvas(canvasBmp)
                val scale = minOf(origW.toFloat() / replacementBitmap.width, origH.toFloat() / replacementBitmap.height)
                val dw = replacementBitmap.width * scale
                val dh = replacementBitmap.height * scale
                val dx = (origW - dw) / 2f
                val dy = (origH - dh) / 2f
                val srcRect = android.graphics.Rect(0, 0, replacementBitmap.width, replacementBitmap.height)
                val dstRect = android.graphics.RectF(dx, dy, dx + dw, dy + dh)
                canvas.drawBitmap(replacementBitmap, srcRect, dstRect, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                fittedBitmap = canvasBmp
                isTemporaryBitmap = true
            }

            // Create replacement PDImageXObject
            val newImageXObject = try {
                if (isLossless) {
                    LosslessFactory.createFromImage(document, fittedBitmap)
                } else {
                    JPEGFactory.createFromImage(document, fittedBitmap, 0.90f)
                }
            } finally {
                if (isTemporaryBitmap) {
                    fittedBitmap.recycle()
                }
            }

            // Replace the object in the target resources dictionary
            targetResources.put(cosName, newImageXObject)

            // Write updated document to temporary file then copy to destination URI
            tempFile = com.pdfchemy.app.jail.JailScratch.namedFile("replaced_image_tmp_${System.currentTimeMillis()}.pdf")
            FileOutputStream(tempFile).use { outStream ->
                document.save(outStream)
            }

            com.pdfchemy.app.jail.CapabilityIo.output(context, destPdfUri)?.let { com.pdfchemy.app.security.BoundedOutputStream(it) }?.use { destStream ->
                com.pdfchemy.app.jail.CapabilityIo.input(tempFile).use { tempIn ->
                    tempIn.copyTo(destStream)
                }
            } ?: throw IllegalStateException("Cannot open destination output stream")

            tempFile.delete()
            tempFile = null

            // Record to history

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
