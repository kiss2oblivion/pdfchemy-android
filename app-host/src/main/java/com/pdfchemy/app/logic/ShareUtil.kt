package com.pdfchemy.app.logic

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.content.ClipData
import com.pdfchemy.app.utils.FileUtils

object ShareUtil {
    fun shareFiles(context: Context, uris: List<Uri>, title: String = "Share PDF") {
        if (uris.isEmpty()) return

        val shareUris = uris.map { uri ->
            DocumentActions.getShareableContentUri(context, uri)
        }
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
        launchChooser(context, intent, title)
    }

    fun shareFile(context: Context, uri: Uri, mimeType: String = "image/jpeg", title: String = "Share Image") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchChooser(context, intent, title)
    }

    private fun launchChooser(context: Context, intent: Intent, title: String) {
        val chooser = Intent.createChooser(intent, title)
        var owner = context
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Context, Boolean>())
        while (owner is ContextWrapper && owner !is Activity && visited.add(owner)) {
            owner = owner.baseContext
        }
        if (owner !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
