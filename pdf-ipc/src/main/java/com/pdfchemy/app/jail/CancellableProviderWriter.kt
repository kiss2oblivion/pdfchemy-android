package com.pdfchemy.app.jail

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/** Host-side writer: closing a pipe does not wake a blocked write on every Android release. */
class CancellableProviderWriter private constructor(private val output: OutputStream) {
    private val pipe = resolvePipe(output)
    private val nonBlockingPipe = pipe != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

    private fun resolvePipe(output: OutputStream): java.io.FileDescriptor? {
        val fd = (output as? FileOutputStream)?.fd ?: return null
        if (!OsConstants.S_ISFIFO(Os.fstat(fd).st_mode)) return null
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        }
        return fd
    }
    private val polls = pipe?.let { descriptor ->
        arrayOf(StructPollfd().apply {
            fd = descriptor
            events = OsConstants.POLLOUT.toShort()
        })
    }

    companion object {
        // POSIX PIPE_BUF (4096 bytes) fits within a single free Linux pipe page once POLLOUT is signaled.
        private const val SAFE_PIPE_CHUNK_BYTES = 4096

        suspend fun create(output: OutputStream): CancellableProviderWriter {
            currentCoroutineContext().ensureActive()
            try {
                return CancellableProviderWriter(output)
            } catch (error: Exception) {
                // Cancellation can close the stream between readiness and FD probing.
                currentCoroutineContext().ensureActive()
                throw error
            }
        }
    }

    private suspend fun pollWritable(pollArray: Array<StructPollfd>): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val ready = try {
                Os.poll(pollArray, 50)
            } catch (pollError: ErrnoException) {
                if (pollError.errno == OsConstants.EINTR) continue
                currentCoroutineContext().ensureActive()
                throw IOException("Unable to wait for destination", pollError)
            }
            if (ready == 0) return false
            val revents = pollArray[0].revents.toInt()
            if ((revents and (OsConstants.POLLOUT or OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                return true
            }
        }
    }

    suspend fun write(bytes: ByteArray, count: Int) {
        currentCoroutineContext().ensureActive()
        require(count in 0..bytes.size)
        val descriptor = pipe
        if (descriptor == null) {
            output.write(bytes, 0, count)
            return
        }
        val pollArray = requireNotNull(polls)
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            if (!nonBlockingPipe && !pollWritable(pollArray)) continue
            val chunkSize = if (nonBlockingPipe) count - offset else minOf(count - offset, SAFE_PIPE_CHUNK_BYTES)
            try {
                val written = Os.write(descriptor, bytes, offset, chunkSize)
                if (written <= 0) throw IOException("Unable to write destination")
                offset += written
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                if (error.errno != OsConstants.EAGAIN) {
                    currentCoroutineContext().ensureActive()
                    throw IOException("Unable to write destination", error)
                }
                pollWritable(pollArray)
            }
        }
    }
}
