package com.pdfchemy.app.logic

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileUtil {

    /**
     * Generates a smart file name based on the original URI and the action performed.
     * @param originalUri The source URI (can be null if creating a new file from scratch).
     * @param action The action performed (e.g., "compressed", "stripped", "merged", "scanned").
     * @param defaultName Fallback name if originalUri doesn't have a valid name.
     * @return A smart filename string like "document_compressed.pdf".
     */
    fun generateSuggestedName(originalUri: Uri?, action: String, defaultName: String = "Document", extension: String = "pdf"): String {
        if (originalUri == null) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            return "${action}_$timestamp.$extension"
        }

        val originalName = originalUri.lastPathSegment ?: defaultName
        
        // Remove extension
        val nameWithoutExt = if (originalName.contains(".")) {
            originalName.substringBeforeLast(".")
        } else {
            originalName
        }

        // Clean up any previous actions to prevent things like "doc_compressed_compressed.pdf"
        val cleanName = nameWithoutExt.replace(Regex("_(compressed|stripped|merged|scanned|cleaned)$"), "")

        return "${cleanName}_$action.$extension"
    }
}
