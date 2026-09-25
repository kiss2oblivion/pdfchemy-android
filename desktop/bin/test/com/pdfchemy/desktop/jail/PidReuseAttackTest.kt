package com.pdfchemy.desktop.jail

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

class PidReuseAttackTest {

    @Test
    fun `test stale worker PID reuse attack mitigated by session signature`() {
        // Simulates:
        // 1. Worker A spawns and gets PID 1234.
        val secretA = JailCrypto.generateSecret()
        val sessionAId = UUID.randomUUID().toString()
        
        // Worker A formulates a malicious response to stall or exfiltrate
        val nonce = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val validSigA = JailCrypto.computeSignature(secretA, sessionAId, jobId, "worker", "res", "SINGLE", 0, 0L, 0L, "", nonce, "", "SUCCESS", emptyMap())
        
        val maliciousResponse = JailResponse(
            type = "SINGLE",
            jobId = jobId,
            status = "SUCCESS",
            nonce = nonce,
            signature = validSigA
        )
        val maliciousHeaderJson = JailIpc.gson.toJson(maliciousResponse)
        
        // 2. Worker A crashes but its output stream (or malicious payload) remains buffered in the OS pipe
        val stalePipeData = ByteArrayOutputStream()
        JailIpc.writeMessageBytes(stalePipeData, maliciousHeaderJson, null)
        
        // 3. The OS reuses PID 1234 for Worker B. The Host establishes a new session for Worker B
        // but somehow reads the stale pipe from Worker A (an OS-level stream reuse or confusion)
        val secretB = JailCrypto.generateSecret()
        val sessionBId = UUID.randomUUID().toString()
        
        val inStreamB = ByteArrayInputStream(stalePipeData.toByteArray())
        val outStreamB = ByteArrayOutputStream()
        
        val sessionB = JailInteractiveSession(outStreamB, inStreamB, secretB, sessionBId)
        
        // 4. Host processes the response, believing it is from Worker B
        var threwSecurityException = false
        try {
            sessionB.receiveResponse { _, _, _ -> 
                fail("Host accepted a response from a previous worker identity (PID reuse attack)!")
            }
        } catch (e: SecurityException) {
            threwSecurityException = true
        }
        
        assertTrue(
            "Host MUST reject the response because Worker A's signature does not match Worker B's cryptographic session identity",
            threwSecurityException
        )
    }
}
