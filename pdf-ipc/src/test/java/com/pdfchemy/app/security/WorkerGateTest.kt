package com.pdfchemy.app.security

import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkerGateTest {

    private class FakeBinder : IBinder {
        var deathRecipient: IBinder.DeathRecipient? = null
        var isAlive = true

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            deathRecipient = recipient
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            if (deathRecipient == recipient) {
                deathRecipient = null
                return true
            }
            return false
        }

        override fun isBinderAlive(): Boolean = isAlive
        override fun pingBinder(): Boolean = isAlive
        override fun getInterfaceDescriptor(): String? = "FakeBinder"
        override fun queryLocalInterface(descriptor: String): android.os.IInterface? = null
        override fun dump(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<out String>?) {}
        override fun transact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean = true

        fun simulateDeath() {
            isAlive = false
            deathRecipient?.binderDied()
        }
    }

    @Test
    fun beginReturnsPositiveTokenAndTransitionsToReserved() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 10_000)
            assertTrue("Token must be positive", token > 0L)
            assertTrue(gate.isOwner(token))
            assertTrue(gate.isBusy())
        }
    }

    @Test
    fun concurrentBeginReturnsZeroBusy() {
        WorkerGate {}.use { gate ->
            val tokenA = gate.begin(null, 10_000)
            assertTrue(tokenA > 0L)

            val tokenB = gate.begin(null, 10_000)
            assertEquals("Concurrent admission must return 0 (BUSY)", 0L, tokenB)
            assertTrue(gate.isOwner(tokenA))
        }
    }

    @Test
    fun startExecutionRequiresMatchingTokenAndReservedState() {
        WorkerGate {}.use { gate ->
            val token = gate.begin(null, 10_000)
            assertFalse("Wrong token cannot start execution", gate.startExecution(token + 999))
            assertFalse("Zero token cannot start execution", gate.startExecution(0L))

            assertTrue("Matching token starts execution", gate.startExecution(token))
            assertFalse("Cannot re-start execution once already RUNNING", gate.startExecution(token))
        }
    }

    @Test
    fun markAwaitingHostAcceptRequiresMatchingTokenAndRunningState() {
        WorkerGate {}.use { gate ->
            val token = gate.begin(null, 10_000)
            assertFalse("Cannot await accept while still in RESERVED state", gate.markAwaitingHostAccept(token))

            assertTrue(gate.startExecution(token))
            assertFalse("Wrong token cannot transition to AWAITING_HOST_ACCEPT", gate.markAwaitingHostAccept(token + 1))
            assertTrue("Transition to AWAITING_HOST_ACCEPT succeeds", gate.markAwaitingHostAccept(token, 10_000))
            assertFalse("Cannot re-transition if already in AWAITING_HOST_ACCEPT", gate.markAwaitingHostAccept(token))
            assertTrue("Still owns the gate while awaiting accept", gate.isOwner(token))
            assertTrue("Gate is still busy while awaiting accept", gate.isBusy())
        }
    }

    @Test
    fun completeReleasesGateAndAllowsNextAdmission() {
        WorkerGate {}.use { gate ->
            val tokenA = gate.begin(null, 10_000)
            gate.startExecution(tokenA)
            gate.markAwaitingHostAccept(tokenA)

            assertTrue("complete(tokenA) must succeed", gate.complete(tokenA))
            assertFalse("tokenA is no longer owner", gate.isOwner(tokenA))
            assertFalse("Gate is now idle", gate.isBusy())

            val tokenB = gate.begin(null, 10_000)
            assertTrue("tokenB can now be admitted", tokenB > 0L)
            assertNotEquals("Tokens must never be reused", tokenA, tokenB)
        }
    }

    @Test
    fun completeStrictlyRequiresAwaitingHostAcceptState() {
        WorkerGate {}.use { gate ->
            val token = gate.begin(null, 10_000)
            // 1. In RESERVED state: complete must fail
            assertFalse("complete while in RESERVED state must return false", gate.complete(token))
            assertTrue(gate.isOwner(token))

            // 2. In RUNNING state: complete must fail
            assertTrue(gate.startExecution(token))
            assertFalse("complete while in RUNNING state must return false", gate.complete(token))
            assertTrue(gate.isOwner(token))

            // 3. In AWAITING_HOST_ACCEPT state: complete must succeed
            assertTrue(gate.markAwaitingHostAccept(token))
            assertTrue("complete while in AWAITING_HOST_ACCEPT must succeed", gate.complete(token))
            assertFalse(gate.isOwner(token))
            assertFalse(gate.isBusy())
        }
    }

    @Test
    fun cancelledExecutionWatchdogDoesNotTerminateAwaitingHostAcceptWorker() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 150) // 150ms execution deadline
            gate.startExecution(token)
            // Transition to AWAITING_HOST_ACCEPT with a long deadline
            gate.markAwaitingHostAccept(token, 60_000)

            // Wait 300ms for old 150ms execution watchdog deadline to pass
            assertFalse("Stale execution watchdog must NOT terminate worker in AWAITING_HOST_ACCEPT", terminated.await(300, TimeUnit.MILLISECONDS))
            assertTrue(gate.isOwner(token))
            assertTrue(gate.complete(token))
        }
    }

    @Test
    fun acquireHelperUsesPrivateReleaseWithoutViolatingStateSemantics() {
        WorkerGate {}.use { gate ->
            val resource = gate.acquire(10_000)
            assertNotNull(resource)
            assertTrue(gate.isBusy())
            resource!!.close()
            assertFalse(gate.isBusy())
        }
    }

    @Test
    fun staleCompleteCannotReleaseCurrentOwner() {
        WorkerGate {}.use { gate ->
            val tokenA = gate.begin(null, 10_000)
            gate.startExecution(tokenA)
            gate.markAwaitingHostAccept(tokenA)

            assertFalse("Stale complete must return false", gate.complete(tokenA + 1234))
            assertFalse("Zero complete must return false", gate.complete(0L))

            assertTrue("tokenA remains owner", gate.isOwner(tokenA))
            assertEquals("Next request still gets BUSY", 0L, gate.begin(null, 10_000))
        }
    }

    @Test
    fun terminateIfOwnerPoisonsGateAndCallsTerminate() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 10_000)
            gate.startExecution(token)

            assertTrue(gate.terminateIfOwner(token))
            assertTrue("Terminate callback must fire", terminated.await(1, TimeUnit.SECONDS))
            assertFalse("Gate cannot be completed once poisoned", gate.complete(token))
            assertEquals("Poisoned gate cannot admit new requests", 0L, gate.begin(null, 10_000))
        }
    }

    @Test
    fun staleTerminateIfOwnerIsNoOpAndPreservesCurrentOwner() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 10_000)
            assertFalse("Stale terminate must return false", gate.terminateIfOwner(token + 999))
            assertFalse("Zero terminate must return false", gate.terminateIfOwner(0L))

            assertEquals("Terminate callback must NOT fire", 1L, terminated.count)
            assertTrue("Current token remains owner", gate.isOwner(token))
        }
    }

    @Test
    fun executionWatchdogFiresAndPoisonsGate() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 150)
            gate.startExecution(token)

            assertTrue("Watchdog must terminate worker on timeout", terminated.await(2, TimeUnit.SECONDS))
            assertFalse("Cannot complete timed-out gate", gate.complete(token))
            assertEquals("Timed-out gate cannot admit new requests", 0L, gate.begin(null, 10_000))
        }
    }

    @Test
    fun hostAcceptWatchdogFiresAndTerminatesWorker() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val token = gate.begin(null, 60_000) // long execution deadline
            gate.startExecution(token)
            gate.markAwaitingHostAccept(token, 150) // short host-accept deadline

            assertTrue("Host accept watchdog must fire", terminated.await(2, TimeUnit.SECONDS))
            assertFalse("Cannot complete timed-out gate", gate.complete(token))
            assertEquals("Timed-out gate cannot admit new requests", 0L, gate.begin(null, 10_000))
        }
    }

    @Test
    fun ownerBinderDeathTriggersImmediateWorkerTermination() {
        val terminated = CountDownLatch(1)
        WorkerGate { terminated.countDown() }.use { gate ->
            val fakeOwner = FakeBinder()
            val token = gate.begin(fakeOwner, 60_000)
            assertTrue(token > 0L)

            fakeOwner.simulateDeath()

            assertTrue("Worker must be terminated upon owner death", terminated.await(2, TimeUnit.SECONDS))
            assertFalse("Poisoned gate cannot be completed", gate.complete(token))
            assertEquals("Dead worker cannot admit new operations", 0L, gate.begin(null, 10_000))
        }
    }
}
