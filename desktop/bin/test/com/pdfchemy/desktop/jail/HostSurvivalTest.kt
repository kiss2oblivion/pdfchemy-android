package com.pdfchemy.desktop.jail

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HostSurvivalTest {

    private val originalMainClass = DesktopJailManager.workerMainClass
    private val originalArgs = DesktopJailManager.workerArgs

    @Before
    fun setup() {
        DesktopJailManager.workerMainClass = "com.pdfchemy.desktop.jail.AdversarialEscapeWorkerKt"
    }

    @After
    fun teardown() {
        DesktopJailManager.workerMainClass = originalMainClass
        DesktopJailManager.workerArgs = originalArgs
    }

    @Test
    fun `test worker crash does not hang host`() {
        DesktopJailManager.workerArgs = listOf("crashMidStream")
        
        runBlocking {
            try {
                DesktopJailManager.executeWithResult(
                    operation = "escape"
                )
                fail("Expected SecurityException or IOException when worker crashes mid-stream")
            } catch (e: Exception) {
                // Expected an exception because the stream abruptly closed or framing failed.
                assertTrue("Exception should indicate stream error or broken pipe: ${e.message}", 
                    e is SecurityException || e is IOException || e is RuntimeException || e.message?.contains("Stream closed") == true || e.message?.contains("Unexpected end of stream") == true || e.message?.contains("Pipe broken") == true || e.message?.contains("Worker died") == true)
            }
        }
    }

    @Test
    fun `test worker hang triggers timeout and does not hang host`() {
        DesktopJailManager.workerArgs = listOf("hangIndefinitely")
        
        // This test will take up to the default 120s timeout, but will successfully prove the host doesn't hang indefinitely.
        runBlocking {
            try {
                DesktopJailManager.executeWithResult(
                    operation = "escape"
                )
                fail("Expected SecurityException or TimeoutException when worker hangs")
            } catch (e: Exception) {
                // The manager should throw a SecurityException or timeout exception when killing the worker.
                assertTrue("Exception should indicate timeout or cancellation: ${e.message}", 
                    e is SecurityException || e is RuntimeException || e.message?.contains("timeout", ignoreCase = true) == true || e.message?.contains("timed out", ignoreCase = true) == true)
            }
        }
    }
}
