package com.pdfchemy.app.billing

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementPersistenceCoordinatorTest {
    @Before fun setup() { EntitlementPersistenceCoordinator.resetForTesting() }
    @After fun cleanup() { EntitlementPersistenceCoordinator.resetForTesting() }

    @Test fun exhaustedWritesRemainRetryableWithoutUnboundedAutomaticWork() = runTest {
        var attempts = 0
        val store = object : EntitlementStore {
            override fun read() = false
            override fun write(entitled: Boolean): Boolean { attempts++; return false }
        }
        val coordinator = EntitlementPersistenceCoordinator(store, this, StandardTestDispatcher(testScheduler))
        coordinator.save(true)
        advanceUntilIdle()
        assertEquals(3, attempts)
        assertTrue(coordinator.state.value.failed)
        assertFalse(coordinator.state.value.saving)
        assertTrue(coordinator.hasPendingWrite())
    }

    @Test fun anotherOwnersNewerRevocationInvalidatesAnOldGrantRetry() = runTest {
        val writes = mutableListOf<Boolean>()
        val store = object : EntitlementStore {
            override fun read() = false
            override fun write(entitled: Boolean): Boolean { writes.add(entitled); return !entitled }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = EntitlementPersistenceCoordinator(store, this, dispatcher)
        val newer = EntitlementPersistenceCoordinator(store, this, dispatcher)
        first.save(true)
        runCurrent()
        newer.save(false)
        advanceUntilIdle()
        assertEquals(listOf(true, false), writes)
        assertFalse(newer.state.value.failed)
        assertFalse(first.hasPendingWrite())
    }

    @Test(timeout = 10_000L) fun cancelledBlockingWriteFinishesBeforeNewerCommitOffMain() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CompletableDeferred<Unit>()
        val ioThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val io = Executors.newSingleThreadExecutor {
            Thread(it, "billing-storage-test").also(ioThread::set)
        }.asCoroutineDispatcher()
        val dispatcher = StandardTestDispatcher()
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val handler = CoroutineExceptionHandler { _, error -> errors.add(error) }
        val firstScope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        val nextScope = CoroutineScope(SupervisorJob() + dispatcher + handler)
        val writes = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val store = object : EntitlementStore {
            override fun read() = false
            override fun write(entitled: Boolean): Boolean {
                if (entitled) entered.complete(Unit)
                assertSame(ioThread.get(), Thread.currentThread())
                if (entitled) release.await()
                writes.add(entitled)
                return true
            }
        }
        try {
            EntitlementPersistenceCoordinator(store, firstScope, io).save(true)
            dispatcher.scheduler.runCurrent()
            entered.await()
            firstScope.cancel()
            EntitlementPersistenceCoordinator(store, nextScope, io).save(false)
            dispatcher.scheduler.runCurrent()
            assertTrue(writes.isEmpty())
            release.countDown()
            withContext(io) { Unit }
            dispatcher.scheduler.runCurrent()
            withContext(io) { Unit }
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(true, false), writes)
            assertTrue(errors.isEmpty())
        } finally {
            release.countDown()
            firstScope.cancel()
            nextScope.cancel()
            dispatcher.scheduler.runCurrent()
            io.close()
        }
    }
}
