package com.pdfchemy.app.logic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.IPdfJailService
import com.pdfchemy.app.jail.IPdfJailStringCallback
import com.pdfchemy.app.jail.PdfJailService
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PdfGateway {

    private const val MAX_INPUT_SIZE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB

    private fun boundedCopy(inputStream: java.io.InputStream, outputStream: java.io.OutputStream) {
        val buffer = ByteArray(8192)
        var totalRead = 0L
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            totalRead += read
            if (totalRead > MAX_INPUT_SIZE_BYTES) {
                throw SecurityException("Input file exceeds maximum allowed size of 2GB")
            }
            outputStream.write(buffer, 0, read)
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
                try { context.unbindService(connection!!) } catch (e: Exception) {}
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jail = IPdfJailService.Stub.asInterface(service)
                var sourceFd: ParcelFileDescriptor? = null

                try {
                    val tempFile = File.createTempFile("jail_snapshot_analyze_", ".pdf", context.cacheDir)
                    context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                        FileOutputStream(tempFile).use { outputStream ->
                            boundedCopy(inputStream, outputStream)
                        }
                    }
                    sourceFd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    tempFile.delete()

                    jail.analyzePdf(sourceFd, object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            sourceFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resume(resultJson)
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            sourceFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resumeWithException(Exception(errorMessage))
                        }
                    })
                } catch (e: Exception) {
                    sourceFd?.close()
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(Exception("Jail Service Disconnected Unexpectedly"))
            }
        }

        val intent = Intent(context, PdfJailService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            cleanup()
            continuation.resumeWithException(Exception("Failed to bind to Jail Service"))
        }

        continuation.invokeOnCancellation { cleanup() }
    }

    suspend fun executeEngine(
        context: Context,
        engineName: String,
        sourceUri: Uri?,
        destUri: Uri?,
        paramsJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
            if (isBound && connection != null) {
                try { context.unbindService(connection!!) } catch (e: Exception) {}
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jail = IPdfJailService.Stub.asInterface(service)
                var sourceFd: ParcelFileDescriptor? = null
                var destFd: ParcelFileDescriptor? = null

                try {
                    // TOCTOU Snapshot for Source
                    if (sourceUri != null) {
                        val tempFile = File.createTempFile("jail_snapshot_", ".pdf", context.cacheDir)
                        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                boundedCopy(inputStream, outputStream)
                            }
                        }
                        sourceFd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        tempFile.delete()
                    }

                    if (destUri != null) {
                        destFd = context.contentResolver.openFileDescriptor(destUri, "w")
                    }

                    jail.executeEngine(engineName, sourceFd, destFd, paramsJson, object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            sourceFd?.close()
                            destFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resume(resultJson)
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            sourceFd?.close()
                            destFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resumeWithException(Exception(errorMessage))
                        }
                    })
                } catch (e: Exception) {
                    sourceFd?.close()
                    destFd?.close()
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(Exception("Jail Service Disconnected Unexpectedly"))
            }
        }

        val intent = Intent(context, PdfJailService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            cleanup()
            continuation.resumeWithException(Exception("Failed to bind to Jail Service"))
        }

        continuation.invokeOnCancellation { cleanup() }
    }

    suspend fun executeEngineExtra(
        context: Context,
        engineName: String,
        sourceUri: Uri?,
        destUri: Uri?,
        extraUri: Uri?,
        paramsJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
            if (isBound && connection != null) {
                try { context.unbindService(connection!!) } catch (e: Exception) {}
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jail = IPdfJailService.Stub.asInterface(service)
                var sourceFd: ParcelFileDescriptor? = null
                var destFd: ParcelFileDescriptor? = null
                var extraFd: ParcelFileDescriptor? = null

                try {
                    if (sourceUri != null) {
                        val tempFile = File.createTempFile("jail_snapshot_", ".pdf", context.cacheDir)
                        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                boundedCopy(inputStream, outputStream)
                            }
                        }
                        sourceFd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        tempFile.delete()
                    }

                    if (destUri != null) {
                        destFd = context.contentResolver.openFileDescriptor(destUri, "w")
                    }
                    
                    if (extraUri != null) {
                        val tempFile = File.createTempFile("jail_snapshot_extra_", ".pdf", context.cacheDir)
                        context.contentResolver.openInputStream(extraUri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                boundedCopy(inputStream, outputStream)
                            }
                        }
                        extraFd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        tempFile.delete()
                    }

                    jail.executeEngineExtra(engineName, sourceFd, destFd, extraFd, paramsJson, object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            sourceFd?.close()
                            destFd?.close()
                            extraFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resume(resultJson)
                        }

                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            sourceFd?.close()
                            destFd?.close()
                            extraFd?.close()
                            cleanup()
                            if (continuation.isActive) continuation.resumeWithException(Exception(errorMessage))
                        }
                    })
                } catch (e: Exception) {
                    sourceFd?.close()
                    destFd?.close()
                    extraFd?.close()
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(Exception("Jail Service Disconnected Unexpectedly"))
            }
        }

        val intent = Intent(context, PdfJailService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            cleanup()
            continuation.resumeWithException(Exception("Failed to bind to Jail Service"))
        }

        continuation.invokeOnCancellation { cleanup() }
    }

    suspend fun executeEngineBatch(
        context: Context,
        engineName: String,
        sourceUris: List<Uri>,
        destUris: List<Uri>,
        paramsJson: String
    ): String = suspendCancellableCoroutine { continuation ->
        var isBound = false
        var connection: ServiceConnection? = null

        fun cleanup() {
            if (isBound && connection != null) {
                try { context.unbindService(connection!!) } catch (e: Exception) {}
                isBound = false
            }
        }

        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val jail = IPdfJailService.Stub.asInterface(service)
                val sourceFds = mutableListOf<ParcelFileDescriptor>()
                val destFds = mutableListOf<ParcelFileDescriptor>()

                try {
                    for (uri in sourceUris) {
                        val tempFile = File.createTempFile("jail_snapshot_src_", ".pdf", context.cacheDir)
                        context.contentResolver.openInputStream(uri)?.use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                boundedCopy(inputStream, outputStream)
                            }
                        }
                        val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        tempFile.delete()
                        sourceFds.add(pfd)
                    }

                    for (uri in destUris) {
                        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                        if (pfd != null) destFds.add(pfd)
                    }

                    jail.executeEngineBatch(
                        engineName,
                        sourceFds.toTypedArray(),
                        destFds.toTypedArray(),
                        paramsJson,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) {
                                sourceFds.forEach { try { it.close() } catch (e: Exception) {} }
                                destFds.forEach { try { it.close() } catch (e: Exception) {} }
                                cleanup()
                                if (continuation.isActive) continuation.resume(resultJson)
                            }

                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                sourceFds.forEach { try { it.close() } catch (e: Exception) {} }
                                destFds.forEach { try { it.close() } catch (e: Exception) {} }
                                cleanup()
                                if (continuation.isActive) continuation.resumeWithException(Exception("Engine Error: $errorMessage"))
                            }
                        }
                    )
                } catch (e: Exception) {
                    sourceFds.forEach { try { it.close() } catch (ex: Exception) {} }
                    destFds.forEach { try { it.close() } catch (ex: Exception) {} }
                    cleanup()
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(Exception("Jail Service Disconnected Unexpectedly"))
            }
        }

        val intent = Intent(context, PdfJailService::class.java)
        isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!isBound) {
            cleanup()
            continuation.resumeWithException(Exception("Failed to bind to Jail Service"))
        }

        continuation.invokeOnCancellation { cleanup() }
    }
}
