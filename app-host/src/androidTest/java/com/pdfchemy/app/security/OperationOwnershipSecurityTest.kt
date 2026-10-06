package com.pdfchemy.app.security

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pdfchemy.app.jail.IPdfJailCallback
import com.pdfchemy.app.jail.IPdfJailService
import com.pdfchemy.app.jail.IPdfJailStringCallback
import com.pdfchemy.app.jail.OperationScratchBroker
import com.pdfchemy.app.logic.PdfGateway
import com.pdfchemy.app.sandbox.IPdfNativeRendererService
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OperationOwnershipSecurityTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private suspend fun <T> withWorker(action: suspend (IPdfJailService) -> T): T {
        val bound = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.complete(requireNotNull(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.jail.PdfJailService"), connection, Context.BIND_AUTO_CREATE))
        try { return action(IPdfJailService.Stub.asInterface(withTimeout(15_000) { bound.await() })) }
        finally { context.unbindService(connection) }
    }

    private suspend fun withRenderer(action: suspend (IPdfNativeRendererService) -> Unit) {
        val bound = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { bound.complete(requireNotNull(binder)) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        check(context.bindService(Intent().setClassName(context, "com.pdfchemy.app.sandbox.PdfNativeRendererService"), connection, Context.BIND_AUTO_CREATE))
        try { action(IPdfNativeRendererService.Stub.asInterface(withTimeout(15_000) { bound.await() })) }
        finally { context.unbindService(connection) }
    }

    private suspend fun pid(worker: IPdfJailService): Int {
        val result = CompletableDeferred<String>()
        OperationScratchBroker(context).use { scratch ->
            val token = worker.beginOperation(scratch)
            check(token > 0L) { "Failed to acquire token: $token" }
            try {
                worker.executeEngine(token, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratch,
                    object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { result.complete(resultJson) }
                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            result.completeExceptionally(IllegalStateException("Jail $errorCode: $errorMessage"))
                        }
                    })
                val out = withTimeout(10_000) { result.await() }
                worker.completeOperation(token)
                return JSONObject(out).getInt("pid")
            } catch (t: Throwable) {
                runCatching { worker.abortOperation(token) }
                throw t
            }
        }
    }

    private fun pdf(): File = File.createTempFile("ownership_src_", ".pdf", context.cacheDir).also { file ->
        PDDocument().use { doc -> repeat(2) { doc.addPage(PDPage()) }; doc.save(file) }
    }

    // A. BUSY has no ownership token
    @Test fun busyHasNoOwnershipToken(): Unit = runBlocking {
        val target = File.createTempFile("ownership_target_", ".pdf", context.cacheDir).apply { writeText("original content") }
        try {
            withWorker { worker ->
                val oldPid = pid(worker)
                OperationScratchBroker(context).use { scratchA ->
                    OperationScratchBroker(context).use { scratchB ->
                        val tokenA = worker.beginOperation(scratchA)
                        assertTrue("Operation A must receive a valid token", tokenA > 0L)

                        worker.executeEngine(tokenA, "DEBUG_BLOCK", null, null, "{}", null, "", 0, scratchA,
                            object : IPdfJailStringCallback.Stub() {
                                override fun onSuccess(resultJson: String) { fail("Block unexpectedly completed") }
                                override fun onFailure(errorCode: Int, errorMessage: String) {}
                            })

                        // Operation B attempts admission
                        val tokenB = worker.beginOperation(scratchB)
                        assertEquals("BUSY request must receive token 0", 0L, tokenB)

                        // Operation A remains alive
                        assertTrue("Worker binder must remain alive", worker.asBinder().isBinderAlive)
                        assertEquals("Original destination file must remain untouched", "original content", target.readText())

                        worker.abortOperation(tokenA)
                    }
                }
            }
        } finally {
            target.delete()
        }
    }

    // B. Cancelled BUSY request cannot kill leaseholder
    @Test fun cancelledBusyRequestCannotKillLeaseholder(): Unit = runBlocking {
        withWorker { worker ->
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratchA ->
                val tokenA = worker.beginOperation(scratchA)
                check(tokenA > 0L)
                worker.executeEngine(tokenA, "DEBUG_BLOCK", null, null, "{}", null, "", 0, scratchA,
                    object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { fail("Block unexpectedly completed") }
                        override fun onFailure(errorCode: Int, errorMessage: String) {}
                    })

                try {
                    repeat(10) {
                        val job = async(Dispatchers.IO) {
                            runCatching { PdfGateway.executeEngine(context, "DEBUG_IDENTITY", null, null, "{}") }
                        }
                        // Cancel immediately while BUSY or staging
                        job.cancelAndJoin()

                        // Verify worker remains alive and leaseholder is untouched
                        assertFalse("Cancelled BUSY request must NOT kill worker", death.isCompleted)
                        assertTrue(worker.asBinder().isBinderAlive)
                    }
                } finally {
                    worker.abortOperation(tokenA)
                    withTimeout(15_000) { death.await() }
                }
            }
        }
    }

    // C. Stale abort token cannot kill current owner
    @Test fun staleAbortTokenCannotKillCurrentOwner(): Unit = runBlocking {
        withWorker { worker ->
            OperationScratchBroker(context).use { scratch ->
                val tokenA = worker.beginOperation(scratch)
                assertTrue(tokenA > 0L)

                // Stale abort attempts
                worker.abortOperation(tokenA + 9999L)
                worker.abortOperation(0L)
                worker.abortOperation(-1L)

                assertTrue("Worker must remain alive after stale aborts", worker.asBinder().isBinderAlive)

                // Legitimate abort terminates worker
                val death = CompletableDeferred<Unit>()
                worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
                worker.abortOperation(tokenA)
                withTimeout(15_000) { death.await() }
                assertFalse(worker.asBinder().isBinderAlive)
            }
        }
    }

    // D. Stale completion token cannot release current owner
    @Test fun staleCompletionTokenCannotReleaseCurrentOwner(): Unit = runBlocking {
        withWorker { worker ->
            OperationScratchBroker(context).use { scratchA ->
                OperationScratchBroker(context).use { scratchB ->
                    val tokenA = worker.beginOperation(scratchA)
                    assertTrue(tokenA > 0L)

                    // Stale completion attempts
                    assertFalse(worker.completeOperation(tokenA + 9999L))
                    assertFalse(worker.completeOperation(0L))

                    // Gate must still be occupied by tokenA
                    val tokenB = worker.beginOperation(scratchB)
                    assertEquals("Gate must still be BUSY; tokenB rejected", 0L, tokenB)

                    worker.abortOperation(tokenA)
                }
            }
        }
    }

    // E. Worker success remains exclusive until Host accepts
    @Test fun workerSuccessRemainsExclusiveUntilHostAccepts(): Unit = runBlocking {
        withWorker { worker ->
            OperationScratchBroker(context).use { scratchA ->
                OperationScratchBroker(context).use { scratchB ->
                    val tokenA = worker.beginOperation(scratchA)
                    assertTrue(tokenA > 0L)

                    val successResult = CompletableDeferred<String>()
                    worker.executeEngine(tokenA, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratchA,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { successResult.complete(resultJson) }
                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                successResult.completeExceptionally(IllegalStateException(errorMessage))
                            }
                        })
                    val out = withTimeout(10_000) { successResult.await() }
                    assertNotNull(out)

                    // Worker execution completed, but Host has NOT called completeOperation yet!
                    // State is AWAITING_HOST_ACCEPT. Operation B attempts admission:
                    val tokenB = worker.beginOperation(scratchB)
                    assertEquals("Worker gate must remain locked in AWAITING_HOST_ACCEPT until Host accepts", 0L, tokenB)

                    // Host accepts A:
                    assertTrue("completeOperation(tokenA) must succeed", worker.completeOperation(tokenA))

                    // Now Operation B can be admitted:
                    val tokenBSecond = worker.beginOperation(scratchB)
                    assertTrue("After Host acceptance, B can be admitted", tokenBSecond > 0L)
                    val successBSecond = CompletableDeferred<String>()
                    worker.executeEngine(tokenBSecond, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratchB,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { successBSecond.complete(resultJson) }
                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                successBSecond.completeExceptionally(IllegalStateException(errorMessage))
                            }
                        })
                    withTimeout(10_000) { successBSecond.await() }
                    assertTrue(worker.completeOperation(tokenBSecond))
                }
            }
        }
    }

    // F. Host rejection kills exact owner
    @Test fun hostRejectionKillsExactOwner(): Unit = runBlocking {
        val hostPid = Process.myPid()
        val oldPid = withWorker { worker ->
            val p = pid(worker)
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratch ->
                val token = worker.beginOperation(scratch)
                check(token > 0L)

                // Execute successfully
                val response = CompletableDeferred<String>()
                worker.executeEngine(token, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratch,
                    object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { response.complete(resultJson) }
                        override fun onFailure(errorCode: Int, errorMessage: String) {}
                    })
                withTimeout(10_000) { response.await() }

                // Host decides to reject this result (e.g. malformed or protocol violation)
                worker.abortOperation(token)
                withTimeout(15_000) { death.await() }
                assertFalse(worker.asBinder().isBinderAlive)
            }
            p
        }

        assertEquals("Host process must survive worker rejection", hostPid, Process.myPid())

        // Next binding uses a fresh PID and benign operation succeeds
        withWorker { fresh ->
            val freshPid = pid(fresh)
            assertNotEquals("Next binding must obtain a new PID", oldPid, freshPid)
        }
    }

    // G. No cross-request kill after success
    @Test fun noCrossRequestKillAfterSuccess(): Unit = runBlocking {
        withWorker { worker ->
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratchA ->
                OperationScratchBroker(context).use { scratchB ->
                    val tokenA = worker.beginOperation(scratchA)
                    check(tokenA > 0L)

                    val responseA = CompletableDeferred<String>()
                    worker.executeEngine(tokenA, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratchA,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { responseA.complete(resultJson) }
                            override fun onFailure(errorCode: Int, errorMessage: String) {}
                        })
                    withTimeout(10_000) { responseA.await() }

                    // Concurrent request B arrives while Host is validating A
                    val tokenB = worker.beginOperation(scratchB)
                    assertEquals("B must receive BUSY (0L)", 0L, tokenB)

                    // B sends no request, holds no token, and calls no abort.
                    // Host completes validation and accepts A:
                    assertTrue(worker.completeOperation(tokenA))
                    assertFalse("Worker must remain alive", death.isCompleted)
                }
            }
        }
    }

    // H. Cancellation after acceptance is stale
    @Test fun cancellationAfterAcceptanceIsStale(): Unit = runBlocking {
        withWorker { worker ->
            OperationScratchBroker(context).use { scratchA ->
                OperationScratchBroker(context).use { scratchB ->
                    // 1. Admit A
                    val tokenA = worker.beginOperation(scratchA)
                    check(tokenA > 0L)

                    // 2. Execute A to completion -> transitions to AWAITING_HOST_ACCEPT
                    val responseA = CompletableDeferred<String>()
                    worker.executeEngine(tokenA, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratchA,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { responseA.complete(resultJson) }
                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                responseA.completeExceptionally(IllegalStateException(errorMessage))
                            }
                        })
                    withTimeout(10_000) { responseA.await() }

                    // 3. Complete A
                    assertTrue("completeOperation(tokenA) must succeed from AWAITING_HOST_ACCEPT", worker.completeOperation(tokenA))

                    // 4. Admit B
                    val tokenB = worker.beginOperation(scratchB)
                    assertTrue("tokenB admitted after A completed", tokenB > 0L)

                    // 5. Delayed cancellation for operation A arrives
                    worker.abortOperation(tokenA)

                    // 6. Operation B must remain alive!
                    assertTrue("Operation B must NOT be killed by stale cancellation from A", worker.asBinder().isBinderAlive)

                    // 7. Operation B executes cleanly and completes
                    val responseB = CompletableDeferred<String>()
                    worker.executeEngine(tokenB, "DEBUG_IDENTITY", null, null, "{}", null, "", 0, scratchB,
                        object : IPdfJailStringCallback.Stub() {
                            override fun onSuccess(resultJson: String) { responseB.complete(resultJson) }
                            override fun onFailure(errorCode: Int, errorMessage: String) {
                                responseB.completeExceptionally(IllegalStateException(errorMessage))
                            }
                        })
                    withTimeout(10_000) { responseB.await() }
                    assertTrue(worker.completeOperation(tokenB))
                }
            }
        }
    }

    // K. Adversarial early success rejected by Host and terminates worker
    @Test fun adversarialEarlySuccessRejectedAndTerminatesWorker(): Unit = runBlocking {
        val hostPid = Process.myPid()
        withWorker { worker ->
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratchA ->
                val tokenA = worker.beginOperation(scratchA)
                assertTrue("tokenA admitted", tokenA > 0L)

                val earlySuccessReceived = CompletableDeferred<String>()
                worker.executeEngine(tokenA, "DEBUG_EARLY_SUCCESS", null, null, "{}", null, "", 0, scratchA,
                    object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { earlySuccessReceived.complete(resultJson) }
                        override fun onFailure(errorCode: Int, errorMessage: String) {
                            earlySuccessReceived.completeExceptionally(RuntimeException(errorMessage))
                        }
                    })

                // 1. Rogue worker emitted success while still in RUNNING state
                val result = withTimeout(10_000) { earlySuccessReceived.await() }
                assertEquals("{\"success\":true}", result)

                // 2. Host tries to completeOperation(tokenA) -> MUST FAIL because state is still RUNNING!
                assertFalse("completeOperation must return false because worker is still in RUNNING state", worker.completeOperation(tokenA))

                // 3. Concurrent B must still receive BUSY (gate was NOT released!)
                OperationScratchBroker(context).use { scratchB ->
                    val tokenB = worker.beginOperation(scratchB)
                    assertEquals("B must receive BUSY because A still occupies the gate", 0L, tokenB)
                }

                // 4. Host aborts suspect operation A
                worker.abortOperation(tokenA)
                withTimeout(15_000) { death.await() }
                assertFalse(worker.asBinder().isBinderAlive)
            }
        }
        assertEquals("Host process must survive rogue worker kill", hostPid, Process.myPid())
    }

    @Test fun gatewayRejectsEarlySuccessAndLeavesDestinationUntouched(): Unit = runBlocking {
        val target = File.createTempFile("early_success_", ".bin", context.cacheDir).apply { writeText("original destination") }
        try {
            val result = runCatching {
                PdfGateway.executeEngine(context, "DEBUG_EARLY_SUCCESS", null, Uri.fromFile(target), "{}")
            }
            assertTrue("Gateway must fail closed on early success rejection", result.isFailure)
            assertEquals("Destination must remain untouched", "original destination", target.readText())
        } finally {
            target.delete()
        }
    }

    // I. Fatal worker Throwable
    @Test fun fatalWorkerThrowableKillsProcessAndNeverReleasesGate(): Unit = runBlocking {
        val hostPid = Process.myPid()
        val oldPid = withWorker { worker ->
            val p = pid(worker)
            val death = CompletableDeferred<Unit>()
            worker.asBinder().linkToDeath({ death.complete(Unit) }, 0)
            OperationScratchBroker(context).use { scratch ->
                val token = worker.beginOperation(scratch)
                check(token > 0L)

                worker.executeEngine(token, "DEBUG_THROW_FATAL", null, null, "{}", null, "", 0, scratch,
                    object : IPdfJailStringCallback.Stub() {
                        override fun onSuccess(resultJson: String) { fail("Fatal throwable should not succeed") }
                        override fun onFailure(errorCode: Int, errorMessage: String) {}
                    })

                withTimeout(15_000) { death.await() }
                assertFalse("Worker process must die after fatal Throwable", worker.asBinder().isBinderAlive)
            }
            p
        }

        assertEquals("Host process must survive worker fatal error", hostPid, Process.myPid())

        // Fresh worker binding recovers
        withWorker { fresh ->
            val freshPid = pid(fresh)
            assertNotEquals("Next binding must be a fresh process", oldPid, freshPid)
        }
    }

    // J. Host owner Binder death
    @Test fun hostOwnerBinderDeathTerminatesWorker(): Unit = runBlocking {
        withWorker { jail ->
            val jailDeath = CompletableDeferred<Unit>()
            jail.asBinder().linkToDeath({ jailDeath.complete(Unit) }, 0)

            withRenderer { renderer ->
                // Pass renderer's binder as the owner binder of the operation in jail
                val token = jail.beginOperation(renderer.asBinder())
                assertTrue("Operation must be admitted with remote owner binder", token > 0L)

                // Terminate the owner process (renderer)
                renderer.abortWorker()

                // Jail's death recipient on the owner binder must fire and kill jail
                withTimeout(15_000) { jailDeath.await() }
                assertFalse("Jail worker must terminate when owner binder dies", jail.asBinder().isBinderAlive)
            }
        }
    }
}
