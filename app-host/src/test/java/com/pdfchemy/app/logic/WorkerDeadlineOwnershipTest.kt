package com.pdfchemy.app.logic

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerDeadlineOwnershipTest {
    @Test
    fun successfulResponseKeepsItsValue() = runTest {
        assertEquals("response", PdfGateway.awaitWorkerDeadline(100, "owned timeout") { "response" })
    }

    @Test
    fun ownDeadlineReportsWorkerTimeout() = runTest {
        val error = runCatching {
            PdfGateway.awaitWorkerDeadline(10, "owned timeout") {
                delay(20)
                "late"
            }
        }.exceptionOrNull()
        assertTrue(error is PdfGateway.WorkerTimeoutException)
        assertEquals("owned timeout", error!!.message)
    }

    @Test
    fun callerDeadlineRemainsCancellation() = runTest {
        val error = runCatching {
            withTimeout(5) {
                PdfGateway.awaitWorkerDeadline(100, "wrong timeout") {
                    delay(20)
                    "late"
                }
            }
        }.exceptionOrNull()
        assertTrue("Caller timeout was converted to a worker error", error is TimeoutCancellationException)
    }

    @Test
    fun nestedDeadlineRemainsCancellation() = runTest {
        val error = runCatching {
            PdfGateway.awaitWorkerDeadline(100, "wrong timeout") {
                withTimeout(5) {
                    delay(20)
                    "late"
                }
            }
        }.exceptionOrNull()
        assertTrue("Nested timeout was converted to a worker error", error is TimeoutCancellationException)
    }
}
