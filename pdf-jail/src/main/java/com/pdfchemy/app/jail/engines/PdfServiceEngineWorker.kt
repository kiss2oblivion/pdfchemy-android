package com.pdfchemy.app.jail.engines
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.security.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
object PdfServiceEngineWorker {
    fun compress(sourceFd: ParcelFileDescriptor?, targetFd: ParcelFileDescriptor?, targetDpi: Float, quality: Float, rasterizePages: Boolean): String {
                require(sourceFd != null && targetFd != null)
                require(targetDpi.isFinite() && targetDpi in 36f..300f && quality.isFinite() && quality in 0f..1f)
                com.pdfchemy.app.jail.capabilityInput(sourceFd.fileDescriptor).use { input ->
                    PDDocument.load(input, com.pdfchemy.app.jail.JailMemory.settings()).use { document ->
                        for (page in document.pages) {
                            val resources = page.resources ?: continue
                            for (name in resources.xObjectNames) {
                                val image = resources.getXObject(name) as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject ?: continue
                                SecurityLimits.requirePixels(image.width, image.height)
                                val bitmap = SafePdfImage.decode(image) ?: continue
                                try { resources.put(name, com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, bitmap, quality, targetDpi.toInt())) } finally { bitmap.recycle() }
                            }
                        }
                        com.pdfchemy.app.jail.boundedFileOutput(targetFd.fileDescriptor).use { output -> document.save(BoundedOutputStream(output)) }
                    }
                }
                return org.json.JSONObject().put("size", targetFd.statSize).toString()
    }
    fun analyze(sourceFd: ParcelFileDescriptor?): String {
                return com.pdfchemy.app.jail.capabilityInput(requireNotNull(sourceFd).fileDescriptor).use { input ->
                    PDDocument.load(input, com.pdfchemy.app.jail.JailMemory.settings()).use { doc ->
                        var images = 0
                        for (page in doc.pages) {
                            val resources = page.resources ?: continue
                            for (name in resources.xObjectNames) if (resources.isImageXObject(name)) images++
                        }
                        val signed = doc.signatureDictionaries.isNotEmpty()
                        val scenario = when { signed -> "SIGNED_OFFICIAL"; images == 0 -> "TEXT_VECTOR"; images >= doc.numberOfPages -> "SCANNED_IMAGE_HEAVY"; else -> "MIXED" }
                        org.json.JSONObject().put("pageCount", doc.numberOfPages).put("imageCount", images).put("hasSignatures", signed).put("scenario", scenario)
                            .put("recommendedQuality", if (signed || images == 0) 0.75 else if (scenario == "SCANNED_IMAGE_HEAVY") 0.25 else 0.5)
                            .put("recommendationReason", "Compression profile based on isolated document analysis.").toString()
                    }
                }
    }
}
