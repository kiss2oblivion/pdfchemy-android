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

    private val watchdogHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val activeTasks = java.util.concurrent.atomic.AtomicInteger(0)
    private val watchdogRunnable = Runnable {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun startTask() {
        activeTasks.incrementAndGet()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 120_000L) // 2 minutes hard limit
    }

    private fun endTask() {
        if (activeTasks.decrementAndGet() == 0) {
            watchdogHandler.removeCallbacks(watchdogRunnable)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

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

            startTask()
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
                    endTask()
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

            startTask()
            serviceScope.launch {
                var jsonResult: String? = null
                var errorMsg: String? = null
                try {
                    val inputStream = ParcelFileDescriptor.AutoCloseInputStream(sourceFd)
                    inputStream.use { streamIn ->
                        val doc = try {
                            PDDocument.load(streamIn, MemoryUsageSetting.setupTempFileOnly())
                        } catch (e: Exception) {
                            errorMsg = "Not a valid PDF file."
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

                            jsonResult = org.json.JSONObject().apply {
                                put("pageCount", pageCount)
                                put("imageCount", imageCount)
                                put("hasSignatures", hasSignatures)
                                put("scenario", scenarioName)
                                put("recommendedQuality", recommendedQuality.toDouble())
                                put("recommendationReason", reason)
                            }.toString()
                        }
                    }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Unknown error"
                } finally {
                    try { sourceFd.close() } catch (e: Exception) {}
                    if (errorMsg != null) {
                        try { callback.onFailure(-1, errorMsg) } catch (re: Exception) {}
                    } else if (jsonResult != null) {
                        try { callback.onSuccess(jsonResult) } catch (re: Exception) {}
                    }
                    endTask()
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
            startTask()
            var finalResult: String? = null
            var errorMsg: String? = null
            try {
                val resultJson = when (engineName) {
                    "METADATA_READ" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.readMetadata(sourceFd!!)
                    "METADATA_WRITE" -> com.pdfchemy.app.jail.engines.PdfMetadataEngineWorker.writeOrSanitizeMetadata(sourceFd!!, targetFd!!, paramsJson)
                    "DELETE_PAGES" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.deletePages(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "ROTATE" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.rotatePdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "PROTECT" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.protectPdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "UNLOCK" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.unlockPdf(this@PdfJailService, sourceFd, targetFd, paramsJson)
                    "CHECK_ENCRYPTION" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.checkEncryption(this@PdfJailService, sourceFd)
                    "GET_PAGE_COUNT" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.getPageCount(this@PdfJailService, sourceFd!!)
                    "PLAN_SPLIT_BLANK" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.planSplitByBlankPages(this@PdfJailService, sourceFd, paramsJson)
                    "PLAN_SPLIT_BOOKMARKS" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.planSplitByBookmarks(this@PdfJailService, sourceFd)
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
                    "ATTACHMENT_LIST" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.listAttachments(sourceFd!!)
                    "ATTACHMENT_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.extractAttachment(sourceFd!!, targetFd!!, paramsJson)
                    "ATTACHMENT_REMOVE" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.removeAttachment(sourceFd!!, targetFd!!, paramsJson)
                    "BOOKLET_GENERATE" -> com.pdfchemy.app.jail.engines.PdfBookletEngineWorker.generateBooklet(sourceFd!!, targetFd!!, paramsJson)
                    "DESKEW" -> com.pdfchemy.app.jail.engines.PdfDeskewEngineWorker.deskew(sourceFd!!, targetFd!!, paramsJson)
                    "TABLE_EXTRACT" -> com.pdfchemy.app.jail.engines.PdfTableExtractorWorker.extractText(sourceFd!!, targetFd, paramsJson)
                    "WATERMARK" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyWatermark(sourceFd!!, targetFd!!, paramsJson)
                    "PAGE_NUMBERS" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyPageNumbers(sourceFd!!, targetFd!!, paramsJson)
                    "BATES_STAMP" -> com.pdfchemy.app.jail.engines.PdfStampAndNumberWorker.applyBatesStamping(sourceFd!!, targetFd!!, paramsJson)
                    "SANITIZE_AUDIT" -> com.pdfchemy.app.jail.engines.PdfSanitizerEngineWorker.audit(sourceFd!!)
                    "SANITIZE_CLEAN" -> com.pdfchemy.app.jail.engines.PdfSanitizerEngineWorker.sanitize(sourceFd!!, targetFd!!, paramsJson)
                    "REPAIR_DIAGNOSE" -> com.pdfchemy.app.jail.engines.PdfRepairEngineWorker.diagnose(sourceFd!!)
                    "REPAIR_APPLY" -> com.pdfchemy.app.jail.engines.PdfRepairEngineWorker.repair(sourceFd!!, targetFd!!)
                    "NUP_GENERATE" -> com.pdfchemy.app.jail.engines.PdfNUpEngineWorker.generateNUpPdf(sourceFd!!, targetFd!!, paramsJson)
                    "OCR_PROCESS" -> com.pdfchemy.app.jail.engines.PdfOcrEngineWorker.createSearchablePdf(this@PdfJailService, sourceFd!!, targetFd!!)
                    "SIGNATURE_APPLY" -> com.pdfchemy.app.jail.engines.SignatureEngineWorker.applySignatures(sourceFd!!, targetFd!!, paramsJson)
                    "SIGNATURE_DIGITAL" -> com.pdfchemy.app.jail.engines.SignatureEngineWorker.applyDigitalSignature(sourceFd!!, targetFd!!, paramsJson)
                    else -> throw IllegalArgumentException("Unknown engine: " + engineName)
                }
                finalResult = com.pdfchemy.app.jail.engines.JailQuotas.enforceResultSize(resultJson)
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error in engine " + engineName
            } finally {
                try { sourceFd?.close() } catch (e: Exception) {}
                try { targetFd?.close() } catch (e: Exception) {}
                if (errorMsg != null) {
                    try { callback.onFailure(500, errorMsg) } catch (e: Exception) {}
                } else if (finalResult != null) {
                    try { callback.onSuccess(finalResult) } catch (e: Exception) {}
                }
                endTask()
            }
        }


        override fun executeEngineExtra(
            engineName: String,
            sourceFd: ParcelFileDescriptor?,
            targetFd: ParcelFileDescriptor?,
            extraFd: ParcelFileDescriptor?,
            paramsJson: String,
            callback: IPdfJailStringCallback
        ) {
            startTask()
            var finalResult: String? = null
            var errorMsg: String? = null
            try {
                val resultJson = when (engineName) {
                    "ATTACHMENT_EMBED" -> com.pdfchemy.app.jail.engines.PdfAttachmentEngineWorker.embedAttachment(sourceFd!!, targetFd!!, extraFd!!, paramsJson)
                    else -> throw IllegalArgumentException("Unknown engine for extra Fd: " + engineName)
                }
                finalResult = com.pdfchemy.app.jail.engines.JailQuotas.enforceResultSize(resultJson)
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error in engine " + engineName
            } finally {
                try { sourceFd?.close() } catch (e: Exception) {}
                try { targetFd?.close() } catch (e: Exception) {}
                try { extraFd?.close() } catch (e: Exception) {}
                if (errorMsg != null) {
                    try { callback.onFailure(500, errorMsg) } catch (e: Exception) {}
                } else if (finalResult != null) {
                    try { callback.onSuccess(finalResult) } catch (e: Exception) {}
                }
                endTask()
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
            startTask()
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
                    endTask()
                }
            }
        }

        override fun executeEngineBatch(
            engineName: String,
            sourceFds: Array<out ParcelFileDescriptor>?,
            targetFds: Array<out ParcelFileDescriptor>?,
            paramsJson: String?,
            callback: com.pdfchemy.app.jail.IPdfJailStringCallback
        ) {
            val nonNullSource = sourceFds ?: emptyArray()
            val nonNullTarget = targetFds ?: emptyArray()
            val jsonParams = paramsJson ?: "{}"
            
            startTask()
            serviceScope.launch {
                var finalResult: String? = null
                var errorMsg: String? = null
                try {
                    val result = when (engineName) {
                        "MERGE" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.mergePdfs(this@PdfJailService, nonNullSource, nonNullTarget.firstOrNull())
                        "SPLIT" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.splitPdf(this@PdfJailService, nonNullSource.firstOrNull(), nonNullTarget, jsonParams)
                        "PDF_TO_IMAGES" -> com.pdfchemy.app.jail.engines.PdfManipulatorWorker.pdfToImages(this@PdfJailService, nonNullSource.firstOrNull(), nonNullTarget, jsonParams)
                        else -> org.json.JSONObject().put("success", false).put("error", "Unknown batch engine: $engineName").toString()
                    }
                    finalResult = com.pdfchemy.app.jail.engines.JailQuotas.enforceResultSize(result)
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Unknown batch error"
                } finally {
                    nonNullSource.forEach { try { it.close() } catch (e: Exception) {} }
                    nonNullTarget.forEach { try { it.close() } catch (e: Exception) {} }
                    if (errorMsg != null) {
                        try { callback.onFailure(-1, errorMsg) } catch (e: Exception) {}
                    } else if (finalResult != null) {
                        try { callback.onSuccess(finalResult) } catch (e: Exception) {}
                    }
                    endTask()
                }
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}

