package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.security.MessageDigest

object DocumentIdentity {

    /**
     * Computes a stable, pseudo-unique identifier for a document based on its metadata.
     * This avoids hashing the entire file (which is slow) and avoids relying on the raw `content://` URI
     * or a randomly generated staging path, which can change.
     */
    fun computeStableId(context: Context, uri: Uri): String {
        var name = ""
        var size = 0L
        
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = cursor.getString(nameIndex) ?: ""
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        } else if (uri.scheme == "file") {
            name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: ""
            size = java.io.File(uri.path ?: "").length()
        }
        
        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: uri.toString()
        }
        
        val rawInput = "$name::$size"
        return hashString(rawInput)
    }
    
    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
