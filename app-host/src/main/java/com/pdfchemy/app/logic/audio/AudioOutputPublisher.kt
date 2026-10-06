package com.pdfchemy.app.logic.audio

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.OutputStream

/** Host-only, best-effort SAF publication. No destination capability reaches Android TTS. */
object AudioOutputPublisher {
    suspend fun publish(context: Context, wav: File, destination: Uri) = copy(
        wav,
        { context.contentResolver.openOutputStream(destination, "wt") },
        { deletePartial(context, destination) }
    )

    fun deletePartial(context: Context, destination: Uri) {
        runCatching { DocumentFile.fromSingleUri(context, destination)?.delete() }
    }

    // Small separate abstraction: HostOutputTransaction is a PDF-worker FD/JSON validator
    // with PDF security quotas, unsuitable for already validated host-generated audio.
    internal suspend fun copy(wav: File, open: () -> OutputStream?, deletePartial: () -> Unit) {
        try {
            requireNotNull(open()) { "Unable to open audio destination" }.use { output ->
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
        } catch (error: Throwable) {
            runCatching { deletePartial() }
            throw error
        }
    }
}
