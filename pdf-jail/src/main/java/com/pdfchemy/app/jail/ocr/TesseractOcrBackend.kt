package com.pdfchemy.app.jail.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TesseractMemoryBridge

/**
 * Production Tesseract-based OcrBackend.
 * Loads traineddata strictly from in-memory byte buffer via JNI bridge to TessBaseAPI::Init(data, data_size).
 * Bypasses all filesystem requirements (filesDir, cacheDir, fopen) for isolated UID compatibility.
 */
class TesseractOcrBackend(
    context: Context,
    language: String = DEFAULT_LANGUAGE
) : OcrBackend {

    companion object {
        const val DEFAULT_LANGUAGE = "eng"
        const val MAX_MODEL_BYTES = 30 * 1024 * 1024 // 30 MB security bound

        @Volatile
        private var cachedModelData: ByteArray? = null
        private val lock = Any()

        fun getOrLoadModelData(context: Context, language: String): ByteArray {
            check(language == DEFAULT_LANGUAGE) { "Untrusted/unsupported language: $language" }
            cachedModelData?.let { return it }
            synchronized(lock) {
                cachedModelData?.let { return it }
                val assetPath = "tessdata/$language.traineddata"
                val stream = context.assets.open(assetPath)
                val bytes = stream.use { it.readBytes() }
                check(bytes.isNotEmpty()) { "Tessdata model buffer is empty: $assetPath" }
                check(bytes.size <= MAX_MODEL_BYTES) { "Tessdata model exceeds security bounds: ${bytes.size} > $MAX_MODEL_BYTES" }
                cachedModelData = bytes
                return bytes
            }
        }
    }

    private val tess = TessBaseAPI()

    init {
        val modelBytes = getOrLoadModelData(context, language)
        val success = TesseractMemoryBridge.initFromMemory(
            tess,
            modelBytes,
            language = language,
            ocrEngineMode = TessBaseAPI.OEM_DEFAULT
        )
        check(success) { "Failed to initialize TessBaseAPI from in-memory model" }
        tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }

    override fun recognize(bitmap: Bitmap): OcrResult {
        tess.setImage(bitmap)
        val utf8Text = tess.utF8Text ?: ""
        val iterator = tess.resultIterator

        val blocks = mutableListOf<OcrBlock>()
        if (iterator != null) {
            try {
                var hasNext = true
                val rilBlock = TessBaseAPI.PageIteratorLevel.RIL_BLOCK
                val rilTextline = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                val rilWord = TessBaseAPI.PageIteratorLevel.RIL_WORD

                var currentBlockLines = mutableListOf<OcrLine>()
                var currentLineElements = mutableListOf<OcrElement>()
                var currentBlockBox: RectF? = null
                var currentLineBox: RectF? = null

                while (hasNext) {
                    val wordText = iterator.getUTF8Text(rilWord)
                    val wordConf = iterator.confidence(rilWord)
                    val wordBox = iterator.getBoundingRect(rilWord)?.let { RectF(it) }

                    if (!wordText.isNullOrBlank()) {
                        val confidenceFraction = (wordConf / 100f).coerceIn(0f, 1f)
                        currentLineElements.add(OcrElement(wordText.trim(), wordBox, confidenceFraction))
                    }

                    if (iterator.isAtBeginningOf(rilBlock)) {
                        currentBlockBox = iterator.getBoundingRect(rilBlock)?.let { RectF(it) }
                    }
                    if (iterator.isAtBeginningOf(rilTextline)) {
                        currentLineBox = iterator.getBoundingRect(rilTextline)?.let { RectF(it) }
                    }

                    if (iterator.isAtFinalElement(rilBlock, rilWord)) {
                        if (currentLineElements.isNotEmpty()) {
                            val lineText = currentLineElements.joinToString(" ") { it.text }
                            currentBlockLines.add(OcrLine(lineText, currentLineBox, currentLineElements))
                            currentLineElements = mutableListOf()
                        }
                        if (currentBlockLines.isNotEmpty()) {
                            val blockText = currentBlockLines.joinToString("\n") { it.text }
                            blocks.add(OcrBlock(blockText, currentBlockBox, currentBlockLines))
                            currentBlockLines = mutableListOf()
                        }
                    } else if (iterator.isAtFinalElement(rilTextline, rilWord)) {
                        if (currentLineElements.isNotEmpty()) {
                            val lineText = currentLineElements.joinToString(" ") { it.text }
                            currentBlockLines.add(OcrLine(lineText, currentLineBox, currentLineElements))
                            currentLineElements = mutableListOf()
                        }
                    }

                    hasNext = iterator.next(rilWord)
                }

                if (currentLineElements.isNotEmpty()) {
                    val lineText = currentLineElements.joinToString(" ") { it.text }
                    currentBlockLines.add(OcrLine(lineText, currentLineBox, currentLineElements))
                }
                if (currentBlockLines.isNotEmpty()) {
                    val blockText = currentBlockLines.joinToString("\n") { it.text }
                    blocks.add(OcrBlock(blockText, currentBlockBox, currentBlockLines))
                }
            } finally {
                iterator.delete()
            }
        }

        if (blocks.isEmpty() && utf8Text.isNotBlank()) {
            val element = OcrElement(utf8Text.trim(), null, null)
            val line = OcrLine(utf8Text.trim(), null, listOf(element))
            blocks.add(OcrBlock(utf8Text.trim(), null, listOf(line)))
        }

        return OcrResult(utf8Text, blocks)
    }

    override fun close() {
        try {
            tess.clear()
        } catch (_: Exception) {}
        try {
            tess.recycle()
        } catch (_: Exception) {}
    }
}
