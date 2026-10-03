package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FileUtils {
    fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") DocumentStager.displayName(uri)?.let { return it }
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore SecurityException / permission denial and fall back to path parsing
            }
        }
        if (result == null) {
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank()) {
                result = if (segment.contains(':')) segment.substringAfterLast(':') else segment
                if (result.contains('/')) {
                    result = result.substringAfterLast('/')
                }
            } else {
                result = uri.path
                val cut = result?.lastIndexOf('/') ?: -1
                if (cut != -1) {
                    result = result?.substring(cut + 1)
                }
            }
        }
        return result
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (index != -1) {
                            return cursor.getLong(index)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore SecurityException / permission denial
            }
        }
        if (uri.scheme == "file") {
            val path = uri.path
            if (path != null) {
                return try { java.io.File(path).length() } catch (_: Exception) { 0L }
            }
        }
        return 0L
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
    }

    fun getMimeType(context: Context, uri: Uri, fileName: String? = null): String {
        val resolverType = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
        if (!resolverType.isNullOrBlank() && resolverType != "application/octet-stream") {
            return resolverType
        }
        val name = fileName ?: getFileName(context, uri) ?: uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "epub" -> "application/epub+zip"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/vnd.comicbook-rar"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "zip" -> "application/zip"
            else -> {
                val fromMap = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                fromMap ?: "application/octet-stream"
            }
        }
    }

    fun openParcelFileDescriptor(context: Context, uri: Uri): android.os.ParcelFileDescriptor? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            // Errors propagate as a null descriptor.
            null
        }
    }
}
