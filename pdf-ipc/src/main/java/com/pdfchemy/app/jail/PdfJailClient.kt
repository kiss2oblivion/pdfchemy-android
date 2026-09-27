package com.pdfchemy.app.jail

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PdfJailClient {
    suspend fun exportModifiedPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        modificationsJson: String,
        expectedSha256: String = "",
        expectedSize: Long = -1L
    ): Boolean = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null
        var sourceFdRef: ParcelFileDescriptor? = null
        var targetFdRef: ParcelFileDescriptor? = null
        
        fun cleanup() {
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
                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(sourceUri, "r")
                    val targetFd = contentResolver.openFileDescriptor(destUri, "w")
                    sourceFdRef = sourceFd
                    targetFdRef = targetFd

                    if (sourceFd == null || targetFd == null) {
                        continuation.resumeWithException(Exception("Failed to open file descriptors"))
                        cleanup()
                        return
                    }

                    jail.exportModifiedPdf(sourceFd, targetFd, modificationsJson, expectedSha256, expectedSize, object : IPdfJailCallback.Stub() {
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
            cleanup()
        }
    }


    suspend fun compressPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetDpi: Float = 140f,
        quality: Float = 0.5f,
        rasterizePages: Boolean = false,
        expectedSha256: String = "",
        expectedSize: Long = -1L
    ): Long = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null
        var sourceFdRef: ParcelFileDescriptor? = null
        var destFdRef: ParcelFileDescriptor? = null
        
        fun cleanup() {
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
                if (jailService == null) {
                    cleanup()
                    continuation.resumeWithException(IllegalStateException("Failed to bind to PdfJailService"))
                    return
                }

                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(sourceUri, "r")
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

                    jailService.compressPdf(sourceFd, destFd, targetDpi, quality, rasterizePages, expectedSha256, expectedSize, callback)

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
            cleanup()
        }
    }

    suspend fun analyzePdf(
        context: Context,
        sourceUri: Uri,
        expectedSha256: String = "",
        expectedSize: Long = -1L
    ): String = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null
        var sourceFdRef: ParcelFileDescriptor? = null
        
        fun cleanup() {
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
                if (jailService == null) {
                    cleanup()
                    continuation.resumeWithException(IllegalStateException("Failed to bind to PdfJailService"))
                    return
                }

                try {
                    val contentResolver = context.contentResolver
                    val sourceFd = contentResolver.openFileDescriptor(sourceUri, "r")
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

                    jailService.analyzePdf(sourceFd, expectedSha256, expectedSize, callback)

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
            cleanup()
        }
    }
}
