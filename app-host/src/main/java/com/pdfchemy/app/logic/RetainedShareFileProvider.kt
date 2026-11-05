package com.pdfchemy.app.logic

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** Shared documents are immutable, including fallback roots and accidentally broad grants. */
class RetainedShareFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Shared documents are read-only")
        return DocumentActions.openRetainedShare(requireNotNull(context), uri, mode) ?: super.openFile(uri, mode)
    }

    // Retained-copy deletion belongs to the explicit backend, which protects reader leases.
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Shared documents are read-only")
}
