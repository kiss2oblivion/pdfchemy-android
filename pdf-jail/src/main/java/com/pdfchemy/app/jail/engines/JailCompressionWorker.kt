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
                                    try {
                                        val image = if (params.optBoolean("useGrayscale")) {
                                            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                                                filtered = it
                                                Canvas(it).drawBitmap(bitmap, 0f, 0f, Paint().apply {
                                                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                                                })
                                            }
                                        } else bitmap
                                        val encoded = if (params.optBoolean("useLossless") || hasTransparentPixels(image)) LosslessFactory.createFromImage(document, image)
                                            else JPEGFactory.createFromImage(document, image, quality, dpi.toInt())
                                        replacements[original] = encoded
                                        processed++
                                        encoded
                                    } finally { filtered?.recycle(); bitmap.recycle() }
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
                JSONObject().put("size", target.statSize).put("imagesProcessed", processed).put("hasSignatures", signed).toString()
            }
        }
    }
}
