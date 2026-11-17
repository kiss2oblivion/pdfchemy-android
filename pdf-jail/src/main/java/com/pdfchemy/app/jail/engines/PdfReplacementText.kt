package com.pdfchemy.app.jail.engines

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.pdfchemy.app.security.BoundedOutputStream
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/** Native Unicode shaping for replacement runs, with an exact searchable Unicode text layer. */
internal class PdfReplacementText(private val document: PDDocument) {
    enum class Mode { EDIT, SEARCH_ONLY, IMAGE_OVERLAY }
    private val visibleFont by lazy {
        PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use {
            PDType0Font.load(document, it, false)
        }
    }
    private val codepoints = linkedMapOf<Int, Int>()
    private var searchFont: PDType0Font? = null
    private data class Picture(val image: PDImageXObject, val width: Int, val height: Int, val baseline: Int, val size: Float)
    private val pictures = linkedMapOf<String, Picture>()

    fun draw(content: PDPageContentStream, transform: Matrix, availableWidth: Float, text: String,
             mask: Boolean, background: Triple<Float, Float, Float>, color: Triple<Float, Float, Float>, emSize: Float, mode: Mode = Mode.EDIT) {
        content.saveGraphicsState()
        try {
            content.transform(transform)
            content.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = 1f; strokingAlphaConstant = 1f
                blendMode = com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode.NORMAL
                cosObject.setItem(COSName.SMASK, COSName.NONE)
            })
            if (mask && mode != Mode.SEARCH_ONLY) {
                content.setNonStrokingColor(background.first, background.second, background.third)
                content.addRect(0f, -.3f * emSize, availableWidth, 1.35f * emSize); content.fill()
            }
            if (text.isEmpty()) return
            content.setNonStrokingColor(color.first, color.second, color.third)
            if (mode == Mode.EDIT && !text.contains('\n') && !text.contains('\r') && runCatching { visibleFont.encode(text) }.isSuccess) {
                val width = visibleFont.getStringWidth(text) / 1000f * emSize
                val fit = min(1f, availableWidth / width.coerceAtLeast(.001f))
                beginText(content); content.setRenderingMode(RenderingMode.FILL)
                content.setFont(visibleFont, emSize * fit); content.setTextMatrix(Matrix())
                content.showText(text); content.endText()
                return
            }
            val picture = pictures[text] ?: picture(text, color).also { if (pictures.size < 64) pictures[text] = it }
            val naturalWidth = picture.width / picture.size * emSize
            val fit = minOf(1f, availableWidth / naturalWidth.coerceAtLeast(.001f), 1.35f * emSize / (picture.height / picture.size * emSize))
            val height = picture.height / picture.size * emSize * fit
            val bottom = -(picture.height - picture.baseline) / picture.size * emSize * fit
            if (mode != Mode.SEARCH_ONLY) content.drawImage(picture.image, Matrix(naturalWidth * fit, 0f, 0f, height, 0f, bottom))
            if (mode == Mode.IMAGE_OVERLAY) return
            // Rendering mode 3 is a normal OCR-style text layer. ToUnicode carries the exact
            // codepoints; no transliteration, missing-glyph substitutions or lossy image text.
            val font = searchFont ?: createSearchFont().also { searchFont = it }
            val codes = visualCodepoints(text)
            require(codes.none { it in 0xD800..0xDFFF }) { "Invalid Unicode replacement text" }
            val encoded = buildString {
                for (point in codes) {
                    val id = codepoints.getOrPut(point) { codepoints.size + 1 }
                    require(id < 65536) { "Replacement character quota exceeded" }
                    append(String.format(Locale.ROOT, "%04X", id))
                }
            }
            beginText(content); content.setRenderingMode(RenderingMode.NEITHER); content.setFont(font, emSize)
            content.setTextMatrix(Matrix(naturalWidth * fit / (codes.size * .5f * emSize).coerceAtLeast(.001f), 0f, 0f, fit, 0f, 0f))
            @Suppress("DEPRECATION")
            content.appendRawCommands("<$encoded> Tj\n")
            content.endText()
        } finally { content.restoreGraphicsState() }
    }

    private fun beginText(content: PDPageContentStream) {
        content.beginText()
        content.setCharacterSpacing(0f); content.setWordSpacing(0f)
        content.setHorizontalScaling(100f); content.setTextRise(0f)
    }

    private fun visualCodepoints(text: String): IntArray {
        val points = text.codePoints().toArray()
        val bidi = java.text.Bidi(text, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
        val levels = ByteArray(points.size)
        var offset = 0
        for (i in points.indices) {
            levels[i] = bidi.getLevelAt(offset).toByte()
            offset += Character.charCount(points[i])
        }
        val order = Array(points.size) { it }
        java.text.Bidi.reorderVisually(levels, 0, order, 0, order.size)
        return IntArray(points.size) { points[order[it]] }
    }

    private fun picture(text: String, color: Triple<Float, Float, Float>): Picture {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 128f
            this.color = Color.rgb((color.first * 255).toInt().coerceIn(0,255),
                (color.second * 255).toInt().coerceIn(0,255), (color.third * 255).toInt().coerceIn(0,255))
        }
        val desired = Layout.getDesiredWidth(text, paint).coerceAtLeast(1f)
        paint.textSize *= min(1f, (SecurityLimits.MAX_RENDER_DIMENSION - 4) / desired)
        val width = ceil(Layout.getDesiredWidth(text, paint).toDouble()).toInt().coerceIn(1, SecurityLimits.MAX_RENDER_DIMENSION)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
        SecurityLimits.requirePixels(width, layout.height)
        val bitmap = Bitmap.createBitmap(width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            layout.draw(Canvas(bitmap))
            return Picture(LosslessFactory.createFromImage(document, bitmap), width, layout.height, layout.getLineBaseline(0), paint.textSize)
        } finally { bitmap.recycle() }
    }

    private fun createSearchFont(): PDType0Font {
        // Reuse the bundled, embedded TrueType program. Invisible CIDs map to .notdef;
        // their Unicode/advance is defined by the explicit ToUnicode and width tables.
        val dictionary = COSDictionary(visibleFont.cosObject)
        val descendant = COSDictionary((visibleFont.cosObject.getDictionaryObject(COSName.DESCENDANT_FONTS) as COSArray).getObject(0) as COSDictionary)
        descendant.removeItem(COSName.W); descendant.removeItem(COSName.W2)
        descendant.setInt(COSName.DW, 500)
        dictionary.setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(descendant) })
        dictionary.removeItem(COSName.TO_UNICODE)
        dictionary.setItem(COSName.ENCODING, COSName.IDENTITY_H)
        return PDType0Font(dictionary)
    }

    fun finish() {
        val font = searchFont ?: return
        val descendant = (font.cosObject.getDictionaryObject(COSName.DESCENDANT_FONTS) as COSArray).getObject(0) as COSDictionary
        val mapping = document.document.createCOSStream()
        BoundedOutputStream(mapping.createOutputStream(COSName.FLATE_DECODE)).use { it.write(ByteArray((codepoints.size + 1) * 2)) }
        descendant.setItem(COSName.CID_TO_GID_MAP, mapping)
        val cmap = buildString {
            append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
            append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
            append("/CMapName /PDFchemyUnicodeSearch def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
            for (chunk in codepoints.entries.chunked(100)) {
                append("${chunk.size} beginbfchar\n")
                for ((point, id) in chunk) {
                    val unicode = String(Character.toChars(point)).toByteArray(Charsets.UTF_16BE).joinToString("") { "%02X".format(it.toInt() and 255) }
                    append("<${String.format(Locale.ROOT, "%04X", id)}> <$unicode>\n")
                }
                append("endbfchar\n")
            }
            append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        }
        val unicode = document.document.createCOSStream()
        BoundedOutputStream(unicode.createOutputStream(COSName.FLATE_DECODE)).use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
        font.cosObject.setItem(COSName.TO_UNICODE, unicode)
    }
}
