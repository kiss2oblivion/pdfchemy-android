package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.pdfchemy.app.security.BoundedOutputStream
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.*
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Adapter-owned jobs; fixed admission bounds even a provider that never returns an open. */
internal class OwnedPdfPrintAdapter(
    private val context: Context,
    private val uri: Uri,
    private val name: String
) : PrintDocumentAdapter() {
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val active = AtomicReference<Write?>()

    override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancellation: CancellationSignal,
                          callback: LayoutResultCallback, extras: Bundle?) {
        if (cancellation.isCanceled || !owner.isActive) callback.onLayoutCancelled()
        else callback.onLayoutFinished(PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
                         cancellation: CancellationSignal, callback: WriteResultCallback) {
        val request = Write(destination, cancellation, callback)
        active.getAndSet(request)?.stop(CANCELLED)
        if (cancellation.isCanceled || !owner.isActive) { request.stop(CANCELLED); return }
        if (!pages.any { it == PageRange.ALL_PAGES }) { request.complete(FAILED); return }
        // No unbounded queue of parked jobs or blocked provider threads.
        if (!slots.tryAcquire()) { request.complete(FAILED); return }
        val job = owner.launch(start = CoroutineStart.LAZY) {
            try {
                runInterruptible(Dispatchers.IO) { copy(request) }
                request.complete(FINISHED)
            } catch (_: CancellationException) {
                request.complete(CANCELLED)
            } catch (_: Exception) {
                request.complete(FAILED)
            }
        }
        request.worker = job
        val deadline = owner.launch {
            delay(SecurityLimits.WORKER_DEADLINE_MS)
            request.stop(FAILED)
        }
        job.invokeOnCompletion {
            // Completion waits for the actual IO block. A stuck open cannot free its slot early.
            slots.release()
            deadline.cancel()
            active.compareAndSet(request, null)
        }
        cancellation.setOnCancelListener { request.stop(CANCELLED) }
        job.start()
    }

    override fun onFinish() {
        active.getAndSet(null)?.stop(CANCELLED)
        owner.cancel()
        super.onFinish()
    }

    private fun copy(request: Write) {
        request.checkActive()
        requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r", request.providerCancellation)) {
            "Print input unavailable"
        }.use { asset ->
            request.checkActive()
            require(asset.declaredLength <= SecurityLimits.MAX_OUTPUT_BYTES) { "Print input exceeds output quota" }
            // Preserve ContentResolver stream semantics: a provider may expose an asset
            // slice, and reliable pipes must report a producer error rather than a clean EOF.
            asset.createInputStream().use { input ->
                // The adapter closes its original destination on every terminal result. Only this
                // IO block owns the duplicate, preventing cancellation from recycling an in-use FD.
                request.duplicateDestination().use { destination ->
                    val sink = object : OutputStream() {
                        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            var position = offset
                            val end = offset + length
                            while (position < end) {
                                awaitReady(destination.fileDescriptor, OsConstants.POLLOUT, request)
                                val written = Os.write(destination.fileDescriptor, bytes, position,
                                    minOf(end - position, 4096))
                                if (written <= 0) throw IOException("Print destination stopped accepting bytes")
                                position += written
                            }
                        }
                    }
                    BoundedOutputStream(sink, SecurityLimits.MAX_OUTPUT_BYTES).use { output ->
                        val buffer = ByteArray(8192)
                        var remaining = asset.declaredLength
                        while (remaining != 0L) {
                            awaitReady(asset.parcelFileDescriptor.fileDescriptor, OsConstants.POLLIN, request)
                            val count = input.read(buffer, 0,
                                if (remaining < 0) buffer.size else minOf(remaining, buffer.size.toLong()).toInt())
                            request.checkActive()
                            if (count < 0) {
                                require(remaining < 0) { "Print input ended before its declared length" }
                                break
                            }
                            output.write(buffer, 0, count)
                            if (remaining > 0) remaining -= count
                        }
                        request.checkActive()
                        output.flush()
                    }
                }
            }
        }
    }

    /** Pipes with no available data/space never park indefinitely in read/write. */
    private fun awaitReady(fd: FileDescriptor, events: Int, request: Write) {
        val poll = StructPollfd().apply { this.fd = fd; this.events = events.toShort() }
        while (true) {
            request.checkActive()
            try { Os.poll(arrayOf(poll), 100) }
            catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                throw error
            }
            request.checkActive()
            if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) throw IOException("Print descriptor unavailable")
            if (poll.revents.toInt() and (events or OsConstants.POLLHUP or OsConstants.POLLERR) != 0) return
        }
    }

    private class Write(
        private val destination: ParcelFileDescriptor,
        private val cancellation: CancellationSignal,
        private val callback: WriteResultCallback
    ) {
        private val terminal = AtomicBoolean()
        private val descriptorLock = Any()
        @Volatile private var stopped = false
        private var stopResult = CANCELLED
        private var outputStarted = false
        val providerCancellation = CancellationSignal()
        @Volatile var worker: Job? = null

        fun checkActive() {
            if (stopped || terminal.get() || cancellation.isCanceled || Thread.currentThread().isInterrupted)
                throw CancellationException("Print write cancelled")
        }

        fun duplicateDestination(): ParcelFileDescriptor = synchronized(descriptorLock) {
            checkActive()
            ParcelFileDescriptor.dup(destination.fileDescriptor).also { outputStarted = true }
        }

        fun stop(result: Int) {
            val canCompleteNow = synchronized(descriptorLock) {
                if (!stopped) { stopResult = result; stopped = true }
                !outputStarted
            }
            // An outstanding provider open cannot write. Once copying starts, its IO
            // owner closes the duplicate before reporting cancellation to the spooler.
            if (canCompleteNow) complete(result)
            providerCancellation.cancel()
            worker?.cancel()
        }

        fun complete(result: Int) {
            val outcome = synchronized(descriptorLock) {
                if (!terminal.compareAndSet(false, true)) return
                val effective = if (stopped) stopResult else result
                if (runCatching { destination.close() }.isSuccess) effective else FAILED
            }
            Handler(Looper.getMainLooper()).post {
                cancellation.setOnCancelListener(null)
                when {
                    cancellation.isCanceled || outcome == CANCELLED -> callback.onWriteCancelled()
                    outcome == FINISHED -> callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    else -> callback.onWriteFailed("Unable to print. Check document access, size and select all pages.")
                }
            }
        }
    }

    companion object {
        private const val FINISHED = 0
        private const val CANCELLED = 1
        private const val FAILED = 2
        private val slots = Semaphore(2)
    }
}
