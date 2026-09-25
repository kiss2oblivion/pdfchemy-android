package com.pdfchemy.desktop.jail

import java.io.File

/**
 * A mock worker used by tests to dump the environment capabilities it inherited.
 * It writes a JSON-like representation of its environment to stdout so the test can parse it.
 */
fun main() {
    try {
        val env = System.getenv()
        val cwd = File(".").absolutePath
        val props = System.getProperties().map { it.key.toString() to it.value.toString() }.toMap()
        
        val result = mutableMapOf<String, Any>()
        result["env"] = env
        result["cwd"] = cwd
        result["props"] = props

        val payload = JailIpc.gson.toJson(result)
        val response = JailResponse(status = "SUCCESS", payload = payload)
        
        val secret = System.getenv("ARKHAM_SECRET") ?: ""
        val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
        response.sendChunked(System.out, null, null, secret, sessionId)
        
    } catch (e: Exception) {
        val errResponse = JailResponse(status = "ERROR", errorMessage = e.message ?: "Unknown error")
        val secret = System.getenv("ARKHAM_SECRET") ?: ""
        val sessionId = System.getenv("ARKHAM_SESSION") ?: ""
        errResponse.sendChunked(System.out, null, null, secret, sessionId)
    }
}
