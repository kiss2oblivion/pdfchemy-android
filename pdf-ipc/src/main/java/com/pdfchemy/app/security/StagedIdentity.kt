package com.pdfchemy.app.security

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.security.MessageDigest

object StagedIdentity {
    fun verifyAndRewind(fd: ParcelFileDescriptor, hash: String, size: Long) {
        SecurityLimits.requireIdentity(hash, size)
        require(fd.statSize == size) { "Staged size mismatch" }
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                require(total <= size) { "Staged file grew during verification" }
                digest.update(buffer, 0, read)
            }
        }
        require(total == size) { "Staged file changed during verification" }
        val expected = ByteArray(32) { hash.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        require(MessageDigest.isEqual(digest.digest(), expected)) { "Staged SHA-256 mismatch" }
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
    }
    fun identity(fd: ParcelFileDescriptor): Pair<String, Long> {
        val size = fd.statSize
        require(size in 1..SecurityLimits.MAX_PDF_FILESIZE)
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        val digest = MessageDigest.getInstance("SHA-256")
        ParcelFileDescriptor.AutoCloseInputStream(fd.dup()).use { stream ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                total += read
                require(total <= size)
                digest.update(buffer, 0, read)
            }
            require(total == size)
        }
        Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_SET)
        return digest.digest().joinToString("") { "%02x".format(it) } to size
    }
}
