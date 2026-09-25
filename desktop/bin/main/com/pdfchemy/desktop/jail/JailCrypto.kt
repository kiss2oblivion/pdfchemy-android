package com.pdfchemy.desktop.jail

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.security.SecureRandom

object JailCrypto {

    fun generateSecret(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun computePayloadHash(payload: ByteArray?): String {
        if (payload == null || payload.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(payload))
    }

    private fun computeConfigHash(config: Map<String, String>?): String {
        if (config.isNullOrEmpty()) return ""
        val sortedEntries = config.entries.sortedBy { it.key }
        val sb = java.lang.StringBuilder()
        for ((k, v) in sortedEntries) {
            sb.append(k).append("=").append(v).append(";")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return Base64.getEncoder().encodeToString(digest.digest(sb.toString().toByteArray(Charsets.UTF_8)))
    }

    fun computeSignature(
        secretBase64: String,
        sessionId: String,
        jobId: String,
        identity: String,
        direction: String,
        messageType: String,
        sequence: Int,
        totalSize: Long,
        payloadLength: Long,
        payloadHash: String,
        nonce: String,
        operation: String,
        status: String,
        config: Map<String, String>?
    ): String {
        val secretBytes = Base64.getDecoder().decode(secretBase64)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        
        val configHash = computeConfigHash(config)
        // Canonical transcript: version:session:job:identity:direction:type:seq:totalsize:payloadlen:payloadhash:nonce:operation:status:configHash
        val transcript = "${JailIpc.VERSION}:$sessionId:$jobId:$identity:$direction:$messageType:$sequence:$totalSize:$payloadLength:$payloadHash:$nonce:$operation:$status:$configHash"
        val hashBytes = mac.doFinal(transcript.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(hashBytes)
    }
    
    fun verifySignature(
        secretBase64: String,
        sessionId: String,
        jobId: String,
        identity: String,
        direction: String,
        messageType: String,
        sequence: Int,
        totalSize: Long,
        payloadLength: Long,
        payloadHash: String,
        nonce: String,
        operation: String,
        status: String,
        config: Map<String, String>?,
        expectedSignature: String
    ): Boolean {
        if (expectedSignature.isBlank()) return false
        val computed = computeSignature(
            secretBase64, sessionId, jobId, identity, direction, messageType, 
            sequence, totalSize, payloadLength, payloadHash, nonce, operation, status, config
        )
        return MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8), 
            expectedSignature.toByteArray(Charsets.UTF_8)
        )
    }
}
