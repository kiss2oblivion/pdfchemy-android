package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.BoundedWorkerStorage
import com.pdfchemy.app.jail.BoundedWorkerStorage.Kind
import com.pdfchemy.app.logic.PdfGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkerStorageAllocationFailureSecurityTest {
    @Test fun partialOutputAllocationFailureImmediatelyReleasesSharedCollector() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val targets = (0..1).map { index ->
            val id = "allocation_failure_${System.nanoTime()}_$index"
            Uri.parse("content://com.pdfchemy.app.security.outputfixture/$id").also {
                context.contentResolver.call(it, "configure", id, Bundle())
            }
        }
        BoundedWorkerStorage(context).use { occupied ->
            repeat(SecurityLimits.MAX_SCRATCH_FDS) { occupied.create(Kind.SCRATCH) }
            repeat(SecurityLimits.MAX_BATCH_FDS - 1) { occupied.create(Kind.OUTPUT) }
            // One global slot remains: the request creates its first output,
            // then fails creating its second, before binding or executing.
            val result = runCatching { PdfGateway.executeEngineBatch(context, "SPLIT", emptyList(), targets, "{}") }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("file quota"))
            BoundedWorkerStorage(context).use { probe ->
                probe.create(Kind.OUTPUT).writer.close()
            }
            targets.forEach { target ->
                val state = requireNotNull(context.contentResolver.call(target, "state", target.lastPathSegment, null))
                assertEquals(0, state.getInt("opens"))
                assertEquals("existing destination", String(state.getByteArray("bytes")!!))
            }
        }
    }
}
