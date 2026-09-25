package com.pdfchemy.desktop.jail

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ArkhamEscapeTest {

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
    fun `test worker cannot open external network sockets`() {
        DesktopJailManager.workerArgs = listOf("testNetworkSocket")
        
        runBlocking {
            val (outputFile, payload) = DesktopJailManager.executeWithResult(
                operation = "escape"
            )
            outputFile.delete()

            assertNotNull(payload)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val result: Map<String, Any> = JailIpc.gson.fromJson(payload, type)

            // The attack should FAIL. If success == true, then the worker successfully opened a socket!
            val success = result["success"] as Boolean
            assertFalse("Worker successfully opened an external network socket!", success)
            
            // Expected: SecurityException or java.net.SocketException (Network is unreachable/permission denied)
            val error = result["error"] as String
            println("Expected connection failure: $error")
        }
    }

    @Test
    fun `test worker cannot write to parent directory`() {
        DesktopJailManager.workerArgs = listOf("testWriteHostFile")
        
        runBlocking {
            val (outputFile, payload) = DesktopJailManager.executeWithResult(
                operation = "escape"
            )
            outputFile.delete()

            assertNotNull(payload)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val result: Map<String, Any> = JailIpc.gson.fromJson(payload, type)

            // The attack should FAIL.
            val success = result["success"] as Boolean
            assertFalse("Worker successfully wrote a file outside its temp directory!", success)
        }
    }

    @Test
    fun `test worker subprocesses die with the sandbox`() {
        DesktopJailManager.workerArgs = listOf("spawnChildAndSleep")
        
        var childPid = -1L
        runBlocking {
            val (outputFile, payload) = DesktopJailManager.executeWithResult(
                operation = "escape"
            )
            outputFile.delete()

            assertNotNull(payload)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val result: Map<String, Any> = JailIpc.gson.fromJson(payload, type)

            // The attack should succeed in spawning the child.
            val success = result["success"] as Boolean
            assertTrue("Worker should be allowed to spawn subprocesses inside the sandbox", success)
            
            childPid = (result["childPid"] as Double).toLong()
            assertTrue("Child PID must be positive", childPid > 0)
        }
        
        // After executeWithResult completes, the sandbox is terminated.
        // We verify that the descendant child process also died.
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        if (isWindows && childPid > 0) {
            val pb = ProcessBuilder("tasklist", "/FI", "PID eq $childPid", "/FO", "CSV", "/NH")
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            
            assertFalse("Child process $childPid must be dead after sandbox terminates, ensuring no escape from Job Object boundary", out.contains(childPid.toString()))
        }
    }

    @Test
    fun `test capability denial - environment and properties`() {
        // Set a dummy secret in the host to verify it is NOT leaked
        System.setProperty("arkham.secret.test", "SENSITIVE_DATA")
        
        DesktopJailManager.workerArgs = listOf("testCapabilities")
        
        runBlocking {
            val (outputFile, payload) = DesktopJailManager.executeWithResult(
                operation = "escape"
            )
            outputFile.delete()

            System.clearProperty("arkham.secret.test")
            
            assertNotNull(payload)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val result: Map<String, Any> = JailIpc.gson.fromJson(payload, type)
            
            val success = result["success"] as Boolean
            assertTrue("Worker failed to gather capabilities", success)

            @Suppress("UNCHECKED_CAST")
            val envKeys = (result["envKeys"] as? List<String>) ?: emptyList()
            
            // Assert no sensitive vars are leaked
            assertFalse("Should not leak user secrets", envKeys.contains("AWS_ACCESS_KEY_ID"))
            assertFalse("Should not leak generic secret", envKeys.contains("SECRET"))
            
            val hostCwd = result["hostCwd"] as? String
            assertNotNull(hostCwd)
            
            // CWD should not be the parent project directory
            val currentHostDir = System.getProperty("user.dir")
            assertNotEquals("Worker CWD must not be the host CWD", currentHostDir, hostCwd)
            
            @Suppress("UNCHECKED_CAST")
            val systemProps = (result["systemProperties"] as? Map<String, String>) ?: emptyMap()
            assertFalse("Should not have access to arkham.secret.test", systemProps.containsKey("arkham.secret.test"))
            
            // JVM injects user.home and user.dir, so we assert they are SPOOFED to prevent host path discovery
            val hostUserHome = System.getProperty("user.home")
            val workerUserHome = systemProps["user.home"]
            assertNotEquals("Worker must not discover host user.home", hostUserHome, workerUserHome)
            assertNotEquals("Worker must not discover host user.dir", currentHostDir, systemProps["user.dir"])
            
            val openHandles = (result["openHandles"] as? Double)?.toInt() ?: -1
            if (openHandles != -1) {
                assertTrue("Should not inherit host handles beyond stdio ($openHandles)", openHandles <= 5)
            }
        }
    }
}
