package com.pdfchemy.app.jail

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.pdfchemy.app.security.SecurityLimits

/** Single-flight service owns this operation resource set. */
object JailScratch {
    private var broker: IScratchBroker? = null
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private val written = AtomicLong()
    private val outputWritten = AtomicLong()
    private var outputs = emptySet<Pair<Long, Long>>()
    private fun identity(fd: java.io.FileDescriptor) = android.system.Os.fstat(fd).let { it.st_dev to it.st_ino }
    fun begin(binder: IBinder?, targets: List<ParcelFileDescriptor>) {
        check(broker == null)
        broker = IScratchBroker.Stub.asInterface(requireNotNull(binder))
        written.set(0); outputWritten.set(0)
        outputs = targets.map { identity(it.fileDescriptor) }.toSet()
    }
    @Synchronized fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
        check(descriptors.size < SecurityLimits.MAX_SCRATCH_FDS)
        val fd = requireNotNull(broker).allocate()
        descriptors.add(fd)
        return object : File("/proc/self/fd/${fd.fd}") {
            override fun delete(): Boolean { synchronized(JailScratch) { descriptors.remove(fd); runCatching { fd.close() } }; return true }
            override fun length(): Long = fd.statSize
            override fun exists(): Boolean = fd.fileDescriptor.valid()
        }
    }
    fun namedFile(name: String) = createTempFile("operation_", ".tmp")
    fun isOutput(fd: java.io.FileDescriptor) = identity(fd) in outputs
    fun accountWrite(bytes: Int, output: Boolean) {
        if (output) {
            require(outputWritten.addAndGet(bytes.toLong()) <= SecurityLimits.MAX_OUTPUT_BYTES) { "Aggregate output byte quota exceeded" }
        }
        require(written.addAndGet(bytes.toLong()) <= SecurityLimits.MAX_OPERATION_WRITE_BYTES) { "Operation write quota exceeded" }
    }
    @Synchronized fun close() { descriptors.forEach { runCatching { it.close() } }; descriptors.clear(); broker = null; outputs = emptySet() }
}

object JailMemory {
    fun settings() = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(SecurityLimits.MAX_PARSER_MEMORY_BYTES)
}
