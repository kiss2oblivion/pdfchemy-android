package com.pdfchemy.app.sandbox

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
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
