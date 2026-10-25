package com.pdfchemy.app.jail

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Worker capabilities and host-only commit snapshots never name a user destination. */
class HostOutputTransaction(
    private val context: Context,
    targets: List<Uri>,
    storage: BoundedWorkerStorage? = null
) : Closeable {
    private val ownsStorage = storage == null
    private val storage = storage ?: BoundedWorkerStorage(context)
    private val targets = targets.toList()
    private val producers = mutableListOf<BoundedWorkerStorage.Sink>()
    private val temporary = mutableListOf<ParcelFileDescriptor>()
    private val snapshots = mutableListOf<ParcelFileDescriptor>()
    private var sizes = emptyList<Long>()
    private var validated = false
    private var commitStarted = false
    private var closed = false
    val workerDescriptors: List<ParcelFileDescriptor> get() = producers.map { it.writer }
    fun releaseWorkerDescriptors() { producers.forEach { runCatching { it.writer.close() } } }
    val validatedBytes: Long get() {
        check(validated && !closed)
        return sizes.sum()
    }

    init {
        require(targets.size <= minOf(SecurityLimits.MAX_BATCH_FDS, SecurityLimits.MAX_OUTPUT_FILES))
        require(targets.distinct().size == targets.size) { "Duplicate output destinations" }
        try { repeat(targets.size) { producers.add(this.storage.create(BoundedWorkerStorage.Kind.OUTPUT)) } }
        catch (error: Throwable) { close(); throw error }
    }

    private fun anonymousFile(): ParcelFileDescriptor {
        val file = File.createTempFile("host_output_", ".bin", context.cacheDir)
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            try { check(file.delete()); return fd }
            catch (error: Throwable) { fd.close(); throw error }
        } finally { file.delete() }
    }

    private fun size(fd: ParcelFileDescriptor): Long {
        val stat = Os.fstat(fd.fileDescriptor)
        require(OsConstants.S_ISREG(stat.st_mode)) { "Output must be a regular temporary file" }
        require(stat.st_size in 0..SecurityLimits.MAX_OUTPUT_BYTES) { "Output size quota exceeded" }
        return stat.st_size
    }

    private fun validateResult(result: String) {
        SecurityLimits.enforceResultSize(result)
        // Reject excessive nesting before the platform JSON parser recurses.
        var depth = 0; var quoted = false; var escaped = false
        for (char in result) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= SecurityLimits.MAX_REQUEST_DEPTH) { "Result nesting quota exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid worker result" } }
            }
        }
        require(depth == 0 && !quoted) { "Invalid worker result" }
        val parser = JSONTokener(result)
        val value = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "Trailing worker result data" }
        if (value is JSONObject) {
            require(!value.has("error")) { "Worker reported failure" }
            for (key in listOf("success", "isSuccess")) {
                require(!value.has(key) || value.get(key) == true) { "Worker reported failure" }
            }
            if (value.has("renderedCount")) {
                val count = value.get("renderedCount")
                require(count is Number && count.toDouble() == targets.size.toDouble()) { "Output count mismatch" }
            }
        }
        require(targets.isEmpty() || (value != false && value != JSONObject.NULL)) { "Worker reported failure" }
    }

    suspend fun validateAndSnapshot(result: String) {
        check(!closed && !validated && !commitStarted)
        validateResult(result)
        require(producers.size == targets.size) { "Output count mismatch" }
        releaseWorkerDescriptors()
        producers.forEach { producer ->
            currentCoroutineContext().ensureActive()
            temporary.add(storage.snapshotCancellable(producer.id))
        }
        require(temporary.size == targets.size) { "Output count mismatch" }
        sizes = temporary.map(::size)
        require(sizes.sum() <= SecurityLimits.MAX_OUTPUT_BYTES) { "Aggregate output quota exceeded" }
        val buffer = ByteArray(64 * 1024)
        // Snapshot every output before opening even the first real destination.
        // Every pipe has reached EOF; producer readback is read-only. These new
        // commit snapshots are host-only and are never sent to another process.
        for (index in temporary.indices) {
            currentCoroutineContext().ensureActive()
            val snapshot = anonymousFile().also(snapshots::add)
            var offset = 0L
            while (offset < sizes[index]) {
                currentCoroutineContext().ensureActive()
                val count = Os.pread(temporary[index].fileDescriptor, buffer, 0,
                    minOf(buffer.size.toLong(), sizes[index] - offset).toInt(), offset)
                if (count <= 0) throw IOException("Worker output changed during validation")
                var written = 0
                while (written < count) {
                    val bytes = Os.write(snapshot.fileDescriptor, buffer, written, count - written)
                    if (bytes <= 0) throw IOException("Unable to snapshot output")
                    written += bytes
                }
                offset += count
            }
            require(size(temporary[index]) == sizes[index]) { "Worker output changed during validation" }
            require(size(snapshot) == sizes[index]) { "Incomplete output snapshot" }
        }
        require(snapshots.size == targets.size && snapshots.sumOf(::size) <= SecurityLimits.MAX_OUTPUT_BYTES)
        currentCoroutineContext().ensureActive()
        validated = true
    }

    suspend fun commit() = kotlinx.coroutines.coroutineScope {
        check(!closed && validated && !commitStarted) { "Outputs must be validated and committed only once" }
        currentCoroutineContext().ensureActive()
        commitStarted = true
        val totalBytes = snapshots.sumOf(::size)
        val handle = com.pdfchemy.app.security.PublicationCoordinator.acquire(targets, totalBytes)
        var completedNormally = false
        val watcher = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                if (!completedNormally) {
                    handle.abort()
                }
            }
        }
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        if (job != null) {
            handle.attachJob(job)
        }
        try {
            val buffer = ByteArray(64 * 1024)
            var committed = 0L
            for (index in targets.indices) {
                currentCoroutineContext().ensureActive()
                // SAF may supply a pipe. No seek/truncate operation is performed on
                // its FD, and no real provider capability crosses the worker boundary.
                val stream = requireNotNull(context.contentResolver.openOutputStream(targets[index], "wt")) {
                    "Unable to open destination: ${targets[index]}"
                }
                handle.registerStream(stream).use {
                    stream.use { output ->
                        var offset = 0L
                        while (offset < sizes[index]) {
                            currentCoroutineContext().ensureActive()
                            val count = Os.pread(snapshots[index].fileDescriptor, buffer, 0,
                                minOf(buffer.size.toLong(), sizes[index] - offset).toInt(), offset)
                            if (count <= 0) throw IOException("Incomplete commit snapshot")
                            require(committed <= SecurityLimits.MAX_OUTPUT_BYTES - count) { "Commit quota exceeded" }
                            output.write(buffer, 0, count)
                            offset += count; committed += count
                        }
                    }
                }
            }
            completedNormally = true
        } finally {
            watcher.cancel()
            handle.close()
        }
    }

    override fun close() {
        closed = true
        (snapshots + temporary).forEach { runCatching { it.close() } }
        releaseWorkerDescriptors()
        snapshots.clear(); temporary.clear()
        if (ownsStorage) storage.close()
    }
}
