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
import java.io.File
import java.io.FileOutputStream

object DocumentActions {
    fun view(context: Context, uri: Uri, displayName: String? = null) {
        val output = getShareableContentUri(context, uri, displayName)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(output, com.pdfchemy.app.utils.FileUtils.getMimeType(context, output))
            clipData = android.content.ClipData.newUri(context.contentResolver, "Document", output)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is android.app.Activity) {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }

    fun share(context: Context, uri: Uri, displayName: String? = null) {
        val shareUri = getShareableContentUri(context, uri, displayName)
        ShareUtil.shareFiles(context, listOf(shareUri), title = displayName ?: "Share PDF")
    }

    fun getShareableContentUri(context: Context, uri: Uri, customDisplayName: String? = null): Uri {
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        val sourceFile = File(path)
        if (!sourceFile.exists()) return uri

        // 1. If sourceFile is already inside a configured FileProvider root (e.g. cache/exports, cache/scans),
        // FileProvider resolves it directly.
        try {
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)
        } catch (_: IllegalArgumentException) {
            // Source is outside configured roots (e.g. internal cache/staged/ snapshot).
            // Fall through to produce a bounded export copy.
        }

        val exportsDir = File(context.cacheDir, "exports").apply { mkdirs() }
        pruneOldShareCopies(exportsDir, maxRetained = 5)

        val rawName = customDisplayName?.takeIf { it.isNotBlank() }
            ?: com.pdfchemy.app.utils.FileUtils.getFileName(context, uri)
            ?: sourceFile.name
        val safeBase = rawName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        val cleanName = if (safeBase.endsWith(".pdf", ignoreCase = true)) safeBase else "$safeBase.pdf"
        val exportFile = File(exportsDir, "shared_${System.currentTimeMillis()}_$cleanName")

        sourceFile.inputStream().use { input ->
            exportFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)
    }

    private fun pruneOldShareCopies(exportsDir: File, maxRetained: Int = 5) {
        try {
            val shareFiles = exportsDir.listFiles { f -> f.name.startsWith("shared_") } ?: return
            if (shareFiles.size >= maxRetained) {
                shareFiles.sortedBy { it.lastModified() }
                    .take(shareFiles.size - maxRetained + 1)
                    .forEach { runCatching { it.delete() } }
            }
        } catch (_: Throwable) {}
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
