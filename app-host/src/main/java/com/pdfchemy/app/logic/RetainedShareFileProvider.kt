package com.pdfchemy.app.logic

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** Retains normal FileProvider grants/roots and tracks completed-copy read descriptors. */
class RetainedShareFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        DocumentActions.openRetainedShare(requireNotNull(context), uri, mode) ?: super.openFile(uri, mode)
}
