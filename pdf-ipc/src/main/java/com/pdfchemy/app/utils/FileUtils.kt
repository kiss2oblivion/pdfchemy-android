package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

object FileUtils {
    fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") DocumentStager.displayName(context, uri)?.takeIf { it.isNotBlank() }?.let { return it }
        var result: String? = null
        if (uri.scheme == "content") result = ProviderMetadata.read(context, uri).name
        if (result == null) {
            val segment = uri.lastPathSegment
            if (!segment.isNullOrBlank()) {
                result = if (segment.contains(':')) segment.substringAfterLast(':') else segment
                if (result.contains('/')) {
                    result = result.substringAfterLast('/')
                }
                if (result.contains('\\')) {
                    result = result.substringAfterLast('\\')
                }
            } else {
                result = uri.path
                val cut = maxOf(result?.lastIndexOf('/') ?: -1, result?.lastIndexOf('\\') ?: -1)
                if (cut != -1) {
                    result = result?.substring(cut + 1)
                }
            }
        }
        return result
    }

    fun getFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "content") return ProviderMetadata.read(context, uri).size
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

    fun getMimeType(context: Context, uri: Uri, fileName: String? = null, allowProviderQuery: Boolean = true): String {
        val resolverType = if (allowProviderQuery) ProviderMetadata.read(context, uri).mime else ProviderMetadata.cached(uri)?.mime
        if (!resolverType.isNullOrBlank() && resolverType != "application/octet-stream") {
            return resolverType
        }
        val name = fileName ?: (if (allowProviderQuery) getFileName(context, uri)
            else ProviderMetadata.cached(uri)?.name ?: DocumentStager.displayName(uri)) ?: uri.lastPathSegment ?: ""
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
