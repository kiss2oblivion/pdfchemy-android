package com.pdfchemy.app.jail.ocr

import android.graphics.RectF

/**
 * Enforces strict upper bounds on OCR results before passing to PDFBox or text extraction.
 * Protects against adversarial/malformed native results that could cause memory bloat or PDF corruption.
 */
object OcrResultBounds {
    const val MAX_BLOCKS_PER_PAGE = 500
    const val MAX_LINES_PER_BLOCK = 100
    const val MAX_ELEMENTS_PER_LINE = 100
    const val MAX_ELEMENT_CHARS = 500
    const val MAX_PAGE_TEXT_CHARS = 65_536

    fun enforce(raw: OcrResult, pageWidth: Float, pageHeight: Float): OcrResult {
        var totalChars = 0
        val boundedBlocks = mutableListOf<OcrBlock>()

        for (block in raw.blocks.take(MAX_BLOCKS_PER_PAGE)) {
            val boundedLines = mutableListOf<OcrLine>()
            for (line in block.lines.take(MAX_LINES_PER_BLOCK)) {
                val boundedElements = mutableListOf<OcrElement>()
                for (elem in line.elements.take(MAX_ELEMENTS_PER_LINE)) {
                    val sanitizedText = elem.text.take(MAX_ELEMENT_CHARS)
                    if (sanitizedText.isEmpty()) continue
                    if (totalChars + sanitizedText.length > MAX_PAGE_TEXT_CHARS) break
                    totalChars += sanitizedText.length

                    val clampedBox = elem.boundingBox?.let { box ->
                        if (box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite()) {
                            RectF(
                                box.left.coerceIn(0f, pageWidth),
                                box.top.coerceIn(0f, pageHeight),
                                box.right.coerceIn(0f, pageWidth),
                                box.bottom.coerceIn(0f, pageHeight)
                            )
                        } else null
                    }
                    val validConfidence = elem.confidence?.takeIf { it.isFinite() && it in 0f..1f }
                    boundedElements.add(OcrElement(sanitizedText, clampedBox, validConfidence))
                }
                if (boundedElements.isNotEmpty()) {
                    boundedLines.add(
                        OcrLine(
                            line.text.take(MAX_ELEMENT_CHARS * MAX_ELEMENTS_PER_LINE),
                            line.boundingBox,
                            boundedElements
                        )
                    )
                }
                if (totalChars >= MAX_PAGE_TEXT_CHARS) break
            }
            if (boundedLines.isNotEmpty()) {
                boundedBlocks.add(
                    OcrBlock(
                        block.text.take(MAX_PAGE_TEXT_CHARS),
                        block.boundingBox,
                        boundedLines
                    )
                )
            }
            if (totalChars >= MAX_PAGE_TEXT_CHARS) break
        }

        val aggregateText = raw.text.take(MAX_PAGE_TEXT_CHARS)
        return OcrResult(aggregateText, boundedBlocks)
    }
}
