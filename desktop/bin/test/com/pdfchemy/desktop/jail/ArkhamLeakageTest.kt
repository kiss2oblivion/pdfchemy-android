package com.pdfchemy.desktop.jail

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ArkhamLeakageTest {

    private val originalMainClass = DesktopJailManager.workerMainClass

    @Before
    fun setup() {
        DesktopJailManager.workerMainClass = "com.pdfchemy.desktop.jail.AdversarialLeakageWorkerKt"
    }

    @After
    fun teardown() {
        DesktopJailManager.workerMainClass = originalMainClass
    }

    @Test
    fun `test worker process environment does not leak host capabilities`() {
        // Plant sensitive host environment variables using reflection hack
        val plantedEnvVars = mapOf(
            "AWS_ACCESS_KEY_ID" to "AKIAIOSFODNN7EXAMPLE",
            "SECRET_API_TOKEN" to "super_secret_token_123",
            "HOST_USER_BANK_PASSWORD" to "hunter2"
        )
        
        try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(env) as MutableMap<String, String>
            map.putAll(plantedEnvVars)
        } catch (e: Exception) {
            println("Warning: Could not plant host env vars via reflection, skipping plant step")
        }

        runBlocking {
            val (outputFile, payload) = DesktopJailManager.executeWithResult(
                operation = "inspectEnvironment"
            )
            outputFile.delete()

            assertNotNull(payload)
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val result: Map<String, Any> = JailIpc.gson.fromJson(payload, type)

            val env = result["env"] as Map<String, String>
            val cwd = result["cwd"] as String
            val props = result["props"] as Map<String, String>

            // 1. Environment variables should NOT contain planted secrets
            plantedEnvVars.keys.forEach { key ->
                assertFalse("Worker inherited planted sensitive env var: $key", env.containsKey(key))
            }

            // 2. Environment variables should NOT contain host user profile paths (they must be spoofed or stripped)
            val restrictedEnvKeys = listOf("USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOME", "HOMEPATH")
            val hostProfile = System.getenv("USERPROFILE") ?: "C:\\Users\\Default"
            restrictedEnvKeys.forEach { key ->
                val workerValue = env[key]
                if (workerValue != null) {
                    // It should not be EXACTLY the host profile, and it should map to our sandbox temp dir
                    assertNotEquals("Worker inherited exact host user path env var: $key", hostProfile, workerValue)
                    assertTrue("Spoofed env var must map to sandbox temp dir: $key", workerValue.contains("pdfchemy", ignoreCase = true) || workerValue.contains("tmp", ignoreCase = true))
                }
            }

            // 3. Environment should only contain the explicit allowlist and ARKHAM variables
            val allowlist = listOf("SystemRoot", "SystemDrive", "PATH", "ComSpec", "OS", "windir", 
                                   "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER", "PROCESSOR_LEVEL", 
                                   "PROCESSOR_REVISION", "NUMBER_OF_PROCESSORS", "TEMP", "TMP",
                                   "USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEPATH")
            val leakedEnv = env.keys.filter { !allowlist.contains(it) && !it.startsWith("ARKHAM_") }
            assertTrue("Worker unexpectedly inherited system environment variables! $leakedEnv", leakedEnv.isEmpty())

            // 4. Working directory should be a temp directory, NOT the host project directory
            val hostCwd = File(".").absolutePath
            assertNotEquals("Worker process inherited host working directory!", hostCwd, cwd)
            assertTrue("Worker cwd is suspicious: $cwd", cwd.contains("tmp") || cwd.contains("Temp") || cwd.contains("pdfchemy"))

            // 5. Properties should not leak sensitive host info
            val hostUserHome = System.getProperty("user.home")
            val workerUserHome = props["user.home"]
            assertNotEquals("Worker must not discover host user.home", hostUserHome, workerUserHome)
            assertTrue("Worker user.home must be the sandbox temp dir", workerUserHome?.contains("pdfchemy") == true)

            val hostUserDir = System.getProperty("user.dir")
            val workerUserDir = props["user.dir"]
            assertNotEquals("Worker must not discover host user.dir", hostUserDir, workerUserDir)
            
            val workerTmpDir = props["java.io.tmpdir"]
            assertNotEquals("Worker must not discover host tmp dir", System.getProperty("java.io.tmpdir"), workerTmpDir)
        }
        
        // Cleanup planted vars
        try {
            val env = System.getenv()
            val field = env.javaClass.getDeclaredField("m")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val map = field.get(env) as MutableMap<String, String>
            plantedEnvVars.keys.forEach { map.remove(it) }
        } catch (e: Exception) {
            // Ignore
        }
    }
}
