package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Collections
import java.util.Locale

enum class DestinationProvenance {
    FRESH_COPY,
    EXISTING_OR_UNKNOWN
}

enum class PublicationState {
    PENDING,
    WRITING,
    PUBLISHED,
    FAILED,
    CANCELLED
}

data class OutputDestination(
    val uri: Uri,
    val provenance: DestinationProvenance = DestinationProvenance.EXISTING_OR_UNKNOWN
) {
    val isFreshCopy: Boolean get() = provenance == DestinationProvenance.FRESH_COPY
}

object OutputPolicy {
    const val DIRECTORY_KEY = "preferred_output_directory"

    private val freshUris = Collections.synchronizedSet(mutableSetOf<Uri>())

    fun registerFreshCopy(uri: Uri) {
        freshUris.add(uri)
    }

    fun unregisterFreshCopy(uri: Uri) {
        freshUris.remove(uri)
    }

    /** Transitions destination to published state: it is no longer an uncommitted fresh copy. */
    fun markPublished(uri: Uri) {
        unregisterFreshCopy(uri)
    }

    fun isFreshCopy(uri: Uri): Boolean = freshUris.contains(uri)

    fun provenanceOf(uri: Uri): DestinationProvenance =
        if (isFreshCopy(uri)) DestinationProvenance.FRESH_COPY else DestinationProvenance.EXISTING_OR_UNKNOWN

    fun destinationFor(uri: Uri): OutputDestination =
        OutputDestination(uri, provenanceOf(uri))

    fun deleteIfFresh(context: Context, uri: Uri): Boolean {
        if (isFreshCopy(uri)) {
            unregisterFreshCopy(uri)
            return runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() == true }.getOrDefault(false)
        }
        return false
    }

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
        registerFreshCopy(created.uri)
        return created
    }
}
