package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.pdfchemy.app.utils.DocumentStager
import java.io.File

/**
 * Enforces source preservation and prevents destructive self-overwrite (SC-09).
 * Guarantees that destination outputs never truncate or overwrite original source documents.
 */
object SourcePreservationPolicy {

    fun isSameDocument(context: Context, sourceUri: Uri, destUri: Uri): Boolean {
        return checkSameDocument(context, sourceUri, destUri, mutableSetOf())
    }

    private fun resolveFilePath(uri: Uri): File? {
        if (uri.scheme != "file") return null
        val rawPath = uri.path?.takeIf { it.isNotEmpty() }
            ?: uri.schemeSpecificPart?.takeIf { it.isNotEmpty() }
            ?: return null
        val trimmed = rawPath.trimStart('/')
        val finalPath = if (trimmed.length >= 2 && trimmed[0].isLetter() && trimmed[1] == ':') {
            trimmed
        } else if (rawPath.startsWith("/")) {
            rawPath
        } else {
            trimmed
        }
        return File(finalPath)
    }

    private fun checkSameDocument(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        visited: MutableSet<Pair<Uri, Uri>>
    ): Boolean {
        // 1. Direct object equality or string identity
        if (sourceUri == destUri) return true
        if (sourceUri.toString() == destUri.toString()) return true
        if (sourceUri.normalizeScheme() == destUri.normalizeScheme()) return true

        // Cycle guard on (source, dest) pair
        if (!visited.add(sourceUri to destUri)) return false

        // 2. Resolve staged capabilities back to original sources
        val originalSource = DocumentStager.originalSourceFor(sourceUri)
        if (originalSource != null && checkSameDocument(context, originalSource, destUri, visited)) {
            return true
        }
        val originalDest = DocumentStager.originalSourceFor(destUri)
        if (originalDest != null && checkSameDocument(context, sourceUri, originalDest, visited)) {
            return true
        }

        // 3. File path aliases (canonical, relative, symlinks)
        if (sourceUri.scheme == "file" && destUri.scheme == "file") {
            val sourceFile = resolveFilePath(sourceUri)
            val destFile = resolveFilePath(destUri)
            if (sourceFile != null && destFile != null) {
                val sourceCanonical = runCatching { sourceFile.canonicalPath }.getOrNull()
                val destCanonical = runCatching { destFile.canonicalPath }.getOrNull()
                if (sourceCanonical != null && sourceCanonical == destCanonical) return true
                if (sourceFile.absolutePath == destFile.absolutePath) return true
            }
        }

        // 4. DocumentsContract SAF document IDs
        if (sourceUri.scheme == "content" && destUri.scheme == "content") {
            if (sourceUri.authority == destUri.authority) {
                if (sourceUri.path == destUri.path) return true
                val isDoc1 = runCatching { DocumentsContract.isDocumentUri(context, sourceUri) }.getOrDefault(false)
                val isDoc2 = runCatching { DocumentsContract.isDocumentUri(context, destUri) }.getOrDefault(false)
                if (isDoc1 && isDoc2) {
                    val docId1 = runCatching { DocumentsContract.getDocumentId(sourceUri) }.getOrNull()
                    val docId2 = runCatching { DocumentsContract.getDocumentId(destUri) }.getOrNull()
                    if (docId1 != null && docId1 == docId2) return true
                }
            }
        }

        return false
    }

    fun requireSeparateDestination(context: Context, sourceUri: Uri, destUri: Uri) {
        if (isSameDocument(context, sourceUri, destUri)) {
            throw SecurityException("Source preservation violation: destination URI resolves to the same document as source ($destUri)")
        }
    }

    fun requireSeparateDestinations(context: Context, sources: List<Uri>, destinations: List<Uri>) {
        for (source in sources) {
            for (dest in destinations) {
                requireSeparateDestination(context, source, dest)
            }
        }
    }
}
