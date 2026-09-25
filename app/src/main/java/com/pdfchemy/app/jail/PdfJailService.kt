package com.pdfchemy.app.jail

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Isolated process service for processing PDF documents securely.
 */
class PdfJailService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val binder = object : IPdfJailService.Stub() {
        override fun compressPdf(
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            targetDpi: Float,
            quality: Float,
            rasterizePages: Boolean,
            callback: IPdfJailCallback?
        ) {
            if (sourceFd == null || targetFd == null || callback == null) {
                callback?.onFailure(-1, "Invalid null arguments")
                return
            }

            serviceScope.launch {
                var success = false
                var outputSize = 0L
                try {
                    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(sourceFd)
                    val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(targetFd)

                    inputStream.use { streamIn ->
                        outputStream.use { streamOut ->
                            PDDocument.load(streamIn, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                                var compressedCount = 0
                                for (page in document.pages) {
                                    val resources = page.resources ?: continue
                                    for (name in resources.xObjectNames) {
                                        if (resources.isImageXObject(name)) {
                                            val xObject = resources.getXObject(name) as? PDImageXObject ?: continue
                                            try {
                                                val image = xObject.image ?: continue
                                                val newImage = JPEGFactory.createFromImage(document, image, quality, targetDpi.toInt())
                                                resources.put(name, newImage)
                                                compressedCount++
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                                document.save(streamOut)
                            }
                        }
                    }
                    outputSize = targetFd.statSize
                    success = true
                } catch (e: Exception) {
                    try { callback.onFailure(1, e.message ?: "Unknown compression error") } catch (re: RemoteException) {}
                } finally {
                    if (success) {
                        try { callback.onSuccess(outputSize) } catch (re: RemoteException) {}
                    }
                    stopSelf()
                }
            }
        }
        
        override fun analyzePdf(
            sourceFd: ParcelFileDescriptor?,
            callback: IPdfJailStringCallback?
        ) {
            if (sourceFd == null || callback == null) {
                callback?.onFailure(-1, "Invalid null arguments")
                return
            }

            serviceScope.launch {
                try {
                    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(sourceFd)
                    inputStream.use { streamIn ->
                        val doc = try {
                            PDDocument.load(streamIn, MemoryUsageSetting.setupTempFileOnly())
                        } catch (e: Exception) {
                            callback.onFailure(-1, "Not a valid PDF file.")
                            return@launch
                        }

                        doc.use { document ->
                            val pageCount = document.numberOfPages
                            var imageCount = 0
                            val hasSignatures = document.signatureDictionaries.isNotEmpty()

                            for (page in document.pages) {
                                val resources = page.resources ?: continue
                                val processedNames = mutableSetOf<String>()

                                for (name in resources.xObjectNames) {
                                    val isImage = try { resources.isImageXObject(name) } catch (e: Exception) { false }
                                    if (isImage && processedNames.add(name.name)) {
                                        imageCount++
                                    }
                                }
                            }

                            val scenarioName = when {
                                hasSignatures -> "SIGNED_OFFICIAL"
                                imageCount == 0 -> "TEXT_VECTOR"
                                imageCount >= pageCount -> "SCANNED_IMAGE_HEAVY"
                                else -> "MIXED"
                            }

                            val recommendedQuality = when (scenarioName) {
                                "SIGNED_OFFICIAL" -> 0.75f
                                "TEXT_VECTOR" -> 0.75f
                                "SCANNED_IMAGE_HEAVY" -> 0.25f
                                else -> 0.50f
                            }

                            val reason = when (scenarioName) {
                                "SIGNED_OFFICIAL" -> "This document is digitally signed or official. High-quality compression is recommended to prevent invalidating signatures or losing document integrity."
                                "TEXT_VECTOR" -> "This document is primarily text-based. Using a better quality profile is recommended as it's already well-compressed by vector graphics."
                                "SCANNED_IMAGE_HEAVY" -> "This document appears to be a scanned copy (high image count). Using maximum compression will significantly reduce file size without excessive readable quality loss."
                                else -> "This document contains a mix of text and images. Balanced compression is the safest default."
                            }

                            val json = org.json.JSONObject().apply {
                                put("pageCount", pageCount)
                                put("imageCount", imageCount)
                                put("hasSignatures", hasSignatures)
                                put("scenario", scenarioName)
                                put("recommendedQuality", recommendedQuality.toDouble())
                                put("recommendationReason", reason)
                            }.toString()

                            callback.onSuccess(json)
                        }
                    }
                } catch (e: Exception) {
                    callback.onFailure(-1, e.message ?: "Unknown error")
                }
            }
        }

        
        override fun executeEngine(
            engineName: String,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            paramsJson: String,
            callback: IPdfJailStringCallback
        ) {
            try {
                val resultJson = when (engineName) {
                    "METADATA_READ" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.readMetadata(sourceFd!!)
                    "METADATA_WRITE" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.writeOrSanitizeMetadata(sourceFd!!, targetFd!!, paramsJson)
                    "BOOKMARK_READ" -> com.pdfchemy.app.jail.engines.PdfBookmarkEngineWorker.readBookmarks(sourceFd!!)
                    "BOOKMARK_WRITE" -> com.pdfchemy.app.jail.engines.PdfBookmarkEngineWorker.writeBookmarks(sourceFd!!, targetFd!!, paramsJson)
                    "TEXT_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfTextExtractorWorker.extractText(this@PdfJailService, sourceFd!!, targetFd!!, paramsJson)
                    "FONT_INSPECT" -> com.pdfchemy.app.jail.engines.PdfFontInspectorEngineWorker.inspectFonts(sourceFd!!)
                    "ARCHIVE_INSPECT" -> com.pdfchemy.app.jail.engines.PdfArchiveValidatorEngineWorker.inspectPdfACompliance(sourceFd!!)
                    "ARCHIVE_CONVERT" -> com.pdfchemy.app.jail.engines.PdfArchiveValidatorEngineWorker.convertToPdfA(sourceFd!!, targetFd!!)
                    "LINEARIZE_CHECK" -> com.pdfchemy.app.jail.engines.PdfLinearizeEngineWorker.checkLinearized(sourceFd!!)
                    "LINEARIZE_OPTIMIZE" -> com.pdfchemy.app.jail.engines.PdfLinearizeEngineWorker.optimizeFastWebView(sourceFd!!, targetFd!!)
                    "OUTLINE_READ" -> com.pdfchemy.app.jail.engines.PdfOutlineReaderWorker.readOutline(this@PdfJailService, sourceFd!!, targetFd, paramsJson)
                    "PAGE_ORGANIZE" -> com.pdfchemy.app.jail.engines.PdfPageOrganizerWorker.reorganizePages(sourceFd!!, targetFd!!, paramsJson)
                    "COMIC_BOOK" -> com.pdfchemy.app.jail.engines.ComicBookEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "ACRO_FORM" -> com.pdfchemy.app.jail.engines.AcroFormEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "CROP" -> com.pdfchemy.app.jail.engines.PdfCropEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "REDACT" -> com.pdfchemy.app.jail.engines.PdfRedactionEngineWorker.execute(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    else -> throw IllegalArgumentException("Unknown engine: " + engineName)
                }
                val finalResult = com.pdfchemy.app.jail.engines.JailQuotas.enforceResultSize(resultJson)
                callback.onSuccess(finalResult)
            } catch (e: Exception) {
                callback.onFailure(500, e.message ?: "Unknown error in engine " + engineName)
            }
        }

        override fun exportModifiedPdf(
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            modificationsJson: String?,
            callback: IPdfJailCallback?
        ) {
            if (sourceFd == null || targetFd == null || modificationsJson == null || callback == null) {
                callback?.onFailure(-1, "Invalid null arguments")
                return
            }
            serviceScope.launch {
                try {
                    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    val typeRef = object : com.fasterxml.jackson.core.type.TypeReference<Map<Int, com.pdfchemy.app.logic.PageModification>>() {}
                    val modifications = mapper.readValue(modificationsJson, typeRef)

                    val success = PdfEditorWorker.exportModifiedPdf(this@PdfJailService, sourceFd, targetFd, modifications)
                    if (success) {
                        val outputSize = targetFd.statSize
                        callback.onSuccess(outputSize)
                    } else {
                        callback.onFailure(-1, "PdfEditorWorker failed to export modified PDF")
                    }
                } catch (e: Exception) {
                    try {
                        callback.onFailure(-1, e.message ?: "Unknown error in export")
                    } catch (re: RemoteException) {}
                } finally {
                    try { sourceFd.close() } catch (e: Exception) {}
                    try { targetFd.close() } catch (e: Exception) {}
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}

