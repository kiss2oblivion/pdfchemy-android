package com.pdfchemy.app.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pdfchemy.app.jail.StagedPdf
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object DocumentIdentity {

    const val PREFIX_V2 = "doc_v2_"
    const val PREFS_NAME = "reader_prefs"
    const val KEY_LEGACY_BINDING_PREFIX = "legacy_binding_"
    const val KEY_LEGACY_AMBIGUOUS_PREFIX = "legacy_ambiguous_"

    /**
     * Formats a versioned V2 document ID from a content SHA-256 hex digest.
     */
    fun formatV2Id(sha256: String): String = "$PREFIX_V2$sha256"

    /**
     * Extracts the content SHA-256 hex digest from a versioned V2 document ID, or null if not V2.
     */
    fun extractSha256(docId: String): String? {
        return if (docId.startsWith(PREFIX_V2)) docId.removePrefix(PREFIX_V2) else null
    }

    /**
     * Computes a stable, collision-resistant identifier for a document based on its cryptographic
     * content hash (SHA-256).
     *
     * 1. If an admitted [StagedPdf] capability is provided or found in [DocumentStager], its pre-computed
     *    immutable snapshot hash is used directly with zero additional I/O.
     * 2. If the URI is not yet staged, its content stream is opened and hashed via SHA-256.
     * 3. Documents with identical metadata (name and size) but distinct content receive distinct IDs.
     * 4. Documents renamed or accessed via aliases/different URIs with identical content receive identical IDs.
     */
    fun computeStableId(context: Context, uri: Uri, staged: StagedPdf? = null): String {
        // Fast path 1: Admitted snapshot capability provided directly
        if (staged != null) {
            return formatV2Id(staged.sha256)
        }

        // Fast path 2: Already admitted snapshot registered in DocumentStager
        val stagedDoc = DocumentStager.stagedFor(uri)
        if (stagedDoc != null) {
            return formatV2Id(stagedDoc.sha256)
        }

        // Direct path: Stream-based SHA-256 calculation
        val contentHash = computeStreamContentHash(context, uri)
        return formatV2Id(contentHash)
    }

    /**
     * Computes the SHA-256 digest of an open [InputStream] in 64KB buffers.
     */
    fun computeContentHash(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeStreamContentHash(context: Context, uri: Uri): String {
        val stream = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> {
                val path = uri.path ?: uri.schemeSpecificPart
                File(path).inputStream()
            }
            null -> {
                val file = File(uri.toString())
                if (file.exists()) file.inputStream()
                else context.contentResolver.openInputStream(uri)
            }
            else -> context.contentResolver.openInputStream(uri)
        } ?: throw IllegalArgumentException("Cannot open content stream for document identity: $uri")

        return stream.use { computeContentHash(it) }
    }

    /**
     * Computes the legacy metadata-only document ID (SHA-256 of "$name::$size").
     * Retained solely for backwards compatibility checks, ambiguity detection, and safe migration.
     */
    fun computeLegacyId(context: Context, uri: Uri): String {
        var name = ""
        var size = 0L

        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) name = cursor.getString(nameIndex) ?: ""
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                // Ignore resolver query errors
            }
        } else if (uri.scheme == "file") {
            val path = uri.path ?: uri.schemeSpecificPart
            name = FileUtils.getFileName(context, uri) ?: File(path).name
            size = File(path).length()
        }

        if (name.isEmpty()) {
            name = uri.lastPathSegment ?: uri.toString()
        }
        name = name.substringAfterLast('/').substringAfterLast('\\')

        return computeLegacyId(name, size)
    }

    /**
     * Computes the legacy metadata-only document ID from name and size.
     */
    fun computeLegacyId(name: String, size: Long): String {
        val rawInput = "$name::$size"
        val bytes = MessageDigest.getInstance("SHA-256").digest(rawInput.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Resolves the stable V2 document ID and safely migrates any existing legacy metadata-only
     * state if and only if:
     * 1. No V2 state already exists for this content.
     * 2. Legacy state exists for the document's metadata tuple.
     * 3. The legacy state has NOT been marked ambiguous and has NOT been claimed/bound by another
     *    document with different content.
     *
     * Once migrated, the legacy keys are atomically retired and bound exclusively to this content
     * SHA-256 so that no subsequent document with identical metadata but different content can
     * ever receive or overwrite this state.
     */
    fun getOrMigrateDocumentId(context: Context, uri: Uri, staged: StagedPdf? = null): String {
        val v2DocId = computeStableId(context, uri, staged)
        val contentSha256 = extractSha256(v2DocId) ?: return v2DocId

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 1. If V2 state already exists, no migration needed
        val hasV2State = prefs.contains("page_$v2DocId") ||
                prefs.contains("bookmarks_$v2DocId") ||
                prefs.contains("editor_page_$v2DocId")

        if (hasV2State) {
            return v2DocId
        }

        // 2. Look up potential legacy state
        val legacyId = runCatching { computeLegacyId(context, uri) }.getOrNull() ?: return v2DocId

        val legacyBindingKey = "$KEY_LEGACY_BINDING_PREFIX$legacyId"
        val legacyAmbiguousKey = "$KEY_LEGACY_AMBIGUOUS_PREFIX$legacyId"

        // 3. Ambiguity check: if marked ambiguous, do not migrate to any document
        if (prefs.getBoolean(legacyAmbiguousKey, false)) {
            return v2DocId
        }

        // 4. Exclusive binding check: if already bound to another document with different content, do NOT assign
        val existingBinding = prefs.getString(legacyBindingKey, null)
        if (existingBinding != null) {
            if (existingBinding != contentSha256) {
                // Claimed by different content: refuse assignment
                return v2DocId
            }
            // Already claimed by this content
            return v2DocId
        }

        // 5. Check if any legacy keys actually exist to migrate
        val legacyPageKey = "page_$legacyId"
        val legacyOffsetKey = "offset_$legacyId"
        val legacyBookmarksKey = "bookmarks_$legacyId"
        val legacyEditorPageKey = "editor_page_$legacyId"

        val hasLegacyKeys = prefs.contains(legacyPageKey) ||
                prefs.contains(legacyBookmarksKey) ||
                prefs.contains(legacyEditorPageKey)

        if (!hasLegacyKeys) {
            return v2DocId
        }

        // 6. Atomic migration: copy to V2 keys, record exclusive binding, and retire legacy keys
        val editor = prefs.edit()
        if (prefs.contains(legacyPageKey)) {
            editor.putInt("page_$v2DocId", prefs.getInt(legacyPageKey, 0))
            editor.remove(legacyPageKey)
        }
        if (prefs.contains(legacyOffsetKey)) {
            editor.putInt("offset_$v2DocId", prefs.getInt(legacyOffsetKey, 0))
            editor.remove(legacyOffsetKey)
        }
        if (prefs.contains(legacyBookmarksKey)) {
            val bookmarks = prefs.getStringSet(legacyBookmarksKey, null)
            if (bookmarks != null) {
                editor.putStringSet("bookmarks_$v2DocId", bookmarks)
            }
            editor.remove(legacyBookmarksKey)
        }
        if (prefs.contains(legacyEditorPageKey)) {
            editor.putInt("editor_page_$v2DocId", prefs.getInt(legacyEditorPageKey, 0))
            editor.remove(legacyEditorPageKey)
        }

        // Record binding to this specific contentSha256
        editor.putString(legacyBindingKey, contentSha256)
        editor.apply()

        return v2DocId
    }

    /**
     * Explicitly marks a legacy metadata ID as ambiguous and retires its legacy state,
     * ensuring no document can claim it.
     */
    fun markLegacyAmbiguous(context: Context, legacyId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("$KEY_LEGACY_AMBIGUOUS_PREFIX$legacyId", true)
            .remove("page_$legacyId")
            .remove("offset_$legacyId")
            .remove("bookmarks_$legacyId")
            .remove("editor_page_$legacyId")
            .apply()
    }

    /**
     * Reads saved reader bookmarks for a document.
     */
    fun getSavedBookmarks(context: Context, docId: String): Set<Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("bookmarks_$docId", emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
    }

    /**
     * Saves reader bookmarks for a document.
     */
    fun saveBookmarks(context: Context, docId: String, bookmarks: Set<Int>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet("bookmarks_$docId", bookmarks.map { it.toString() }.toSet())
            .apply()
    }

    /**
     * Reads saved reader scroll position (page and pixel offset).
     */
    fun getSavedReaderPosition(context: Context, docId: String): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val page = prefs.getInt("page_$docId", 0)
        val offset = prefs.getInt("offset_$docId", 0)
        return Pair(page, offset)
    }

    /**
     * Saves reader scroll position (page and pixel offset).
     */
    fun saveReaderPosition(context: Context, docId: String, page: Int, offset: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("page_$docId", page)
            .putInt("offset_$docId", offset)
            .apply()
    }

    /**
     * Reads saved editor page index.
     */
    fun getSavedEditorPage(context: Context, docId: String, fallback: Int = 0): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("editor_page_$docId", fallback).coerceAtLeast(0)
    }

    /**
     * Saves editor page index.
     */
    fun saveEditorPage(context: Context, docId: String, page: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("editor_page_$docId", page)
            .apply()
    }

    /**
     * Clears all reader preferences for testing.
     */
    fun clearPreferencesForTesting(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }
}
