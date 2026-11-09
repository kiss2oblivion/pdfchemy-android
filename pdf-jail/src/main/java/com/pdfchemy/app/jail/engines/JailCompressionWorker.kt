package com.pdfchemy.app.jail.engines

import android.graphics.*
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.*
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.*
import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

object JailCompressionWorker {
    private fun hasTransparentPixels(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { Color.alpha(it) < 255 }) return true
        }
        return false
    }
    fun compress(source: ParcelFileDescriptor, target: ParcelFileDescriptor, params: JSONObject): String {
        val dpi = params.optDouble("targetDpi", 150.0).toFloat()
        val quality = params.optDouble("quality", 0.5).toFloat()
        require(dpi.isFinite() && dpi in 36f..300f && quality.isFinite() && quality in 0f..1f)
        return capabilityInput(source.fileDescriptor).use { input ->
            PDDocument.load(input, JailMemory.settings()).use { document ->
                val signed = document.signatureDictionaries.isNotEmpty()
                val replacements = IdentityHashMap<COSBase, PDImageXObject>()
                val visited = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                var processed = 0
                fun optimize(resources: PDResources?, depth: Int) {
                    if (resources == null || !visited.add(resources.cosObject)) return
                    require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && visited.size <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph quota exceeded" }
                    for (name in resources.xObjectNames.toList()) {
                        when (val xObject = resources.getXObject(name)) {
                            is PDFormXObject -> optimize(xObject.resources, depth + 1)
                            is PDImageXObject -> {
                                val original = xObject.cosObject
                                val replacement = replacements[original] ?: run {
                                    val bitmap = SafePdfImage.decode(xObject)
                                    var filtered: Bitmap? = null
                                    var scaled: Bitmap? = null
                                    try {
                                        val useLossless = params.optBoolean("useLossless")
                                        val useGrayscale = params.optBoolean("useGrayscale")
                                        val maxDimension = if (useLossless) {
                                            SecurityLimits.MAX_IMAGE_DECODE_DIMENSION
                                        } else {
                                            (dpi * 11.7f * (0.6f + 0.4f * quality)).toInt()
                                                .coerceIn(512, SecurityLimits.MAX_IMAGE_DECODE_DIMENSION)
                                        }
                                        val sourceBitmap = if (maxOf(bitmap.width, bitmap.height) > maxDimension) {
                                            val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
                                            val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
                                            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                                            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true).also {
                                                if (it !== bitmap) scaled = it
                                            }
                                        } else bitmap
                                        val image = if (useGrayscale) {
                                            Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, Bitmap.Config.ARGB_8888).also {
                                                filtered = it
                                                Canvas(it).drawBitmap(sourceBitmap, 0f, 0f, Paint().apply {
                                                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                                                })
                                            }
                                        } else sourceBitmap
                                        val encoded = if (useLossless || hasTransparentPixels(image)) LosslessFactory.createFromImage(document, image)
                                            else JPEGFactory.createFromImage(document, image, quality, dpi.toInt())
                                        val keepEncoded = useGrayscale ||
                                            xObject.cosObject.length <= 0L ||
                                            encoded.cosObject.length < xObject.cosObject.length
                                        val chosen = if (keepEncoded) encoded else xObject
                                        replacements[original] = chosen
                                        processed++
                                        chosen
                                    } finally {
                                        filtered?.recycle()
                                        scaled?.recycle()
                                        bitmap.recycle()
                                    }
                                }
                                resources.put(name, replacement)
                            }
                        }
                    }
                }
                document.pages.forEach { optimize(it.resources, 0) }
                if (params.optBoolean("stripMetadata")) {
                    document.documentInformation = PDDocumentInformation()
                    document.documentCatalog.metadata = null
                }
                boundedFileOutput(target.fileDescriptor).use { document.save(it) }
                JSONObject().put("size", com.pdfchemy.app.jail.JailScratch.outputSize(target.fileDescriptor)).put("imagesProcessed", processed).put("hasSignatures", signed).toString()
            }
        }
    }
}
