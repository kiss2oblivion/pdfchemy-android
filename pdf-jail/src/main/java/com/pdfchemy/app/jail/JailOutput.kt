package com.pdfchemy.app.jail

import java.io.File
import java.io.FileDescriptor
import com.pdfchemy.app.security.BoundedOutputStream

fun boundedFileOutput(file: File) = CapabilityIo.writableFd(file).use {
    if (android.system.OsConstants.S_ISREG(android.system.Os.fstat(it.fileDescriptor).st_mode)) {
        android.system.Os.lseek(it.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
        android.system.Os.ftruncate(it.fileDescriptor, 0)
    }
    boundedFileOutput(it.fileDescriptor)
}
fun boundedFileOutput(path: String) = boundedFileOutput(File(path))
fun boundedFileOutput(fd: FileDescriptor): java.io.OutputStream {
    val output = JailScratch.isOutput(fd)
    val count = JailScratch.outputCounter(fd)
    val bounded = BoundedOutputStream(android.os.ParcelFileDescriptor.AutoCloseOutputStream(android.os.ParcelFileDescriptor.dup(fd)))
    return object : java.io.OutputStream() {
        override fun write(value: Int) { JailScratch.accountWrite(1, output); bounded.write(value); count?.incrementAndGet() }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { JailScratch.accountWrite(length, output); bounded.write(bytes, offset, length); count?.addAndGet(length.toLong()) }
        override fun flush() = bounded.flush()
        override fun close() = bounded.close()
    }
}
