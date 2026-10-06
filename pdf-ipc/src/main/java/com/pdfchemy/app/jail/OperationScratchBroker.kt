package com.pdfchemy.app.jail

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import com.pdfchemy.app.security.SecurityLimits
import java.io.Closeable
import java.io.File

/** Anonymous scratch files are capabilities, never paths into host private storage. */
class OperationScratchBroker(private val context: Context) : IScratchBroker.Stub(), Closeable {
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private var closed = false
    @Synchronized override fun allocate(): ParcelFileDescriptor {
        check(!closed && descriptors.size < SecurityLimits.MAX_SCRATCH_FDS) { "Scratch descriptor quota exceeded" }
        val file = File.createTempFile("scratch_", ".bin", context.cacheDir)
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            try {
                check(file.delete()) { "Unable to unlink scratch capability" }
                descriptors.add(fd.dup())
                return fd // Binder closes the return-value descriptor after transfer.
            } catch (e: Exception) { fd.close(); throw e }
        } finally { file.delete() }
    }
    @Synchronized fun verifyBudget() {
        require(descriptors.sumOf { it.statSize.coerceAtLeast(0) } <= SecurityLimits.MAX_OUTPUT_BYTES) { "Scratch storage quota exceeded" }
    }
    @Synchronized override fun close() {
        closed = true
        descriptors.forEach { runCatching { it.close() } }
        descriptors.clear()
    }
}
