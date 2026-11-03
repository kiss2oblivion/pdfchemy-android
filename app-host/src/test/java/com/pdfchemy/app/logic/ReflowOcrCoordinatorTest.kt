package com.pdfchemy.app.logic

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReflowOcrCoordinatorTest {
    private val uri = Uri.parse("content://fixture/source.pdf")
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    @After fun cleanup() { scope.cancel() }

    @Test fun failureAndEmptyTextHaveDistinctRetryableStates() {
        var answer = Result.failure<String>(IOException("worker unavailable"))
        val coordinator = ReflowOcrCoordinator { answer }
        coordinator.request(scope, uri)
        assertEquals(ReflowOcrState.Failed(uri), coordinator.state.value)
        answer = Result.success(""); coordinator.request(scope, uri)
        assertEquals(ReflowOcrState.Empty(uri), coordinator.state.value)
        answer = Result.success("recognized"); coordinator.request(scope, uri)
        assertEquals(ReflowOcrState.Ready(uri, "recognized"), coordinator.state.value)
        coordinator.close()
    }
    @Test fun replacedRequestCannotPublishLateTextOrFailure() = runBlocking<Unit> {
        for (failure in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val old = Uri.parse("content://fixture/old.pdf")
            val coordinator = ReflowOcrCoordinator { selected ->
                if (selected == old) withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    if (failure) Result.failure(IOException("obsolete failure")) else Result.success("obsolete")
                } else Result.success("latest")
            }
            coordinator.request(scope, old); entered.await()
            val previous = scope.coroutineContext[Job]!!.children.single()
            coordinator.request(scope, uri)
            release.complete(Unit); previous.join()
            assertEquals(ReflowOcrState.Ready(uri, "latest"), coordinator.state.value)
            coordinator.close()
        }
    }
    @Test fun closeRejectsLateUncooperativeResultAndIsIdempotent() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val coordinator = ReflowOcrCoordinator {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); Result.success("obsolete") }
        }
        coordinator.request(scope, uri); entered.await()
        val previous = scope.coroutineContext[Job]!!.children.single()
        coordinator.close(); coordinator.close(); release.complete(Unit); previous.join()
        assertEquals(ReflowOcrState.Idle, coordinator.state.value)
        coordinator.request(scope, uri)
        assertEquals(ReflowOcrState.Idle, coordinator.state.value)
    }
    @Test fun workerCancellationDoesNotBecomeAnErrorOrLeaveSpinnerRunning() {
        val coordinator = ReflowOcrCoordinator { throw CancellationException("cancelled") }
        coordinator.request(scope, uri)
        assertEquals(ReflowOcrState.Idle, coordinator.state.value)
        assertTrue(scope.isActive); coordinator.close()
    }
    @Test fun cancelledScopeNeverLeavesProcessingOrStartsExtraction() {
        var calls = 0
        val coordinator = ReflowOcrCoordinator { calls++; Result.success("unexpected") }
        scope.cancel(); coordinator.request(scope, uri)
        assertEquals(0, calls); assertEquals(ReflowOcrState.Idle, coordinator.state.value)
        coordinator.close()
    }
}
