package com.pdfchemy.app.jail

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import com.pdfchemy.app.security.SecurityLimits

/** Single-flight service owns this operation resource set. */
object JailScratch {
    private var broker: IScratchBroker? = null
    private class Capability(val descriptor: ParcelFileDescriptor, val output: Boolean) {
        val id = android.system.Os.fstat(descriptor.fileDescriptor).st_ino
        val bytes = AtomicLong()
        var sealed = false
        var opened = false
    }
    private val capabilities = linkedMapOf<Int, Capability>()
    private val written = AtomicLong()
    private val outputWritten = AtomicLong()
    private fun identity(fd: java.io.FileDescriptor) = android.system.Os.fstat(fd).let { it.st_dev to it.st_ino }
    @Synchronized fun begin(binder: IBinder?, targets: List<ParcelFileDescriptor>) {
        check(broker == null)
        broker = IScratchBroker.Stub.asInterface(requireNotNull(binder))
        written.set(0); outputWritten.set(0)
        targets.forEach { capabilities[it.fd] = Capability(it, true) }
    }
    @Synchronized fun createTempFile(prefix: String, suffix: String?, directory: File? = null): File {
        check(capabilities.values.count { !it.output } < SecurityLimits.MAX_SCRATCH_FDS)
        val fd = requireNotNull(broker).allocate()
        val number = fd.fd
        capabilities[number] = Capability(fd, false)
        return object : File("/proc/self/fd/${fd.fd}") {
            override fun delete(): Boolean { synchronized(JailScratch) { capabilities.remove(number); runCatching { fd.close() } }; return true }
            override fun length(): Long = readDescriptor(number)!!.use { it.statSize }
            override fun exists(): Boolean = fd.fileDescriptor.valid()
        }
    }
    fun namedFile(name: String) = createTempFile("operation_", ".tmp")
    @Synchronized private fun find(fd: java.io.FileDescriptor): Capability? {
        capabilities.values.firstOrNull { it.descriptor.fileDescriptor === fd }?.let { return it }
        val key = identity(fd)
        return capabilities.values.firstOrNull { it.descriptor.fileDescriptor.valid() && identity(it.descriptor.fileDescriptor) == key }
    }
    fun isOutput(fd: java.io.FileDescriptor) = find(fd)?.output == true
    fun outputCounter(fd: java.io.FileDescriptor): AtomicLong? = find(fd)?.takeIf { it.output }?.bytes
    fun outputSize(fd: java.io.FileDescriptor): Long = requireNotNull(find(fd)) { "Unknown output capability" }.let {
        if (it.sealed) it.descriptor.statSize else it.bytes.get()
    }

    @Synchronized fun writeDescriptor(number: Int): ParcelFileDescriptor? {
        val capability = capabilities[number] ?: return null
        check(!capability.sealed && !capability.opened) { "Capability has already been written or sealed" }
        capability.opened = true
        return capability.descriptor.dup()
    }

    @Synchronized fun readDescriptor(number: Int): ParcelFileDescriptor? {
        val capability = capabilities[number] ?: return null
        if (!capability.sealed) {
            // Reserve the numeric FD with a read-only placeholder while closing
            // its pipe writer. This prevents FD reuse during the EOF handoff.
            val placeholder = ParcelFileDescriptor.createPipe()
            try {
                placeholder[1].close()
                android.system.Os.dup2(placeholder[0].fileDescriptor, capability.descriptor.fd)
            } finally { placeholder.forEach { runCatching { it.close() } } }
            capability.sealed = true
            requireNotNull(broker).snapshot(capability.id).use { snapshot ->
                android.system.Os.dup2(snapshot.fileDescriptor, capability.descriptor.fd)
            }
        }
        return capability.descriptor.dup()
    }
    fun accountWrite(bytes: Int, output: Boolean) {
        if (output) {
            require(outputWritten.addAndGet(bytes.toLong()) <= SecurityLimits.MAX_OUTPUT_BYTES) { "Aggregate output byte quota exceeded" }
        }
        require(written.addAndGet(bytes.toLong()) <= SecurityLimits.MAX_OPERATION_WRITE_BYTES) { "Operation write quota exceeded" }
    }
    @Synchronized fun close() { capabilities.values.forEach { runCatching { it.descriptor.close() } }; capabilities.clear(); broker = null }
}

object JailMemory {
    fun settings() = com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMainMemoryOnly(SecurityLimits.MAX_PARSER_MEMORY_BYTES)
}
