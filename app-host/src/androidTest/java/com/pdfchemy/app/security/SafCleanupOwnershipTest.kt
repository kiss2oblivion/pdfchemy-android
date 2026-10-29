package com.pdfchemy.app.security

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.HostOutputTransaction
import com.pdfchemy.app.logic.OutputPolicy
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafCleanupOwnershipTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun fixture(allowDelete: Boolean, blockDelete: Boolean = false): Uri {
        val uri = Uri.parse("content://com.pdfchemy.app.security.outputfixture/cleanup_${System.nanoTime()}")
        context.contentResolver.call(uri, "configure", uri.lastPathSegment, Bundle().apply {
            putBoolean("allowDelete", allowDelete); putBoolean("blockDelete", blockDelete)
        })
        return uri
    }
    private fun control(uri: Uri, method: String) = context.contentResolver.call(uri, method, uri.lastPathSegment, null)!!

    @Test fun stalledProviderCleanupCannotBlockOtherOwnershipChecksOrReclaimItsUri() = runBlocking {
        val fresh = fixture(allowDelete = true, blockDelete = true)
        val existing = fixture(allowDelete = false)
        OutputPolicy.registerFreshCopy(fresh)
        val transaction = HostOutputTransaction(context, listOf(fresh))
        val closing = async(Dispatchers.IO) { transaction.close() }
        var probe: Deferred<Boolean>? = null
        try {
            withContext(Dispatchers.IO) { control(fresh, "awaitDelete") }
            probe = async(Dispatchers.IO) { FreshOutputRegistry.contains(existing) }
            val progressed = withTimeoutOrNull(5_000) { probe.await() }
            assertNotNull("A stalled SAF provider must not hold the global ownership monitor", progressed)
            assertEquals(false, progressed)
            assertTrue(FreshOutputRegistry.contains(fresh))
            assertTrue("In-flight deletion must retain exclusive ownership", FreshOutputRegistry.isClaimed(fresh))
            assertTrue("Active deletion provenance cannot be retired by another caller",
                runCatching { OutputPolicy.unregisterFreshCopy(fresh) }.isFailure)
            assertFalse(FreshOutputRegistry.deleteIfFresh(fresh) { fail("A second delete raced the active cleanup"); false })
            assertTrue(runCatching { HostOutputTransaction(context, listOf(fresh)).use { } }.isFailure)
        } finally {
            control(fresh, "releaseDelete")
            closing.join(); probe?.join()
            OutputPolicy.unregisterFreshCopy(fresh)
        }
        assertTrue(control(fresh, "state").getBoolean("deleted"))
        assertArrayEquals("existing destination".toByteArray(), control(existing, "state").getByteArray("bytes"))
        assertEquals(0, control(existing, "state").getInt("opens"))
    }

    @Test fun failedCleanupRetainsPendingProvenanceForExplicitRetry() = runBlocking {
        val fresh = fixture(allowDelete = false)
        OutputPolicy.registerFreshCopy(fresh)
        HostOutputTransaction(context, listOf(fresh)).close()
        try {
            assertTrue(FreshOutputRegistry.contains(fresh))
            assertFalse(FreshOutputRegistry.isClaimed(fresh))
            control(fresh, "allowDelete")
            assertTrue(FreshOutputRegistry.deleteIfFresh(context, fresh))
            assertFalse(FreshOutputRegistry.contains(fresh))
            assertTrue(control(fresh, "state").getBoolean("deleted"))
        } finally { OutputPolicy.unregisterFreshCopy(fresh) }
    }
}
