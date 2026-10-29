package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import com.pdfchemy.app.security.SecurityLimits
import com.pdfchemy.app.utils.ResourceTransferOwner

object DocumentActions {
    fun view(context: Context, uri: Uri, displayName: String? = null) {
        val output = getShareableContentUri(context, uri, displayName)
        val mimeType = com.pdfchemy.app.utils.FileUtils.getMimeType(context, output)
        val internalReaderType = mimeType in setOf("application/pdf", "application/epub+zip",
            "application/vnd.comicbook+zip", "application/x-cbz", "application/vnd.comicbook-rar")
        if (internalReaderType && com.pdfchemy.app.security.ExternalUriAdmission.isAppOwnedContentUri(context, output)) {
            context.startActivity(com.pdfchemy.app.security.InternalDocumentIntents.create(context, output))
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(output, mimeType)
            clipData = android.content.ClipData.newUri(context.contentResolver, "Document", output)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!context.hasActivityContext()) {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    @androidx.annotation.VisibleForTesting
    internal var copyHookForTesting: (() -> Unit)? = null

    suspend fun shareAsync(context: Context, uri: Uri, displayName: String? = null) {
        val shareUri = getShareableContentUriAsync(context, uri, displayName)
        withContext(Dispatchers.Main) {
            ShareUtil.launchShareIntent(context, listOf(shareUri), title = displayName ?: "Share PDF")
        }
    }

    suspend fun getShareableContentUriAsync(
        context: Context,
        uri: Uri,
        customDisplayName: String? = null
    ): Uri = ResourceTransferOwner<PreparedShare> { it.discard() }.use { owner ->
        val result = withContext(Dispatchers.IO) {
            val jobContext = currentCoroutineContext()
            prepareShare(context, uri, customDisplayName) { jobContext.ensureActive() }.also(owner::stage)
        }
        // withContext may discard a result on prompt cancellation. Retain only
        // after the caller has resumed; otherwise the owner deletes this copy.
        owner.commit()?.retain()
        result.uri
    }

    fun share(context: Context, uri: Uri, displayName: String? = null) {
        val shareUri = getShareableContentUri(context, uri, displayName)
        ShareUtil.shareFiles(context, listOf(shareUri), title = displayName ?: "Share PDF")
    }

    /** Blocking compatibility API; callers can interrupt or supply a cancellation signal. */
    fun getShareableContentUri(
        context: Context, uri: Uri, customDisplayName: String? = null,
        cancellation: CancellationSignal? = null
    ): Uri = prepareShare(context, uri, customDisplayName) { cancellation?.throwIfCanceled() }
        .also { it.retain() }.uri

    internal const val MAX_RETAINED_SHARE_COPIES = 64
    internal const val MAX_ACTIVE_SHARE_READERS = 64
    internal const val MAX_RETAINED_SHARE_BYTES = SecurityLimits.MAX_OUTPUT_BYTES
    private val shareLock = Any()
    private val activeCopies = mutableSetOf<File>()
    private val reservations = mutableMapOf<File, Long>()
    private val activeReaders = mutableMapOf<String, Int>()
    @androidx.annotation.VisibleForTesting
    internal var retainedDeleteForTesting: ((File) -> Boolean)? = null

    private data class RetainedFile(val file: File, val kind: RetainedShareKind, val root: String)

    private fun retainedId(record: RetainedFile): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((record.root + ":" + record.file.name).toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(64) { for (byte in digest) { append(hex[(byte.toInt() ushr 4) and 15]); append(hex[byte.toInt() and 15]) } }
    }

    /** Dedicated files storage proves ownership; historical cache names alone do not. */
    private fun retainedFiles(context: Context): List<RetainedFile> = listOf(
        Triple("retained", context.filesDir, "shared_copies"),
        Triple("legacy", context.cacheDir, "exports")
    ).flatMap { (root, parent, name) ->
        val directory = File(parent.canonicalFile, name)
        if (!directory.exists()) emptyList() else {
            if (!directory.isDirectory || directory.canonicalFile != directory) throw IOException("Share storage unavailable")
            val files = directory.listFiles() ?: throw IOException("Unable to inspect share storage")
            files.filter { it.name.startsWith("shared_") || (root == "retained" && it.name.startsWith(".pending_")) }.map { file ->
                if (!file.isFile || file.canonicalFile != file.absoluteFile) throw IOException("Unrecognized share storage entry")
                val kind = when {
                    root == "legacy" -> RetainedShareKind.LEGACY_UNVERIFIED
                    file.name.startsWith(".pending_") -> RetainedShareKind.INCOMPLETE_COPY
                    else -> RetainedShareKind.RETAINED_COPY
                }
                RetainedFile(file, kind, root)
            }
        }
    }

    private fun protections(record: RetainedFile): Set<RetainedShareProtection> = buildSet {
        if (record.kind == RetainedShareKind.LEGACY_UNVERIFIED) add(RetainedShareProtection.UNVERIFIED_LEGACY_OWNERSHIP)
        if (activeCopies.any { it.canonicalFile == record.file }) add(RetainedShareProtection.ACTIVE_COPY)
        if ((activeReaders[record.file.path] ?: 0) > 0) add(RetainedShareProtection.ACTIVE_READER)
    }

    /** Logical bytes, including active partials and separately identified legacy candidates. Call off Main. */
    @androidx.annotation.WorkerThread
    fun inspectRetainedShares(context: Context): RetainedShareStorage = synchronized(shareLock) {
        RetainedShareStorage(retainedFiles(context).map { record ->
            RetainedShareEntry(retainedId(record), record.file.name, record.kind, record.file.length(), protections(record))
        }, reservations.values.sum(), activeReaders.values.sum())
    }

    /**
     * Explicit removal of selected snapshot IDs only. No expiry and no URI-grant revocation.
     * Active writers/readers and unproven legacy ownership are protected. A signal or
     * thread interruption returns actual partial results, without deleting further files.
     */
    @androidx.annotation.WorkerThread
    fun cleanupRetainedShares(context: Context, ids: Set<String>, cancellation: CancellationSignal? = null): RetainedShareCleanupResult {
        require(ids.size <= SecurityLimits.MAX_OUTPUT_FILES) { "Too many retained-copy selections" }
        require(ids.all { id -> id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' } }) { "Invalid retained-copy selection" }
        val selected = ids.toSet()
        return synchronized(shareLock) {
            // Fail before deletion if either store cannot be safely inventoried.
            val records = retainedFiles(context).associateBy(::retainedId)
            val deleted = linkedMapOf<String, Long>()
            val protected = linkedMapOf<String, Set<RetainedShareProtection>>()
            val failed = linkedMapOf<String, String>()
            val missing = linkedSetOf<String>()
            val unprocessed = selected.toMutableSet()
            var cancelled = false
            for (id in selected) {
                if (cancellation?.isCanceled == true || Thread.currentThread().isInterrupted) { cancelled = true; break }
                unprocessed.remove(id)
                val record = records[id]
                if (record == null) { missing.add(id); continue }
                val protection = protections(record)
                if (protection.isNotEmpty()) { protected[id] = protection; continue }
                val bytes = record.file.length()
                val removed = runCatching { retainedDeleteForTesting?.invoke(record.file) ?: record.file.delete() }.getOrDefault(false)
                if (removed) deleted[id] = bytes else failed[id] = "Unable to delete retained copy"
            }
            RetainedShareCleanupResult(deleted.toMap(), protected.toMap(), failed.toMap(), missing.toSet(), unprocessed.toSet(), cancelled)
        }
    }

    /** Narrow FileProvider hook: only immutable completed copies in the existing shared roots. */
    internal fun openRetainedShare(context: Context, uri: Uri, mode: String): ParcelFileDescriptor? = synchronized(shareLock) {
        if (uri.scheme != "content" || uri.authority?.substringAfterLast('@') != "${context.packageName}.fileprovider") return null
        val segments = uri.pathSegments
        val root = when (segments.firstOrNull()) {
            "retained_share_copies" -> File(context.filesDir.canonicalFile, "shared_copies")
            "shared_exports" -> {
                if (segments.getOrNull(1)?.startsWith("shared_") != true) return null
                File(context.cacheDir.canonicalFile, "exports")
            }
            else -> return null
        }
        fun unavailable(): Nothing = throw java.io.FileNotFoundException("Retained share is unavailable")
        if (mode != "r" || segments.size != 2 || !segments[1].startsWith("shared_") || root.canonicalFile != root) unavailable()
        val file = File(root, segments[1])
        if (file.canonicalFile != file.absoluteFile || file.parentFile != root || !file.isFile) unavailable()
        if (activeReaders.values.sum() >= MAX_ACTIVE_SHARE_READERS) throw java.io.FileNotFoundException("Share reader limit exceeded")
        val key = file.path
        activeReaders[key] = (activeReaders[key] ?: 0) + 1
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) synchronized(shareLock) {
                val remaining = (activeReaders[key] ?: 1) - 1
                if (remaining == 0) activeReaders.remove(key) else activeReaders[key] = remaining
            }
        }
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY, Handler(Looper.getMainLooper())) { release() }
        } catch (error: Throwable) { release(); throw error }
    }

