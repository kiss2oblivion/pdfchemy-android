package com.pdfchemy.desktop.jail

import java.io.InputStream
import java.util.concurrent.TimeUnit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.MessageDigest

data class JailProcessInfo(
    val process: SandboxedProcess,
    val secret: String,
    val sessionId: String,
    val sandbox: ArkhamSandbox,
    val worker: VerifiedWorker
)

/**
 * Manages the lifecycle of the Desktop PDF Jail Subprocess.
 */
object DesktopJailManager {
    
    // VisibleForTesting
    var TIMEOUT_SECONDS = 120L // Allow more time for heavy rasterization/OCR jobs
    private const val IDLE_TIMEOUT_SECONDS = 15L
    
    // These 2 GB limits are explicit product/security policy values, not inherent protocol requirements.
    const val MAX_INPUT_DOCUMENT_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB
    const val MAX_OUTPUT_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB
    const val MAX_TOTAL_OUTPUT_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB
    const val MAX_OUTPUT_FILES = 100
    const val MAX_STDERR_SIZE = 16 * 1024

    
    // VisibleForTesting
    var workerMainClass: String = System.getProperty("pdfchemy.worker.class", "com.pdfchemy.desktop.jail.PdfJailWorkerKt")
    // VisibleForTesting
    var workerArgs: List<String> = emptyList()


    suspend fun execute(
        operation: String,
        config: Map<String, String> = emptyMap(),
        sourceFile: File? = null,
        sandboxFiles: Map<String, File> = emptyMap()
    ): File = withContext(Dispatchers.IO) {
        val (file, _) = executeWithResult(operation, config, sourceFile, sandboxFiles)
        file
    }

    suspend fun executeWithResult(
        operation: String,
        config: Map<String, String> = emptyMap(),
        sourceFile: File? = null,
        sandboxFiles: Map<String, File> = emptyMap()
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        var responsePayload = ""
        val tempFiles = mutableListOf<File>()
        
        executeInteractive { session ->
            val updatedConfig = config.toMutableMap()
            for ((key, file) in sandboxFiles) {
                val destFile = File(session.worker!!.workingDirectory, "${session.sessionId}_${key}_${file.name}")
                file.copyTo(destFile, overwrite = true)
                updatedConfig[key] = destFile.absolutePath
            }
            
            val req = JailRequest(operation = operation, config = updatedConfig, nonce = java.util.UUID.randomUUID().toString())
            if (sourceFile != null) session.sendRequest(req, sourceFile) else session.sendRequest(req, null as ByteArray?)
            session.receiveResponse { response, payloadLength, boundedStream ->
                if (response.status == "SUCCESS") {
                    responsePayload = response.payload ?: ""
                    if (payloadLength > 0) {
                        val outputFile = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("jail_out_", ".pdf")
                        tempFiles.add(outputFile)
                        outputFile.outputStream().use { fileOut ->
                            boundedStream.copyTo(fileOut, bufferSize = 8192)
                        }
                    }
                } else if (response.status == "ERROR") {
                    throw RuntimeException("Jail Worker Error: ${response.errorMessage}")
                }
            }
        }
        Pair(tempFiles.firstOrNull() ?: com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("empty", ".pdf"), responsePayload)
    }

