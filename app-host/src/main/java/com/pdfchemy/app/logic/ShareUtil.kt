package com.pdfchemy.app.logic

import android.content.Context
import android.content.Intent
import android.net.Uri

object ShareUtil {
    fun shareFiles(context: Context, uris: List<Uri>, title: String = "Share PDF") {
        if (uris.isEmpty()) return

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
