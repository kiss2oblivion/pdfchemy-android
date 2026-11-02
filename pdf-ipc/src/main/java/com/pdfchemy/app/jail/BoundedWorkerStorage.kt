package com.pdfchemy.app.jail

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.pdfchemy.app.security.SecurityLimits
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host-only backing files. Untrusted producers receive write-only pipes, never writable files. */
class BoundedWorkerStorage(
    private val context: Context,
    outputLimit: Long = SecurityLimits.MAX_OUTPUT_BYTES,
    scratchLimit: Long = SecurityLimits.MAX_OUTPUT_BYTES,
    totalLimit: Long = SecurityLimits.MAX_OPERATION_WRITE_BYTES,
    timeoutMs: Long = SecurityLimits.WORKER_DEADLINE_MS
) : Closeable {
    enum class Kind { OUTPUT, SCRATCH }

    class Sink internal constructor(
        val id: Long,
        val writer: ParcelFileDescriptor,
        internal val reader: ParcelFileDescriptor,
        internal val backing: ParcelFileDescriptor,
        internal val readOnly: ParcelFileDescriptor,
        internal val kind: Kind
    ) {
        internal val finished = CountDownLatch(1)
        @Volatile var storedBytes = 0L
            internal set
    }

    private val lock = Object()
    private val quota = WorkerStorageQuota(outputLimit, scratchLimit, totalLimit)
    private val sinks = linkedMapOf<Long, Sink>()
    private val deadline: Long
    @Volatile private var closed = false
    @Volatile private var failure: Throwable? = null
    private var pump: Thread? = null
    private var sharedBytes = 0L
    private var sharedFiles = 0
    private var disposed = false
    @Volatile var peakStoredBytes = 0L
        private set

    init {
        require(timeoutMs in 1..SecurityLimits.WORKER_DEADLINE_MS)
        deadline = SystemClock.elapsedRealtime() + timeoutMs
        cleanupFailure?.let { throw IOException("Earlier worker storage cleanup failed", it) }
    }

    fun create(kind: Kind): Sink = synchronized(lock) {
        checkHealthy()
        val limit = if (kind == Kind.OUTPUT) SecurityLimits.MAX_BATCH_FDS else SecurityLimits.MAX_SCRATCH_FDS
        require(sinks.values.count { it.kind == kind } < limit) { "Worker storage descriptor quota exceeded" }
        ActiveWorkerStorageQuota.reserveFile()
        val file = try { File.createTempFile("bounded_worker_", ".bin", context.cacheDir) }
        catch (error: Throwable) { ActiveWorkerStorageQuota.release(0, 1); throw error }
        var backing: ParcelFileDescriptor? = null
        var readOnly: ParcelFileDescriptor? = null
        var pipe: Array<ParcelFileDescriptor>? = null
        try {
            Os.chmod(file.path, 0x180) // 0600: only the host UID may reopen the inode.
            backing = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            readOnly = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            check(file.delete()) { "Unable to unlink worker backing file" }
            pipe = ParcelFileDescriptor.createPipe()
            Os.fcntlInt(pipe[0].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            val id = Os.fstat(pipe[0].fileDescriptor).st_ino
            check(id !in sinks)
            val sink = Sink(id, pipe[1], pipe[0], backing, readOnly, kind)
            sinks[id] = sink
            sharedFiles++
            if (pump == null) {
                pump = Thread(::drain, "pdfchemy-worker-storage").apply { isDaemon = true; start() }
            }
            lock.notifyAll()
            sink
        } catch (error: Throwable) {
            if (sinks.values.none { it.backing === backing }) ActiveWorkerStorageQuota.release(0, 1)
            pipe?.forEach { runCatching { it.close() } }
            runCatching { backing?.close() }; runCatching { readOnly?.close() }
            throw error
        } finally { file.delete() }
    }

    /** Caller closes every writer before requesting a seekable, immutable read capability. */
    fun snapshot(id: Long): ParcelFileDescriptor {
        val sink = synchronized(lock) { checkHealthy(); requireNotNull(sinks[id]) { "Unknown storage capability" } }
        await(sink)
        return synchronized(lock) {
            checkHealthy()
            require(Os.fstat(sink.readOnly.fileDescriptor).st_size == sink.storedBytes)
            sink.readOnly.dup()
        }
    }

    suspend fun snapshotCancellable(id: Long): ParcelFileDescriptor {
        val owner = com.pdfchemy.app.utils.ResourceTransferOwner<ParcelFileDescriptor> { it.close() }
        try {
            kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) { owner.stage(snapshot(id)) }
            return checkNotNull(owner.commit())
        } finally { owner.close() }
    }

    fun verify(kind: Kind) {
        val allocated = synchronized(lock) { checkHealthy(); sinks.values.filter { it.kind == kind } }
        allocated.forEach(::await)
        checkHealthy()
    }

    private fun await(sink: Sink) {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0 || !sink.finished.await(remaining, TimeUnit.MILLISECONDS)) {
            val timeout = IOException("Worker storage completion timed out")
            poison(timeout)
            throw timeout
        }
        checkHealthy()
    }

    private fun checkHealthy() {
        failure?.let { throw IOException("Worker storage rejected", it) }
        check(!closed) { "Worker storage closed" }
        cleanupFailure?.let { throw IOException("Worker storage cleanup failed", it) }
        if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Worker storage deadline exceeded")
    }

    private fun poison(error: Throwable) {
        synchronized(lock) {
            if (failure == null) failure = error
            sinks.values.forEach { it.finished.countDown() }
            lock.notifyAll()
        }
    }

    private fun drain() {
        val buffer = ByteArray(16 * 1024)
        try {
            while (!closed && failure == null) {
                val active = synchronized(lock) {
                    checkHealthy()
                    sinks.values.filter { it.finished.count != 0L }
                }
                if (active.isEmpty()) {
                    synchronized(lock) { if (!closed && failure == null) lock.wait(50) }
                    continue
                }
                val polls = active.map { sink -> StructPollfd().apply {
                    fd = sink.reader.fileDescriptor
                    events = (OsConstants.POLLIN or OsConstants.POLLHUP or OsConstants.POLLERR).toShort()
                } }.toTypedArray()
                try { Os.poll(polls, 50) }
                catch (error: ErrnoException) { if (error.errno != OsConstants.EINTR) throw error; continue }
                for (index in active.indices) {
                    if (closed || failure != null) break
                    if (polls[index].revents.toInt() == 0) continue
                    val sink = active[index]
                    val count = try { Os.read(sink.reader.fileDescriptor, buffer, 0, buffer.size) }
                    catch (error: ErrnoException) {
                        if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue
                        throw error
                    }
                    if (count == 0) {
                        sink.reader.close()
                        sink.finished.countDown()
                    } else {
                        quota.reserve(sink.kind, count.toLong()) // Before any disk write, in the host.
                        ActiveWorkerStorageQuota.reserveBytes(count.toLong())
                        sharedBytes += count
                        var offset = 0
                        while (offset < count) {
                            val written = Os.write(sink.backing.fileDescriptor, buffer, offset, count - offset)
                            if (written <= 0) throw IOException("Unable to store worker bytes")
                            offset += written
                        }
                        sink.storedBytes += count
                        peakStoredBytes = quota.totalBytes
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed) poison(error)
        } finally {
            disposeBacking()
        }
    }

    private fun disposeBacking() = synchronized(lock) {
        if (disposed) return@synchronized
        disposed = true
        var truncated = true
        sinks.values.forEach { sink ->
            // A hostile process may retain read-only duplicates across operations.
            // Truncation frees the owned backing storage even while those FDs survive.
            try { if (sink.backing.fileDescriptor.valid()) Os.ftruncate(sink.backing.fileDescriptor, 0) }
            catch (error: Throwable) { cleanupFailure = error; truncated = false }
            listOf(sink.reader, sink.writer, sink.readOnly, sink.backing).forEach { runCatching { it.close() } }
            sink.finished.countDown()
        }
        if (truncated) {
            ActiveWorkerStorageQuota.release(sharedBytes, sharedFiles)
            sharedBytes = 0; sharedFiles = 0
        }
    }

    override fun close() {
        val worker = synchronized(lock) {
            if (closed) return
            closed = true
            sinks.values.forEach { it.finished.countDown() }
            lock.notifyAll()
            pump
        }
        worker?.interrupt()
        worker?.join(1_000)
        if (worker?.isAlive != true) disposeBacking()
        // The pump owns read/backing FDs until it exits, avoiding FD-reuse races.
        // A failed filesystem cleanup poisons subsequent sessions in this host process.
    }

    companion object {
        @Volatile private var cleanupFailure: Throwable? = null
    }
}

/** All live host collectors share this bound, including collectors still completing cleanup. */
internal object ActiveWorkerStorageQuota {
    private var bytes = 0L
    private var files = 0

    @Synchronized fun reserveFile() {
        if (files >= SecurityLimits.MAX_SCRATCH_FDS + SecurityLimits.MAX_BATCH_FDS) throw IOException("Active worker file quota exceeded")
        files++
    }

    @Synchronized fun reserveBytes(count: Long) {
        require(count >= 0)
        if (count > SecurityLimits.MAX_OPERATION_WRITE_BYTES - bytes) throw IOException("Active worker storage quota exceeded")
        bytes += count
    }

    @Synchronized fun release(count: Long, fileCount: Int) {
        check(count in 0..bytes && fileCount in 0..files)
        bytes -= count; files -= fileCount
    }
}

/** Charged only by the host pipe drainer; no worker-supplied count is trusted. */
internal class WorkerStorageQuota(
    private val outputLimit: Long,
    private val scratchLimit: Long,
    private val totalLimit: Long
) {
    var totalBytes = 0L
        private set
    private var outputBytes = 0L
    private var scratchBytes = 0L

    init {
        require(outputLimit in 1..SecurityLimits.MAX_OUTPUT_BYTES)
        require(scratchLimit in 1..SecurityLimits.MAX_OUTPUT_BYTES)
        require(totalLimit in 1..SecurityLimits.MAX_OPERATION_WRITE_BYTES)
    }

    @Synchronized fun reserve(kind: BoundedWorkerStorage.Kind, bytes: Long) {
        require(bytes >= 0)
        val used = if (kind == BoundedWorkerStorage.Kind.OUTPUT) outputBytes else scratchBytes
        val limit = if (kind == BoundedWorkerStorage.Kind.OUTPUT) outputLimit else scratchLimit
        if (bytes > limit - used || bytes > totalLimit - totalBytes) throw IOException("Host worker storage quota exceeded")
        if (kind == BoundedWorkerStorage.Kind.OUTPUT) outputBytes += bytes else scratchBytes += bytes
        totalBytes += bytes
    }
}
