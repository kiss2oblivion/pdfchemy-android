package com.pdfchemy.app.jail.ocr

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Pluggable OCR backend interface.
 * Decouples PDF searchable layer generation and text extraction from underlying OCR engines.
 */
interface OcrBackend : AutoCloseable {
    fun recognize(bitmap: Bitmap): OcrResult
    override fun close()
}

data class OcrResult(
    val text: String,
    val blocks: List<OcrBlock>
)

data class OcrBlock(
    val text: String,
    val boundingBox: RectF?,
    val lines: List<OcrLine>
)

data class OcrLine(
    val text: String,
    val boundingBox: RectF?,
    val elements: List<OcrElement>
)

data class OcrElement(
    val text: String,
    val boundingBox: RectF?,
    val confidence: Float? = null
)
