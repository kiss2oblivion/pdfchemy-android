package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.logic.PageStreamEvidence
import com.pdfchemy.app.logic.PageTextEvidence
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.Writer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import org.json.JSONObject

/**
 * Optional evidence for readable input/output, not a rendering or OCR comparison.
 * Only counts cross IPC. Text hashes remain in the isolated worker. Compare
 * exact UTF-16 code units produced by the same extractor, with no normalization.
 */
internal object ReadablePageEvidence {
    private const val MAX_TEXT_PAGES = 500
    private const val MAX_TEXT_POSITIONS = 250_000
    private const val MAX_TEXT_CHARS = SecurityLimits.MAX_TEXT_BYTES / 2
    private const val TEXT_TIME_NS = 5_000_000_000L

    data class TextSample(val hash: ByteArray, val hasText: Boolean)
    data class Snapshot(val geometry: List<ByteArray?>, val text: List<TextSample?>)
    private class EvidenceBudget : IOException("Optional text evidence budget reached")

    fun capture(document: PDDocument): Snapshot {
        val count = document.numberOfPages
        require(count in 1..SecurityLimits.MAX_PAGE_COUNT)
        val geometry = ArrayList<ByteArray?>(count)
        val text = MutableList<TextSample?>(count) { null }
        val indices = IdentityHashMap<COSDictionary, Int>()
        for (page in document.pages) {
            val index = geometry.size
            check(index < count)
            geometry.add(pageGeometry(page))
            if (index < MAX_TEXT_PAGES) {
                indices[page.cosObject] = index
                // No /Contents is known to have no extractable page text;
                // an unreadable or nonvisited stream is not treated as empty.
                val contents = page.cosObject.getItem(COSName.CONTENTS)
                if (contents == null || contents is COSNull)
                    text[index] = TextSample(MessageDigest.getInstance("SHA-256").digest(), false)
            }
        }
        check(geometry.size == count)
        val started = System.nanoTime()
        var positions = 0
        var characters = 0
        fun timeCheck() {
            if (System.nanoTime() - started > TEXT_TIME_NS) throw EvidenceBudget()
        }
        val writer = object : Writer() {
            var current: Int? = null
            var digest = MessageDigest.getInstance("SHA-256")
            var hasText = false
            fun begin(index: Int?) {
                current = index
                digest.reset()
                hasText = false
            }
            fun finish() {
                current?.let { text[it] = TextSample(digest.digest(), hasText) }
                current = null
            }
            override fun write(chars: CharArray, offset: Int, length: Int) {
                timeCheck()
                if (current == null) return
                if (length > MAX_TEXT_CHARS - characters) throw EvidenceBudget()
                characters += length
                for (index in offset until offset + length) {
                    val ch = chars[index]
                    digest.update((ch.code ushr 8).toByte())
                    digest.update(ch.code.toByte())
                    if (!ch.isWhitespace()) hasText = true
                }
            }
            override fun flush() {}
            override fun close() {}
        }
        try {
            val stripper = object : PDFTextStripper() {
                override fun startPage(page: PDPage) {
                    timeCheck()
                    writer.begin(indices[page.cosObject])
                    super.startPage(page)
                }
                override fun processTextPosition(textPosition: TextPosition) {
                    timeCheck()
                    if (++positions > MAX_TEXT_POSITIONS) throw EvidenceBudget()
                    super.processTextPosition(textPosition)
                }
                override fun endPage(page: PDPage) {
                    super.endPage(page)
                    writer.finish()
                }
            }
            stripper.setStartPage(1)
            stripper.setEndPage(minOf(count, MAX_TEXT_PAGES))
            stripper.writeText(document, writer)
        } catch (_: IOException) {
            // Retain only completed samples; partial text is never evidence.
        }
        return Snapshot(geometry, text)
    }

    private fun pageGeometry(page: PDPage): ByteArray? {
        // Effective PDFBox box values, not a statement that source dictionaries
        // are conformant. Compare all five boxes including their origins.
        return try {
            val boxes = listOf(page.mediaBox, page.cropBox, page.bleedBox, page.trimBox, page.artBox)
            val values = boxes.flatMap { listOf(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY) }
            val unit = page.cosObject.getFloat(COSName.USER_UNIT, 1f)
            if (values.any { !it.isFinite() } || !unit.isFinite() || unit <= 0f ||
                boxes.any { it.width <= 0f || it.height <= 0f }) return null
            val encoded = ByteBuffer.allocate((values.size + 2) * 4)
            values.forEach { encoded.putFloat(if (it == 0f) 0f else it) }
            encoded.putFloat(unit)
            encoded.putInt(page.rotation)
            MessageDigest.getInstance("SHA-256").digest(encoded.array())
        } catch (_: IllegalArgumentException) { null }
    }

    fun compareText(input: List<TextSample?>, output: List<TextSample?>): PageTextEvidence {
        require(input.size == output.size && input.size in 1..SecurityLimits.MAX_PAGE_COUNT)
        var same = 0
        var changed = 0
        var empty = 0
        var unknown = 0
        input.indices.forEach { index ->
            val a = input[index]
            val b = output[index]
            when {
                a == null || b == null -> unknown++
                !a.hasText && !b.hasText -> empty++
                a.hasText != b.hasText -> changed++
                a.hash.contentEquals(b.hash) -> same++
                else -> changed++
            }
        }
        return PageTextEvidence(same, changed, empty, unknown)
    }

    fun compareGeometry(input: Snapshot, output: Snapshot): PageStreamEvidence =
        PageContentEvidence.compare(input.geometry, output.geometry)

    fun json(evidence: PageTextEvidence): JSONObject = JSONObject()
        .put("matchingTextPages", evidence.matchingTextPages)
        .put("differentPages", evidence.differentPages)
        .put("noTextPages", evidence.noTextPages)
        .put("unmeasuredPages", evidence.unmeasuredPages)
}
