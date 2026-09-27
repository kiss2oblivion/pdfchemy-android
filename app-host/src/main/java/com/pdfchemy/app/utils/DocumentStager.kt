package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object DocumentStager {
    
    /**
     * Copies the content of the provided URI to an immutable, securely-hashed temporary file.
     * This prevents TOCTOU attacks where a ContentProvider returns clean bytes during scan
     * and malicious bytes during actual processing.
     *
     * @return A `file://` URI pointing to the staged immutable file.
     */
    fun stageDocument(context: Context, sourceUri: Uri): Uri {
        // If it's already a staged file, don't re-stage it.
        if (sourceUri.scheme == "file" && sourceUri.path?.contains("pdf_staged_") == true) {
            return sourceUri
        }

        val maxBytes = 2L * 1024 * 1024 * 1024 // 2 GB limit
        
        // 1. Create a truly temporary staging file (unpredictable name)
        val tempStageFile = File.createTempFile("pdf_staging_", ".tmp", context.cacheDir)
        
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempStageFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesCopied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        bytesCopied += read
                        if (bytesCopied > maxBytes) {
                            throw Exception("Input document exceeds hard size limit of 2GB")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            } ?: throw Exception("Failed to open input stream for URI: $sourceUri")
            
            // 2. Hash the bytes
            val hashBytes = digest.digest()
            val hashString = hashBytes.joinToString("") { "%02x".format(it) }
            
            // 3. Rename to final immutable cache file based on SHA-256
            val finalStagedFile = File(context.cacheDir, "pdf_staged_$hashString.pdf")
            
            if (!finalStagedFile.exists()) {
                if (!tempStageFile.renameTo(finalStagedFile)) {
                    // Fallback to copy if rename fails
                    tempStageFile.copyTo(finalStagedFile, overwrite = true)
                    tempStageFile.delete()
                }
            } else {
                // If it already exists, it has the exact same SHA-256 hash, so we can reuse it
                tempStageFile.delete()
            }
            
            return Uri.fromFile(finalStagedFile)
            
        } catch (e: Exception) {
            tempStageFile.delete()
            throw e
        }
    }
}
