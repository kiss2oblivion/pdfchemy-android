package com.pdfchemy.app.logic

import android.net.Uri
import com.pdfchemy.app.utils.DocumentStager
import java.io.Closeable

/** Keeps selected inputs alive independently of picker, screen and worker lifetimes. */
class StagedSelectionOwner(
    private val release: (Uri) -> Unit = DocumentStager::release
) : Closeable {
    private val leases = linkedMapOf<Uri, Closeable>()
    val uris: List<Uri> @Synchronized get() = leases.keys.toList()

    @Synchronized internal fun adopt(uri: Uri, lease: Closeable) {
        val previous = leases.put(uri, lease)
        previous?.close()
    }

    /** Acquire new leases before dropping old ones; callers invoke this before publishing UI state. */
    @Synchronized fun retainOnly(uris: List<Uri>) {
        val wanted = uris.toSet()
        val added = linkedMapOf<Uri, Closeable>()
        try {
            wanted.filter { it !in leases }.forEach { uri ->
                added[uri] = DocumentStager.retain(uri) ?: Closeable {}
            }
        } catch (error: Throwable) {
            added.values.forEach { it.close() }
            throw error
        }
        leases.putAll(added)
        leases.keys.filter { it !in wanted }.forEach { uri ->
            val lease = leases.remove(uri)!!
            try { release(uri) } finally { lease.close() }
        }
    }

    @Synchronized override fun close() = retainOnly(emptyList())
}
