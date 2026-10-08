package com.pdfchemy.app.logic

import androidx.documentfile.provider.DocumentFile
import java.util.Locale

object OutputPolicy {
    const val DIRECTORY_KEY = "preferred_output_directory"
    fun safeName(name: String, fallback: String = "Document.pdf"): String = name
        .substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").trim().trim('.')
        .take(180).ifBlank { fallback }

    fun uniqueName(requested: String, existing: Collection<String>): String {
        val name = safeName(requested)
        val names = existing.map { it.lowercase(Locale.ROOT) }.toSet()
        if (name.lowercase(Locale.ROOT) !in names) return name
        val stem = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        for (n in 2..10_000) {
            val candidate = "$stem ($n)$suffix"
            if (candidate.lowercase(Locale.ROOT) !in names) return candidate
        }
        error("Choose a different output name")
    }

    /** Create a new capability; never open an existing file for overwrite. */
    @Synchronized fun createCopy(directory: DocumentFile, mime: String, requested: String): DocumentFile {
        check(directory.exists() && directory.isDirectory && directory.canWrite()) { "Saved folder is unavailable" }
        val existing = directory.listFiles()
        val name = uniqueName(requested, existing.mapNotNull { it.name })
        val created = checkNotNull(directory.createFile(mime, name)) { "Unable to create output" }
        check(existing.none { it.uri == created.uri }) { "Provider did not create a separate copy" }
        return created
    }
}
