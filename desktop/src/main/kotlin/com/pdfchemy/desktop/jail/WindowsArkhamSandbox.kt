package com.pdfchemy.desktop.jail

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.nio.file.Path
import java.nio.file.Paths

class WindowsArkhamSandbox(private val launcherExePath: Path) : ArkhamSandbox {

    private var process: Process? = null

    override fun launch(worker: VerifiedWorker): SandboxedProcess {
        val launcherArgs = mutableListOf<String>()
        launcherArgs.add(launcherExePath.toAbsolutePath().toString())
        launcherArgs.add(worker.verifiedJarHash)
        launcherArgs.add(worker.maxMemoryBytes.toString())
        launcherArgs.add(worker.maxCpuPercentage.toString())
        launcherArgs.add(worker.verifiedJarPath)
        
        // The JVM and its arguments
        launcherArgs.addAll(worker.toCommandList())

        println("LAUNCHER ARGS: $launcherArgs")

        // Grant LPAC access to necessary files before launch
        val jreDir = java.io.File(worker.jvmPath).parentFile.parentFile
        
        // Copy the global PDFBox font cache if it exists, to avoid rebuilding it in the sandbox
        val globalFontCache = java.io.File(System.getProperty("user.home"), ".pdfbox.cache")
        val localFontCache = java.io.File(worker.workingDirectory, ".pdfbox.cache")
        if (globalFontCache.exists()) {
            try {
                globalFontCache.copyTo(localFontCache, overwrite = true)
            } catch (e: Exception) {
                // Ignore errors, PDFBox will just rebuild it slowly
            }
        }
        
        fun runIcacls(vararg args: String) {
            val pb = ProcessBuilder(*args)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            if (pb.waitFor() != 0) {
                throw SecurityException("Failed to grant filesystem ACLs via icacls: ${args.joinToString(" ")}")
            }
        }
        
        // Retrieve exact AppContainer SID for precise ACL grants
        val sidPb = ProcessBuilder(launcherExePath.toAbsolutePath().toString(), "--get-sid").start()
        val sidStr = sidPb.inputStream.bufferedReader().use { it.readText().trim() }
        if (sidPb.waitFor() != 0 || sidStr.isEmpty()) {
            throw SecurityException("Failed to retrieve AppContainer SID from launcher")
        }
        val sidGrant = "*$sidStr"
        
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val icaclsPath = "$systemRoot\\System32\\icacls.exe"
        runIcacls(icaclsPath, jreDir.absolutePath, "/grant", "$sidGrant:(OI)(CI)(RX)")
        runIcacls(icaclsPath, worker.classPath, "/grant", "$sidGrant:(RX)")
        runIcacls(icaclsPath, worker.workingDirectory.absolutePath, "/grant", "$sidGrant:(OI)(CI)(M)")

        println("DEBUG: launcherArgs = $launcherArgs")

        val pb = ProcessBuilder(launcherArgs)
        
        // Explicit allowlist of environment variables to prevent host information leakage.
        // We only pass variables strictly required for JVM execution and OS interaction.
        val hostEnv = System.getenv()
        pb.environment().clear()
        
        val allowlist = listOf(
            "SystemRoot",             // Required by JVM/Windows to locate essential system DLLs (e.g., ntdll.dll)
            "SystemDrive",            // Required for basic Windows path resolution
            "PATH",                   // Required by JVM to find native libraries dynamically
            "ComSpec",                // Standard shell variable often expected by runtime
            "OS",                     // OS identifier
            "windir",                 // Legacy Windows directory mapping
            "PROCESSOR_ARCHITECTURE", // Useful for JIT/JVM internals
            "PROCESSOR_IDENTIFIER",   // Useful for JIT/JVM internals
            "PROCESSOR_LEVEL",        // Useful for JIT/JVM internals
            "PROCESSOR_REVISION",     // Useful for JIT/JVM internals
            "NUMBER_OF_PROCESSORS"    // Used by JVM to set default thread pools
        )
        
        hostEnv.forEach { (key, value) ->
            if (allowlist.any { it.equals(key, ignoreCase = true) }) {
                pb.environment()[key] = value
            }
        }
        
        // Override temp directories and user profiles to keep them contained within the sandbox
        pb.environment()["TEMP"] = worker.workingDirectory.absolutePath
        pb.environment()["TMP"] = worker.workingDirectory.absolutePath
        pb.environment()["USERPROFILE"] = worker.workingDirectory.absolutePath
        pb.environment()["APPDATA"] = worker.workingDirectory.absolutePath
        pb.environment()["LOCALAPPDATA"] = worker.workingDirectory.absolutePath
        pb.environment()["HOMEPATH"] = worker.workingDirectory.absolutePath
        
        // Add Arkham-specific variables (ARKHAM_SECRET, ARKHAM_SESSION)
        pb.environment().putAll(worker.environment)

        pb.directory(worker.workingDirectory)

        try {
            val startedProcess = pb.start()
            this.process = startedProcess
            
            return object : SandboxedProcess {
                override val inputStream: InputStream
                    get() = startedProcess.inputStream
                override val outputStream: OutputStream
                    get() = startedProcess.outputStream
                override val errorStream: InputStream
                    get() = startedProcess.errorStream
                override val pid: Long
                    get() = startedProcess.toHandle().children().findFirst().orElseThrow { SecurityException("Missing worker PID") }.pid()

                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    return startedProcess.waitFor(timeout, unit)
                }

                override fun exitValue(): Int {
                    return startedProcess.exitValue()
                }

                override val isAlive: Boolean
                    get() = startedProcess.isAlive
            }
        } catch (e: Exception) {
            throw SecurityException("Failed to launch Windows Arkham Sandbox via native launcher", e)
        }
    }

    override fun terminate() {
        // The native launcher puts the process in a Job Object with KILL_ON_JOB_CLOSE.
        // Destroying the launcher process closes its handle to the Job Object, 
        // which instantly kills the worker process tree.
        process?.destroyForcibly()
    }

    override fun inspect(): SandboxStatus {
        val p = process ?: return SandboxStatus.CRASHED
        if (p.isAlive) return SandboxStatus.RUNNING
        
        val exitCode = p.exitValue()
        return if (exitCode == 0) SandboxStatus.TERMINATED_CLEANLY else SandboxStatus.KILLED_BY_HOST
    }

    override fun getCapabilitySnapshot(): SandboxCapabilitySnapshot {
        // Hash the launcher executable to verify its integrity
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val launcherHash = digest.digest(java.nio.file.Files.readAllBytes(launcherExePath)).joinToString("") { "%02x".format(it) }
        
        // Find the worker PID (it is a direct child of the launcher)
        val p = process ?: throw IllegalStateException("Process not launched")
        val launcherPid = p.pid()
        
        // Read worker PID from stderr
        // Read worker PID from stderr byte-by-byte to avoid buffering and stealing subsequent error output
        val errorStream = p.errorStream
        var workerPid: Long = -1
        val startTime = System.currentTimeMillis()
        val lineBuffer = StringBuilder()
        
        while (System.currentTimeMillis() - startTime < 30000) {
            if (errorStream.available() > 0) {
                val b = errorStream.read()
                if (b == -1) break
                val c = b.toChar()
                if (c == '\n') {
                    val line = lineBuffer.toString().trim()
                    println("LAUNCHER STDERR: $line")
                    if (line.startsWith("WORKER_PID:")) {
                        workerPid = line.substringAfter("WORKER_PID:").trim().toLong()
                        break
                    }
                    lineBuffer.clear()
                } else if (c != '\r') {
                    lineBuffer.append(c)
                }
            } else {
                Thread.sleep(50)
            }
        }
        
        // (Stderr is subsequently consumed by DesktopJailManager's StderrDrainer)
        
        if (workerPid == -1L) {
            throw SecurityException("Failed to locate worker child process from stderr")
        }
        println("DEBUG: Launcher PID = $launcherPid, Worker PID = $workerPid")

        // Inspect the worker via the native launcher
        val inspectorPb = ProcessBuilder(launcherExePath.toAbsolutePath().toString(), "--inspect", workerPid.toString())
        val inspectorProc = inspectorPb.start()
        val jsonOutput = inspectorProc.inputStream.bufferedReader().use { it.readText() }
        inspectorProc.waitFor()

        if (inspectorProc.exitValue() != 0) {
            throw SecurityException("Failed to inspect worker OS capabilities. Output: $jsonOutput")
        }

        // Parse simple JSON (we don't use gson here to avoid exposing a dependency on the jail internals)
        // Format:
        // {
        //   "protocolVersion": 1,
        //   "pid": 23412,
        //   "processCreateTime": 123456789,
        //   "appContainerSid": "S-1-15-2-...",
        //   "integrityLevel": "Low",
        //   "isInJob": true
        // }
        fun extractString(key: String): String {
            val idx = jsonOutput.indexOf("\"$key\": \"")
            if (idx == -1) return "unknown"
            val start = idx + key.length + 5
            val end = jsonOutput.indexOf("\"", start)
            return jsonOutput.substring(start, end)
        }
        fun extractLong(key: String): Long {
            val idx = jsonOutput.indexOf("\"$key\": ")
            if (idx == -1) return 0L
            val start = idx + key.length + 4
            val end = jsonOutput.indexOf(",", start).takeIf { it != -1 } ?: jsonOutput.indexOf("\n", start)
            return jsonOutput.substring(start, end).trim().toLongOrNull() ?: 0L
        }
        fun extractBool(key: String): Boolean {
            val idx = jsonOutput.indexOf("\"$key\":")
            if (idx == -1) return false
            val start = idx + key.length + 4
            val end = jsonOutput.indexOf("\n", start)
            return jsonOutput.substring(start, end).trim() == "true"
        }

        val appContainerSid = extractString("appContainerSid")
        val integrityLevel = extractString("integrityLevel")
        val isInJob = extractBool("isInJob")
        val createTime = extractLong("processCreateTime")
        val workerJavaExe = java.io.File(System.getProperty("java.home"), "bin/java.exe").absolutePath

        return SandboxCapabilitySnapshot(
            osVersion = System.getProperty("os.version"),
            kernelVersion = System.getProperty("os.version"),
            sandboxBackendVersion = "LPAC_JobObject",
            containmentStrategy = "LPAC",
            networkDenied = true,
            
            launcherHash = launcherHash,
            workerHash = "",
            
            bwrapHash = null,
            bwrapVersion = null,
            sandboxPolicyHash = null,
            filesystemManifestHash = null,
            resourcePolicyHash = null,
            namespaceInodes = null,
            cgroupIdentity = null,
            
            tokenAppContainerIdentity = appContainerSid,
            capabilitySetHash = "INTEGRITY:$integrityLevel",
            jobIdentity = if (isInJob) "Assigned" else "Unassigned",
            creationTime = createTime
        )
    }
}
