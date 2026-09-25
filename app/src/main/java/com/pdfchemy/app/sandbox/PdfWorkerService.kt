package com.pdfchemy.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.logic.PdfRedactionEngine
import com.pdfchemy.app.logic.PdfSanitizerEngine
import com.pdfchemy.app.logic.PdfToEpubEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

class PdfWorkerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : IPdfSandboxService.Stub() {
        override fun getWorkerPid(): Int {
            return Process.myPid()
        }

        override fun auditDocument(inputPfd: ParcelFileDescriptor?, callback: IPdfSandboxCallback?) {
            if (inputPfd == null || callback == null) return
            serviceScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        val report = PdfSanitizerEngine.auditDocumentThreats(this@PdfWorkerService, inputStream)
                        val json = JSONObject()
                        json.put("threatsFound", report.threatsFound)
                        json.put("jsCount", report.jsCount)
                        json.put("launchActionsCount", report.launchActionsCount)
                        json.put("attachmentCount", report.attachmentCount)
                        json.put("uriCount", report.uriCount)
                        json.put("hasMetadata", report.hasMetadata)
                        json.put("isClean", report.isClean)
                        json.put("isEncrypted", report.isEncrypted)
                        json.put("parseFailed", report.parseFailed)
                        callback.onSuccess(json.toString())
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown audit error")
                }
            }
        }

        override fun sanitizeDocument(
            inputPfd: ParcelFileDescriptor?,
            outputPfd: ParcelFileDescriptor?,
            purgeJs: Boolean,
            purgeActions: Boolean,
            purgeMetadata: Boolean,
            callback: IPdfSandboxCallback?
        ) {
            if (inputPfd == null || outputPfd == null || callback == null) return
            serviceScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        ParcelFileDescriptor.AutoCloseOutputStream(outputPfd).use { outputStream ->
                            val result = PdfSanitizerEngine.sanitizeDocument(
                                context = this@PdfWorkerService,
                                inputStream = inputStream,
                                outStream = outputStream,
                                purgeJs = purgeJs,
                                purgeActions = purgeActions,
                                purgeMetadata = purgeMetadata,
                                purgeAttachments = true
                            )
                            val json = JSONObject()
                            json.put("isSuccess", result.isSuccess)
                            json.put("threatsRemoved", result.threatsRemoved)
                            json.put("jsRemoved", result.jsRemoved)
                            json.put("actionsRemoved", result.actionsRemoved)
                            json.put("metadataRemoved", result.metadataRemoved)
                            json.put("attachmentsRemoved", result.attachmentsRemoved)
                            callback.onSuccess(json.toString())
                        }
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown sanitize error")
                }
            }
        }

        override fun searchRedactionTargets(
            inputPfd: ParcelFileDescriptor?,
            query: String?,
            isRegex: Boolean,
            callback: IPdfSandboxCallback?
        ) {
            if (inputPfd == null || query == null || callback == null) return
            serviceScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        val result = PdfRedactionEngine.searchRedactionTargets(
                            context = this@PdfWorkerService,
                            pdfUri = android.net.Uri.EMPTY,
                            query = query,
                            isRegex = isRegex
                        )
                        if (result.isSuccess) {
                            val boxes = result.getOrNull() ?: emptyList()
                            val jsonArray = org.json.JSONArray()
                            for (box in boxes) {
                                val obj = JSONObject()
                                obj.put("pageIndex", box.pageIndex)
                                obj.put("left", box.normalizedRect.left.toDouble())
                                obj.put("top", box.normalizedRect.top.toDouble())
                                obj.put("right", box.normalizedRect.right.toDouble())
                                obj.put("bottom", box.normalizedRect.bottom.toDouble())
                                obj.put("overlayLabel", box.overlayLabel)
                                jsonArray.put(obj)
                            }
                            val response = JSONObject()
                            response.put("boxes", jsonArray)
                            callback.onSuccess(response.toString())
                        } else {
                            callback.onError(result.exceptionOrNull()?.message ?: "Search failed")
                        }
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown search error")
                }
            }
        }

        override fun redactDocument(
            inputPfd: ParcelFileDescriptor?,
            outputPfd: ParcelFileDescriptor?,
            configJson: String?,
            callback: IPdfSandboxCallback?
        ) {
            if (inputPfd == null || outputPfd == null || configJson == null || callback == null) return
            serviceScope.launch {
                try {
                    val configObj = JSONObject(configJson)
                    val isBlackout = configObj.optBoolean("isBlackout", true)
                    val forensicSanitize = configObj.optBoolean("forensicSanitize", true)
                    val defaultOverlayText = configObj.optString("defaultOverlayText", "")
                    val boxesArray = configObj.optJSONArray("boxes")
                    
                    val boxes = mutableListOf<com.pdfchemy.app.logic.RedactionBox>()
                    if (boxesArray != null) {
                        for (i in 0 until boxesArray.length()) {
                            val obj = boxesArray.getJSONObject(i)
                            boxes.add(
                                com.pdfchemy.app.logic.RedactionBox(
                                    pageIndex = obj.getInt("pageIndex"),
                                    normalizedRect = android.graphics.RectF(
                                        obj.getDouble("left").toFloat(),
                                        obj.getDouble("top").toFloat(),
                                        obj.getDouble("right").toFloat(),
                                        obj.getDouble("bottom").toFloat()
                                    ),
                                    overlayLabel = obj.optString("overlayLabel", "")
                                )
                            )
                        }
                    }

                    val config = com.pdfchemy.app.logic.RedactionConfig(
                        isBlackout = isBlackout,
                        forensicSanitize = forensicSanitize,
                        defaultOverlayText = defaultOverlayText
                    )

                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        ParcelFileDescriptor.AutoCloseOutputStream(outputPfd).use { outputStream ->
                            val result = PdfRedactionEngine.applyRedactions(
                                context = this@PdfWorkerService,
                                sourceUri = android.net.Uri.EMPTY,
                                destUri = android.net.Uri.EMPTY,
                                redactions = boxes,
                                config = config
                            )
                            if (result.isSuccess) {
                                val count = result.getOrNull() ?: 0
                                val response = JSONObject()
                                response.put("redactedCount", count)
                                callback.onSuccess(response.toString())
                            } else {
                                callback.onError(result.exceptionOrNull()?.message ?: "Redaction failed")
                            }
                        }
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown redaction error")
                }
            }
        }

        override fun convertPdfToEpub(
            inputPfd: ParcelFileDescriptor?,
            outputPfd: ParcelFileDescriptor?,
            title: String?,
            author: String?,
            callback: IPdfSandboxCallback?
        ) {
            if (inputPfd == null || outputPfd == null || callback == null) return
            serviceScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        ParcelFileDescriptor.AutoCloseOutputStream(outputPfd).use { outputStream ->
                            val result = com.pdfchemy.app.logic.PdfToEpubEngine.pdfToEpub(
                                context = this@PdfWorkerService,
                                inputStream = inputStream,
                                outputStream = outputStream,
                                bookTitle = title ?: "Untitled E-Book",
                                authorName = author ?: "Unknown Author"
                            )
                            if (result.isSuccess) {
                                callback.onSuccess("{}")
                            } else {
                                callback.onError(result.exceptionOrNull()?.message ?: "PDF to EPUB failed")
                            }
                        }
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown PDF to EPUB error")
                }
            }
        }

        override fun convertEpubToPdf(
            inputPfd: ParcelFileDescriptor?,
            outputPfd: ParcelFileDescriptor?,
            callback: IPdfSandboxCallback?
        ) {
            if (inputPfd == null || outputPfd == null || callback == null) return
            serviceScope.launch {
                try {
                    ParcelFileDescriptor.AutoCloseInputStream(inputPfd).use { inputStream ->
                        ParcelFileDescriptor.AutoCloseOutputStream(outputPfd).use { outputStream ->
                            val result = com.pdfchemy.app.logic.PdfToEpubEngine.epubToPdf(
                                context = this@PdfWorkerService,
                                inputStream = inputStream,
                                outputStream = outputStream,
                                onProgress = { current, total ->
                                    val progress = if (total > 0) (current * 100) / total else 0
                                    callback.onProgress(progress, "Rendering chapter $current of $total")
                                }
                            )
                            if (result.isSuccess) {
                                callback.onSuccess("{}")
                            } else {
                                callback.onError(result.exceptionOrNull()?.message ?: "EPUB to PDF failed")
                            }
                        }
                    }
                } catch (e: Exception) {
                    callback.onError(e.message ?: "Unknown EPUB to PDF error")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
