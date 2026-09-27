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
        return@withContext try {
            com.pdfchemy.app.logic.PdfSanitizerEngine.auditDocumentThreats(context, sourceUri)
        } catch (e: Exception) {
            AppLogger.e("SandboxCoordinator audit error", e)
            null
        }
    }

    suspend fun sanitizeDocument(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        purgeJs: Boolean = true,
        purgeActions: Boolean = true,
        purgeMetadata: Boolean = true
    ): SanitizerResult? = withContext(Dispatchers.IO) {
        return@withContext try {
            com.pdfchemy.app.logic.PdfSanitizerEngine.sanitizeDocument(context, sourceUri, destUri, purgeJs, purgeActions, purgeMetadata)
        } catch (e: Exception) {
            AppLogger.e("SandboxCoordinator sanitize error", e)
            null
        }
    }

    suspend fun hasExecutableThreats(context: Context, pdfUri: Uri): Boolean = withContext(Dispatchers.IO) {
        return@withContext com.pdfchemy.app.logic.PdfSanitizerEngine.hasExecutableThreats(context, pdfUri)
    }

    suspend fun checkVanguardThreat(context: Context, pdfUri: Uri): com.pdfchemy.app.logic.VanguardThreatResult = withContext(Dispatchers.IO) {
        return@withContext com.pdfchemy.app.logic.PdfSanitizerEngine.checkVanguardThreat(context, pdfUri)
    }

    suspend fun smartRedact(
        context: Context,
        pdfUri: Uri,
        destUri: Uri,
        patterns: List<com.pdfchemy.app.logic.RedactPattern>,
        config: com.pdfchemy.app.logic.RedactionConfig = com.pdfchemy.app.logic.RedactionConfig(isBlackout = true, defaultOverlayText = "REDACTED", forensicSanitize = true)
    ): Result<Int> {
        val result = com.pdfchemy.app.logic.PdfRedactionEngine.smartRedact(context, pdfUri, destUri, patterns, config)
        if (result.isSuccess) {
            val count = result.getOrNull() ?: 0
            val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
            historyRepo.addHistoryItem(
                destUri,
                com.pdfchemy.app.utils.FileUtils.getFileName(context, destUri) ?: "redacted.pdf",
                "Sanitized & Redacted PDF (\ elements)"
            )
        }
        return result
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
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourcePdfUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destEpubUri, "w")
                    
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
                } finally {
                    try { inputPfd?.close() } catch (e: Exception) {}
                    try { outputPfd?.close() } catch (e: Exception) {}
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
        var timeoutOrCancel = true
        val result = try {
            val res = withTimeoutOrNull(180_000L) {
                channel.receive()
            }
            if (res != null) {
                timeoutOrCancel = false
            }
            res
        } finally {
            try { context.unbindService(connection) } catch (_: Exception) {}
            if (timeoutOrCancel && workerPid != -1) {
                AppLogger.e("SandboxCoordinator: pdfToEpub timed out or disconnected. Killing worker process PID $workerPid")
                Process.killProcess(workerPid)
            }
        }

        if (result == null) {
            return@withContext Result.failure(Exception("pdfToEpub timed out or cancelled"))
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
                var inputPfd: ParcelFileDescriptor? = null
                var outputPfd: ParcelFileDescriptor? = null
                try {
                    workerPid = sandbox.workerPid
                    inputPfd = context.contentResolver.openFileDescriptor(sourceEpubUri, "r")
                    outputPfd = context.contentResolver.openFileDescriptor(destPdfUri, "w")
                    
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
                } finally {
                    try { inputPfd?.close() } catch (e: Exception) {}
                    try { outputPfd?.close() } catch (e: Exception) {}
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
        var timeoutOrCancel = true
        val result = try {
            val res = withTimeoutOrNull(180_000L) {
                channel.receive()
            }
            if (res != null) {
                timeoutOrCancel = false
            }
            res
        } finally {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {}
        
            if (timeoutOrCancel && workerPid != -1) {
                AppLogger.e("SandboxCoordinator: epubToPdf timed out or disconnected. Killing worker process PID $workerPid")
                Process.killProcess(workerPid)
            }
        }

        if (result == null) {
            return@withContext Result.failure(Exception("epubToPdf timed out or cancelled"))
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
        return@withContext com.pdfchemy.app.logic.PdfRedactionEngine.searchRedactionTargets(context, pdfUri, query, isRegex)
    }

    suspend fun applyRedactions(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        boxes: List<com.pdfchemy.app.logic.RedactionBox>,
        config: com.pdfchemy.app.logic.RedactionConfig
    ): Result<Int> = withContext(Dispatchers.IO) {
        val result = com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(context, sourcePdfUri, destPdfUri, boxes, config)
        if (result.isSuccess) {
            val count = result.getOrNull() ?: 0
            val historyRepo = com.pdfchemy.app.logic.HistoryRepository(context)
            historyRepo.addHistoryItem(
                destPdfUri,
                com.pdfchemy.app.utils.FileUtils.getFileName(context, destPdfUri) ?: "redacted.pdf",
                "Sanitized & Redacted PDF (\ elements)"
            )
        }
        return@withContext result
    }
}
