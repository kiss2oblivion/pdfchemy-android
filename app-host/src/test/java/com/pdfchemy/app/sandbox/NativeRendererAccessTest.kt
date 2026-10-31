package com.pdfchemy.app.sandbox

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class NativeRendererAccessTest {
    @Test fun queuedCancellationPreservesTheOwnerAndNextRequest() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            NativeRendererAccess.withPermit { events += "first"; release.await(); events += "first-done" }
        }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            NativeRendererAccess.withPermit { events += "cancelled" }
        }
        val last = launch(start = CoroutineStart.UNDISPATCHED) {
            NativeRendererAccess.withPermit { events += "last" }
        }
        try {
            assertEquals(listOf("first"), events)
            cancelled.cancelAndJoin()
            assertEquals(listOf("first"), events)
            release.complete(Unit)
            first.join(); last.join()
            assertEquals(listOf("first", "first-done", "last"), events)
        } finally { release.complete(Unit); first.cancelAndJoin(); cancelled.cancelAndJoin(); last.cancelAndJoin() }
    }

    @Test fun failureReleasesTheRendererForTheNextCaller() = runTest {
        val error = runCatching { NativeRendererAccess.withPermit { error("render failed") } }.exceptionOrNull()
        assertEquals("render failed", error?.message)
        assertEquals(42, NativeRendererAccess.withPermit { 42 })
    }

    @Test fun staleOrRepeatedCloseCannotReleaseASuccessor() = runTest {
        val first = NativeRendererAccess.acquire()
        first.close()
        val successor = NativeRendererAccess.acquire()
        first.close()
        var entered = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            NativeRendererAccess.withPermit { entered = true }
        }
        try {
            assertFalse(entered)
            successor.close(); successor.close()
            waiter.join()
            assertTrue(entered)
        } finally { successor.close(); waiter.cancelAndJoin() }
    }
}
