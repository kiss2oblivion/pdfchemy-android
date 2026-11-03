package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.security.SecurityLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Publishes host text through the same bounded snapshots and fresh-copy policy as worker outputs. */
internal object TextOutputPublisher {
    suspend fun publish(context: Context, destination: Uri, produceText: suspend () -> String) = withContext(Dispatchers.IO) {
        require(OutputPolicy.isFreshCopy(destination)) { "Saving requires a newly created output copy" }
        HostOutputTransaction(context, listOf(destination)).use { transaction ->
            // Claim ownership before conversion so cancellation/failure also cleans a never-written copy.
            val text = produceText()
            SecurityLimits.enforceStringLength(text)
            val bytes = text.toByteArray(Charsets.UTF_8)
            ParcelFileDescriptor.AutoCloseOutputStream(transaction.workerDescriptors.single().dup()).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(64 * 1024, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            }
            transaction.validateAndSnapshot("{\"success\":true}")
            transaction.commit()
        }
    }
}
