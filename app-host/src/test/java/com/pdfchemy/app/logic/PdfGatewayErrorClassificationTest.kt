package com.pdfchemy.app.logic

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PdfGatewayErrorClassificationTest {

    @Test
    fun verifyDistinctErrorCategoriesAreNotConflated() {
        val busyEx = PdfGateway.WorkerAdmissionBusyException("Jail 429: BUSY")
        val diedEx = PdfGateway.WorkerDiedException("Isolated worker disconnected: PdfJailService")
        val bindingEx = PdfGateway.WorkerBindingException("Failed to bind worker service: PdfJailService")
        val timeoutEx = PdfGateway.WorkerTimeoutException("Service binding timed out after 30s: PdfJailService")
        val failEx = PdfGateway.WorkerFailure(400, "Jail 400: Malformed input")

        // 1. Admission BUSY exception
        assertTrue(busyEx is IllegalStateException)
        assertTrue(busyEx.message.orEmpty().contains("BUSY"))

        // 2. Worker death / disconnection must NOT be disguised as BUSY
        assertTrue(diedEx is IllegalStateException)
        assertFalse("Worker death must not be labeled BUSY", diedEx.message.orEmpty().contains("BUSY"))
        assertTrue(diedEx.message.orEmpty().contains("disconnected"))

        // 3. Binding failures must NOT be disguised as BUSY
        assertTrue(bindingEx is IllegalStateException)
        assertFalse("Binding failure must not be labeled BUSY", bindingEx.message.orEmpty().contains("BUSY"))
        assertTrue(bindingEx.message.orEmpty().contains("Failed to bind"))

        // 4. Timeout must NOT be disguised as BUSY
        assertTrue(timeoutEx is IllegalStateException)
        assertFalse("Timeout must not be labeled BUSY", timeoutEx.message.orEmpty().contains("BUSY"))
        assertTrue(timeoutEx.message.orEmpty().contains("timed out"))

        // 5. Worker failure must retain error code and message
        assertEquals(400, failEx.errorCode)
        assertFalse("Generic worker failure must not be labeled BUSY", failEx.message.orEmpty().contains("BUSY"))
    }
}
