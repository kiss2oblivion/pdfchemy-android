package com.pdfchemy.app.security

import android.net.Uri
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates host output publications across PDF and audio workflows,
 * enforcing bounded concurrency, aggregate byte budgets, destination collision
 * avoidance, and cancellation-aware stream closure.
 */
object PublicationCoordinator {
    const val DEFAULT_MAX_CONCURRENT_PUBLICATIONS = 2
    const val DEFAULT_MAX_OUTSTANDING_BYTES = SecurityLimits.MAX_OUTPUT_BYTES
    const val DEFAULT_STOP_TIMEOUT_MS = 2000L

    @Volatile var maxConcurrentPublications: Int = DEFAULT_MAX_CONCURRENT_PUBLICATIONS
    @Volatile var maxOutstandingBytes: Long = DEFAULT_MAX_OUTSTANDING_BYTES
    @Volatile var stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS

    private val idGenerator = AtomicLong(0L)
    private val activeHandles = ConcurrentHashMap<Long, PublicationHandle>()

    class PublicationHandle internal constructor(
        val id: Long,
        val targets: List<Uri>,
        val requestedBytes: Long
    ) : Closeable {
        @Volatile internal var activeStream: Closeable? = null
        @Volatile internal var activeJob: Job? = null
        @Volatile var completed: Boolean = false
            internal set
        @Volatile var cancelled: Boolean = false
            internal set
        @Volatile var aborted: Boolean = false
            internal set

        fun attachJob(job: Job) {
            activeJob = job
            job.invokeOnCompletion {
                abort()
            }
        }

        fun registerStream(stream: Closeable): AutoCloseable {
            activeStream = stream
            if (cancelled || aborted) {
                runCatching { stream.close() }
            }
            return AutoCloseable {
                if (activeStream === stream) {
                    activeStream = null
                }
            }
        }

        fun abort() {
            aborted = true
            runCatching { activeStream?.close() }
            activeStream = null
            activeJob?.cancel()
        }

        override fun close() {
            completed = true
            activeStream = null
            PublicationCoordinator.release(this)
        }
    }

    @Synchronized
    fun acquire(targets: List<Uri>, bytes: Long): PublicationHandle {
        // 1. Destination collision check: no destination may be concurrently written by another active publication
        if (targets.isNotEmpty()) {
            val colliding = activeHandles.values.find { handle ->
                !handle.completed && handle.targets.any { target -> target in targets }
            }
            if (colliding != null) {
                throw IllegalStateException("Destination is currently in use by active publication ${colliding.id}")
            }
        }

        // 2. Concurrency limit
        val activeCount = activeHandles.values.count { !it.completed }
        if (activeCount >= maxConcurrentPublications) {
            throw SecurityException("Publication concurrency limit exceeded (active=$activeCount, max=$maxConcurrentPublications)")
        }

        // 3. Byte budget check
        val activeBytes = activeHandles.values.filter { !it.completed }.sumOf { it.requestedBytes }
        if (activeBytes + bytes > maxOutstandingBytes) {
            throw SecurityException("Publication quota exceeded (activeBytes=$activeBytes, requested=$bytes, max=$maxOutstandingBytes)")
        }

        val id = idGenerator.incrementAndGet()
        val handle = PublicationHandle(id, targets, bytes)
        activeHandles[id] = handle
        return handle
    }

    @Synchronized
    fun hasActiveWriter(uri: Uri): Boolean {
        return activeHandles.values.any { !it.completed && uri in it.targets }
    }

    @Synchronized
    fun activeCount(): Int = activeHandles.values.count { !it.completed }

    @Synchronized
    fun activeBytes(): Long = activeHandles.values.filter { !it.completed }.sumOf { it.requestedBytes }

    fun cancel(handle: PublicationHandle) {
        handle.cancelled = true
        handle.abort()
    }

    suspend fun awaitWriterStop(handle: PublicationHandle, timeoutMs: Long = stopTimeoutMs): Boolean {
        val job = handle.activeJob ?: return true
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    @Synchronized
    internal fun release(handle: PublicationHandle) {
        activeHandles.remove(handle.id)
    }

    @Synchronized
    fun resetForTesting() {
        activeHandles.values.forEach { it.abort() }
        activeHandles.clear()
        maxConcurrentPublications = DEFAULT_MAX_CONCURRENT_PUBLICATIONS
        maxOutstandingBytes = DEFAULT_MAX_OUTSTANDING_BYTES
        stopTimeoutMs = DEFAULT_STOP_TIMEOUT_MS
    }
}
