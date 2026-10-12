package com.pdfchemy.app.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import com.pdfchemy.app.utils.FileUtils

object ShareUtil {
    fun shareFiles(context: Context, uris: List<Uri>, title: String = "Share PDF") {
        if (uris.isEmpty()) return

        val shareUris = uris.map { uri -> if (uri.scheme == "file") androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", java.io.File(requireNotNull(uri.path)), FileUtils.getFileName(context, uri) ?: "Document"
        ) else uri }
        val types = shareUris.map { FileUtils.getMimeType(context, it) }.distinct()
        val mimeType = types.singleOrNull() ?: "*/*"

        val intent = if (shareUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, shareUris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(shareUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        intent.clipData = ClipData.newUri(context.contentResolver, title, shareUris.first()).apply {
            shareUris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        val chooser = Intent.createChooser(intent, title)
        context.startActivity(chooser)
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String = "image/jpeg", title: String = "Share Image") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title)
        context.startActivity(chooser)
    }
}
