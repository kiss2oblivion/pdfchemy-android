package com.pdfchemy.app.jail

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.StagedPdf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PdfJailClient {

    suspend fun exportModifiedPdf(
        context: Context,
        source: StagedPdf,
        destUri: Uri,
        modificationsJson: String
    ): Boolean = kotlinx.coroutines.withTimeout(com.pdfchemy.app.security.SecurityLimits.WORKER_DEADLINE_MS + 5000) { suspendCancellableCoroutine { continuation ->
        val scratch = OperationScratchBroker(context)
        var isBound = false
        var connection: ServiceConnection? = null
        var jailRef: IPdfJailService? = null
        var sourceFdRef: ParcelFileDescriptor? = null
        var targetFdRef: ParcelFileDescriptor? = null

        fun cleanup() {
            scratch.close()
            try { sourceFdRef?.close() } catch (e: Exception) {}
            try { targetFdRef?.close() } catch (e: Exception) {}
            if (isBound && connection != null) {
                try {
                    context.unbindService(connection!!)
                } catch (e: Exception) {}
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jail = IPdfJailService.Stub.asInterface(service)
                jailRef = jail
                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(source.uri, "r")
                    val targetFd = contentResolver.openFileDescriptor(destUri, "w")
                    sourceFdRef = sourceFd
                    targetFdRef = targetFd

                    if (sourceFd == null || targetFd == null) {
                        continuation.resumeWithException(Exception("Failed to open file descriptors"))
                        cleanup()
                        return
                    }

                    jail.exportModifiedPdf(sourceFd, targetFd, modificationsJson, source.sha256, source.size, scratch, object : IPdfJailCallback.Stub() {
                        override fun onSuccess(outputSizeBytes: Long) {
                            if (continuation.isActive) continuation.resume(true)
                            cleanup()
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String?) {
                            if (continuation.isActive) continuation.resumeWithException(Exception(errorMessage ?: "Export failed"))
                            cleanup()
                        }
                    })

                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                    cleanup()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (continuation.isActive) continuation.resumeWithException(Exception("Jail Service Disconnected Unexpectedly"))
                cleanup()
            }
        }

        val intent = Intent().apply { setClassName(context, "com.pdfchemy.app.jail.PdfJailService") }
        isBound = context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)

        if (!isBound) {
            cleanup()
            continuation.resumeWithException(Exception("Failed to bind to Jail Service"))
        }

        continuation.invokeOnCancellation {
            runCatching { jailRef?.abortWorker() }
            cleanup()
        }
    } }


    suspend fun compressPdf(
        context: Context,
        source: StagedPdf,
        destUri: Uri,
        targetDpi: Float = 140f,
        quality: Float = 0.5f,
        rasterizePages: Boolean = false
    ): Long = kotlinx.coroutines.withTimeout(com.pdfchemy.app.security.SecurityLimits.WORKER_DEADLINE_MS + 5000) { suspendCancellableCoroutine { continuation ->
        val scratch = OperationScratchBroker(context)
        var isBound = false
        var connection: ServiceConnection? = null
        var jailRef: IPdfJailService? = null
        var sourceFdRef: ParcelFileDescriptor? = null
        var destFdRef: ParcelFileDescriptor? = null

        fun cleanup() {
            scratch.close()
            try { sourceFdRef?.close() } catch (e: Exception) {}
            try { destFdRef?.close() } catch (e: Exception) {}
            if (isBound && connection != null) {
                try {
                    context.unbindService(connection!!)
                } catch (e: Exception) {
                    // Ignore
                }
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jailService = IPdfJailService.Stub.asInterface(service)
                jailRef = jailService
                if (jailService == null) {
                    cleanup()
                    continuation.resumeWithException(IllegalStateException("Failed to bind to PdfJailService"))
                    return
                }

                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(source.uri, "r")
                    val destFd = contentResolver.openFileDescriptor(destUri, "w")
                    sourceFdRef = sourceFd
                    destFdRef = destFd

                    if (sourceFd == null || destFd == null) {
                        cleanup()
                        continuation.resumeWithException(IllegalStateException("Failed to open file descriptors"))
                        return
                    }

                    val callback = object : IPdfJailCallback.Stub() {
                        override fun onSuccess(outputSizeBytes: Long) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(outputSizeBytes)
                            }
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(RuntimeException("Jail Error $errorCode: $errorMessage"))
                            }
                        }
                    }

                    jailService.compressPdf(sourceFd, destFd, targetDpi, quality, rasterizePages, source.sha256, source.size, scratch, callback)

                } catch (e: Exception) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(RuntimeException("PdfJailService disconnected unexpectedly"))
                }
            }
        }

        val intent = Intent().apply { setClassName(context, "com.pdfchemy.app.jail.PdfJailService") }
        isBound = context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)

        if (!isBound) {
            cleanup()
            continuation.resumeWithException(IllegalStateException("Could not bind to PdfJailService"))
        }

        continuation.invokeOnCancellation {
            runCatching { jailRef?.abortWorker() }
            cleanup()
        }
    } }

    suspend fun analyzePdf(
        context: Context,
        source: StagedPdf
    ): String = kotlinx.coroutines.withTimeout(com.pdfchemy.app.security.SecurityLimits.WORKER_DEADLINE_MS + 5000) { suspendCancellableCoroutine { continuation ->
        val scratch = OperationScratchBroker(context)
        var isBound = false
        var connection: ServiceConnection? = null
        var jailRef: IPdfJailService? = null
        var sourceFdRef: ParcelFileDescriptor? = null

        fun cleanup() {
            scratch.close()
            try { sourceFdRef?.close() } catch (e: Exception) {}
            if (isBound && connection != null) {
                try {
                    context.unbindService(connection!!)
                } catch (e: Exception) {
                    // Ignore
                }
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jailService = IPdfJailService.Stub.asInterface(service)
                jailRef = jailService
                if (jailService == null) {
                    cleanup()
                    continuation.resumeWithException(IllegalStateException("Failed to bind to PdfJailService"))
                    return
                }

                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(source.uri, "r")
                    sourceFdRef = sourceFd

                    if (sourceFd == null) {
                        cleanup()
                        continuation.resumeWithException(IllegalStateException("Failed to open file descriptor"))
                        return
                    }

                    val callback = object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(resultJson)
                            }
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(RuntimeException("Jail Error $errorCode: $errorMessage"))
                            }
                        }
                    }

                    jailService.analyzePdf(sourceFd, source.sha256, source.size, scratch, callback)

                } catch (e: Exception) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(RuntimeException("PdfJailService disconnected unexpectedly"))
                }
            }
        }

        val intent = Intent().apply { setClassName(context, "com.pdfchemy.app.jail.PdfJailService") }
        isBound = context.bindService(intent, connection!!, Context.BIND_AUTO_CREATE)

        if (!isBound) {
            cleanup()
            continuation.resumeWithException(IllegalStateException("Could not bind to PdfJailService"))
        }

        continuation.invokeOnCancellation {
            runCatching { jailRef?.abortWorker() }
            cleanup()
        }
    } }
}