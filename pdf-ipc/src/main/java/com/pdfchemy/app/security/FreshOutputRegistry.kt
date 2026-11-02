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
    @Synchronized fun unregister(uri: Uri) {
        check(fresh[uri] == null) { "Output capability is still in use" }
        fresh.remove(uri)
    }
    @Synchronized fun contains(uri: Uri): Boolean = uri in fresh
    @Synchronized fun isClaimed(uri: Uri): Boolean = fresh[uri] != null

    @Synchronized fun claimKnown(targets: List<Uri>, owner: Any) {
        check(targets.none { fresh[it] != null }) { "Output capability already in use" }
        targets.filter { it in fresh }.forEach { fresh[it] = owner }
    }

    @Synchronized fun published(targets: List<Uri>, owner: Any) {
        targets.filter { fresh[it] === owner }.forEach { fresh.remove(it) }
    }

    fun abandon(context: Context, targets: List<Uri>, owner: Any) {
        targets.forEach { uri -> deleteOwned(uri, owner) { deleteDocument(context, uri) } }
    }

    /** Check the entire batch before opening its first destination. SAF has no general rollback API. */
    @Synchronized fun requireSafeSafTargets(targets: List<Uri>, owner: Any) {
        if (targets.any { it.scheme != "file" && fresh[it] !== owner }) {
            throw UnsupportedOperationException("Saving requires a newly created output copy; choose an output folder")
        }
    }

    /** Delete only an unpublished capability created by this host. Retain provenance if deletion fails. */
    private fun deleteDocument(context: Context, uri: Uri): Boolean =
        if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
        else if (uri.scheme == "file") java.io.File(requireNotNull(uri.path)).delete()
        else context.contentResolver.delete(uri, null, null) > 0

    fun deleteIfFresh(context: Context, uri: Uri): Boolean = deleteIfFresh(uri) { deleteDocument(context, uri) }

    fun deleteIfFresh(uri: Uri, delete: () -> Boolean): Boolean = deleteOwned(uri, null, delete)

    /** Claim before I/O; a blocked provider may not hold the global monitor. */
    private fun deleteOwned(uri: Uri, owner: Any?, delete: () -> Boolean): Boolean {
        val deleting = Any()
        synchronized(this) {
            if (uri !in fresh || fresh[uri] !== owner || PublicationCoordinator.hasActiveWriter(uri)) return false
            fresh[uri] = deleting
        }
        val deleted = runCatching(delete).getOrDefault(false)
        synchronized(this) {
            if (fresh[uri] === deleting) {
                if (deleted) fresh.remove(uri) else fresh[uri] = null
            }
        }
        return deleted
    }
}
