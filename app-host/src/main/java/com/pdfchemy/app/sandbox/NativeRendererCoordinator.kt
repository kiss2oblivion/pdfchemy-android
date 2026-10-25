package com.pdfchemy.app.sandbox

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.security.*
import com.pdfchemy.app.utils.DocumentStager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import com.pdfchemy.app.utils.ResourceTransferOwner
import com.pdfchemy.app.jail.BoundedWorkerStorage

object NativeRendererCoordinator {
    private suspend fun stageRenderedPixels(
        context: Context,
        owner: ResourceTransferOwner<android.graphics.Bitmap>,
        render: suspend (ParcelFileDescriptor) -> Unit
    ) {
        BoundedWorkerStorage(context, timeoutMs = SecurityLimits.RENDER_DEADLINE_MS).use { storage ->
            val sink = storage.create(BoundedWorkerStorage.Kind.OUTPUT)
            sink.writer.use { render(it) }
            storage.snapshotCancellable(sink.id).use { snapshot ->
                ParcelFileDescriptor.AutoCloseInputStream(snapshot).use { input ->
                    PixelWire.read(input)?.let(owner::stage)
                }
            }
        }
    }
    private suspend fun <T> withRenderer(context: Context, block: (IPdfNativeRendererService) -> T): T = withContext(Dispatchers.IO) {
        val channel = Channel<IPdfNativeRendererService>(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) { channel.trySend(IPdfNativeRendererService.Stub.asInterface(service)) }
            override fun onServiceDisconnected(name: ComponentName?) { channel.close(IllegalStateException("Renderer died")) }
            override fun onNullBinding(name: ComponentName?) { channel.close(IllegalStateException("Null renderer")) }
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.sandbox.PdfNativeRendererService"), connection, Context.BIND_AUTO_CREATE))
        try { block(withTimeout(10_000) { channel.receive() }) }
        finally { context.unbindService(connection) }
    }
    suspend fun renderUriToBitmap(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        width: Int,
        transferOwner: ResourceTransferOwner<android.graphics.Bitmap>? = null
    ): android.graphics.Bitmap? {
        val owner = transferOwner ?: ResourceTransferOwner { if (!it.isRecycled) it.recycle() }
        try {
            withContext(Dispatchers.IO) {
                val (staged, inputLease) = DocumentStager.stageDocumentCancellableWithLease(context, uri)
                try {
                    stageRenderedPixels(context, owner) { dest ->
                        context.contentResolver.openFileDescriptor(staged.uri, "r")!!.use { source ->
                            withRenderer(context) { it.renderPageToPixels(source, pageIndex, dest, staged.sha256, staged.size, width) }
                        }
                    }
                } finally {
                    if (staged.uri != uri) DocumentStager.release(staged)
                    inputLease.close()
                }
            }
            return owner.commit()
        } catch (e: Throwable) {
            owner.discard()
            throw e
        }
    }
    suspend fun getPageCount(context: Context, pdfPfd: ParcelFileDescriptor, rendererBinder: IBinder? = null): Int? = withContext(Dispatchers.IO) {
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        if (rendererBinder != null) IPdfNativeRendererService.Stub.asInterface(rendererBinder).getPageCount(pdfPfd, hash, size)
        else withRenderer(context) { it.getPageCount(pdfPfd, hash, size) }
    }
    suspend fun renderPageToBitmap(
        context: Context,
        pdfPfd: ParcelFileDescriptor,
        pageIndex: Int,
        scaleWidth: Int = 0,
        transferOwner: ResourceTransferOwner<android.graphics.Bitmap>? = null
    ): android.graphics.Bitmap? {
        val owner = transferOwner ?: ResourceTransferOwner { if (!it.isRecycled) it.recycle() }
        try {
            withContext(Dispatchers.IO) {
                val (hash, size) = StagedIdentity.identity(pdfPfd)
                stageRenderedPixels(context, owner) { target ->
                    withRenderer(context) { it.renderPageToPixels(pdfPfd, pageIndex, target, hash, size, if (scaleWidth > 0) scaleWidth else SecurityLimits.MAX_RENDER_DIMENSION) }
                }
            }
            return owner.commit()
        } catch (e: Throwable) {
            owner.discard()
            throw e
        }
    }
    suspend fun renderPageToJpeg(context: Context, pdfPfd: ParcelFileDescriptor, pageIndex: Int, outputJpegPfd: ParcelFileDescriptor, rendererBinder: IBinder? = null): Boolean? = withContext(Dispatchers.IO) {
        val (hash, size) = StagedIdentity.identity(pdfPfd)
        BoundedWorkerStorage(context, timeoutMs = SecurityLimits.RENDER_DEADLINE_MS).use { storage ->
            val sink = storage.create(BoundedWorkerStorage.Kind.OUTPUT)
            sink.writer.use { target ->
                if (rendererBinder != null) IPdfNativeRendererService.Stub.asInterface(rendererBinder).renderPageToJpeg(pdfPfd, pageIndex, target, hash, size)
                else withRenderer(context) { it.renderPageToJpeg(pdfPfd, pageIndex, target, hash, size) }
            }
            storage.snapshotCancellable(sink.id).use { snapshot ->
                ParcelFileDescriptor.AutoCloseInputStream(snapshot).use { input ->
                    BoundedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(outputJpegPfd.dup())).use { input.copyTo(it) }
                }
            }
        }
        true
    }
}
