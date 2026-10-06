package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import com.pdfchemy.app.jail.StagedPdf
import com.pdfchemy.app.security.SecurityLimits
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

object DocumentStager {
    private val staged = ConcurrentHashMap<String, StagedPdf>()
    private val displayNames = ConcurrentHashMap<String, String>()
    private val leases = mutableMapOf<String, Int>()
    private val pendingRelease = mutableSetOf<String>()
    private val initializedDirectories = mutableSetOf<String>()
    @Synchronized
    fun stageDocument(context: Context, sourceUri: Uri, checkCancellation: () -> Unit = {}, onInputOpened: (InputStream) -> Unit = {}): StagedPdf {
        checkCancellation()
        if (sourceUri.scheme == "file") {
            val file = File(requireNotNull(sourceUri.path)).canonicalFile
            val directory = File(context.cacheDir, "staged").canonicalFile
            if (file.parentFile == directory) {
                staged[file.path]?.let {
                    require(file.length() == it.size) { "Staged capability changed" }
                    return it
                }
            }
        }
        val name = FileUtils.getFileName(context, sourceUri)
        val snapshot = (context.contentResolver.openInputStream(sourceUri) ?: error("Cannot open input")).use {
            onInputOpened(it)
            stageStream(context, it, name?.substringAfterLast('.')?.lowercase(), checkCancellation)
        }
        name?.take(512)?.let { displayNames[requireNotNull(snapshot.uri.path)] = it }
        return snapshot
    }
    @Synchronized
    fun stageStream(context: Context, input: InputStream, extension: String? = null, checkCancellation: () -> Unit = {}): StagedPdf {
        checkCancellation()
        require(staged.size < SecurityLimits.MAX_STAGED_DOCUMENTS) { "Too many staged documents" }
        val directory = File(context.cacheDir, "staged").apply { mkdirs() }.canonicalFile
        if (directory.path !in initializedDirectories) {
            directory.listFiles().orEmpty().filter { it.name.startsWith("snapshot_") && it.canonicalFile.parentFile == directory }
                .forEach { check(it.delete()) { "Unable to remove an expired snapshot" } }
            initializedDirectories.add(directory.path)
        }
        val suffix = if (extension in setOf("pdf", "epub", "cbz", "jpg", "jpeg", "png", "webp", "heic", "heif", "txt")) ".$extension" else ".bin"
        val file = File.createTempFile("snapshot_", suffix, directory)
        val remainingBytes = SecurityLimits.MAX_BATCH_INPUT_BYTES - staged.values.sumOf { it.size }
        try {
            var size = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancellation()
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Staging cancelled")
                    val read = input.read(buffer)
                    checkCancellation()
                    if (read == -1) break
                    if (read.toLong() > minOf(SecurityLimits.MAX_PDF_FILESIZE, remainingBytes) - size) throw SecurityException("Staging input quota exceeded")
                    size += read
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            checkCancellation()
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            SecurityLimits.requireIdentity(hash, size)
            check(file.setReadOnly()) { "Unable to seal staged input" }
            val capability = StagedPdf(Uri.fromFile(file), hash, size)
            staged[file.canonicalPath] = capability
            return capability
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }
    @Synchronized fun retain(document: StagedPdf): java.io.Closeable {
        val path = requireNotNull(document.uri.path)
        require(staged[path] == document) { "Expired staged capability" }
        leases[path] = (leases[path] ?: 0) + 1
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable {
            if (closed.compareAndSet(false, true)) synchronized(this) {
                val remaining = requireNotNull(leases[path]) - 1
                if (remaining == 0) {
                    leases.remove(path)
                    if (pendingRelease.remove(path)) remove(document)
                } else leases[path] = remaining
            }
        }
    }
    suspend fun stageDocumentCancellable(context: Context, sourceUri: Uri): StagedPdf {
        return copyCancellable(sourceUri) { checkpoint, opened -> stageDocument(context, sourceUri, checkpoint, opened) }
    }
    suspend fun stageStreamCancellable(context: Context, input: InputStream): StagedPdf {
        return copyCancellable(null) { checkpoint, opened ->
            opened(input)
            stageStream(context, input, checkCancellation = checkpoint)
        }
    }
    private suspend fun copyCancellable(borrowedUri: Uri?, copy: (() -> Unit, (InputStream) -> Unit) -> StagedPdf): StagedPdf {
        val job = currentCoroutineContext()[Job]
        val input = java.util.concurrent.atomic.AtomicReference<InputStream>()
        var result: StagedPdf? = null
        try {
            return coroutineScope {
                val finished = java.util.concurrent.atomic.AtomicBoolean()
                val closeOnCancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { if (!finished.get()) runCatching { input.get()?.close() } }
                }
                try {
                    runInterruptible(Dispatchers.IO) {
                        copy({ job?.ensureActive() }, { input.set(it) }).also { result = it }
                    }
                } finally {
                    finished.set(true)
                    withContext(NonCancellable) { closeOnCancellation.cancelAndJoin() }
                }
            }
        } catch (error: Throwable) {
            result?.takeIf { it.uri != borrowedUri }?.let(::release)
            throw error
        }
    }
    private fun remove(document: StagedPdf) {
        val path = document.uri.path ?: return
        if (staged.remove(path, document)) { displayNames.remove(path); File(path).delete() }
    }
    @Synchronized fun release(document: StagedPdf) {
        val path = document.uri.path ?: return
        if (staged[path] != document) return
        if ((leases[path] ?: 0) > 0) pendingRelease.add(path) else remove(document)
    }
    @Synchronized fun releaseAll() { staged.values.toList().forEach(::release) }
    fun release(uri: Uri) {
        if (uri.scheme == "file") uri.path?.let { staged[it]?.let(::release) }
    }
    fun displayName(uri: Uri): String? = uri.path?.let(displayNames::get)
}
