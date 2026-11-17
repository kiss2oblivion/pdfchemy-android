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
    private val pipe = (output as? FileOutputStream)?.fd?.takeIf {
        OsConstants.S_ISFIFO(Os.fstat(it).st_mode)
    }?.also {
        val flags = Os.fcntlInt(it, OsConstants.F_GETFL, 0)
        Os.fcntlInt(it, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }
    private val polls = pipe?.let { descriptor ->
        arrayOf(StructPollfd().apply {
            fd = descriptor
            events = OsConstants.POLLOUT.toShort()
        })
    }

    companion object {
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

    suspend fun write(bytes: ByteArray, count: Int) {
        currentCoroutineContext().ensureActive()
        require(count in 0..bytes.size)
        val descriptor = pipe
        if (descriptor == null) {
            output.write(bytes, 0, count)
            return
        }
        var offset = 0
        while (offset < count) {
            currentCoroutineContext().ensureActive()
            try {
                val written = Os.write(descriptor, bytes, offset, count - offset)
                if (written <= 0) throw IOException("Unable to write destination")
                offset += written
            } catch (error: ErrnoException) {
                if (error.errno == OsConstants.EINTR) continue
                if (error.errno != OsConstants.EAGAIN) {
                    currentCoroutineContext().ensureActive()
                    throw IOException("Unable to write destination", error)
                }
                try { Os.poll(requireNotNull(polls), 50) }
                catch (pollError: ErrnoException) {
                    if (pollError.errno != OsConstants.EINTR) {
                        currentCoroutineContext().ensureActive()
                        throw IOException("Unable to wait for destination", pollError)
                    }
                }
            }
        }
    }
}
