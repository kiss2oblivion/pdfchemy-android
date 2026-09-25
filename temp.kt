package com.pdfchemy.app.sandbox

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.pdfchemy.app.logic.SanitizerAuditReport
import com.pdfchemy.app.logic.SanitizerResult
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.FileNotFoundException

object SandboxCoordinator {

    private const val HARD_TIMEOUT_MS = 60_000L // 60 seconds

    suspend fun auditDocumentThreats(context: Context, sourceUri: Uri): SanitizerAuditReport? = withContext(Dispatchers.IO) {
        val channel = Channel<SanitizerAuditReport?>()
        var workerPid = -1
        
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val pfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    if (pfd == null) {
                        channel.trySend(null)
                        return
                    }
                    sandbox.auditDocument(pfd, object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            try {
                                val json = JSONObject(resultJson ?: "{}")
                                val report = SanitizerAuditReport(
                                    threatsFound = json.optInt("threatsFound", 0),
                                    jsCount = json.optInt("jsCount", 0),
                                    launchActionsCount = json.optInt("launchActionsCount", 0),
                                    attachmentCount = json.optInt("attachmentCount", 0),
                                    uriCount = json.optInt("uriCount", 0),
                                    hasMetadata = json.optBoolean("hasMetadata", false),
                                    isClean = json.optBoolean("isClean", true),
                                    isEncrypted = json.optBoolean("isEncrypted", false),
                                    parseFailed = json.optBoolean("parseFailed", false)
                                )
                                channel.trySend(report)
                            } catch (e: Exception) {
                                channel.trySend(null)
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator audit error: $errorMessage")
                            channel.trySend(null)
                        }

                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(null)
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext null
        }

        val result = withTimeoutOrNull(HARD_TIMEOUT_MS) {
            channel.receive()
        }

        // Cleanup
        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // Ignore
        }

        if (result == null && workerPid != -1) {
            // Hard timeout or death. Treat as hostile and kill the worker boundary.
            AppLogger.e("SandboxCoordinator: Audit timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
        }

        result
    }

    suspend fun sanitizeDocument(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        purgeJs: Boolean = true,
        purgeActions: Boolean = true,
        purgeMetadata: Boolean = true
    ): SanitizerResult? = withContext(Dispatchers.IO) {
        val channel = Channel<SanitizerResult?>()
        var workerPid = -1

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourceUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destUri, "w")
                    
                    if (inputPfd == null || outputPfd == null) {
                        channel.trySend(null)
                        return
                    }

                    sandbox.sanitizeDocument(inputPfd, outputPfd, purgeJs, purgeActions, purgeMetadata, object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            try {
                                val json = JSONObject(resultJson ?: "{}")
                                val res = SanitizerResult(
                                    isSuccess = json.optBoolean("isSuccess", false),
                                    threatsRemoved = json.optInt("threatsRemoved", 0),
                                    jsRemoved = json.optInt("jsRemoved", 0),
                                    actionsRemoved = json.optInt("actionsRemoved", 0),
                                    metadataRemoved = json.optBoolean("metadataRemoved", false),
                                    attachmentsRemoved = json.optInt("attachmentsRemoved", 0)
                                )
                                channel.trySend(res)
                            } catch (e: Exception) {
                                channel.trySend(null)
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator sanitize error: $errorMessage")
                            channel.trySend(null)
                        }

                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(null)
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext null
        }

        val result = withTimeoutOrNull(HARD_TIMEOUT_MS) {
            channel.receive()
        }

        try {
            context.unbindService(connection)
        } catch (e: Exception) {
            // Ignore
        }

        if (result == null && workerPid != -1) {
            AppLogger.e("SandboxCoordinator: Sanitize timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
        }

        result
    }

    suspend fun hasExecutableThreats(context: Context, pdfUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val report = auditDocumentThreats(context, pdfUri) ?: return@withContext true // true = blocked if crashed
        report.jsCount > 0 || report.launchActionsCount > 0 || report.attachmentCount > 0 || report.isEncrypted || report.parseFailed
    }

    suspend fun checkVanguardThreat(context: Context, pdfUri: Uri): com.pdfchemy.app.logic.VanguardThreatResult = withContext(Dispatchers.IO) {
        val report = auditDocumentThreats(context, pdfUri)
        if (report == null || report.parseFailed) {
            return@withContext com.pdfchemy.app.logic.VanguardThreatResult.ParseFailed(pdfUri)
        }
        if (report.isEncrypted) {
            return@withContext com.pdfchemy.app.logic.VanguardThreatResult.EncryptedCannotVerify(pdfUri)
        }
        if (report.jsCount > 0 || report.launchActionsCount > 0 || report.attachmentCount > 0) {
            return@withContext com.pdfchemy.app.logic.VanguardThreatResult.ExecutableThreat(report)
        }
        com.pdfchemy.app.logic.VanguardThreatResult.Clean
    }

