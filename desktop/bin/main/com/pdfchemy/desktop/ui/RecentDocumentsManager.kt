package com.pdfchemy.desktop.ui

import java.io.File

object RecentDocumentsManager {
    private val recentsDir = File(System.getProperty("user.home"), ".pdfchemy")
    private val recentsFile = File(recentsDir, "recent_files.txt")
    private const val MAX_RECENTS = 6

    fun addRecent(file: File) {
        try {
            if (!file.exists() || !file.isFile) return
            recentsDir.mkdirs()
            val existing = getRecents().map { it.absolutePath }.toMutableList()
            existing.remove(file.absolutePath)
            existing.add(0, file.absolutePath)
            val trimmed = existing.take(MAX_RECENTS)
            recentsFile.writeText(trimmed.joinToString("\n"))
        } catch (_: Exception) {}
    }

    fun getRecents(): List<File> {
        return try {
            if (!recentsFile.exists()) return emptyList()
            recentsFile.readLines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { File(it) }
                .filter { it.exists() && it.isFile }
                .take(MAX_RECENTS)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearRecents() {
        try {
            if (recentsFile.exists()) {
                recentsFile.delete()
            }
        } catch (_: Exception) {}
    }
}
