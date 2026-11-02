package com.pdfchemy.app.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import com.pdfchemy.app.utils.FileUtils

object ShareUtil {
    fun shareFiles(context: Context, uris: List<Uri>, title: String = "Share PDF") {
        if (uris.isEmpty()) return

        val shareUris = try {
            uris.map { uri ->
                DocumentActions.getShareableContentUri(context, uri)
            }
        } catch (e: RetainedShareQuotaException) {
            android.widget.Toast.makeText(context, e.message ?: "Share storage limit reached", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        launchShareIntent(context, shareUris, title)
    }

    fun launchShareIntent(context: Context, shareUris: List<Uri>, title: String = "Share PDF") {
        if (shareUris.isEmpty()) return
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
        val chooser = Intent.createChooser(intent, title).apply {
            if (!context.hasActivityContext()) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String = "image/jpeg", title: String = "Share Image") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, title).apply {
            if (!context.hasActivityContext()) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(chooser)
    }
}
