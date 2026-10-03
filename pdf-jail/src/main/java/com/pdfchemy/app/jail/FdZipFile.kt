package com.pdfchemy.app.jail

import android.os.ParcelFileDescriptor
import android.system.Os
import com.pdfchemy.app.security.SecurityLimits
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Bounded archive index and one spool FD, usable by isolated UIDs on API 24+. */
class FdZipFile(file: File) : Closeable {
    private data class Entry(val entry: ZipEntry, val offset: Long, val length: Long)
    private val scratch = JailScratch.createTempFile("archive_", ".spool")
    private val fd = CapabilityIo.fd(scratch)
    private val index = linkedMapOf<String, Entry>()
    init {
        try {
            boundedFileOutput(fd.fileDescriptor).use { output ->
                ZipInputStream(CapabilityIo.input(file)).use { zip ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    var count = 0
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++count <= SecurityLimits.MAX_ARCHIVE_FILE_COUNT)
                        require(entry.name.length <= SecurityLimits.MAX_METADATA_LENGTH && !index.containsKey(entry.name)) { "Invalid or duplicate archive entry" }
                        val start = total
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break
                            total += read
                            require(total <= SecurityLimits.MAX_ARCHIVE_BYTES_READ) { "Archive expansion quota exceeded" }
                            output.write(buffer, 0, read)
                        }
                        val copy = ZipEntry(entry.name).apply { size = total - start }
                        index[entry.name] = Entry(copy, start, total - start)
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Throwable) { close(); throw e }
    }
    fun entries(): Enumeration<ZipEntry> = Collections.enumeration(index.values.map { it.entry })
    fun getEntry(name: String): ZipEntry? = index[name]?.entry
    fun getInputStream(entry: ZipEntry): InputStream {
        val record = requireNotNull(index[entry.name])
        return object : InputStream() {
            private var position = 0L
            override fun read(): Int { val bytes = ByteArray(1); return if (read(bytes, 0, 1) < 0) -1 else bytes[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= record.length) return -1
                val count = minOf(length.toLong(), record.length - position).toInt()
                val read = Os.pread(fd.fileDescriptor, bytes, offset, count, record.offset + position)
                require(read > 0) { "Archive spool truncated" }
                position += read
                return read
            }
        }
    }
    override fun close() { runCatching { fd.close() }; scratch.delete() }
}
