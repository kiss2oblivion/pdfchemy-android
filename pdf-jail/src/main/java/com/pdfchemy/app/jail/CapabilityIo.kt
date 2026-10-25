package com.pdfchemy.app.jail

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.io.OutputStream

/** No open(path) occurs for transferred capabilities: dup preserves Android FD authorization. */
object CapabilityIo {
    fun fd(file: File): ParcelFileDescriptor {
        require(file.path.startsWith("/proc/self/fd/")) { "Descriptor capability required" }
        val number = file.name.toInt()
        return JailScratch.readDescriptor(number) ?: ParcelFileDescriptor.fromFd(number)
    }
    fun writableFd(file: File): ParcelFileDescriptor {
        require(file.path.startsWith("/proc/self/fd/")) { "Descriptor capability required" }
        val number = file.name.toInt()
        return JailScratch.writeDescriptor(number) ?: ParcelFileDescriptor.fromFd(number)
    }
    fun input(file: File): InputStream = fd(file).use { input(it.fileDescriptor) }
    fun input(path: String): InputStream = input(File(path))
    fun input(descriptor: FileDescriptor): InputStream {
        val duplicate = ParcelFileDescriptor.dup(descriptor)
        try {
            android.system.Os.lseek(duplicate.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            return ParcelFileDescriptor.AutoCloseInputStream(duplicate)
        } catch (error: Exception) { duplicate.close(); throw error }
    }
    fun output(file: File): OutputStream = boundedFileOutput(file)
    fun input(context: Context, uri: Uri): InputStream = input(File(requireNotNull(uri.path)))
    fun output(context: Context, uri: Uri): OutputStream = output(File(requireNotNull(uri.path)))
    fun descriptor(context: Context, uri: Uri, mode: String): ParcelFileDescriptor =
        if (mode.contains('w')) writableFd(File(requireNotNull(uri.path))) else fd(File(requireNotNull(uri.path)))
}

fun capabilityInput(file: File) = CapabilityIo.input(file)
fun capabilityInput(path: String) = CapabilityIo.input(path)
fun capabilityInput(fd: FileDescriptor) = CapabilityIo.input(fd)
