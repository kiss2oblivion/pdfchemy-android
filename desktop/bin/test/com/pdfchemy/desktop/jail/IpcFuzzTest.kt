package com.pdfchemy.desktop.jail

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class IpcFuzzTest {

    @Test
    fun `test invalid signature rejected by interactive session`() {
        val secret = JailCrypto.generateSecret()
        val sessionId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        
        // Generate a forged response from the worker
        val fakeNonce = UUID.randomUUID().toString()
        val forgedResponse = JailResponse(
            type = "SINGLE",
            jobId = jobId,
            status = "SUCCESS",
            nonce = fakeNonce,
            signature = "invalid_signature_bytes"
        )
        
        val headerJson = JailIpc.gson.toJson(forgedResponse)
        
        val outStream = ByteArrayOutputStream()
        // Simulate reading the forged message from the worker
        val fakeInStream = ByteArrayOutputStream()
        JailIpc.writeMessageBytes(fakeInStream, headerJson, null)
        val inStream = ByteArrayInputStream(fakeInStream.toByteArray())
        
        val session = JailInteractiveSession(outStream, inStream, secret, sessionId)
        
        var threwSecurityException = false
        try {
            session.receiveResponse { _, _, _ -> 
                // Should not reach here
            }
        } catch (e: SecurityException) {
            threwSecurityException = true
        }
        
        assertTrue("Session must reject invalid signature", threwSecurityException)
    }

    @Test
    fun `test cross-session replay rejected`() {
        val secretA = JailCrypto.generateSecret()
        val sessionAId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        
        val secretB = JailCrypto.generateSecret()
        val sessionBId = UUID.randomUUID().toString()
        
        val nonce = UUID.randomUUID().toString()
        // Worker A signs the response correctly for Session A
        val validSigA = JailCrypto.computeSignature(secretA, sessionAId, jobId, "worker", "res", "SINGLE", 0, 0L, 0L, "", nonce, "", "SUCCESS", emptyMap())
        
        val responseA = JailResponse(
            type = "SINGLE",
            jobId = jobId,
            status = "SUCCESS",
            nonce = nonce,
            signature = validSigA
        )
        val headerJson = JailIpc.gson.toJson(responseA)
        
        val fakeInStream = ByteArrayOutputStream()
        JailIpc.writeMessageBytes(fakeInStream, headerJson, null)
        
        // Attacker replays Response A to Session B
        val inStreamB = ByteArrayInputStream(fakeInStream.toByteArray())
        val outStreamB = ByteArrayOutputStream()
        
        val sessionB = JailInteractiveSession(outStreamB, inStreamB, secretB, sessionBId)
        
        var threwSecurityException = false
        try {
            sessionB.receiveResponse { _, _, _ -> 
                // Should not reach here
            }
        } catch (e: SecurityException) {
            threwSecurityException = true
        }
        
        assertTrue("Session B must reject replay from Session A", threwSecurityException)
    }
    
    @Test
    fun `test host rejects forged requests`() {
        val secret = JailCrypto.generateSecret()
        val sessionId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        
        val nonce = UUID.randomUUID().toString()
        val validReqSig = JailCrypto.computeSignature(secret, sessionId, jobId, "host", "req", "SINGLE", 0, 0L, 0L, "", nonce, "inspectMetadata", "", emptyMap())
        
        val req = JailRequest(type = "SINGLE", jobId = jobId, operation = "inspectMetadata", nonce = nonce, signature = validReqSig)
        
        val isValid = JailCrypto.verifySignature(
            secretBase64 = secret,
            sessionId = sessionId,
            jobId = req.jobId,
            identity = "host",
            direction = "req",
            messageType = req.type,
            sequence = req.sequence,
            totalSize = req.totalSize,
            payloadLength = 0L,
            payloadHash = "",
            nonce = req.nonce,
            operation = req.operation,
            status = "",
            config = req.config,
            expectedSignature = req.signature
        )
        
        assertTrue("Worker must accept valid request", isValid)
        
        // Fuzz the operation (actually operation is not in the signature, wait)
        // Wait, the signature DOES NOT contain the operation in the new chunked protocol!
        // It binds type (START/CHUNK/END/SINGLE), jobId, sequence, sizes, payloadHash, nonce.
        // To fuzz it properly, let's fuzz the type or sequence.
        val fuzzedReq = req.copy(type = "START", sequence = 1)
        val isFuzzedValid = JailCrypto.verifySignature(
            secretBase64 = secret,
            sessionId = sessionId,
            jobId = fuzzedReq.jobId,
            identity = "host",
            direction = "req",
            messageType = fuzzedReq.type,
            sequence = fuzzedReq.sequence,
            totalSize = fuzzedReq.totalSize,
            payloadLength = 0L,
            payloadHash = "",
            nonce = fuzzedReq.nonce,
            operation = fuzzedReq.operation,
            status = "",
            config = fuzzedReq.config,
            expectedSignature = fuzzedReq.signature // Old signature
        )
        
        assertFalse("Worker must reject request with tampered state", isFuzzedValid)
    }

    @Test
    fun `test invalid magic header`() {
        val inStream = ByteArrayInputStream(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        
        var threwException = false
        try {
            JailIpc.readMessage(inStream) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Invalid IPC magic") == true) {
                threwException = true
            }
        }
        assertTrue("Must throw IllegalStateException for invalid magic", threwException)
    }

    @Test
    fun `test negative payload length`() {
        val outStream = ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(outStream)
        
        dos.writeInt(JailIpc.MAGIC)
        dos.writeShort(JailIpc.VERSION.toInt())
        
        val headerBytes = "{}".toByteArray()
        dos.writeInt(headerBytes.size)
        dos.write(headerBytes)
        
        // Write negative payload length
        dos.writeLong(-1L)
        dos.flush()

        val inStream = ByteArrayInputStream(outStream.toByteArray())
        
        var threwException = false
        try {
            JailIpc.readMessage(inStream) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Invalid payload length") == true) {
                threwException = true
            }
        }
        assertTrue("Must throw IllegalStateException for negative payload length", threwException)
    }
}
