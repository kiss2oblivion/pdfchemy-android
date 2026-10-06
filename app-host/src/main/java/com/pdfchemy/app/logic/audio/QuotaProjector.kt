package com.pdfchemy.app.logic.audio

class QuotaProjector(private val totalCharacters: Int) {
    private var synthesizedCharacters = 0
    private var totalSynthesisTimeMs = 0L
    private var bytesWritten = 0L

    fun recordChunk(chunkChars: Int, synthesisTimeMs: Long, chunkBytes: Long) {
        synthesizedCharacters += chunkChars
        totalSynthesisTimeMs += synthesisTimeMs
        bytesWritten += chunkBytes
    }

    fun isProjectionSafe(sampleRate: Int, channelCount: Int, bytesPerSample: Int, maxAllowedBytes: Long = 4L * 1024 * 1024 * 1024 - 100): Boolean {
        if (synthesizedCharacters == 0) return true
        
        val remainingCharacters = totalCharacters - synthesizedCharacters
        if (remainingCharacters <= 0) return bytesWritten < maxAllowedBytes
        
        val observedSecondsPerCharacter = (totalSynthesisTimeMs / 1000.0) / synthesizedCharacters
        val estimatedRemainingDurationSeconds = remainingCharacters * observedSecondsPerCharacter
        
        val estimatedRemainingBytes = (estimatedRemainingDurationSeconds * sampleRate * channelCount * bytesPerSample).toLong()
        val projectedTotalBytes = bytesWritten + estimatedRemainingBytes
        
        return projectedTotalBytes <= maxAllowedBytes
    }
    
    fun getProjectedTotalBytes(sampleRate: Int, channelCount: Int, bytesPerSample: Int): Long {
        if (synthesizedCharacters == 0) return 0L
        val remainingCharacters = totalCharacters - synthesizedCharacters
        val observedSecondsPerCharacter = (totalSynthesisTimeMs / 1000.0) / synthesizedCharacters
        val estimatedRemainingDurationSeconds = remainingCharacters * observedSecondsPerCharacter
        val estimatedRemainingBytes = (estimatedRemainingDurationSeconds * sampleRate * channelCount * bytesPerSample).toLong()
        return bytesWritten + estimatedRemainingBytes
    }
}
