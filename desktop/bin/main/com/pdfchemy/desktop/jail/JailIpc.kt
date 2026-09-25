package com.pdfchemy.desktop.jail

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class JailRequest(
    val type: String = "SINGLE", // "SINGLE", "START", "CHUNK", "END"
    val operation: String = "",
    val jobId: String = "",
    val nonce: String = "",
    val sequence: Int = 0,
    val totalSize: Long = 0L,
    val payloadHash: String = "",
    val config: Map<String, String> = emptyMap(),
    val signature: String = ""
)

data class JailResponse(
    val status: String,
    val type: String = "SINGLE", // "SINGLE", "START", "CHUNK", "END"
    val jobId: String = "",
    val nonce: String = "",
    val sequence: Int = 0,
    val totalSize: Long = 0L,
    val payloadHash: String = "",
    val errorMessage: String? = null,
    val payload: String = "",
    val config: Map<String, String> = emptyMap(),
    val signature: String = ""
) {
    fun sendChunked(
        outStream: OutputStream,
        sourceFile: java.io.File?,
        payloadBytes: ByteArray?,
        secret: String,
        sessionId: String
    ) {
        if (sourceFile != null && sourceFile.length() > JailIpc.MAX_IPC_FRAME_SIZE) {
            val totalSize = sourceFile.length()
            val jobId = this.jobId.ifEmpty { java.util.UUID.randomUUID().toString() }
            
            // START
            val startNonce = java.util.UUID.randomUUID().toString()
            val startReq = this.copy(
                type = "START", jobId = jobId, sequence = 0, totalSize = totalSize, nonce = startNonce
            )
            val startSigned = startReq.copy(
                signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "START", 0, totalSize, 0L, "", startNonce, "", this.status, this.config)
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
                    
                    val chunkReq = this.copy(
                        type = "CHUNK", jobId = jobId, sequence = seq, totalSize = totalSize, payloadHash = payloadHash, nonce = chunkNonce
                    )
                    val chunkSigned = chunkReq.copy(
                        signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "CHUNK", seq, totalSize, chunkBytes.size.toLong(), payloadHash, chunkNonce, "", this.status, this.config)
                    )
                    JailIpc.writeMessageBytes(outStream, JailIpc.gson.toJson(chunkSigned), chunkBytes)
                    seq++
                }
            }
            
            // END
            val endNonce = java.util.UUID.randomUUID().toString()
            val endReq = this.copy(
                type = "END", jobId = jobId, sequence = seq, totalSize = totalSize, nonce = endNonce
            )
            val endSigned = endReq.copy(
                signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "END", seq, totalSize, 0L, "", endNonce, "", this.status, this.config)
            )
            JailIpc.writeMessage(outStream, JailIpc.gson.toJson(endSigned), null, 0L)
        } else {
            val pBytes = payloadBytes ?: sourceFile?.readBytes()
            val payloadHash = JailCrypto.computePayloadHash(pBytes)
            val payloadLen = pBytes?.size?.toLong() ?: 0L
            val jobId = this.jobId.ifEmpty { java.util.UUID.randomUUID().toString() }
            val nonce = java.util.UUID.randomUUID().toString()
            
            val singleReq = this.copy(
                type = "SINGLE", jobId = jobId, nonce = nonce, payloadHash = payloadHash, totalSize = payloadLen
            )
            val singleSigned = singleReq.copy(
                signature = JailCrypto.computeSignature(secret, sessionId, jobId, "worker", "res", "SINGLE", 0, payloadLen, payloadLen, payloadHash, nonce, "", this.status, this.config)
            )
            JailIpc.writeMessageBytes(outStream, JailIpc.gson.toJson(singleSigned), pBytes)
        }
    }
}

object JailIpc {
    val gson: Gson = GsonBuilder().create()

    const val MAGIC = 0x4A41494C // "JAIL"
    const val VERSION = 1.toShort()
    const val MAX_HEADER_SIZE = 64 * 1024 // 64KB max header size
    const val MAX_IPC_FRAME_SIZE = 16L * 1024 * 1024 // 16 MB
    const val MAX_INPUT_DOCUMENT_SIZE = 2L * 1024 * 1024 * 1024 // 2 GB