    private class PreparedShare(val uri: Uri, private val file: File? = null) {
        fun retain() = synchronized(shareLock) { activeCopies.remove(file); Unit }
        fun discard() = synchronized(shareLock) { activeCopies.remove(file); file?.delete(); Unit }
    }

    /**
     * Never infer expiry of another app's URI grant from age or file count.
     * Completed copies remain immutable in private files storage, outside OS
     * cache eviction. At the budget limit new copies fail without evicting any
     * recipient's copy. Only unreturned partial copies are eligible for cleanup.
     */
    internal fun cleanupAbandonedShareCopies(context: Context) = synchronized(shareLock) {
        retainedFiles(context).filter { it.kind == RetainedShareKind.INCOMPLETE_COPY && protections(it).isEmpty() }
            .forEach { check(it.file.delete()) { "Unable to clean incomplete share copy" } }
    }

    private fun prepareShare(context: Context, uri: Uri, displayName: String?, checkCancellation: () -> Unit): PreparedShare {
        fun checkActive() {
            checkCancellation()
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Share copy interrupted")
        }
        checkActive()
        if (uri.scheme != "file") return PreparedShare(uri)
        val source = File(uri.path ?: throw IOException("Source path unavailable"))
        val lease = com.pdfchemy.app.utils.DocumentStager.retain(uri)
        var working: File? = null
        try {
            if (!source.isFile) throw IOException("Share source unavailable")
            try {
                return PreparedShare(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source))
            } catch (_: IllegalArgumentException) { /* Private staged source needs a separate export. */ }
            val size = source.length()
            require(size in 1..SecurityLimits.MAX_PDF_FILESIZE) { "Share source exceeds size limit" }
            val raw = displayName?.takeIf { it.isNotBlank() }
                ?: com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: source.name
            val base = raw.replace("[^a-zA-Z0-9._-]".toRegex(), "_").take(120).ifBlank { "Document.pdf" }
            val name = if (base.endsWith(".pdf", true)) base else "$base.pdf"
            val directory = File(context.filesDir, "shared_copies")
            val pending = synchronized(shareLock) {
                check(directory.isDirectory || directory.mkdirs()) { "Share storage unavailable" }
                cleanupAbandonedShareCopies(context)
                val retained = retainedFiles(context).filter { it.kind != RetainedShareKind.INCOMPLETE_COPY }.map { it.file }
                require(retained.size + reservations.size < MAX_RETAINED_SHARE_COPIES &&
                    retained.sumOf { it.length() } + reservations.values.sum() <= MAX_RETAINED_SHARE_BYTES - size) {
                    "Share storage is full; existing recipient copies have been preserved"
                }
                File.createTempFile(".pending_", "_$name", directory).also {
                    working = it; activeCopies.add(it); reservations[it] = size
                }
            }
            source.inputStream().use { input ->
                pending.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (true) {
                        checkActive()
                        copyHookForTesting?.invoke()
                        val count = input.read(buffer)
                        if (count < 0) break
                        checkActive()
                        require(copied <= size - count) { "Share source changed or exceeds size limit" }
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    require(copied == size) { "Share source changed during copy" }
                    output.flush()
                }
            }
            checkActive()
            val target = File(directory, "shared_${pending.name.removePrefix(".pending_")}")
            synchronized(shareLock) {
                check(!target.exists() && pending.renameTo(target)) { "Unable to complete share copy" }
                reservations.remove(pending); activeCopies.remove(pending); activeCopies.add(target)
                working = target
            }
            val result = PreparedShare(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target), target)
            checkActive()
            return result
        } catch (error: Throwable) {
            synchronized(shareLock) {
                working?.let { reservations.remove(it); activeCopies.remove(it); it.delete() }
            }
            throw error
        } finally { lease?.close() }
    }

    fun print(context: Context, uri: Uri, name: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(name, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancellation: CancellationSignal,
                callback: LayoutResultCallback, extras: Bundle?) {
                if (cancellation.isCanceled) callback.onLayoutCancelled()
                else callback.onLayoutFinished(PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
            }

            override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor,
                cancellation: CancellationSignal, callback: WriteResultCallback) {
                Thread {
                    try {
                        require(pages.any { it == PageRange.ALL_PAGES }) { "Select all pages to print this document." }
                        context.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input)
                            FileOutputStream(destination.fileDescriptor).use { output ->
                                val buffer = ByteArray(8192)
                                while (!cancellation.isCanceled) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        Handler(Looper.getMainLooper()).post {
                            if (cancellation.isCanceled) callback.onWriteCancelled()
                            else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        }
                    } catch (_: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            if (cancellation.isCanceled) callback.onWriteCancelled()
                            else callback.onWriteFailed("Unable to print. Check document access and select all pages.")
                        }
                    }
                }.start()
            }
        }, null)
    }
}
