package com.pdfchemy.desktop.jail

import java.io.InputStream
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val secret = System.getenv("ARKHAM_SECRET")
    val sessionId = System.getenv("ARKHAM_SESSION")
    
    if (secret.isNullOrBlank() || sessionId.isNullOrBlank()) {
        System.err.println("Worker missing cryptographic session material")
        exitProcess(1)
    }
    
    var currentJobId: String? = null
    var currentTempFile: java.io.File? = null
    var currentOutputStream: java.io.OutputStream? = null
    var currentTotalSize = 0L
    var currentSequence = 0
    var bytesReceived = 0L

    try {
        while (true) {
            var handledMessage = false
            try {
                JailIpc.readMessage(System.`in`) { headerJson, payloadLength, boundedStream ->
                    handledMessage = true
                    val request = JailIpc.gson.fromJson(headerJson, JailRequest::class.java)
                    
                    val payloadHash = if (payloadLength > 0 && request.payloadHash.isNotEmpty()) request.payloadHash else ""
                    val isValid = JailCrypto.verifySignature(
                        secretBase64 = secret,
                        sessionId = sessionId,
                        jobId = request.jobId,
                        identity = "host",
                        direction = "req",
                        messageType = request.type,
                        sequence = request.sequence,
                        totalSize = request.totalSize,
                        payloadLength = payloadLength,
                        payloadHash = payloadHash,
                        nonce = request.nonce,
                        operation = request.operation,
                        status = "",
                        config = request.config,
                        expectedSignature = request.signature
                    )
                    if (!isValid) throw SecurityException("Invalid IPC signature from host!")

                    when (request.type) {
                        "START" -> {
                            if (currentJobId != null) throw SecurityException("New START received for active job")
                            if (request.totalSize > JailIpc.MAX_INPUT_DOCUMENT_SIZE) throw SecurityException("Declared size exceeds MAX_INPUT_DOCUMENT_SIZE")
                            currentJobId = request.jobId
                            currentTotalSize = request.totalSize
                            currentSequence = 0
                            bytesReceived = 0L
                            val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "pdfchemy_jail").apply { mkdirs() }
                            currentTempFile = java.io.File(tempDir, "jail_in_${java.util.UUID.randomUUID()}.pdf")
                            currentOutputStream = currentTempFile!!.outputStream()
                        }
                        "CHUNK" -> {
                            if (request.jobId != currentJobId) throw SecurityException("CHUNK jobId mismatch")
                            if (request.sequence != currentSequence) throw SecurityException("CHUNK sequence mismatch: expected $currentSequence, got ${request.sequence}")
                            
                            val chunkBytes = boundedStream.readBytes()
                            if (chunkBytes.size.toLong() != payloadLength) throw SecurityException("Truncated chunk")
                            val computedHash = JailCrypto.computePayloadHash(chunkBytes)
                            if (computedHash != request.payloadHash) throw SecurityException("Payload hash mismatch")
                            
                            bytesReceived += payloadLength
                            if (bytesReceived > currentTotalSize) throw SecurityException("Received bytes exceeded declared total size")
                            if (bytesReceived > JailIpc.MAX_INPUT_DOCUMENT_SIZE) throw SecurityException("Received bytes exceeded MAX_INPUT_DOCUMENT_SIZE")
                            
                            currentOutputStream?.write(chunkBytes)
                            currentSequence++
                        }
                        "END" -> {
                            if (request.jobId != currentJobId) throw SecurityException("END jobId mismatch")
                            if (bytesReceived != currentTotalSize) throw SecurityException("END size mismatch: declared $currentTotalSize, received $bytesReceived")
                            
                            currentOutputStream?.close()
                            handleAssembledRequest(request, currentTempFile, secret, sessionId)
                            
                            currentTempFile?.delete()
                            currentJobId = null
                            currentTempFile = null
                            currentOutputStream = null
                        }
                        "SINGLE" -> {
                            val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "pdfchemy_jail").apply { mkdirs() }
                            val payloadFile = java.io.File(tempDir, "jail_in_${java.util.UUID.randomUUID()}.pdf")
                            if (payloadLength > 0) {
                                val chunkBytes = boundedStream.readBytes()
                                if (chunkBytes.size.toLong() != payloadLength) throw SecurityException("Truncated SINGLE payload")
                                val computedHash = JailCrypto.computePayloadHash(chunkBytes)
                                if (computedHash != request.payloadHash) throw SecurityException("Payload hash mismatch")
                                payloadFile.writeBytes(chunkBytes)
                            }
                            handleAssembledRequest(request, if (payloadLength > 0) payloadFile else null, secret, sessionId)
                            payloadFile.delete()
                        }
                        else -> throw SecurityException("Unknown message type: ${request.type}")
                    }
                }
            } catch (e: java.io.EOFException) {
                break
            } catch (e: Exception) {
                if (!handledMessage) {
                    System.err.println("Failed to read IPC message: ${e.message}")
                    exitProcess(1)
                } else {
                    System.err.println("Error processing request: ${e.message}")
                    e.printStackTrace(System.err)
                    
                    val errResp = JailResponse(status = "ERROR", errorMessage = e.message ?: "Unknown error")
                    errResp.sendChunked(System.out, null, null, secret, sessionId)
                }
                break
            }
        }
        exitProcess(0)
    } catch (e: Exception) {
        System.err.println("Worker crashed: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(4)
    }
}

private fun handleAssembledRequest(request: JailRequest, payloadFile: java.io.File?, secret: String, sessionId: String) {
    val operation = request.operation
    try {
        if (operation == "PREPARE_SIGN" || operation == "APPLY_SIGNATURE") {
            // These aren't in the schema, keep them as TODOs for Phase 9
        } else {
            val handled = PdfJailRouter.routeRequest(operation, request, payloadFile, System.out, secret, sessionId)
            if (!handled) {
                val errResp = JailResponse(status = "ERROR", errorMessage = "Unknown operation: $operation")
                errResp.sendChunked(System.out, null, null, secret, sessionId)
                exitProcess(3)
            }
        }
        System.out.flush()
    } catch (e: Exception) {
        System.err.println("Operation failed: ${e.message}")
        e.printStackTrace(System.err)
        val errResp = JailResponse(status = "ERROR", errorMessage = e.message ?: "Operation failed")
        errResp.sendChunked(System.out, null, null, secret, sessionId)
    } finally {
        exitProcess(0)
    }
}