    suspend fun smartRedact(
        context: Context,
        pdfUri: Uri,
        destUri: Uri,
        patterns: List<com.pdfchemy.app.logic.RedactPattern>,
        config: com.pdfchemy.app.logic.RedactionConfig = com.pdfchemy.app.logic.RedactionConfig(isBlackout = true, defaultOverlayText = "REDACTED", forensicSanitize = true)
    ): Result<Int> {
        val combinedRegex = patterns.joinToString(separator = "|") { it.regex }
        val searchResult = searchRedactionTargets(context, pdfUri, combinedRegex, isRegex = true)
        if (searchResult.isFailure) return Result.failure(searchResult.exceptionOrNull() ?: Exception("Unknown error"))
        
        val boxes = searchResult.getOrNull() ?: emptyList()
        if (boxes.isEmpty()) return Result.success(0)
        
        return applyRedactions(context, pdfUri, destUri, boxes, config)
    }



    suspend fun pdfToEpub(
        context: Context,
        sourcePdfUri: Uri,
        destEpubUri: Uri,
        bookTitle: String = "Untitled E-Book",
        authorName: String = "Unknown Author"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val channel = Channel<Result<Boolean>?>()
        var workerPid = -1

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destEpubUri, "w")
                    
                    if (inputPfd == null || outputPfd == null) {
                        channel.trySend(Result.failure(IllegalArgumentException("Cannot open file descriptors")))
                        return
                    }

