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
    internal const val MAX_RETAINED_SHARE_BYTES = SecurityLimits.MAX_OUTPUT_BYTES
    private val shareLock = Any()
    private val activeCopies = mutableSetOf<File>()
    private val reservations = mutableMapOf<File, Long>()

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
        File(context.filesDir, "shared_copies").listFiles()?.filter {
            it.name.startsWith(".pending_") && it !in activeCopies
        }?.forEach { check(it.delete()) { "Unable to clean incomplete share copy" } }
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
                val retained = directory.listFiles().orEmpty().filter { it.name.startsWith("shared_") } +
                    File(context.cacheDir, "exports").listFiles().orEmpty().filter { it.name.startsWith("shared_") }
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
