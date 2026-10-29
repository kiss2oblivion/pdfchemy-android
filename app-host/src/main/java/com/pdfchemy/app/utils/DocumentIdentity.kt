package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.security.MessageDigest

data class SavedReaderPosition(
    val pageIndex: Int,
    val scrollOffsetPx: Int
)

enum class DocumentLoadFailureKind {
    REVOKED_OR_INACCESSIBLE,
    ENCRYPTED,
    DAMAGED,
    VANGUARD_THREAT
}

object DocumentIdentity {
    private const val PREFS_NAME = "reader_prefs"

    /**
     * Computes a stable, pseudo-unique identifier for a document based on its original display name
     * and byte size. Works identically across both `content://` URIs and staged `file://` snapshots
     * registered in [DocumentStager], avoiding random snapshot filename drift.
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
            } catch (_: Exception) {
            }
        } else if (uri.scheme == "file") {
            name = DocumentStager.displayName(uri)
                ?: FileUtils.getFileName(context, uri)
                ?: uri.lastPathSegment
                ?: ""
            size = java.io.File(uri.path ?: "").length()
        }

        if (name.isEmpty()) {
            name = FileUtils.getFileName(context, uri) ?: uri.lastPathSegment ?: uri.toString()
        }

        val cleanName = name
            .trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')

        val rawInput = "$cleanName::$size"
        return hashString(rawInput)
    }

    fun saveReaderPosition(
        context: Context,
        docId: String,
        pageIndex: Int,
        scrollOffsetPx: Int = 0
    ) {
        if (docId.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("page_$docId", pageIndex.coerceAtLeast(0))
            .putInt("offset_$docId", scrollOffsetPx.coerceAtLeast(0))
            .apply()
    }

    fun getSavedReaderPosition(
        context: Context,
        docId: String,
        totalPages: Int
    ): SavedReaderPosition {
        if (docId.isBlank() || totalPages <= 0) {
            return SavedReaderPosition(pageIndex = 0, scrollOffsetPx = 0)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawPage = prefs.getInt("page_$docId", 0).coerceAtLeast(0)
        val rawOffset = prefs.getInt("offset_$docId", 0).coerceAtLeast(0)
        return if (rawPage < totalPages) {
            SavedReaderPosition(pageIndex = rawPage, scrollOffsetPx = rawOffset)
        } else {
            SavedReaderPosition(pageIndex = (totalPages - 1).coerceAtLeast(0), scrollOffsetPx = 0)
        }
    }

    fun classifyLoadException(throwable: Throwable?): DocumentLoadFailureKind {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is SecurityException || current is FileNotFoundException) {
                return DocumentLoadFailureKind.REVOKED_OR_INACCESSIBLE
            }
            val msg = current.message ?: ""
            if (msg.contains("Cannot open document", ignoreCase = true) ||
                msg.contains("Permission Denial", ignoreCase = true) ||
                msg.contains("FileNotFound", ignoreCase = true) ||
                msg.contains("ENOENT", ignoreCase = true)
            ) {
                return DocumentLoadFailureKind.REVOKED_OR_INACCESSIBLE
            }
            current = current.cause
        }
        return DocumentLoadFailureKind.DAMAGED
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
