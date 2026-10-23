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
    data class StagedLease(
        val document: StagedPdf,
        val lease: java.io.Closeable
    ) : java.io.Closeable {
        override fun close() = lease.close()

        fun discard(borrowedUri: Uri? = null) {
            if (borrowedUri == null || document.uri != borrowedUri) {
                release(document)
            }
            lease.close()
        }
    }

    private val staged = ConcurrentHashMap<String, StagedPdf>()
    private val displayNames = ConcurrentHashMap<String, String>()
    private val originalSourceUris = ConcurrentHashMap<String, Uri>()
    private val leases = mutableMapOf<String, Int>()
    private val pendingRelease = mutableSetOf<String>()
    private val initializedDirectories = mutableSetOf<String>()

    @Synchronized
    fun stageDocument(
        context: Context,
        sourceUri: Uri,
        checkCancellation: () -> Unit = {},
        onInputOpened: (InputStream) -> Unit = {}
    ): StagedPdf = stageDocumentInternal(context, sourceUri, checkCancellation, onInputOpened, acquireInitialLease = false).document

    @Synchronized
    fun stageDocumentWithLease(
        context: Context,
        sourceUri: Uri,
        checkCancellation: () -> Unit = {},
        onInputOpened: (InputStream) -> Unit = {}
    ): StagedLease = stageDocumentInternal(context, sourceUri, checkCancellation, onInputOpened, acquireInitialLease = true)

    @Synchronized
    private fun stageDocumentInternal(
        context: Context,
        sourceUri: Uri,
        checkCancellation: () -> Unit = {},
        onInputOpened: (InputStream) -> Unit = {},
        acquireInitialLease: Boolean
    ): StagedLease {
        checkCancellation()
        if (sourceUri.scheme == "file") {
            val file = File(requireNotNull(sourceUri.path)).canonicalFile
            val directory = File(context.cacheDir, "staged").canonicalFile
            if (file.parentFile == directory) {
                staged[file.path]?.let {
                    require(file.length() == it.size) { "Staged capability changed" }
                    val lease = if (acquireInitialLease) retain(it) else java.io.Closeable {}
                    return StagedLease(it, lease)
                }
            }
        }
        val name = FileUtils.getFileName(context, sourceUri)
        val stagedLease = (context.contentResolver.openInputStream(sourceUri) ?: error("Cannot open input")).use {
            onInputOpened(it)
            stageStreamInternal(context, it, name?.substringAfterLast('.')?.lowercase(), checkCancellation, acquireInitialLease)
        }
        val path = requireNotNull(stagedLease.document.uri.path)
        name?.take(512)?.let { displayNames[path] = it }
        originalSourceUris[path] = sourceUri
        runCatching { File(path).canonicalPath }.getOrNull()?.let {
            if (it != path) originalSourceUris[it] = sourceUri
        }
        return stagedLease
    }
    const val MAX_UNLEASED_DOCUMENTS = 4

    @Synchronized
    fun pruneUnleasedSnapshots(neededBytes: Long = 0L, targetCount: Int = SecurityLimits.MAX_STAGED_DOCUMENTS - 1): Int {
        val unleased = staged.values.toSet().filter { doc ->
            val path = doc.uri.path ?: return@filter false
            (leases[path] ?: 0) == 0
        }.sortedBy { doc ->
            File(doc.uri.path!!).lastModified()
        }

        var pruned = 0
        for (doc in unleased) {
            val totalBytes = staged.values.toSet().sumOf { it.size }
            val remainingBytes = SecurityLimits.MAX_BATCH_INPUT_BYTES - totalBytes
            if (staged.values.toSet().size <= targetCount && remainingBytes >= neededBytes) {
                break
            }
            remove(doc)
            pruned++
        }
        return pruned
    }

    @Synchronized
    fun trimUnleasedToBound(maxUnleased: Int = MAX_UNLEASED_DOCUMENTS): Int {
        val unleased = staged.values.toSet().filter { doc ->
            val path = doc.uri.path ?: return@filter false
            (leases[path] ?: 0) == 0
        }.sortedBy { doc ->
            File(doc.uri.path!!).lastModified()
        }

        var pruned = 0
        val excess = unleased.size - maxUnleased
        if (excess > 0) {
            for (i in 0 until excess) {
                remove(unleased[i])
                pruned++
            }
        }
        return pruned
    }

    @Synchronized
    fun stageStream(
        context: Context,
        input: InputStream,
        extension: String? = null,
        checkCancellation: () -> Unit = {}
    ): StagedPdf = stageStreamInternal(context, input, extension, checkCancellation, acquireInitialLease = false).document

    @Synchronized
    fun stageStreamWithLease(
        context: Context,
        input: InputStream,
        extension: String? = null,
        checkCancellation: () -> Unit = {}
    ): StagedLease = stageStreamInternal(context, input, extension, checkCancellation, acquireInitialLease = true)

    @Synchronized
    private fun stageStreamInternal(
        context: Context,
        input: InputStream,
        extension: String? = null,
        checkCancellation: () -> Unit = {},
        acquireInitialLease: Boolean
    ): StagedLease {
        checkCancellation()
        if (staged.values.toSet().size >= SecurityLimits.MAX_STAGED_DOCUMENTS) {
            pruneUnleasedSnapshots(targetCount = SecurityLimits.MAX_STAGED_DOCUMENTS - 1)
        }
        require(staged.values.toSet().size < SecurityLimits.MAX_STAGED_DOCUMENTS) { "Too many staged documents" }
        val directory = File(context.cacheDir, "staged").apply { mkdirs() }.canonicalFile
        if (directory.path !in initializedDirectories) {
            directory.listFiles().orEmpty().filter { it.name.startsWith("snapshot_") && it.canonicalFile.parentFile == directory }
                .forEach { check(it.delete()) { "Unable to remove an expired snapshot" } }
            initializedDirectories.add(directory.path)
        }
        val suffix = if (extension in setOf("pdf", "epub", "cbz", "jpg", "jpeg", "png", "webp", "heic", "heif", "txt")) ".$extension" else ".bin"
        val file = File.createTempFile("snapshot_", suffix, directory)
        var remainingBytes = SecurityLimits.MAX_BATCH_INPUT_BYTES - staged.values.toSet().sumOf { it.size }
        if (remainingBytes <= 0) {
            pruneUnleasedSnapshots(neededBytes = 1024 * 1024)
            remainingBytes = SecurityLimits.MAX_BATCH_INPUT_BYTES - staged.values.toSet().sumOf { it.size }
        }
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
                    if (read.toLong() > minOf(SecurityLimits.MAX_PDF_FILESIZE, remainingBytes) - size) {
                        pruneUnleasedSnapshots(neededBytes = read.toLong())
                        val updatedRemaining = SecurityLimits.MAX_BATCH_INPUT_BYTES - staged.values.toSet().sumOf { it.size }
                        if (read.toLong() > minOf(SecurityLimits.MAX_PDF_FILESIZE, updatedRemaining) - size) {
                            throw SecurityException("Staging input quota exceeded")
                        }
                    }
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
            val canonical = file.canonicalPath
            staged[canonical] = capability
            val uriPath = capability.uri.path
            if (uriPath != null && uriPath != canonical) {
                staged[uriPath] = capability
            }
            val lease = if (acquireInitialLease) retain(capability) else java.io.Closeable {}
            trimUnleasedToBound()
            return StagedLease(capability, lease)
        } catch (error: Throwable) {
            staged.remove(file.canonicalPath)
            file.canonicalFile.path.let { staged.remove(it) }
            file.absolutePath.let { staged.remove(it) }
            leases.remove(file.canonicalPath)
            leases.remove(file.absolutePath)
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
                val remaining = (leases[path] ?: 1) - 1
                if (remaining <= 0) {
                    leases.remove(path)
                    if (pendingRelease.remove(path)) remove(document)
                } else leases[path] = remaining
            }
        }
    }
    @Synchronized fun retain(uri: Uri): java.io.Closeable? {
        val snapshot = stagedFor(uri) ?: return null
        return retain(snapshot)
    }
    fun stagedFor(uri: Uri): StagedPdf? = if (uri.scheme == "file") uri.path?.let { staged[it] } else null
    @Synchronized fun activeLeases(uri: Uri): Int = uri.path?.let { leases[it] } ?: 0
    @Synchronized fun activeLeases(document: StagedPdf): Int = document.uri.path?.let { leases[it] } ?: 0
    @Synchronized fun isPendingRelease(uri: Uri): Boolean = uri.path?.let { pendingRelease.contains(it) } ?: false

    @Synchronized
    fun isLeased(file: File): Boolean {
        val candidates = listOfNotNull(
            file.path,
            file.absolutePath,
            runCatching { file.canonicalPath }.getOrNull()
        )
        return candidates.any { path ->
            (leases[path] ?: 0) > 0 || staged[path]?.let { doc -> doc.uri.path?.let { (leases[it] ?: 0) > 0 } } == true
        }
    }

    @Synchronized
    fun isLeased(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return (leases[path] ?: 0) > 0 || isLeased(File(path))
    }

    @Synchronized
    fun reclaimUnleasedSnapshot(file: File): Boolean {
        val candidates = listOfNotNull(
            file.path,
            file.absolutePath,
            runCatching { file.canonicalPath }.getOrNull()
        )
        for (path in candidates) {
            val doc = staged[path]
            if (doc != null) {
                val docPath = doc.uri.path ?: return false
                if ((leases[docPath] ?: 0) > 0) {
                    return false
                }
                remove(doc)
                return true
            }
        }
        return false
    }

    val stagedCount: Int get() = staged.values.toSet().size
    val totalStagedBytes: Long get() = staged.values.toSet().sumOf { it.size }
    suspend fun stageDocumentCancellable(context: Context, sourceUri: Uri): StagedPdf {
        return copyCancellable(sourceUri) { checkpoint, opened -> stageDocument(context, sourceUri, checkpoint, opened) }
    }
    suspend fun stageStreamCancellable(context: Context, input: InputStream): StagedPdf {
        return copyCancellable(null) { checkpoint, opened ->
            opened(input)
            stageStream(context, input, checkCancellation = checkpoint)
        }
    }
    suspend fun stageDocumentCancellableWithLease(context: Context, sourceUri: Uri): StagedLease {
        return copyCancellableWithLease(sourceUri) { checkpoint, opened ->
            stageDocumentWithLease(context, sourceUri, checkpoint, opened)
        }
    }
    suspend fun stageStreamCancellableWithLease(context: Context, input: InputStream): StagedLease {
        return copyCancellableWithLease(null) { checkpoint, opened ->
            opened(input)
            stageStreamWithLease(context, input, checkCancellation = checkpoint)
        }
    }
    suspend fun stageDocumentCancellationSafe(
        context: Context,
        sourceUri: Uri,
        transferOwner: ResourceTransferOwner<StagedLease>? = null
    ): StagedLease {
        val owner = transferOwner ?: ResourceTransferOwner { it.discard(sourceUri) }
        try {
            withContext(Dispatchers.IO) {
                val stagedLease = stageDocumentCancellableWithLease(context, sourceUri)
                owner.stage(stagedLease)
                stagedLease
            }
            return checkNotNull(owner.commit()) { "Staged lease transfer not committed" }
        } catch (e: Throwable) {
            owner.discard()
            throw e
        }
    }
    suspend fun stageStreamCancellationSafe(
        context: Context,
        input: InputStream,
        transferOwner: ResourceTransferOwner<StagedLease>? = null
    ): StagedLease {
        val owner = transferOwner ?: ResourceTransferOwner { it.discard(null) }
        try {
            withContext(Dispatchers.IO) {
                val stagedLease = stageStreamCancellableWithLease(context, input)
                owner.stage(stagedLease)
                stagedLease
            }
            return checkNotNull(owner.commit()) { "Staged stream lease transfer not committed" }
        } catch (e: Throwable) {
            owner.discard()
            throw e
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
    private suspend fun copyCancellableWithLease(
        borrowedUri: Uri?,
        copy: (() -> Unit, (InputStream) -> Unit) -> StagedLease
    ): StagedLease {
        val job = currentCoroutineContext()[Job]
        val input = java.util.concurrent.atomic.AtomicReference<InputStream>()
        var result: StagedLease? = null
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
            result?.let {
                if (it.document.uri != borrowedUri) {
                    release(it.document)
                }
                it.lease.close()
            }
            throw error
        }
    }
    private fun remove(document: StagedPdf) {
        val path = document.uri.path ?: return
        val removed = staged.remove(path, document)
        displayNames.remove(path)
        originalSourceUris.remove(path)
        val file = File(path)
        val canonical = runCatching { file.canonicalPath }.getOrNull()
        if (canonical != null && canonical != path) {
            staged.remove(canonical, document)
            displayNames.remove(canonical)
            originalSourceUris.remove(canonical)
        }
        file.delete()
    }
    @Synchronized fun release(document: StagedPdf) {
        val path = document.uri.path ?: return
        if (staged[path] != document) return
        if ((leases[path] ?: 0) > 0) {
            pendingRelease.add(path)
        } else {
            remove(document)
        }
        trimUnleasedToBound()
    }
    @Synchronized fun releaseAll() {
        staged.values.toList().forEach(::release)
        trimUnleasedToBound(0)
    }
    fun release(uri: Uri) {
        if (uri.scheme == "file") uri.path?.let { staged[it]?.let(::release) }
    }
    fun displayName(uri: Uri): String? = uri.path?.let(displayNames::get)
    fun originalSourceFor(uri: Uri): Uri? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            return originalSourceUris[path] ?: runCatching { File(path).canonicalPath }.getOrNull()?.let { originalSourceUris[it] }
        }
        return null
    }
    fun originalSourceFor(document: StagedPdf): Uri? = originalSourceFor(document.uri)
}