    fun writeMessage(
        outStream: OutputStream,
        headerJson: String,
        payloadStream: InputStream?,
        payloadLength: Long
    ) {
        val dos = DataOutputStream(outStream)
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        if (headerBytes.size > MAX_HEADER_SIZE) {
            throw IllegalArgumentException("Header size exceeds maximum allowed ($MAX_HEADER_SIZE bytes)")
        }

        dos.writeInt(MAGIC)
        dos.writeShort(VERSION.toInt())
        dos.writeInt(headerBytes.size)
        dos.write(headerBytes)
        dos.writeLong(payloadLength)
        
        if (payloadLength > 0 && payloadStream != null) {
            val buffer = ByteArray(8192)
            var remaining = payloadLength
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = payloadStream.read(buffer, 0, toRead)
                if (read == -1) {
                    throw IllegalStateException("Stream ended prematurely. Expected to write $payloadLength payload bytes, but short by $remaining")
                }
                dos.write(buffer, 0, read)
                remaining -= read
            }
        }
        dos.flush()
    }

    fun writeMessageBytes(
        outStream: OutputStream,
        headerJson: String,
        payloadBytes: ByteArray?
    ) {
        val dos = DataOutputStream(outStream)
        val headerBytes = headerJson.toByteArray(Charsets.UTF_8)
        if (headerBytes.size > MAX_HEADER_SIZE) {
            throw IllegalArgumentException("Header size exceeds maximum allowed ($MAX_HEADER_SIZE bytes)")
        }

        dos.writeInt(MAGIC)
        dos.writeShort(VERSION.toInt())
        dos.writeInt(headerBytes.size)
        dos.write(headerBytes)
        val payloadLength = payloadBytes?.size?.toLong() ?: 0L
        dos.writeLong(payloadLength)
        
        if (payloadLength > 0 && payloadBytes != null) {
            dos.write(payloadBytes)
        }
        dos.flush()
    }

    fun readMessage(
        inStream: InputStream,
        onPayloadReady: (headerJson: String, payloadLength: Long, payloadStream: InputStream) -> Unit
    ) {
        val dis = DataInputStream(inStream)
        val magic = dis.readInt()
        if (magic != MAGIC) {
            throw IllegalStateException("Invalid IPC magic: expected 0x${Integer.toHexString(MAGIC)}, got 0x${Integer.toHexString(magic)}")
        }
        val version = dis.readShort()
        if (version != VERSION) {
            throw IllegalStateException("Invalid IPC version: expected $VERSION, got $version")
        }
        
        val headerLen = dis.readInt()
        if (headerLen <= 0 || headerLen > MAX_HEADER_SIZE) {
            throw IllegalStateException("Invalid header length: $headerLen")
        }
        
        val headerBytes = ByteArray(headerLen)
        dis.readFully(headerBytes)
        val headerJson = String(headerBytes, Charsets.UTF_8)
        
        val payloadLen = dis.readLong()
        if (payloadLen < 0) {
            throw IllegalStateException("Invalid payload length: $payloadLen")
        }
        if (payloadLen > MAX_IPC_FRAME_SIZE) {
            throw SecurityException("Payload length $payloadLen exceeds MAX_IPC_FRAME_SIZE ($MAX_IPC_FRAME_SIZE)")
        }
        
        var remainingBytes = payloadLen
        
        val boundedStream = object : InputStream() {
            override fun read(): Int {
                if (remainingBytes <= 0) return -1
                val r = dis.read()
                if (r != -1) remainingBytes--
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remainingBytes <= 0) return -1
                val toRead = minOf(len.toLong(), remainingBytes).toInt()
                val r = dis.read(b, off, toRead)
                if (r > 0) remainingBytes -= r
                return r
            }
        }
        
        onPayloadReady(headerJson, payloadLen, boundedStream)
        
        val buffer = ByteArray(8192)
        while (remainingBytes > 0) {
            val toRead = minOf(buffer.size.toLong(), remainingBytes).toInt()
            val r = dis.read(buffer, 0, toRead)
            if (r == -1) break
            remainingBytes -= r
        }
    }
}
