package com.pdfchemy.desktop.jail

import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class ConfusedDeputyTest {

    private val originalMainClass = DesktopJailManager.workerMainClass
    private val originalArgs = DesktopJailManager.workerArgs
    private val originalTimeout = DesktopJailManager.TIMEOUT_SECONDS

    @Before
    fun setup() {
        DesktopJailManager.workerMainClass = "com.pdfchemy.desktop.jail.AdversarialEscapeWorkerKt"
        DesktopJailManager.TIMEOUT_SECONDS = 5L // Shorten timeout for tests
    }

    @After
    fun teardown() {
        DesktopJailManager.workerMainClass = originalMainClass
        DesktopJailManager.workerArgs = originalArgs
        DesktopJailManager.TIMEOUT_SECONDS = originalTimeout
    }

    @Test
    fun `testIpcIdentityConfusion`() {
        DesktopJailManager.workerArgs = listOf("testIpcIdentityConfusion")
        
        runBlocking {
            try {
                DesktopJailManager.executeWithResult(
                    operation = "escape"
                )
                fail("Expected SecurityException due to invalid IPC signature/jobId")
            } catch (e: Exception) {
                assertTrue("Exception should be SecurityException: ${e.message}", e is SecurityException)
                assertTrue("Exception should mention invalid signature or job id", e.message?.contains("signature", ignoreCase = true) == true || e.message?.contains("job", ignoreCase = true) == true)
            }
        }
    }

    @Test
    fun `testCancellationRaces`() {
        DesktopJailManager.workerArgs = listOf("slowResponse")
        
        runBlocking {
            val job = launch {
                try {
                    DesktopJailManager.executeWithResult(
                        operation = "escape"
                    )
                } catch (e: CancellationException) {
                    // Expected
                } catch (e: Exception) {
                    fail("Should have been cancelled")
                }
            }
            
            // Wait briefly to let it establish connection and receive START
            delay(1000)
            
            // Cancel mid-stream
            job.cancelAndJoin()
            
            // Wait briefly to ensure the manager killed the worker
            delay(500)
            
            // Verify that we can execute another valid request (even a failing one) without the host hanging
            // This proves the session and internal streams were properly terminated during cancellation.
            DesktopJailManager.workerArgs = listOf("testEnvironmentPoisoning")
            try {
                DesktopJailManager.executeWithResult(
                    operation = "escape"
                )
            } catch (e: Exception) {
                // We expect it to fail, but it shouldn't hang!
            }
        }
    }

    @Test
    fun `testEnvironmentPoisoning`() {
        DesktopJailManager.workerArgs = listOf("testEnvironmentPoisoning")
        
        runBlocking {
            try {
                DesktopJailManager.executeWithResult(
                    operation = "escape"
                )
                fail("Expected RuntimeException due to exit code 42")
            } catch (e: Exception) {
                val msg = e.message ?: ""
                assertTrue("Should be a RuntimeException", e is RuntimeException)
                
                // Assert no ANSI sequences
                assertFalse("Exception contains ANSI codes", msg.contains("\u001B"))
                
                // Assert no NULL bytes
                assertFalse("Exception contains NULL bytes", msg.contains("\u0000"))
                
                assertTrue("Exception contains sanitized text (was: $msg)", msg.contains("Error message"))
                assertTrue("Exception contains sanitized text2 (was: $msg)", msg.contains("Some valid textwith nulls"))
                assertTrue("Exception contains sanitized text3 (was: $msg)", msg.contains("Double returns"))
            }
        }
    }
}
