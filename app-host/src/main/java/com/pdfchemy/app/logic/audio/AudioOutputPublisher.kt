package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.CancellableProviderWriter
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.security.FreshOutputRegistry
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream

/** Host-only, best-effort SAF publication. No destination capability reaches Android TTS. */
object AudioOutputPublisher {
    suspend fun publish(
        context: Context,
        wav: File,
        destination: Uri,
        isFreshCopy: Boolean = OutputPolicy.isFreshCopy(destination),
        onPublished: (Uri) -> Unit = {}
    ) {
        require(destination.scheme == "content") { "Audio destination must be a content URI" }
        if (!isFreshCopy) throw UnsupportedOperationException("Audio export requires a newly created output copy")
        val owner = Any()
        FreshOutputRegistry.claimKnown(listOf(destination), owner)
        try {
            publishClaimed(context, wav, destination, owner, onPublished)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                FreshOutputRegistry.abandon(context, listOf(destination), owner)
            }
        }
    }

    internal suspend fun publishClaimed(
        context: Context,
        wav: File,
        destination: Uri,
        owner: Any,
        onPublished: (Uri) -> Unit
    ) {
        require(destination.scheme == "content") { "Audio destination must be a content URI" }
        FreshOutputRegistry.requireSafeSafTargets(listOf(destination), owner)
        copy(
            wav,
            listOf(destination),
            { openWholeFile(context, destination) },
            {}, // The owner abandons only after copy releases its writer lease.
            {
                FreshOutputRegistry.published(listOf(destination), owner)
                onPublished(destination)
            }
        )
    }

    /** Whole-file access rejects bounded asset slices before raw FD pipe writes. */
    internal fun openWholeFile(context: Context, destination: Uri): OutputStream? =
        context.contentResolver.openFileDescriptor(destination, "wt")?.let {
            try { ParcelFileDescriptor.AutoCloseOutputStream(it) }
            catch (error: Throwable) { runCatching { it.close() }; throw error }
        }

    fun deletePartial(
        context: Context,
        destination: Uri,
        isFreshCopy: Boolean = OutputPolicy.isFreshCopy(destination)
    ) {
        if (!isFreshCopy) {
            // Existing or unknown destinations must not be deleted during pre-publication failure cleanup
            return
        }
        // A historical Boolean cannot authorize deletion after publication, claim or failed cleanup.
        OutputPolicy.deleteIfFresh(context, destination)
    }

    // Small separate abstraction: HostOutputTransaction is a PDF-worker FD/JSON validator
    // with PDF security quotas, unsuitable for already validated host-generated audio.
    internal suspend fun copy(
        wav: File,
        open: () -> OutputStream?,
        deletePartial: () -> Unit
    ) {
        copy(wav, emptyList(), open, deletePartial)
    }

    internal suspend fun copy(
        wav: File,
        targets: List<Uri>,
        open: () -> OutputStream?,
        deletePartial: () -> Unit,
        onPublished: () -> Unit = {}
    ) = kotlinx.coroutines.coroutineScope {
        val handle = com.pdfchemy.app.security.PublicationCoordinator.acquire(targets, wav.length())
        var completedNormally = false
        val watcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!completedNormally) {
                    handle.abort()
                }
            }
        }
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        if (job != null) {
            handle.attachJob(job)
        }
        try {
            val stream = requireNotNull(open()) { "Unable to open audio destination" }
            handle.registerStream(stream).use {
                stream.use { output ->
                    val writer = CancellableProviderWriter.create(output)
                    wav.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            writer.write(buffer, count)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            // Retire freshness and record the result before coroutineScope can cancel result delivery.
            onPublished()
            completedNormally = true
        } catch (error: Throwable) {
            runCatching { deletePartial() }
            // A canceled provider can return null, fail during open, or close a stream.
            // Preserve cancellation while retaining genuine active-copy exceptions.
            if (error is Exception) currentCoroutineContext().ensureActive()
            throw error
        } finally {
            watcher.cancel()
            handle.close()
        }
    }
}
