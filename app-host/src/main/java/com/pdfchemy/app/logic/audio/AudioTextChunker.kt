package com.pdfchemy.app.logic.audio

object AudioTextChunker {
    // Caller supplies TextToSpeech.getMaxSpeechInputLength().
    fun chunkText(text: String, maxLength: Int): List<String> {
        require(maxLength >= 2) { "Speech input limit is too small" }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + maxLength)
            if (end < text.length) {
                if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                val window = text.substring(start, end)
                val paragraph = window.lastIndexOf("\n\n").let { if (it >= 0) it + 2 else -1 }
                val sentence = window.indices.lastOrNull { i ->
                    window[i] in "。！？" || (window[i] in ".!?" && (i + 1 == window.length || window[i + 1].isWhitespace()))
                }?.plus(1) ?: -1
                val word = window.indices.lastOrNull { window[it].isWhitespace() }?.plus(1) ?: -1
                val boundary = listOf(paragraph, sentence, word).firstOrNull { it > 0 }
                if (boundary != null) end = start + boundary
            }
            check(end > start)
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
}
