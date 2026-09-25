package com.pdfchemy.desktop.jail

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

data class VerifiedWorker(
    val jvmPath: String,
    val verifiedJarPath: String,
    val classPath: String,
    val mainClass: String,
    val workerArgs: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
    val maxMemoryBytes: Long,
    val maxCpuPercentage: Int,
    val verifiedJarHash: String
) {
    fun toCommandList(): List<String> {
        return listOf(
            jvmPath, 
            "-Djava.awt.headless=true",
            "-Djava.io.tmpdir=${workingDirectory.absolutePath}",
            "-Duser.home=${workingDirectory.absolutePath}",
            "-Duser.dir=${workingDirectory.absolutePath}",
            "-Dpdfbox.fontcache=${workingDirectory.absolutePath}",
            "-XX:-UsePerfData",
            "-cp", 
            classPath, 
            mainClass
        ) + workerArgs
    }
}

data class SandboxCapabilitySnapshot(
    val osVersion: String,
    val kernelVersion: String,
    val sandboxBackendVersion: String,
    val containmentStrategy: String,
    val networkDenied: Boolean,
    
    // Core Identity
    val launcherHash: String,
    val workerHash: String,
    
    // Linux Specific
    val bwrapHash: String?,
    val bwrapVersion: String?,
    val sandboxPolicyHash: String?,
    val filesystemManifestHash: String?,
    val resourcePolicyHash: String?,
    val namespaceInodes: Map<String, String>?,
    val cgroupIdentity: String?,
    
    // Windows Specific
    val tokenAppContainerIdentity: String?,
    val capabilitySetHash: String?,
    val jobIdentity: String?,
    val creationTime: Long?
)

interface SandboxedProcess {
    val inputStream: InputStream
    val outputStream: OutputStream
    val errorStream: InputStream
    val pid: Long
    
    fun waitFor(timeout: Long, unit: TimeUnit): Boolean
    fun exitValue(): Int
    val isAlive: Boolean
}

enum class SandboxStatus {
    RUNNING, TERMINATED_CLEANLY, KILLED_BY_HOST, CRASHED
}

interface ArkhamSandbox {
    /** 
     * Launches the worker process atomically within the constrained OS environment.
     * The implementation (e.g. native launcher) MUST guarantee that either the process 
     * is returned already inside the required OS boundary, or the launch fails entirely 
     * without exposing the worker to the host.
     */
    @Throws(SecurityException::class)
    fun launch(worker: VerifiedWorker): SandboxedProcess
    
    /** 
     * Recursively terminates the entire process tree. 
     * E.g. Explicit TerminateJobObject on Windows, or PID namespace killing on Linux.
     */
    fun terminate()
    
    /** Inspects the sandbox state or exit reason. */
    fun inspect(): SandboxStatus
    
    /** Returns the capability fingerprint snapshot established at launch. */
    fun getCapabilitySnapshot(): SandboxCapabilitySnapshot
}

object ArkhamSandboxFactory {
    @Synchronized
    fun create(): ArkhamSandbox {
        val osName = System.getProperty("os.name").lowercase()
        val isWin = osName.contains("win")
        val isLinux = osName.contains("linux")
        
        if (!isWin && !isLinux) {
            throw SecurityException("Arkham OS isolation unavailable on OS: $osName; secure processing cannot start.")
        }
        
        val launcherName = if (isWin) "arkham-launcher.exe" else "arkham-launcher-linux"
        val jailDir = Paths.get(System.getProperty("user.home"), ".pdfchemy", "jail")
        if (!Files.exists(jailDir)) {
            Files.createDirectories(jailDir)
        }
        val launcherPath = jailDir.resolve(launcherName)
        
        // Always try to extract from classpath first
        val resourceStream = ArkhamSandboxFactory::class.java.getResourceAsStream("/jail/$launcherName")
        if (resourceStream != null) {
            resourceStream.use { input ->
                Files.copy(input, launcherPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            if (isLinux) {
                launcherPath.toFile().setExecutable(true, true)
            }
        } else if (!Files.exists(launcherPath)) {
            // Dev fallback if not built yet and not in JAR
            val userDir = System.getProperty("user.dir")
            val sourceLauncher = if (userDir.endsWith("desktop")) {
                Paths.get(userDir, "src", "main", "cpp", "build", "Release", launcherName).takeIf { Files.exists(it) }
                    ?: Paths.get(userDir, "src", "main", "cpp", "build", launcherName).takeIf { Files.exists(it) }
                    ?: Paths.get(userDir, "src", "main", "cpp", launcherName)
            } else {
                Paths.get(userDir, "desktop", "src", "main", "cpp", "build", "Release", launcherName).takeIf { Files.exists(it) }
                    ?: Paths.get(userDir, "desktop", "src", "main", "cpp", "build", launcherName).takeIf { Files.exists(it) }
                    ?: Paths.get(userDir, "desktop", "src", "main", "cpp", launcherName)
            }
            
            if (Files.exists(sourceLauncher)) {
                Files.copy(sourceLauncher, launcherPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                if (isLinux) {
                    launcherPath.toFile().setExecutable(true, true)
                }
            } else {
                throw SecurityException("Arkham OS isolation unavailable; native launcher not found in resources or source tree.")
            }
        }

        return when {
            isWin -> WindowsArkhamSandbox(launcherPath)
            isLinux -> LinuxArkhamSandbox("/usr/bin/bwrap", launcherPath)
            else -> throw SecurityException("Unreachable")
        }
    }
}