    suspend fun <T> executeInteractive(
        block: suspend (JailInteractiveSession) -> T
    ): T = withContext(Dispatchers.IO) {
        val (process, secret, sessionId, sandbox, worker) = startJailProcess()
        
        try {
            val stderrDrainer = StderrDrainer(process.errorStream, MAX_STDERR_SIZE)
            val stderrThread = Thread(stderrDrainer).apply { start() }

            val boundedProcOut = AggregateBoundedInputStream(process.inputStream, MAX_TOTAL_OUTPUT_SIZE)
            val session = JailInteractiveSession(process.outputStream, boundedProcOut, secret, sessionId, sandbox, worker)
            val isTimeout = java.util.concurrent.atomic.AtomicBoolean(false)
            val timeoutJob = launch {
                delay(TIMEOUT_SECONDS * 1000)
                isTimeout.set(true)
                sandbox.terminate()
                try { process.inputStream.close() } catch (e: Exception) {}
                try { process.errorStream.close() } catch (e: Exception) {}
            }

            val result = try {
                block(session)
            } catch (e: Exception) {
                if (isTimeout.get()) {
                    sandbox.terminate()
                    throw RuntimeException("Jail Worker timed out after $TIMEOUT_SECONDS seconds.")
                }
                // If the process died prematurely, we want to fall through and let the exit code logic handle it
                if (!process.isAlive && process.exitValue() != 0) {
                    // Ignore the EOFException and let the finally block close streams, 
                    // and the code below will throw the proper exit code and stderr.
                } else {
                    sandbox.terminate()
                    if (e is java.io.EOFException || e is java.io.IOException) {
                        throw RuntimeException("Jail Worker died prematurely without a clear exit code.", e)
                    }
                    throw e
                }
                null // Return null to fall through for the exit logic
            } finally {
                timeoutJob.cancel()
                try { process.outputStream.close() } catch (e: Exception) {}
                try { process.inputStream.close() } catch (e: Exception) {}
            }
            
            val finishedInTime = process.waitFor(5, TimeUnit.SECONDS) // should already be dead or finished
            if (!finishedInTime) {
                sandbox.terminate()
                throw RuntimeException("Jail Worker timed out after $TIMEOUT_SECONDS seconds.")
            }
            
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                stderrThread.join(1000)
                val rawError = stderrDrainer.builder.toString()
                val workerError = sanitizeStderr(rawError)
                throw RuntimeException("Jail Worker failed. Exit: $exitCode. stderr:\n$workerError")
            }
            
            if (result == null) {
                throw RuntimeException("Jail Worker failed but no exit code was recorded.")
            }
            
            return@withContext result
        } finally {
            sandbox.terminate() // Ensure it's dead
            try { worker.workingDirectory.deleteRecursively() } catch (e: Exception) {}
        }
    }

    private fun startJailProcess(): JailProcessInfo {
        val javaHome = System.getProperty("java.home")
        val javaBin = File(javaHome, "bin/" + (if (System.getProperty("os.name").startsWith("Win")) "javaw.exe" else "java"))
        
        if (!javaBin.exists()) {
            throw IllegalStateException("Cannot locate Java executable: ${javaBin.absolutePath}")
        }
        val sessionSecret = JailCrypto.generateSecret()
        val sessionId = java.util.UUID.randomUUID().toString()
        val jailDir = File(File(System.getProperty("user.home"), ".pdfchemy/jail"), "pdfchemy_jail_cwd_$sessionId").apply { mkdirs() }
        
        val workerJar = getWorkerJar()
        val jarPath = workerJar.absolutePath
        val jarHash = hashFile(workerJar)

        val env = mapOf(
            "ARKHAM_SECRET" to sessionSecret,
            "ARKHAM_SESSION" to sessionId
        )

        val testClasspath = System.getProperty("java.class.path")
        val effectiveClasspath = if (testClasspath != null && testClasspath.contains("gradle")) {
            testClasspath // We are in test environment, pass the full classpath
        } else {
            jarPath // Production, we only have the jarPath (or whatever Compose sets)
        }

        val verifiedWorker = VerifiedWorker(
            jvmPath = File(javaHome, "bin/" + (if (System.getProperty("os.name").startsWith("Win")) "java.exe" else "java")).absolutePath,
            verifiedJarPath = jarPath,
            classPath = effectiveClasspath,
            mainClass = workerMainClass,
            workerArgs = workerArgs,
            environment = env,
            workingDirectory = jailDir,
            maxMemoryBytes = 1024L * 1024L * 1024L, // 1GB
            maxCpuPercentage = 80, // 80%
            verifiedJarHash = jarHash
        )

        val sandbox = ArkhamSandboxFactory.create()
        val process = sandbox.launch(verifiedWorker)
        
        // Security Fingerprint Validation
        val snapshot = sandbox.getCapabilitySnapshot()
        if (System.getProperty("os.name").startsWith("Win")) {
            if (snapshot.tokenAppContainerIdentity == null || snapshot.tokenAppContainerIdentity == "unknown" || !snapshot.tokenAppContainerIdentity!!.startsWith("S-1-15-2-")) {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Windows (Not in AppContainer): $snapshot")
            }
            if (snapshot.jobIdentity != "Assigned") {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Windows (Not in Job Object): $snapshot")
            }
            if (snapshot.capabilitySetHash == null || !snapshot.capabilitySetHash!!.contains("Low")) {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Windows (Not Low Integrity): $snapshot")
            }
        } else {
            if (snapshot.capabilitySetHash == null || !snapshot.capabilitySetHash!!.contains("Seccomp: 2")) {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Linux (Seccomp not enforced): $snapshot")
            }
            if (!snapshot.networkDenied) {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Linux (Network not isolated): $snapshot")
            }
            if (snapshot.processMitigations == null || !snapshot.processMitigations!!.contains("mnt")) {
                sandbox.terminate()
                throw SecurityException("Security Fingerprint Validation Failed for Linux (Mount namespace not isolated): $snapshot")
            }
        }
        
        return JailProcessInfo(process, sessionSecret, sessionId, sandbox, verifiedWorker)
    }

    fun getWorkerJar(): File {
        var buildDir = File("build/libs")
        if (!buildDir.exists() || buildDir.listFiles()?.find { it.name.contains("desktop") && it.name.endsWith(".jar") } == null) {
            buildDir = File("desktop/build/libs")
        }
        val jarFile = buildDir.listFiles()?.find { it.name.contains("desktop") && it.name.endsWith(".jar") }
        
        if (jarFile == null || !jarFile.exists()) {
            throw IllegalStateException("Worker JAR not found in $buildDir. Please run gradle build.")
        }
        return jarFile
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = Files.readAllBytes(file.toPath())
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeStderr(input: String): String {
        // Strip ANSI CSI sequences
        val noAnsi = input.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
        // Strip non-printable control characters, but keep \n and \r
        val noControls = noAnsi.replace(Regex("[\\x00-\\x09\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
        // Normalize line breaks
        return noControls.replace(Regex("\r\n|\r"), "\n")
    }
}

class JailInteractiveSession(
    private val outStream: java.io.OutputStream,
    private val inStream: java.io.InputStream,
    private val secret: String,
    val sessionId: String,
    val sandbox: ArkhamSandbox? = null,
    val worker: VerifiedWorker? = null
) {
    fun sendRequest(request: JailRequest, payloadBytes: ByteArray?) {
        val payloadHash = JailCrypto.computePayloadHash(payloadBytes)
        val payloadLen = payloadBytes?.size?.toLong() ?: 0L
        val nonce = java.util.UUID.randomUUID().toString()
        val jobId = request.jobId.ifEmpty { java.util.UUID.randomUUID().toString() }
        
        val req = request.copy(
            type = "SINGLE", jobId = jobId, nonce = nonce, 
            payloadHash = payloadHash, totalSize = payloadLen
        )
        val signedReq = req.copy(
            signature = JailCrypto.computeSignature(
                secret, sessionId, jobId, "host", "req", "SINGLE", 0, payloadLen, payloadLen, payloadHash, nonce, request.operation, "", request.config
            )
        )
        val headerJson = JailIpc.gson.toJson(signedReq)
        JailIpc.writeMessageBytes(outStream, headerJson, payloadBytes)
    }

    fun sendRequest(request: JailRequest, sourceFile: File) {
        if (sourceFile.length() > JailIpc.MAX_IPC_FRAME_SIZE) {
            val jobId = java.util.UUID.randomUUID().toString()
            val totalSize = sourceFile.length()
            if (totalSize > DesktopJailManager.MAX_INPUT_DOCUMENT_SIZE) {
                throw SecurityException("File size exceeds MAX_INPUT_DOCUMENT_SIZE")
            }
            
            // START
            val startNonce = java.util.UUID.randomUUID().toString()
            val startReq = request.copy(
                type = "START", jobId = jobId, sequence = 0, totalSize = totalSize, nonce = startNonce
            )
            val startSigned = startReq.copy(
                signature = JailCrypto.computeSignature(
                    secret, sessionId, jobId, "host", "req", "START", 0, totalSize, 0L, "", startNonce, request.operation, "", request.config
                )
            )
            JailIpc.writeMessage(outStream, JailIpc.gson.toJson(startSigned), null, 0L)
            
            // CHUNKs
            var seq = 0
            sourceFile.inputStream().use { fileIn ->
                val buffer = ByteArray(JailIpc.MAX_IPC_FRAME_SIZE.toInt())
                while (true) {
                    var remainingToRead = buffer.size
                    var offset = 0
                    while (remainingToRead > 0) {
                        val read = fileIn.read(buffer, offset, remainingToRead)
                        if (read == -1) break
                        offset += read
                        remainingToRead -= read
                    }
                    if (offset == 0) break
                    
                    val chunkBytes = if (offset == buffer.size) buffer else buffer.copyOfRange(0, offset)
                    val payloadHash = JailCrypto.computePayloadHash(chunkBytes)
                    val chunkNonce = java.util.UUID.randomUUID().toString()
                    
                    val chunkReq = request.copy(
                        type = "CHUNK", jobId = jobId, sequence = seq, totalSize = totalSize, 
                        payloadHash = payloadHash, nonce = chunkNonce
                    )
                    val chunkSigned = chunkReq.copy(
                        signature = JailCrypto.computeSignature(
                            secret, sessionId, jobId, "host", "req", "CHUNK", seq, totalSize, chunkBytes.size.toLong(), payloadHash, chunkNonce, request.operation, "", request.config
                        )
                    )
                    JailIpc.writeMessageBytes(outStream, JailIpc.gson.toJson(chunkSigned), chunkBytes)
                    seq++
                }
            }
            
            // END
            val endNonce = java.util.UUID.randomUUID().toString()
            val endReq = request.copy(
                type = "END", jobId = jobId, sequence = seq, totalSize = totalSize, nonce = endNonce
            )
            val endSigned = endReq.copy(
                signature = JailCrypto.computeSignature(
                    secret, sessionId, jobId, "host", "req", "END", seq, totalSize, 0L, "", endNonce, request.operation, "", request.config
                )
            )
            JailIpc.writeMessage(outStream, JailIpc.gson.toJson(endSigned), null, 0L)
            
        } else {
            sendRequest(request, sourceFile.readBytes())
        }
    }

    fun receiveResponse(onPayloadReady: (response: JailResponse, payloadLength: Long, boundedStream: java.io.InputStream) -> Unit) {
        var currentJobId: String? = null
        var currentTempFile: File? = null
        var currentOutputStream: java.io.OutputStream? = null
        var currentTotalSize = 0L
        var currentSequence = 0
        var bytesReceived = 0L

        while (true) {
            var isDone = false
            JailIpc.readMessage(inStream) { headerJson, payloadLength, boundedStream ->
                val response = JailIpc.gson.fromJson(headerJson, JailResponse::class.java)
                
                val payloadHash = if (payloadLength > 0 && response.payloadHash.isNotEmpty()) response.payloadHash else ""
                val isValid = JailCrypto.verifySignature(
                    secretBase64 = secret,
                    sessionId = sessionId,
                    jobId = response.jobId,
                    identity = "worker",
                    direction = "res",
                    messageType = response.type,
                    sequence = response.sequence,
                    totalSize = response.totalSize,
                    payloadLength = payloadLength,
                    payloadHash = payloadHash,
                    nonce = response.nonce,
                    operation = "",
                    status = response.status,
                    config = response.config,
                    expectedSignature = response.signature
                )
                if (!isValid) throw SecurityException("Invalid IPC signature from worker in interactive session!")

                when (response.type) {
                    "START" -> {
                        if (currentJobId != null) throw SecurityException("New START received for active job")
                        if (response.totalSize > DesktopJailManager.MAX_OUTPUT_FILE_SIZE) throw SecurityException("Declared size exceeds MAX_OUTPUT_FILE_SIZE")
                        currentJobId = response.jobId
                        currentTotalSize = response.totalSize
                        currentSequence = 0
                        bytesReceived = 0L
                        currentTempFile = com.pdfchemy.desktop.engine.DesktopStaging.createTempFile("jail_out_", ".pdf")
                        currentOutputStream = currentTempFile!!.outputStream()
                    }
                    "CHUNK" -> {
                        if (response.jobId != currentJobId) throw SecurityException("CHUNK jobId mismatch")
                        if (response.sequence != currentSequence) throw SecurityException("CHUNK sequence mismatch: expected $currentSequence, got ${response.sequence}")
                        
                        val chunkBytes = boundedStream.readBytes()
                        if (chunkBytes.size.toLong() != payloadLength) throw SecurityException("Truncated chunk")
                        val computedHash = JailCrypto.computePayloadHash(chunkBytes)
                        if (computedHash != response.payloadHash) throw SecurityException("Payload hash mismatch")
                        
                        bytesReceived += payloadLength
                        if (bytesReceived > currentTotalSize) throw SecurityException("Received bytes exceeded declared total size")
                        if (bytesReceived > DesktopJailManager.MAX_OUTPUT_FILE_SIZE) throw SecurityException("Received bytes exceeded MAX_OUTPUT_FILE_SIZE")
                        
                        currentOutputStream?.write(chunkBytes)
                        currentSequence++
                    }
                    "END" -> {
                        if (response.jobId != currentJobId) throw SecurityException("END jobId mismatch")
                        if (bytesReceived != currentTotalSize) throw SecurityException("END size mismatch: declared $currentTotalSize, received $bytesReceived")
                        
                        currentOutputStream?.close()
                        currentTempFile?.inputStream()?.use { fileIn ->
                            onPayloadReady(response, currentTotalSize, fileIn)
                        }
                        currentTempFile?.delete() // Cleanup after callback is done with it
                        isDone = true
                    }
                    "SINGLE" -> {
                        if (payloadLength > 0) {
                            val chunkBytes = boundedStream.readBytes()
                            if (chunkBytes.size.toLong() != payloadLength) throw SecurityException("Truncated SINGLE payload")
                            val computedHash = JailCrypto.computePayloadHash(chunkBytes)
                            if (computedHash != response.payloadHash) throw SecurityException("Payload hash mismatch")
                            
                            java.io.ByteArrayInputStream(chunkBytes).use {
                                onPayloadReady(response, payloadLength, it)
                            }
                        } else {
                            onPayloadReady(response, 0L, boundedStream)
                        }
                        isDone = true
                    }
                    else -> throw SecurityException("Unknown message type: ${response.type}")
                }
            }
            if (isDone) break
        }
    }
}

class AggregateBoundedInputStream(private val wrapped: InputStream, private val limit: Long) : InputStream() {
    private var readCount = 0L
    private var lastReadTime = System.currentTimeMillis()
    private val idleTimeout = 15000L // 15s

    private fun checkTimeouts() {
        if (System.currentTimeMillis() - lastReadTime > idleTimeout) {
            throw java.util.concurrent.TimeoutException("Stream idle timeout exceeded")
        }
    }

    override fun read(): Int {
        checkTimeouts()
        val r = wrapped.read()
        if (r != -1) {
            lastReadTime = System.currentTimeMillis()
            readCount++
            if (readCount > limit) throw SecurityException("Stream exceeded aggregate limit of $limit bytes")
        }
        return r
    }
    
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        checkTimeouts()
        val r = wrapped.read(b, off, len)
        if (r > 0) {
            lastReadTime = System.currentTimeMillis()
            readCount += r
            if (readCount > limit) throw SecurityException("Stream exceeded aggregate limit of $limit bytes")
        }
        return r
    }
}

class StderrDrainer(private val stream: InputStream, private val limit: Int) : Runnable {
    val builder = java.lang.StringBuilder()
    var bytesRead = 0
    override fun run() {
        try {
            val reader = stream.bufferedReader()
            val buffer = CharArray(1024)
            var read: Int
            while (reader.read(buffer).also { read = it } != -1) {
                if (bytesRead < limit) {
                    val toAppend = Math.min(read, limit - bytesRead)
                    builder.append(buffer, 0, toAppend)
                    bytesRead += toAppend
                }
            }
        } catch (e: Exception) {}
    }
}
