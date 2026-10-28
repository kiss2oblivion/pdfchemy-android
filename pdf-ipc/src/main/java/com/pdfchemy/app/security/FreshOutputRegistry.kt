package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** Host-only provenance. A picker result or a worker result cannot establish freshness. */
object FreshOutputRegistry {
    private val fresh = mutableMapOf<Uri, Any?>()

    @Synchronized fun register(uri: Uri) {
        check(uri !in fresh) { "Output capability already registered" }
        fresh[uri] = null
    }
    @Synchronized fun unregister(uri: Uri) { fresh.remove(uri) }
    @Synchronized fun contains(uri: Uri): Boolean = uri in fresh
    @Synchronized fun isClaimed(uri: Uri): Boolean = fresh[uri] != null

    @Synchronized fun claimKnown(targets: List<Uri>, owner: Any) {
        check(targets.none { fresh[it] != null }) { "Output capability already in use" }
        targets.filter { it in fresh }.forEach { fresh[it] = owner }
    }

    @Synchronized fun published(targets: List<Uri>, owner: Any) {
        targets.filter { fresh[it] === owner }.forEach { fresh.remove(it) }
    }

    @Synchronized fun abandon(context: Context, targets: List<Uri>, owner: Any) {
        targets.filter { fresh[it] === owner }.forEach {
            fresh[it] = null
            deleteIfFresh(context, it)
        }
    }

    /** Check the entire batch before opening its first destination. SAF has no general rollback API. */
    @Synchronized fun requireSafeSafTargets(targets: List<Uri>, owner: Any) {
        if (targets.any { it.scheme != "file" && fresh[it] !== owner }) {
            throw UnsupportedOperationException("Saving requires a newly created output copy; choose an output folder")
        }
    }

    /** Delete only an unpublished capability created by this host. Retain provenance if deletion fails. */
    fun deleteIfFresh(context: Context, uri: Uri): Boolean = deleteIfFresh(uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
        else if (uri.scheme == "file") java.io.File(requireNotNull(uri.path)).delete()
        else context.contentResolver.delete(uri, null, null) > 0
    }

    /** The ownership check and deletion share one lock with claimKnown. */
    @Synchronized fun deleteIfFresh(uri: Uri, delete: () -> Boolean): Boolean {
        if (uri !in fresh || isClaimed(uri) || PublicationCoordinator.hasActiveWriter(uri)) return false
        val deleted = runCatching(delete).getOrDefault(false)
        if (deleted) fresh.remove(uri)
        return deleted
    }
}
