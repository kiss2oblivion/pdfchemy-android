package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FileUtils {
    fun getFileName(context: Context, uri: Uri): String? {
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
            val maxBytes = 2L * 1024 * 1024 * 1024 // 2 GB limit
            val randomId = java.util.UUID.randomUUID().toString()
            val tempFile = java.io.File(context.cacheDir, "pdf_sandbox_cache_$randomId.pdf")
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesCopied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesCopied += read
                        if (bytesCopied > maxBytes) {
                            throw Exception("Input document exceeds hard size limit of 2GB")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            
            if (tempFile.exists() && tempFile.length() > 0) {
                // Ensure file is deleted on exit if possible, though we can't fully guarantee it without tracking.
                tempFile.deleteOnExit()
                android.os.ParcelFileDescriptor.open(tempFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                tempFile.delete()
                null
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("FileUtils: failed to open parcel file descriptor", e)
            null
        }
    }
}
