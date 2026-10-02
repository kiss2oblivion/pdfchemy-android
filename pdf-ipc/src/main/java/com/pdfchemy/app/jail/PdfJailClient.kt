package com.pdfchemy.app.jail

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.StagedPdf
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PdfJailClient {
    private class WorkerFailure(val errorCode: Int, message: String) : IllegalStateException(message)

    suspend fun exportModifiedPdf(
        context: Context,
        source: StagedPdf,
        destUri: Uri,
        modificationsJson: String
    ): Boolean {
        publish(context, source, destUri) { jail, token, input, output, scratch, callback ->
            jail.exportModifiedPdf(token, input, output, modificationsJson, source.sha256, source.size, scratch, callback)
        }
        return true
    }

    suspend fun compressPdf(
        context: Context,
        source: StagedPdf,
        destUri: Uri,
        targetDpi: Float = 140f,
        quality: Float = 0.5f,
        rasterizePages: Boolean = false
    ): Long = publish(context, source, destUri) { jail, token, input, output, scratch, callback ->
        jail.compressPdf(token, input, output, targetDpi, quality, rasterizePages, source.sha256, source.size, scratch, callback)
    }

    /** Legacy writer entry points obey the same host-only publication boundary. */
    private suspend fun publish(
        context: Context,
        source: StagedPdf,
        destUri: Uri,
        invoke: (IPdfJailService, Long, ParcelFileDescriptor, ParcelFileDescriptor, IBinder, IPdfJailCallback) -> Unit
    ): Long = withContext(Dispatchers.IO) {
        val scratch = OperationScratchBroker(context)
        val output = HostOutputTransaction(context, listOf(destUri))
        val bound = CompletableDeferred<IBinder>()
        val response = CompletableDeferred<Unit>()
        var isBound = false
        var jail: IPdfJailService? = null
        var operationToken = 0L
        var input: ParcelFileDescriptor? = null
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient {
            response.completeExceptionally(IllegalStateException("Isolated worker died"))
        }
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) bound.completeExceptionally(IllegalStateException("Null worker binding"))
                else bound.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                val error = IllegalStateException("Isolated worker disconnected")
                bound.completeExceptionally(error)
                response.completeExceptionally(error)
            }
            override fun onNullBinding(name: ComponentName?) {
                bound.completeExceptionally(IllegalStateException("Null worker binding"))
            }
            override fun onBindingDied(name: ComponentName?) { onServiceDisconnected(name) }
        }
        try {
            val sourceFd = requireNotNull(context.contentResolver.openFileDescriptor(source.uri, "r")).also { input = it }
            isBound = context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE)
            check(isBound) { "Failed to bind to PdfJailService" }
            binder = withTimeout(10_000L) { bound.await() }
            jail = IPdfJailService.Stub.asInterface(binder)

            // Synchronous admission handshake
            operationToken = jail.beginOperation(scratch)
            if (operationToken == 0L) {
                throw WorkerFailure(429, "Jail 429: BUSY")
            }

            binder.linkToDeath(death, 0)
            val callback = object : IPdfJailCallback.Stub() {
                override fun onSuccess(outputSizeBytes: Long) { response.complete(Unit) }
                override fun onFailure(errorCode: Int, errorMessage: String?) {
                    response.completeExceptionally(WorkerFailure(errorCode, "Jail $errorCode: $errorMessage"))
                }
            }
            withTimeout(SecurityLimits.WORKER_DEADLINE_MS + 5000) {
                invoke(jail!!, operationToken, sourceFd, output.workerDescriptors.single(), scratch, callback)
                response.await()
            }
            scratch.verifyBudget()
            output.validateAndSnapshot("{\"success\":true}")
            val size = output.validatedBytes // Never trust the worker's reported byte count.
            check(jail.completeOperation(operationToken)) { "Worker rejected Host acceptance handshake" }
            operationToken = 0L // Successfully completed/released
            output.commit()
            size
        } catch (error: Throwable) {
            if (operationToken > 0L) {
                runCatching { jail?.takeIf { it.asBinder().isBinderAlive }?.abortOperation(operationToken) }
            }
            throw error
        } finally {
            runCatching { binder?.unlinkToDeath(death, 0) }
            output.close()
            scratch.close()
            runCatching { input?.close() }
            if (isBound) runCatching { context.unbindService(connection) }
        }
    }

    suspend fun analyzePdf(
        context: Context,
        source: StagedPdf
    ): String {
        val bound = CompletableDeferred<IPdfJailService>()
        var isBound = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound.complete(IPdfJailService.Stub.asInterface(service))
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val scratch = OperationScratchBroker(context)
        var sourceFd: ParcelFileDescriptor? = null
        var operationToken = 0L
        var jail: IPdfJailService? = null
        var binder: IBinder? = null
        val response = CompletableDeferred<String>()
        val death = IBinder.DeathRecipient {
            response.completeExceptionally(IllegalStateException("Isolated worker died"))
        }

        return try {
            val intent = Intent().apply { setClassName(context, "com.pdfchemy.app.jail.PdfJailService") }
            isBound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            check(isBound) { "Could not bind to PdfJailService" }

            jail = withTimeout(15_000) { bound.await() }
            binder = jail.asBinder()

            val token = jail.beginOperation(scratch)
            if (token == 0L) {
                throw WorkerFailure(429, "Jail 429: BUSY")
            }
            operationToken = token

            sourceFd = context.contentResolver.openFileDescriptor(source.uri, "r")
                ?: throw IllegalStateException("Failed to open file descriptor")

            binder.linkToDeath(death, 0)
            val callback = object : IPdfJailStringCallback.Stub() {
                override fun onSuccess(resultJson: String) {
                    response.complete(resultJson)
                }

                override fun onFailure(errorCode: Int, errorMessage: String) {
                    response.completeExceptionally(WorkerFailure(errorCode, "Jail $errorCode: $errorMessage"))
                }
            }

            val result = withTimeout(SecurityLimits.WORKER_DEADLINE_MS + 5000) {
                jail.analyzePdf(operationToken, sourceFd, source.sha256, source.size, scratch, callback)
                response.await()
            }

            scratch.verifyBudget()
            check(jail.completeOperation(operationToken)) { "Worker rejected Host acceptance handshake" }
            operationToken = 0L // Successfully completed/released
            result
        } catch (error: Throwable) {
            if (operationToken > 0L) {
                runCatching { jail?.takeIf { it.asBinder().isBinderAlive }?.abortOperation(operationToken) }
            }
            throw error
        } finally {
            runCatching { binder?.unlinkToDeath(death, 0) }
            scratch.close()
            runCatching { sourceFd?.close() }
            if (isBound) runCatching { context.unbindService(connection) }
        }
    }
}
