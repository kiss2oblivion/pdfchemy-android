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
        modificationsJson: String
    ): Boolean = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
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

                    if (sourceFd == null || targetFd == null) {
                        continuation.resumeWithException(Exception("Failed to open file descriptors"))
                        sourceFd?.close()
                        targetFd?.close()
                        cleanup()
                        return
                    }

                    jail.exportModifiedPdf(sourceFd, targetFd, modificationsJson, object : IPdfJailCallback.Stub() {
                        override fun onSuccess(outputSizeBytes: Long) {
                            if (continuation.isActive) continuation.resume(true)
                            cleanup()
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String?) {
                            if (continuation.isActive) continuation.resumeWithException(Exception(errorMessage ?: "Export failed"))
                            cleanup()
                        }
                    })
                    
                    sourceFd.close()
                    targetFd.close()
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

        val intent = Intent(context, PdfJailService::class.java)
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
        rasterizePages: Boolean = false
    ): Long = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
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

                    if (sourceFd == null || destFd == null) {
                        sourceFd?.close()
                        destFd?.close()
                        cleanup()
                        continuation.resumeWithException(IllegalStateException("Failed to open file descriptors"))
                        return
                    }

                    val callback = object : IPdfJailCallback.Stub() {
                        override fun onSuccess(outputSizeBytes: Long) {
                            sourceFd.close()
                            destFd.close()
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(outputSizeBytes)
                            }
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            sourceFd.close()
                            destFd.close()
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(RuntimeException("Jail Error $errorCode: $errorMessage"))
                            }
                        }
                    }

                    jailService.compressPdf(sourceFd, destFd, targetDpi, quality, rasterizePages, callback)

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

        val intent = Intent(context, PdfJailService::class.java)
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
        sourceUri: Uri
    ): String = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
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

                    if (sourceFd == null) {
                        cleanup()
                        continuation.resumeWithException(IllegalStateException("Failed to open file descriptor"))
                        return
                    }

                    val callback = object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            sourceFd.close()
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(resultJson)
                            }
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            sourceFd.close()
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(RuntimeException("Jail Error $errorCode: $errorMessage"))
                            }
                        }
                    }

                    jailService.analyzePdf(sourceFd, callback)

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

        val intent = Intent(context, PdfJailService::class.java)
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