                    sandbox.convertPdfToEpub(inputPfd, outputPfd, bookTitle, authorName, object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            channel.trySend(Result.success(true))
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator pdfToEpub error: $errorMessage")
                            channel.trySend(Result.failure(Exception(errorMessage)))
                        }

                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(Result.failure(Exception("Service disconnected")))
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext Result.failure(Exception("Failed to bind service"))
        }

        // EPUB conversion can take a while
        val result = withTimeoutOrNull(180_000L) {
            channel.receive()
        }

        try { context.unbindService(connection) } catch (_: Exception) {}

        if (result == null && workerPid != -1) {
            AppLogger.e("SandboxCoordinator: pdfToEpub timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
            return@withContext Result.failure(Exception("pdfToEpub timed out"))
        }

        if (result?.isSuccess == true) {
            val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
            historyRepo.addHistoryItem(
                destEpubUri,
                com.pdfchemy.app.utils.FileUtils.getFileName(context, destEpubUri) ?: "book.epub",
                "PDF to EPUB 3.0"
            )
        }

        result ?: Result.failure(Exception("Unknown pdfToEpub error"))
    }

    suspend fun epubToPdf(
        context: Context,
        sourceEpubUri: Uri,
        destPdfUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val channel = Channel<Result<Boolean>?>()
        var workerPid = -1

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourceEpubUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")
                    
                    if (inputPfd == null || outputPfd == null) {
                        channel.trySend(Result.failure(IllegalArgumentException("Cannot open file descriptors")))
                        return
                    }

                    sandbox.convertEpubToPdf(inputPfd, outputPfd, object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            channel.trySend(Result.success(true))
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator epubToPdf error: $errorMessage")
                            channel.trySend(Result.failure(Exception(errorMessage)))
                        }

                        override fun onProgress(progress: Int, message: String?) {
                            // Extract current and total if possible, or just emit progress percentage
                            // Progress is 0-100 from PdfWorkerService
                            onProgress(progress, 100)
                        }
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(Result.failure(Exception("Service disconnected")))
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext Result.failure(Exception("Failed to bind service"))
        }

        // EPUB conversion can take a while
        val result = withTimeoutOrNull(180_000L) {
            channel.receive()
        }

        try { context.unbindService(connection) } catch (_: Exception) {}

        if (result == null && workerPid != -1) {
            AppLogger.e("SandboxCoordinator: epubToPdf timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
            return@withContext Result.failure(Exception("epubToPdf timed out"))
        }

        if (result?.isSuccess == true) {
            val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "book.pdf",
                "EPUB to PDF"
            )
        }

        result ?: Result.failure(Exception("Unknown epubToPdf error"))
    }

    suspend fun searchRedactionTargets(
        context: Context,
        pdfUri: Uri,
        query: String,
        isRegex: Boolean = false
    ): Result<List<com.pdfchemy.app.logic.RedactionBox>> = withContext(Dispatchers.IO) {
        val channel = Channel<Result<List<com.pdfchemy.app.logic.RedactionBox>>?>()
        var workerPid = -1

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(pdfUri, "r")
                    if (inputPfd == null) {
                        channel.trySend(Result.failure(IllegalArgumentException("Cannot open file")))
                        return
                    }

                    sandbox.searchRedactionTargets(inputPfd, query, isRegex, object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            try {
                                val json = JSONObject(resultJson ?: "{}")
                                val boxesArray = json.optJSONArray("boxes")
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
                                channel.trySend(Result.success(boxes))
                            } catch (e: Exception) {
                                channel.trySend(Result.failure(e))
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator search error: $errorMessage")
                            channel.trySend(Result.failure(Exception(errorMessage)))
                        }

                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(Result.failure(Exception("Service disconnected")))
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext Result.failure(Exception("Failed to bind service"))
        }

        val result = withTimeoutOrNull(HARD_TIMEOUT_MS) {
            channel.receive()
        }

        try { context.unbindService(connection) } catch (_: Exception) {}

        if (result == null && workerPid != -1) {
            AppLogger.e("SandboxCoordinator: Search timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
            return@withContext Result.failure(Exception("Search timed out"))
        }

        result ?: Result.failure(Exception("Unknown search error"))
    }

    suspend fun applyRedactions(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        boxes: List<com.pdfchemy.app.logic.RedactionBox>,
        config: com.pdfchemy.app.logic.RedactionConfig
    ): Result<Int> = withContext(Dispatchers.IO) {
        val channel = Channel<Result<Int>?>()
        var workerPid = -1

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val sandbox = IPdfSandboxService.Stub.asInterface(service)
                try {
                    workerPid = sandbox.workerPid
                    val inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    val outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")
                    
                    if (inputPfd == null || outputPfd == null) {
                        channel.trySend(Result.failure(IllegalArgumentException("Cannot open file descriptors")))
                        return
                    }

                    val configJson = JSONObject().apply {
                        put("isBlackout", config.isBlackout)
                        put("forensicSanitize", config.forensicSanitize)
                        put("defaultOverlayText", config.defaultOverlayText)
                        
                        val boxesArray = org.json.JSONArray()
                        boxes.forEach { box ->
                            val obj = JSONObject()
                            obj.put("pageIndex", box.pageIndex)
                            obj.put("left", box.normalizedRect.left.toDouble())
                            obj.put("top", box.normalizedRect.top.toDouble())
                            obj.put("right", box.normalizedRect.right.toDouble())
                            obj.put("bottom", box.normalizedRect.bottom.toDouble())
                            obj.put("overlayLabel", box.overlayLabel)
                            boxesArray.put(obj)
                        }
                        put("boxes", boxesArray)
                    }

                    sandbox.redactDocument(inputPfd, outputPfd, configJson.toString(), object : IPdfSandboxCallback.Stub() {
                        override fun onSuccess(resultJson: String?) {
                            try {
                                val json = JSONObject(resultJson ?: "{}")
                                channel.trySend(Result.success(json.optInt("redactedCount", 0)))
                            } catch (e: Exception) {
                                channel.trySend(Result.failure(e))
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            AppLogger.e("SandboxCoordinator redact error: $errorMessage")
                            channel.trySend(Result.failure(Exception(errorMessage)))
                        }

                        override fun onProgress(progress: Int, message: String?) {}
                    })
                } catch (e: Exception) {
                    channel.trySend(Result.failure(e))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                channel.trySend(Result.failure(Exception("Service disconnected")))
            }
        }

        val intent = Intent(context, PdfWorkerService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        if (!bound) {
            return@withContext Result.failure(Exception("Failed to bind service"))
        }

        // Redaction is slow, give it more time (3 minutes max)
        val result = withTimeoutOrNull(180_000L) {
            channel.receive()
        }

        try { context.unbindService(connection) } catch (_: Exception) {}

        if (result == null && workerPid != -1) {
            AppLogger.e("SandboxCoordinator: Redaction timed out or disconnected. Killing worker process PID $workerPid")
            Process.killProcess(workerPid)
            return@withContext Result.failure(Exception("Redaction timed out"))
        }
        
        if (result?.isSuccess == true) {
            val count = result.getOrNull() ?: 0
            val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "redacted.pdf",
                "Sanitized & Redacted PDF ($count elements)"
            )
        }

        result ?: Result.failure(Exception("Unknown redaction error"))
    }
}
