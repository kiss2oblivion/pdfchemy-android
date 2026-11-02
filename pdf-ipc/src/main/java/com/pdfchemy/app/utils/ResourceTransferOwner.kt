package com.pdfchemy.app.utils

import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference

/**
 * Retains ownership of a transferred resource across dispatcher and thread boundaries
 * in a cancellation-safe and disposal-safe manner.
 *
 * If a coroutine or component allocates a resource on a background dispatcher (e.g. Dispatchers.IO)
 * and transfers it to a consumer on another dispatcher (e.g. Dispatchers.Main), prompt cancellation
 * or component disposal occurring while resumption is queued or running will discard the return
 * value of withContext.
 *
 * ResourceTransferOwner bridges this boundary: the producer stages the resource into the owner
 * before leaving the background dispatcher. The caller dispatcher commits the resource upon
 * successful resumption. If cancellation, disposal, or an exception interrupts the transfer before
 * commit, [discard] or [close] invokes [onDiscard] to release, close, or recycle the orphaned resource.
 */
class ResourceTransferOwner<T : Any>(
    private val onDiscard: (T) -> Unit
) : Closeable {
    private val slot = AtomicReference<T?>()
    @Volatile private var closed = false

    /**
     * Stages a newly acquired resource for transfer.
     * If this owner is already closed, [resource] is discarded immediately.
     * If another resource was previously staged and uncommitted, the previous resource is discarded.
     */
    fun stage(resource: T) {
        if (closed) {
            onDiscard(resource)
            return
        }
        val previous = slot.getAndSet(resource)
        if (previous != null && previous != resource) {
            onDiscard(previous)
        }
        if (closed && slot.compareAndSet(resource, null)) {
            onDiscard(resource)
        }
    }

    /**
     * Commits the transfer and claims ownership of the staged resource.
     * Returns null if no resource was staged or if the owner was closed.
     */
    fun commit(): T? {
        if (closed) return null
        return slot.getAndSet(null)
    }

    /**
     * Explicitly discards any uncommitted staged resource.
     */
    fun discard() {
        slot.getAndSet(null)?.let(onDiscard)
    }

    /**
     * Closes this transfer owner, preventing further stagings and discarding any uncommitted resource.
     */
    override fun close() {
        closed = true
        discard()
    }

    val isClosed: Boolean get() = closed
    val hasPending: Boolean get() = slot.get() != null
}
