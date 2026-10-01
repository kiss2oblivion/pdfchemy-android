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
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object PdfGateway {
    private suspend fun request(context: Context, sources: List<Uri>, targets: List<Uri>, paramsJson: String,
        invoke: (IPdfJailService, List<com.pdfchemy.app.jail.StagedPdf>, List<ParcelFileDescriptor>, List<ParcelFileDescriptor>, IBinder?, IBinder, IPdfJailStringCallback) -> Unit
    ): String = withContext(Dispatchers.IO) {
        SecurityLimits.enforceStringLength(paramsJson, SecurityLimits.MAX_PARAMS_JSON_BYTES, "Request")
        require(sources.size <= SecurityLimits.MAX_BATCH_FDS && targets.size <= SecurityLimits.MAX_BATCH_FDS)
        val staged = mutableListOf<com.pdfchemy.app.jail.StagedPdf>()
        val inputLeases = mutableListOf<java.io.Closeable>()
        val descriptors = mutableListOf<ParcelFileDescriptor>()
        val outputDescriptors = mutableListOf<ParcelFileDescriptor>()
        val connections = mutableListOf<ServiceConnection>()
        var jail: IPdfJailService? = null
        val scratch = com.pdfchemy.app.jail.OperationScratchBroker(context)
        try {
            sources.forEach {
                val snapshot = DocumentStager.stageDocumentCancellable(context, it)
                staged.add(snapshot)
                inputLeases.add(DocumentStager.retain(snapshot))
            }
            require(staged.sumOf { it.size } <= SecurityLimits.MAX_BATCH_INPUT_BYTES)
            val inputs = staged.map { context.contentResolver.openFileDescriptor(it.uri, "r")!!.also(descriptors::add) }
            val outputs = targets.map { requireNotNull(context.contentResolver.openFileDescriptor(it, "rwt")).also { fd ->
                descriptors.add(fd); outputDescriptors.add(fd)
            } }
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
            withTimeout(SecurityLimits.WORKER_DEADLINE_MS + 5000) {
                suspendCancellableCoroutine { continuation ->
                    val death = IBinder.DeathRecipient {
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Isolated worker died"))
                    }
                    binder.linkToDeath(death, 0)
                    fun unlink() { runCatching { binder.unlinkToDeath(death, 0) } }
                    continuation.invokeOnCancellation { unlink(); runCatching { jail?.abortWorker() } }
                    val callback = object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) {
                            unlink()
                            try { scratch.verifyBudget(); if (continuation.isActive) continuation.resume(resultJson) }
                            catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                        }
                        override fun onFailure(errorCode: Int, errorMessage: String) { unlink(); if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Jail $errorCode: $errorMessage")) }
                    }
                    try { invoke(jail!!, staged, inputs, outputs, renderer, scratch, callback) }
                    catch (e: Exception) { unlink(); if (continuation.isActive) continuation.resumeWithException(e) }
                }
            }
        } catch (error: Throwable) {
            // A killed native process cannot run its own failure cleanup. Keep
            // the host's destination capabilities open until partial output is
            // truncated, including coroutine cancellation and Binder death.
            outputDescriptors.forEach { runCatching { android.system.Os.ftruncate(it.fileDescriptor, 0) } }
            throw error
        } finally {
            scratch.close()
            descriptors.forEach { runCatching { it.close() } }
            connections.forEach { runCatching { context.unbindService(it) } }
            staged.zip(sources).filter { (snapshot, original) -> snapshot.uri != original }.forEach { DocumentStager.release(it.first) }
            inputLeases.forEach { it.close() }
        }
    }
    suspend fun analyzePdf(context: Context, sourceUri: Uri): String = request(context, listOf(sourceUri), emptyList(), "{}") { jail, staged, inputs, _, _, scratch, callback ->
        jail.analyzePdf(inputs[0], staged[0].sha256, staged[0].size, scratch, callback)
    }
    suspend fun executeEngine(context: Context, engineName: String, sourceUri: Uri?, destUri: Uri?, paramsJson: String): String =
        request(context, listOfNotNull(sourceUri), listOfNotNull(destUri), paramsJson) { jail, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngine(engineName, inputs.firstOrNull(), outputs.firstOrNull(), paramsJson, renderer, staged.firstOrNull()?.sha256 ?: "", staged.firstOrNull()?.size ?: 0, scratch, callback)
        }
    suspend fun executeEngineExtra(context: Context, engineName: String, sourceUri: Uri?, destUri: Uri?, extraUri: Uri?, paramsJson: String): String =
        request(context, listOfNotNull(sourceUri, extraUri), listOfNotNull(destUri), paramsJson) { jail, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngineExtra(engineName, inputs.firstOrNull(), outputs.firstOrNull(), inputs.getOrNull(1), paramsJson, renderer, staged.firstOrNull()?.sha256 ?: "", staged.firstOrNull()?.size ?: 0, staged.getOrNull(1)?.sha256 ?: "", staged.getOrNull(1)?.size ?: 0, scratch, callback)
        }
    suspend fun executeEngineBatch(context: Context, engineName: String, sourceUris: List<Uri>, destUris: List<Uri>, paramsJson: String): String =
        request(context, sourceUris, destUris, paramsJson) { jail, staged, inputs, outputs, renderer, scratch, callback ->
            jail.executeEngineBatch(engineName, inputs.toTypedArray(), outputs.toTypedArray(), paramsJson, renderer, staged.map { it.sha256 }.toTypedArray(), staged.map { it.size }.toLongArray(), scratch, callback)
        }
}
