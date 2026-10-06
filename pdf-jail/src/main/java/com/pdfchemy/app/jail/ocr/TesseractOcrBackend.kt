package com.pdfchemy.app.jail.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream

/**
 * Production Tesseract-based OcrBackend.
 * Loads traineddata directly from an in-memory byte buffer via nativeInitMemory.
 * The model byte buffer is trusted and privately held within the isolated worker process.
 * Bypasses all filesystem requirements (filesDir, cacheDir, fopen) for isolated UID compatibility.
 */
class TesseractOcrBackend(
    context: Context,
    language: String = DEFAULT_LANGUAGE
) : OcrBackend {

    companion object {
        const val DEFAULT_LANGUAGE = "eng"
        const val MAX_MODEL_BYTES = 30 * 1024 * 1024 // 30 MiB security bound

        // Trusted and privately held within the isolated worker process
        @Volatile
        private var cachedModelData: ByteArray? = null
        private val lock = Any()

        fun getOrLoadModelData(context: Context, language: String): ByteArray {
            check(language == DEFAULT_LANGUAGE) { "Untrusted/unsupported language: $language" }
            cachedModelData?.let { return it }
            synchronized(lock) {
                cachedModelData?.let { return it }
                val assetPath = "tessdata/$language.traineddata"
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var totalRead = 0
                context.assets.open(assetPath).use { input ->
                    while (true) {
                        val read = input.read(chunk)
                        if (read == -1) break
                        totalRead += read
                        if (totalRead > MAX_MODEL_BYTES) {
                            throw SecurityException("Tessdata model exceeds 30 MiB hard limit: $totalRead bytes")
                        }
                        output.write(chunk, 0, read)
                    }
                }
                val bytes = output.toByteArray()
                check(bytes.isNotEmpty()) { "Tessdata model buffer is empty: $assetPath" }
                cachedModelData = bytes
                return bytes
            }
        }
    }

    private val tess = TessBaseAPI()

    init {
        val modelBytes = getOrLoadModelData(context, language)
        val success = tess.init(modelBytes, language, TessBaseAPI.OEM_DEFAULT)
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

                fun clampToBitmap(rect: android.graphics.Rect?): RectF? {
                    if (rect == null) return null
                    val l = minOf(rect.left, rect.right).coerceIn(0, bitmap.width).toFloat()
                    val r = maxOf(rect.left, rect.right).coerceIn(0, bitmap.width).toFloat()
                    val t = minOf(rect.top, rect.bottom).coerceIn(0, bitmap.height).toFloat()
                    val b = maxOf(rect.top, rect.bottom).coerceIn(0, bitmap.height).toFloat()
                    return RectF(l, t, r, b)
                }

                while (hasNext) {
                    val wordText = iterator.getUTF8Text(rilWord)
                    val wordConf = iterator.confidence(rilWord)
                    val wordBox = clampToBitmap(iterator.getBoundingRect(rilWord))

                    if (!wordText.isNullOrBlank()) {
                        val confidenceFraction = (wordConf / 100f).coerceIn(0f, 1f)
                        currentLineElements.add(OcrElement(wordText.trim(), wordBox, confidenceFraction))
                    }

                    if (iterator.isAtBeginningOf(rilBlock)) {
                        currentBlockBox = clampToBitmap(iterator.getBoundingRect(rilBlock))
                    }
                    if (iterator.isAtBeginningOf(rilTextline)) {
                        currentLineBox = clampToBitmap(iterator.getBoundingRect(rilTextline))
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
