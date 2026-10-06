package com.pdfchemy.app.logic.audio

import android.speech.tts.TextToSpeech

object AudioTextChunker {

    fun chunkText(text: String, maxLength: Int = TextToSpeech.getMaxSpeechInputLength()): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val remainingLength = text.length - currentIndex
            if (remainingLength <= maxLength) {
                chunks.add(text.substring(currentIndex))
                break
            }

            var splitIndex = findSplitIndex(text, currentIndex, currentIndex + maxLength)
            
            // Fallback: If no good boundary found, split exactly at maxLength, ensuring surrogate pair safety.
            if (splitIndex == -1 || splitIndex == currentIndex) {
                splitIndex = currentIndex + maxLength
                if (Character.isHighSurrogate(text[splitIndex - 1])) {
                    splitIndex-- // Don't split in the middle of a surrogate pair
                }
            }

            val chunk = text.substring(currentIndex, splitIndex).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            currentIndex = splitIndex
        }

        return chunks
    }

    private fun findSplitIndex(text: String, start: Int, maxEnd: Int): Int {
        val substring = text.substring(start, maxEnd)
        
        // Try to find double newline
        var lastIdx = substring.lastIndexOf("\n\n")
        if (lastIdx > 0) return start + lastIdx + 2

        // Try to find single newline
        lastIdx = substring.lastIndexOf("\n")
        if (lastIdx > 0) return start + lastIdx + 1

        // Try to find sentence end
        lastIdx = substring.lastIndexOf(". ")
        if (lastIdx > 0) return start + lastIdx + 2
        
        lastIdx = substring.lastIndexOf("! ")
        if (lastIdx > 0) return start + lastIdx + 2
        
        lastIdx = substring.lastIndexOf("? ")
        if (lastIdx > 0) return start + lastIdx + 2

        // Try to find space
        lastIdx = substring.lastIndexOf(" ")
        if (lastIdx > 0) return start + lastIdx + 1

        return -1
    }
}
