package com.pdfchemy.app.logic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.jail.IPdfJailService
import com.pdfchemy.app.jail.IPdfJailStringCallback
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object PdfGateway {
    private class WorkerFailure(val errorCode: Int, message: String) : IllegalStateException(message)

    private suspend fun request(
        context: Context,
        sources: List<Uri>,
        targets: List<Uri>,
        paramsJson: String,
        invoke: (IPdfJailService, Long, List<com.pdfchemy.app.jail.StagedPdf>, List<ParcelFileDescriptor>, List<ParcelFileDescriptor>, IBinder?, IBinder, IPdfJailStringCallback) -> Unit
    ): String = withContext(Dispatchers.IO) {
        SecurityLimits.enforceStringLength(paramsJson, SecurityLimits.MAX_PARAMS_JSON_BYTES, "Request")
        require(sources.size <= SecurityLimits.MAX_BATCH_FDS && targets.size <= SecurityLimits.MAX_BATCH_FDS)
        val staged = mutableListOf<com.pdfchemy.app.jail.StagedPdf>()
        val inputLeases = mutableListOf<java.io.Closeable>()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val connections = mutableListOf<ServiceConnection>()
        var jail: IPdfJailService? = null
        var operationToken: Long = 0L
        val scratch = com.pdfchemy.app.jail.OperationScratchBroker(context)
        val outputTransaction = HostOutputTransaction(context, targets)
        try {
            sources.forEach {
                val snapshot = DocumentStager.stageDocumentCancellable(context, it)
                staged.add(snapshot)
                inputLeases.add(DocumentStager.retain(snapshot))
            }
            require(staged.sumOf { it.size } <= SecurityLimits.MAX_BATCH_INPUT_BYTES)
            val inputs = staged.map { context.contentResolver.openFileDescriptor(it.uri, "r")!!.also(descriptors::add) }
            val outputs = outputTransaction.workerDescriptors

            suspend fun bind(className: String): IBinder {
                val channel = Channel<IBinder>(1)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) { if (service != null) channel.trySend(service) }
                    override fun onServiceDisconnected(name: ComponentName?) { channel.close(IllegalStateException("Isolated worker died")) }
                    override fun onNullBinding(name: ComponentName?) { channel.close(IllegalStateException("Null worker binding")) }
                    override fun onBindingDied(name: ComponentName?) { channel.close(IllegalStateException("Worker binding died")) }
                }
                check(context.bindService(Intent().setClassName(context, className), connection, Context.BIND_AUTO_CREATE))
                connections.add(connection)
                return withTimeout(10_000L) { channel.receive() }
            }

            val renderer = bind("com.pdfchemy.app.sandbox.PdfNativeRendererService")
            val binder = bind("com.pdfchemy.app.jail.PdfJailService")
            jail = IPdfJailService.Stub.asInterface(binder)

            // Synchronous admission handshake: obtain exclusive token or detect BUSY
            operationToken = jail.beginOperation(scratch)
            if (operationToken == 0L) {
                throw WorkerFailure(429, "Jail 429: BUSY")
            }

            val response = CompletableDeferred<String>()
            val death = IBinder.DeathRecipient { response.completeExceptionally(IllegalStateException("Isolated worker died")) }
            binder.linkToDeath(death, 0)
            val callback = object : IPdfJailStringCallback.Stub() {
                override fun onSuccess(resultJson: String) { response.complete(resultJson) }
                override fun onFailure(errorCode: Int, errorMessage: String) {
                    response.completeExceptionally(WorkerFailure(errorCode, "Jail $errorCode: $errorMessage"))
                }
            }

            val result = try {
                withTimeout(SecurityLimits.WORKER_DEADLINE_MS + 5000) {
                    invoke(jail!!, operationToken, staged, inputs, outputs, renderer, scratch, callback)
                    response.await()
                }
            } finally { runCatching { binder.unlinkToDeath(death, 0) } }

            scratch.verifyBudget()

            // Acceptance handshake:
            // For output operations: validate and snapshot into private FDs, then ACK before committing to SAF.
            if (targets.isNotEmpty()) {
                outputTransaction.validateAndSnapshot(result)
                jail.completeOperation(operationToken)
                operationToken = 0L // Successfully completed/released
                outputTransaction.commit()
            } else {
                // Non-output operation: Host accepts result
                jail.completeOperation(operationToken)
                operationToken = 0L // Successfully completed/released
            }
            result
        } catch (error: Throwable) {
            // Terminate worker ONLY if this operation holds a valid, active token.
            // Never abort if token == 0L (e.g. BUSY or pre-admission failure).
            if (operationToken > 0L) {
                runCatching {
                    jail?.takeIf { it.asBinder().isBinderAlive }?.abortOperation(operationToken)
                }
            }
            throw error
        } finally {
            outputTransaction.close()
            scratch.close()
            descriptors.forEach { runCatching { it.close() } }
            connections.forEach { runCatching { context.unbindService(it) } }
            staged.zip(sources).filter { (snapshot, original) -> snapshot.uri != original }.forEach { DocumentStager.release(it.first) }
            inputLeases.forEach { it.close() }
        }
    }

    suspend fun analyzePdf(context: Context, sourceUri: Uri): String = request(context, listOf(sourceUri), emptyList(), "{}") { jail, token, staged, inputs, _, _, scratch, callback ->
        jail.analyzePdf(token, inputs[0], staged[0].sha256, staged[0].size, scratch, callback)
    }

    suspend fun executeEngine(context: Context, engineName: String, sourceUri: Uri?, destUri: Uri?, paramsJson: String): String =
        request(context, listOfNotNull(sourceUri), listOfNotNull(destUri), paramsJson) { jail, token, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngine(token, engineName, inputs.firstOrNull(), outputs.firstOrNull(), paramsJson, renderer, staged.firstOrNull()?.sha256 ?: "", staged.firstOrNull()?.size ?: 0L, scratch, callback)
        }

    suspend fun executeEngineExtra(context: Context, engineName: String, sourceUri: Uri?, destUri: Uri?, extraUri: Uri?, paramsJson: String): String =
        request(context, listOfNotNull(sourceUri, extraUri), listOfNotNull(destUri), paramsJson) { jail, token, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngineExtra(token, engineName, inputs.firstOrNull(), outputs.firstOrNull(), inputs.getOrNull(1), paramsJson, renderer, staged.firstOrNull()?.sha256 ?: "", staged.firstOrNull()?.size ?: 0L, staged.getOrNull(1)?.sha256 ?: "", staged.getOrNull(1)?.size ?: 0L, scratch, callback)
        }

    suspend fun executeEngineBatch(context: Context, engineName: String, sourceUris: List<Uri>, destUris: List<Uri>, paramsJson: String): String =
        request(context, sourceUris, destUris, paramsJson) { jail, token, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngineBatch(token, engineName, inputs.toTypedArray(), outputs.toTypedArray(), paramsJson, renderer, staged.map { it.sha256 }.toTypedArray(), staged.map { it.size }.toLongArray(), scratch, callback)
        }
}
