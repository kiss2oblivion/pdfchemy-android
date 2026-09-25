package com.pdfchemy.desktop.jail

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.contains("HOSTILE_STDERR")) {
        val secret = System.getenv("ARKHAM_SECRET") ?: exitProcess(1)
        val sessionId = System.getenv("ARKHAM_SESSION") ?: exitProcess(1)
        val out = System.out
        val err = System.err
        
        // Endless stderr spam thread
        Thread {
            while (true) {
                err.print("ERROR SPAM ")
                Thread.sleep(1)
            }
        }.start()
        
        // Spam stdout with FILE_READY chunks to trigger limits
        val jobId = UUID.randomUUID().toString()
        var seq = 0
        
        val startNonce = UUID.randomUUID().toString()
        val startReq = JailResponse(
            type = "START", jobId = jobId, status = "SUCCESS", sequence = 0, totalSize = 5L * 1024 * 1024, nonce = startNonce
        )
        val startSigned = startReq.copy(
            signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "START", 0, 5L * 1024 * 1024, 0L, "", startNonce, "", "SUCCESS", emptyMap())
        )
        JailIpc.writeMessage(out, JailIpc.gson.toJson(startSigned), null, 0L)
        
        val chunkBytes = ByteArray(1024 * 1024) { 'A'.code.toByte() }
        while (true) {
            val payloadHash = JailCrypto.computePayloadHash(chunkBytes)
            val nonce = UUID.randomUUID().toString()
            val chunkReq = JailResponse(
                type = "CHUNK", jobId = jobId, status = "SUCCESS", sequence = seq, totalSize = 5L * 1024 * 1024, payloadHash = payloadHash, nonce = nonce
            )
            val chunkSigned = chunkReq.copy(
                signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "CHUNK", seq, 5L * 1024 * 1024, chunkBytes.size.toLong(), payloadHash, nonce, "", "SUCCESS", emptyMap())
            )
            JailIpc.writeMessageBytes(out, JailIpc.gson.toJson(chunkSigned), chunkBytes)
            seq++
        }
    }
}

class IncrementalLimitsTest {

    @Test
    fun `test exact bounds are accepted by aggregate stream`() {
        val stream = ByteArrayInputStream(ByteArray(100))
        val bounded = AggregateBoundedInputStream(stream, 100L)
        val buf = ByteArray(100)
        val read = bounded.read(buf)
        assertEquals(100, read)
        assertEquals(-1, bounded.read())
    }

    @Test
    fun `test limit breach rejected by aggregate stream`() {
        val stream = ByteArrayInputStream(ByteArray(101))
        val bounded = AggregateBoundedInputStream(stream, 100L)
        val buf = ByteArray(100)
        bounded.read(buf) // Should pass
        
        var threwSecurity = false
        try {
            bounded.read() // the 101st byte
        } catch (e: SecurityException) {
            threwSecurity = true
        }
        assertTrue("Stream must throw SecurityException on limit breach", threwSecurity)
    }

    @Test
    fun `test declared less than actual invalidates connection`() {
        val payloadLen = 10L
        val jobId = UUID.randomUUID().toString()
        val header = JailIpc.gson.toJson(JailResponse(type = "SINGLE", jobId = jobId, status = "SUCCESS", nonce = "n"))
        
        val outStream = ByteArrayOutputStream()
        // Write standard MAGIC, len, header
        val dos = java.io.DataOutputStream(outStream)
        dos.writeInt(JailIpc.MAGIC)
        dos.writeShort(JailIpc.VERSION.toInt())
        val hBytes = header.toByteArray(Charsets.UTF_8)
        dos.writeInt(hBytes.size)
        dos.write(hBytes)
        dos.writeLong(payloadLen)
        
        // Write 100 bytes (attacker payload) which is greater than payloadLen=10
        val maliciousPayload = ByteArray(100) { 'A'.code.toByte() }
        dos.write(maliciousPayload)
        
        val inStream = ByteArrayInputStream(outStream.toByteArray())
        
        // readMessage should read header, then yield a bounded stream of 10 bytes
        var payloadRead = 0
        JailIpc.readMessage(inStream) { _, len, bounded ->
            assertEquals(10L, len)
            val b = bounded.readBytes()
            assertEquals(10, b.size)
            payloadRead = b.size
        }
        
        assertEquals(10, payloadRead)
        
        // Now if we try to read the NEXT message on the connection, it should fail
        // because the next byte is 'A', not the MAGIC header!
        var invalidated = false
        try {
            JailIpc.readMessage(inStream) { _, _, _ -> }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Invalid IPC magic") == true) {
                invalidated = true
            }
        }
        
        assertTrue("Connection must be invalidated when next frame magic is corrupted by undeclared payload bytes", invalidated)
    }

    @Test
    fun `test interaction worker exceeds stdout limit while spamming stderr`() = runBlocking {
        DesktopJailManager.workerMainClass = "com.pdfchemy.desktop.jail.IncrementalLimitsTestKt"
        DesktopJailManager.workerArgs = listOf("HOSTILE_STDERR")

        var exceptionThrown = false
        try {
            DesktopJailManager.execute(operation = "test")
        } catch (e: Exception) {
            exceptionThrown = true
            println("Exception message: ${e.message}")
            assertTrue("Exception message must indicate worker failure or stream limit. Was: ${e.message}", 
                e.message!!.contains("exceeded") || e.message!!.contains("Worker failed") || e.message!!.contains("exceeds MAX_PAYLOAD_SIZE") || e.message!!.contains("aggregate limit") || e.message!!.contains("limits") || e.message!!.contains("MAX_OUTPUT_FILE_SIZE") || e.message!!.contains("Invalid IPC") || e.message!!.contains("Jail Worker Error"))
        }
        
        assertTrue("Host must violently abort the hostile worker without deadlocking on stderr", exceptionThrown)
    }
}
