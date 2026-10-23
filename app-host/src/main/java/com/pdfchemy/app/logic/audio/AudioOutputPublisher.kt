package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.*
import java.io.File
import java.io.OutputStream

/** Host-only, best-effort SAF publication. No destination capability reaches Android TTS. */
object AudioOutputPublisher {
    suspend fun publish(
        context: Context,
        wav: File,
        destination: Uri,
        isFreshCopy: Boolean = OutputPolicy.isFreshCopy(destination)
    ) {
        copy(
            wav,
            listOf(destination),
            { context.contentResolver.openOutputStream(destination, "wt") },
            { deletePartial(context, destination, isFreshCopy) }
        )
        OutputPolicy.markPublished(destination)
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
        if (com.pdfchemy.app.security.PublicationCoordinator.hasActiveWriter(destination)) {
            // Uninterruptible writer still active: do not delete destination while writer is writing
            return
        }
        OutputPolicy.unregisterFreshCopy(destination)
        runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
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
        deletePartial: () -> Unit
    ) = kotlinx.coroutines.coroutineScope {
        val handle = com.pdfchemy.app.security.PublicationCoordinator.acquire(targets, wav.length())
        var completedNormally = false
        val watcher = launch(Dispatchers.IO) {
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
                    wav.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            completedNormally = true
        } catch (error: Throwable) {
            runCatching { deletePartial() }
            throw error
        } finally {
            watcher.cancel()
            handle.close()
        }
    }
}
