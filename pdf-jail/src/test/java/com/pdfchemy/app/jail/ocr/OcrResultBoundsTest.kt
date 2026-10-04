package com.pdfchemy.app.jail.ocr

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OcrResultBoundsTest {

    @Test
    fun clampsBoundingBoxesToPageDimensions() {
        val element = OcrElement("test", RectF(-10f, -50f, 700f, 900f))
        val line = OcrLine("test", null, listOf(element))
        val block = OcrBlock("test", null, listOf(line))
        val raw = OcrResult("test", listOf(block))

        val bounded = OcrResultBounds.enforce(raw, 600f, 800f)
        val box = bounded.blocks.single().lines.single().elements.single().boundingBox!!

        assertEquals(0f, box.left)
        assertEquals(0f, box.top)
        assertEquals(600f, box.right)
        assertEquals(800f, box.bottom)
    }

    @Test
    fun rejectsNonFiniteBoundingBoxCoordinates() {
        val elementNan = OcrElement("nan", RectF(Float.NaN, 0f, 100f, 100f))
        val elementInf = OcrElement("inf", RectF(0f, Float.POSITIVE_INFINITY, 100f, 100f))
        val line = OcrLine("test", null, listOf(elementNan, elementInf))
        val block = OcrBlock("test", null, listOf(line))
        val raw = OcrResult("test", listOf(block))

        val bounded = OcrResultBounds.enforce(raw, 600f, 800f)
        val elements = bounded.blocks.single().lines.single().elements

        assertEquals(2, elements.size)
        assertNull(elements[0].boundingBox)
        assertNull(elements[1].boundingBox)
    }

    @Test
    fun enforcesAggregatePageTextLimit() {
        val longString = "A".repeat(100)
        val elements = (0 until 1_000).map { OcrElement(longString, RectF(0f, 0f, 10f, 10f)) }
        val line = OcrLine("all", null, elements)
        val block = OcrBlock("all", null, listOf(line))
        val raw = OcrResult("A".repeat(100_000), listOf(block))

        val bounded = OcrResultBounds.enforce(raw, 600f, 800f)
        val totalChars = bounded.blocks.sumOf { b -> b.lines.sumOf { l -> l.elements.sumOf { it.text.length } } }

        assertTrue(totalChars <= OcrResultBounds.MAX_PAGE_TEXT_CHARS)
        assertTrue(bounded.text.length <= OcrResultBounds.MAX_PAGE_TEXT_CHARS)
    }

    @Test
    fun enforcesBlockAndLineQuotas() {
        val blocks = (0 until 1_000).map { bIdx ->
            val lines = (0 until 200).map { lIdx ->
                OcrLine("L$lIdx", null, listOf(OcrElement("E", RectF(0f, 0f, 10f, 10f))))
            }
            OcrBlock("B$bIdx", null, lines)
        }
        val raw = OcrResult("quota", blocks)

        val bounded = OcrResultBounds.enforce(raw, 600f, 800f)
        assertTrue(bounded.blocks.size <= OcrResultBounds.MAX_BLOCKS_PER_PAGE)
        bounded.blocks.forEach { block ->
            assertTrue(block.lines.size <= OcrResultBounds.MAX_LINES_PER_BLOCK)
        }
    }

    @Test
    fun sanitizesConfidenceScore() {
        val validElement = OcrElement("valid", null, 0.95f)
        val invalidElementHigh = OcrElement("high", null, 1.5f)
        val invalidElementLow = OcrElement("low", null, -0.1f)
        val invalidElementNan = OcrElement("nan", null, Float.NaN)
        val line = OcrLine("test", null, listOf(validElement, invalidElementHigh, invalidElementLow, invalidElementNan))
        val block = OcrBlock("test", null, listOf(line))
        val raw = OcrResult("test", listOf(block))

        val bounded = OcrResultBounds.enforce(raw, 600f, 800f)
        val elements = bounded.blocks.single().lines.single().elements

        assertEquals(0.95f, elements[0].confidence)
        assertNull(elements[1].confidence)
        assertNull(elements[2].confidence)
        assertNull(elements[3].confidence)
    }
}
