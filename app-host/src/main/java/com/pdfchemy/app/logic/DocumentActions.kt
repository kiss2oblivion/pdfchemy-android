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
    fun view(context: Context, uri: Uri) {
        val output = if (uri.scheme == "file") FileProvider.getUriForFile(context,
            "${context.packageName}.fileprovider", File(requireNotNull(uri.path))) else uri
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(output, com.pdfchemy.app.utils.FileUtils.getMimeType(context, output))
            clipData = android.content.ClipData.newUri(context.contentResolver, "Document", output)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
    fun share(context: Context, uri: Uri) {
        val shareUri = if (uri.scheme == "file") FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", File(requireNotNull(uri.path))
        ) else uri
        ShareUtil.shareFiles(context, listOf(shareUri))
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
