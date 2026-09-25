package com.pdfchemy.desktop.engine

import java.io.File

object SecurityUtil {
    fun safeOutputChild(outputDir: File, untrustedName: String): File {
        val safeName = File(untrustedName).name
        require(!safeName.contains("..")) { "Path traversal attempt detected in filename: $untrustedName" }
        require(safeName.isNotEmpty()) { "Filename cannot be empty" }
        
        val finalOut = File(outputDir, safeName)
        if (!finalOut.canonicalFile.toPath().startsWith(outputDir.canonicalFile.toPath())) {
            throw SecurityException("Path traversal attempt detected: $untrustedName")
        }
        return finalOut
    }
}
