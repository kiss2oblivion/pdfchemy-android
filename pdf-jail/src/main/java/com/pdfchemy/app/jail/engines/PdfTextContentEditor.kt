package com.pdfchemy.app.jail.engines

import android.graphics.RectF
import com.pdfchemy.app.logic.FindReplaceSummary
import com.pdfchemy.app.logic.TextMatchOccurrence
import com.pdfchemy.app.security.BoundedOutputStream
import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDTransparencyGroup
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.text.Normalizer
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

/** Edits encoded page text without flattening surrounding content, annotations or page boxes. */
internal class PdfTextContentEditor(
    private val editingDocument: PDDocument,
    query: String,
    private val matchCase: Boolean,
    private val replacement: String? = null,
    private val maskColor: Triple<Float, Float, Float> = Triple(1f, 1f, 1f),
    private val textColor: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    private val checkpoint: () -> Unit = {}
) : PDFTextStripper() {
    private val query = Normalizer.normalize(query, Normalizer.Form.NFC)
    private class GlyphGroup { val glyphs = mutableListOf<Glyph>() }
    private class TextBytes(val source: COSString) {
        val glyphs = mutableListOf<Glyph>()
    }
    private class Glyph(
        val bytes: TextBytes, val start: Int, val end: Int,
        val matrix: Matrix, val advance: Float, val vertical: Boolean,
        val clipping: Boolean, val invisible: Boolean,
        val textMatrix: Matrix, val lineMatrix: Matrix, val ctm: Matrix, val fontSize: Float, val horizontalScale: Float, val markedContent: List<Operation>
    ) {
        var group = GlyphGroup().also { it.glyphs.add(this) }
        var position: TextPosition? = null
        var removed = false
        var insertion: Placement? = null
    }
    private class Operation(val operator: Operator, val operands: List<COSBase>) {
        val strings: List<TextBytes> = when (operator.name) {
            "TJ" -> (operands.firstOrNull() as? COSArray)?.filterIsInstance<COSString>()?.map(::TextBytes).orEmpty()
            "Tj", "'", "\"" -> listOfNotNull((operands.lastOrNull() as? COSString)?.let(::TextBytes))
            else -> emptyList()
        }
        var nextString = 0
        var child: Invocation? = null
        var editedTag = false
    }
    private class Invocation(val source: PDContentStream, val resources: PDResources?) {
        val operations = mutableListOf<Operation>()
        fun changed(): Boolean = operations.any { op -> op.strings.any { b -> b.glyphs.any { it.removed } } || op.child?.changed() == true }
    }
    private data class Character(val value: Char, val glyph: Glyph?)
    private data class Range(val start: Int, val end: Int)
    private data class Placement(val matrix: Matrix, val width: Float, val text: String, val mask: Boolean)

    private val matches = mutableListOf<TextMatchOccurrence>()
    private val affectedPages = mutableSetOf<Int>()
    private var totalMatches = 0
    private var responseBytes = 1024
    private val responseEncoder by lazy { com.google.gson.Gson() }
    private val positions = IdentityHashMap<TextPosition, Glyph>()
    private val duplicateColumns = mutableMapOf<String, java.util.TreeMap<Float, MutableList<Glyph>>>()
    private val placements = mutableListOf<Placement>()
    private val markedContent = mutableListOf<Operation>()
    private val activeForms = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
    private val line = mutableListOf<Character>()
    private val pageGlyphs = mutableListOf<Glyph>()
    private var invocation: Invocation? = null
    private var currentOperation: Operation? = null
    private var operatorDepth = 0
    private var activeGlyph: Glyph? = null
    private var pageIndex = -1
    private var operationCount = 0
    private val painter by lazy { PdfReplacementText(editingDocument) }

    init {
        sortByPosition = true
        require(query.isNotBlank()) { "Search query cannot be blank" }
        SecurityLimits.enforceStringLength(query)
        replacement?.let { SecurityLimits.enforceStringLength(it) }
    }

    fun execute(): FindReplaceSummary {
        writeText(editingDocument, StringWriter())
        if (replacement != null && totalMatches > 0) {
            painter.finish()
            // Resource pruning also understands annotation appearances and inherited Forms.
            // Editing preserves annotations and metadata; secure erasure is a separate tool.
            RedactionResources.pruneUnused(editingDocument)
        }
        return FindReplaceSummary(totalMatches, affectedPages.size, matches.toList())
    }

    override fun processPage(page: PDPage) {
        checkpoint()
        pageIndex = (currentPageNo - 1).coerceAtLeast(0)
        positions.clear(); line.clear(); pageGlyphs.clear(); duplicateColumns.clear(); placements.clear()
        activeForms.clear(); markedContent.clear(); operationCount = 0
        val root = Invocation(page, page.resources)
        invocation = root
        super.processPage(page)
        flushLine()
        if (replacement != null && root.changed()) {
            pageGlyphs.filter { it.removed }.forEach { g -> g.markedContent.forEach { it.editedTag = true } }
            val resources = copyResources(root.resources)
            val stream = PDStream(editingDocument)
            BoundedOutputStream(stream.createOutputStream(COSName.FLATE_DECODE)).use { writeInvocation(root, resources, ContentStreamWriter(it)) }
            page.resources = resources
            page.setContents(stream)
            // Hidden OCR text can precede the scanned image. Paint its visual edit last,
            // while the sole searchable replacement stays at its original reading position.
            if (placements.any { it.mask }) PDPageContentStream(editingDocument, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                for (p in placements.filter { it.mask }) painter.draw(content, p.matrix, p.width, p.text,
                    true, maskColor, textColor, 1f, PdfReplacementText.Mode.IMAGE_OVERLAY)
            }

        }
        invocation = null
    }

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>?) {
        val actualOperands = operands ?: mutableListOf()
        checkpoint()
        val outer = operatorDepth == 0
        val previous = currentOperation
        if (outer) {
            require(++operationCount <= SecurityLimits.MAX_GRAPH_NODES) { "Page content exceeds the editing quota" }
            currentOperation = Operation(operator, actualOperands.toList()).also { operation ->
                invocation?.operations?.add(operation)
                when (operator.name) {
                    "BMC", "BDC" -> markedContent.add(operation)
                    "EMC" -> if (markedContent.isNotEmpty()) markedContent.removeAt(markedContent.lastIndex)
                }
            }
        }
        operatorDepth++
        try { super.processOperator(operator, actualOperands) }
        finally { operatorDepth--; if (outer) currentOperation = previous }
    }

    override fun showForm(form: PDFormXObject) = visitForm(form) { super.showForm(form) }
    override fun showTransparencyGroup(group: PDTransparencyGroup) = visitForm(group) { super.showTransparencyGroup(group) }

    private fun visitForm(form: PDFormXObject, action: () -> Unit) {
        require(activeForms.size < SecurityLimits.MAX_GRAPH_DEPTH && activeForms.add(form.cosObject)) { "Cyclic or excessive Form nesting" }
        val previousMarks = markedContent.toList()
        val parent = invocation
        val parentOperation = currentOperation
        val depth = operatorDepth
        val child = Invocation(form, form.resources ?: parent?.resources)
        parentOperation?.child = child
        invocation = child; operatorDepth = 0; currentOperation = null
        try { action() }
        finally { invocation = parent; currentOperation = parentOperation; operatorDepth = depth; activeForms.remove(form.cosObject); markedContent.clear(); markedContent.addAll(previousMarks) }
    }

    override fun showText(string: ByteArray) {
        val operation = currentOperation ?: error("Text outside a recorded operator")
        val bytes = operation.strings.getOrNull(operation.nextString++) ?: error("Invalid text operator")
        require(bytes.source.bytes.contentEquals(string)) { "Text provenance mismatch" }
        val font = graphicsState.textState.font ?: PDType1Font.HELVETICA
        val input = ByteArrayInputStream(string)
        val encodedGlyphs = mutableListOf<Triple<Int, Int, Int>>()
        while (input.available() > 0) {
            val start = string.size - input.available()
            val code = font.readCode(input)
            val end = string.size - input.available()
            require(end > start) { "Font decoder made no progress" }
            encodedGlyphs.add(Triple(start, end, code))
        }
        pendingGlyphs = encodedGlyphs
        pendingBytes = bytes
        pendingIndex = 0
        try { super.showText(string) }
        finally { pendingGlyphs = emptyList(); pendingBytes = null; activeGlyph = null }
    }
    private var pendingGlyphs: List<Triple<Int, Int, Int>> = emptyList()
    private var pendingBytes: TextBytes? = null
    private var pendingIndex = 0

    override fun showGlyph(matrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        if (pageGlyphs.size % 128 == 0) checkpoint()
        val (start, end, expectedCode) = pendingGlyphs[pendingIndex++]
        require(code == expectedCode) { "Glyph provenance mismatch" }
        val state = graphicsState.textState
        val wordSpacing = if (end - start == 1 && code == 32) state.wordSpacing else 0f
        val spacing = state.characterSpacing + wordSpacing
        val advance = (if (font.isVertical) displacement.y else displacement.x) +
            if (spacing == 0f) 0f else spacing / state.fontSize
        val bytes = requireNotNull(pendingBytes)
        val glyph = Glyph(bytes, start, end, matrix.clone(), advance, font.isVertical,
            state.renderingMode.isClip, !(state.renderingMode.isFill && graphicsState.nonStrokeAlphaConstant > 0.0) && !(state.renderingMode.isStroke && graphicsState.alphaConstant > 0.0),
            textMatrix.clone(), textLineMatrix.clone(), graphicsState.currentTransformationMatrix.clone(),
            state.fontSize, state.horizontalScaling / 100f, markedContent.toList())
        bytes.glyphs.add(glyph); pageGlyphs.add(glyph)
        require(pageGlyphs.size <= SecurityLimits.MAX_GRAPH_NODES) { "Page text exceeds the editing quota" }
        activeGlyph = glyph
        try { super.showGlyph(matrix, font, code, displacement) }
        finally { activeGlyph = null }
    }

    private fun link(a: Glyph, b: Glyph) {
        if (a.group === b.group) return
        val target = if (a.group.glyphs.size >= b.group.glyphs.size) a.group else b.group
        val source = if (target === a.group) b.group else a.group
        source.glyphs.forEach { it.group = target }
        target.glyphs.addAll(source.glyphs); source.glyphs.clear()
    }

    override fun processTextPosition(text: TextPosition) {
        val glyph = activeGlyph
        val originalUnicode = text.unicode.orEmpty()
        // Mirror the extractor's overlap tolerance so every suppressed overpaint is
        // edited too; an ignored OCR/shadow copy must not retain the original word.
        if (glyph != null) {
            positions[text] = glyph; glyph.position = text
            val columns = duplicateColumns.getOrPut(originalUnicode) { java.util.TreeMap() }
            val tolerance = text.width / originalUnicode.length.coerceAtLeast(1) / 3f
            val duplicate = columns.subMap(text.x - tolerance, text.x + tolerance).values.asSequence().flatten()
                .firstOrNull { p -> p.position?.let { it.y >= text.y - tolerance && it.y < text.y + tolerance } == true }
            if (duplicate != null) link(duplicate, glyph)
            else columns.getOrPut(text.x) { mutableListOf() }.add(glyph)
        }
        val previous = charactersByArticle.mapNotNull { it.lastOrNull() }.map { it to it.unicode }
        super.processTextPosition(text)
        if (glyph != null) for ((before, unicode) in previous) {
            if (before.unicode != unicode || (text.unicode != originalUnicode && before.isDiacritic && text.contains(before))) {
                positions[before]?.let { link(glyph, it) }
            }
        }
    }

    private fun canonicalCharacters(characters: List<Character>): List<Character> {
        val raw = characters.joinToString("") { it.value.toString() }
        val iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT)
        iterator.setText(raw)
        val result = mutableListOf<Character>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            val original = raw.substring(start, end)
            val normalized = Normalizer.normalize(original, Normalizer.Form.NFC)
            if (original == normalized) result.addAll(characters.subList(start, end))
            else {
                val glyphs = characters.subList(start, end).mapNotNull { it.glyph }.distinct()
                val owner = glyphs.firstOrNull()
                if (owner != null) glyphs.drop(1).forEach { link(owner, it) }
                result.addAll(normalized.map { Character(it, owner) })
            }
            start = end; end = iterator.next()
        }
        return result
    }

    private fun normalized(text: String): String = buildString {
        for (c in text) if (c in '\uFB00'..'\uFDFF' || c in '\uFE70'..'\uFEFF')
            append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC).trim()) else append(c)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        val characters = textPositions.flatMap { position -> normalized(position.unicode.orEmpty()).map { Character(it, positions[position]) } }
        val actual = characters.joinToString("") { it.value.toString() }
        val rawMapped = when {
            actual == text -> characters
            actual.reversed() == text -> characters.reversed()
            else -> {
                // Preserve provenance when PDFBox normalizes bidirectional/presentation text.
                val available = characters.groupBy { it.value }.mapValues { (_, values) -> java.util.ArrayDeque(values) }
                text.map { c -> available[c]?.pollFirst() ?: Character(c, null) }
            }
        }
        val mapped = canonicalCharacters(rawMapped)
        if (line.lastOrNull()?.let { it.value == ' ' && it.glyph == null } == true) {
            val previous = line.asReversed().firstOrNull { it.glyph != null }?.glyph
            val next = mapped.firstOrNull { it.glyph != null }?.glyph
            val adjacent = if (previous != null && next != null) {
                val end = previous.matrix.transformPoint(if (previous.vertical) 0f else previous.advance,
                    if (previous.vertical) previous.advance else 0f)
                val tolerance = maxOf(.001f, previous.matrix.scalingFactorX * .02f)
                abs(end.x - next.matrix.translateX) <= tolerance && abs(end.y - next.matrix.translateY) <= tolerance
            } else false
            if (adjacent || mapped.firstOrNull()?.value?.isWhitespace() == true) line.removeAt(line.lastIndex)
        }
        line.addAll(mapped)
        require(line.size <= SecurityLimits.MAX_TEXT_BYTES) { "Text line exceeds the editing quota" }
    }
    override fun writeWordSeparator() { if (line.lastOrNull()?.value?.isWhitespace() != true) line.add(Character(' ', null)) }
    override fun writeLineSeparator() { flushLine() }
    override fun writeParagraphEnd() { flushLine() }
    override fun writePageEnd() { flushLine() }

    private fun flushLine() {
        if (line.isEmpty()) return
        val text = line.joinToString("") { it.value.toString() }
        val ranges = mutableListOf<Range>()
        var offset = 0
        while (offset <= text.length - query.length) {
            val found = text.indexOf(query, offset, ignoreCase = !matchCase)
            if (found < 0) break
            val end = found + query.length
            val glyphs = line.subList(found, end).mapNotNull { it.glyph }.distinct()
            require(glyphs.isNotEmpty()) { "Cannot map matching text to page glyphs" }
            val p = glyphs.mapNotNull { it.position }
            require(p.isNotEmpty()) { "Cannot locate matching text" }
            val left = p.minOf { it.xDirAdj }; val top = p.minOf { it.yDirAdj }
            val right = p.maxOf { it.xDirAdj + it.widthDirAdj }
            val height = p.maxOf { it.heightDir }.coerceAtLeast(1f)
            val occurrence = TextMatchOccurrence(pageIndex, text.substring(found, end),
                text.substring((found - 20).coerceAtLeast(0), (end + 20).coerceAtMost(text.length)),
                RectF(left, top, right, top + height), p.map { it.fontSizeInPt }.average().toFloat())
            totalMatches = Math.addExact(totalMatches, 1)
            affectedPages.add(pageIndex)
            if (replacement == null) {
                val size = responseEncoder.toJson(occurrence).toByteArray(Charsets.UTF_8).size + 1
                require(size <= SecurityLimits.MAX_JSON_RESPONSE_SIZE - responseBytes) { "Search results exceed the response quota; use a more specific query" }
                responseBytes += size
                matches.add(occurrence)
            }
            ranges.add(Range(found, end)); offset = end
        }
        if (replacement != null && ranges.isNotEmpty()) {
            // Expand partial ligatures, then merge overlapping physical glyph spans.
            val expanded = mutableListOf<Range>()
            for (range in ranges) {
                var start = range.start; var end = range.end
                val first = line[start].glyph; val last = line[end - 1].glyph
                while (first != null && start > 0 && line[start - 1].glyph === first) start--
                while (last != null && end < line.size && line[end].glyph === last) end++
                val previous = expanded.lastOrNull()
                if (previous != null && start < previous.end) expanded[expanded.lastIndex] = Range(previous.start, maxOf(previous.end, end))
                else expanded.add(Range(start, end))
            }
            for (span in expanded) {
                val glyphs = line.subList(span.start, span.end).mapNotNull { it.glyph }.distinct()
                require(glyphs.flatMap { it.group.glyphs }.none { it.clipping }) { "Matching text is a clipping path; this document cannot be edited without changing other content" }
                require(glyphs.flatMap { it.group.glyphs }.all { it.advance.isFinite() }) { "Invalid text advance" }
                val newText = buildString {
                    var next = span.start
                    for (range in ranges.filter { it.start >= span.start && it.end <= span.end }) {
                        append(text.substring(next, range.start)); append(replacement); next = range.end
                    }
                    append(text.substring(next, span.end))
                }
                glyphs.forEach { it.group.glyphs.forEach { physical -> physical.removed = true } }
                // Put the new run at the first original paint operator, preserving source
                // reading order as well as the positions of all subsequent characters.
                val placement = placement(glyphs, newText)
                pageGlyphs.first { it in glyphs }.insertion = placement
                placements.add(placement)
            }
        }
        line.clear()
    }

    private fun placement(glyphs: List<Glyph>, text: String): Placement {
        val first = glyphs.first(); val m = first.matrix
        val a = m.getValue(0, 0); val b = m.getValue(0, 1)
        val c = m.getValue(1, 0); val d = m.getValue(1, 1)
        val det = a * d - b * c
        require(abs(det) > 0.000001f) { "Matching text has a singular transform" }
        fun local(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - m.translateX; val dy = y - m.translateY
            return (d * dx - c * dy) / det to (-b * dx + a * dy) / det
        }
        val physical = glyphs.flatMap { it.group.glyphs }.distinct()
        val ends = physical.flatMap { g ->
            val end = g.matrix.transformPoint(if (g.vertical) 0f else g.advance, if (g.vertical) g.advance else 0f)
            listOf(local(g.matrix.translateX, g.matrix.translateY), local(end.x, end.y))
        }
        val vertical = first.vertical
        val min = ends.minOf { if (vertical) it.second else it.first }
        val max = ends.maxOf { if (vertical) it.second else it.first }
        val anchor = m.transformPoint(if (vertical) 0f else min, if (vertical) max else 0f)
        val placed = if (vertical) Matrix(-c, -d, a, b, anchor.x, anchor.y) else Matrix(a, b, c, d, anchor.x, anchor.y)
        return Placement(placed, (max - min).coerceAtLeast(.05f), text, physical.any { it.invisible })
    }

    private fun edited(bytes: TextBytes): List<Any> {
        if (bytes.glyphs.none { it.removed }) return listOf(bytes.source)
        val result = mutableListOf<Any>()
        val raw = bytes.source.bytes
        var start = 0
        for (glyph in bytes.glyphs.filter { it.removed }) {
            if (glyph.start > start) result.add(COSString(raw.copyOfRange(start, glyph.start)))
            if (glyph.insertion != null) result.add(glyph)
            result.add(COSFloat(-1000f * glyph.advance))
            start = glyph.end
        }
        if (start < raw.size) result.add(COSString(raw.copyOfRange(start, raw.size)))
        return result
    }

    private fun writeInvocation(node: Invocation, resources: PDResources, writer: ContentStreamWriter) {
        for (operation in node.operations) {
            val child = operation.child
            if (child?.changed() == true) {
                val source = child.source as PDFormXObject
                val clone = PDFormXObject(editingDocument)
                for (key in source.cosObject.keySet()) if (key !in setOf(COSName.LENGTH, COSName.FILTER, COSName.DECODE_PARMS, COSName.RESOURCES))
                    clone.cosObject.setItem(key, source.cosObject.getItem(key))
                val childResources = copyResources(child.resources)
                clone.resources = childResources
                BoundedOutputStream(clone.cosObject.createOutputStream(COSName.FLATE_DECODE)).use { writeInvocation(child, childResources, ContentStreamWriter(it)) }
                val name = resources.add(clone)
                writer.writeTokens(listOf(name, operation.operator))
                continue
            }
            if (operation.editedTag && operation.operator.name == "BDC") {
                writer.writeTokens(editedTagOperands(operation, resources) + operation.operator)
                continue
            }
            if (operation.strings.none { bytes -> bytes.glyphs.any { it.removed } }) {
                writer.writeTokens(operation.operands + operation.operator); continue
            }
            val elements = mutableListOf<Any>()
            if (operation.operator.name == "TJ") {
                var index = 0
                for (item in operation.operands.first() as COSArray) {
                    if (item is COSString) elements.addAll(edited(operation.strings[index++])) else elements.add(item)
                }
            } else elements.addAll(edited(operation.strings.single()))
            when (operation.operator.name) {
                "'" -> writer.writeToken(Operator.getOperator("T*"))
                "\"" -> {
                    writer.writeTokens(listOf(operation.operands[0], Operator.getOperator("Tw")))
                    writer.writeTokens(listOf(operation.operands[1], Operator.getOperator("Tc")))
                    writer.writeToken(Operator.getOperator("T*"))
                }
            }
            var pending = COSArray()
            for (element in elements) {
                if (element is Glyph) {
                    if (pending.size() > 0) writer.writeTokens(listOf(pending, Operator.getOperator("TJ")))
                    pending = COSArray()
                    writeInsertion(element, resources, writer)
                } else pending.add(element as COSBase)
            }
            if (pending.size() > 0) writer.writeTokens(listOf(pending, Operator.getOperator("TJ")))
        }
    }

    private fun editedTagOperands(operation: Operation, resources: PDResources): List<COSBase> {
        val value = operation.operands.lastOrNull()
        val properties = when (value) {
            is COSDictionary -> value
            is COSName -> resources.getProperties(value)?.cosObject
            else -> null
        } ?: return operation.operands
        val originalActual = properties.getString(COSName.ACTUAL_TEXT) ?: return operation.operands
        val actual = Normalizer.normalize(originalActual, Normalizer.Form.NFC)
        val updated = buildString {
            var from = 0
            while (true) {
                val found = actual.indexOf(query, from, ignoreCase = !matchCase)
                if (found < 0) { append(actual.substring(from)); break }
                append(actual.substring(from, found)); append(replacement.orEmpty()); from = found + query.length
            }
        }
        if (actual == updated) return operation.operands
        val copy = COSDictionary(properties).apply { setString(COSName.ACTUAL_TEXT, updated) }
        val target: COSBase = if (value is COSName) resources.add(
            com.tom_roush.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList.create(copy)) else copy
        return operation.operands.dropLast(1) + target
    }

    private fun writeInsertion(glyph: Glyph, resources: PDResources, writer: ContentStreamWriter) {
        val placement = requireNotNull(glyph.insertion)
        val form = PDFormXObject(editingDocument).apply {
            this.resources = PDResources()
            // Replacement shaping is fitted to at most 1.35 em in height.
            bBox = com.tom_roush.pdfbox.pdmodel.common.PDRectangle(-.5f * glyph.fontSize, -1.5f * glyph.fontSize, (placement.width + 1f) * glyph.fontSize, 3f * glyph.fontSize)
        }
        PDPageContentStream(editingDocument, form, form.cosObject.createOutputStream(COSName.FLATE_DECODE)).use { content ->
            painter.draw(content, Matrix(), placement.width * glyph.fontSize, placement.text, false, maskColor, textColor, glyph.fontSize,
                if (placement.mask) PdfReplacementText.Mode.SEARCH_ONLY else PdfReplacementText.Mode.EDIT)
        }
        val name = resources.add(form)
        fun matrixTokens(matrix: Matrix): List<COSBase> = listOf(
            COSFloat(matrix.getValue(0, 0)), COSFloat(matrix.getValue(0, 1)),
            COSFloat(matrix.getValue(1, 0)), COSFloat(matrix.getValue(1, 1)),
            COSFloat(matrix.translateX), COSFloat(matrix.translateY))
        val local = Matrix.getScaleInstance(1f / glyph.fontSize, 1f / glyph.fontSize).multiply(placement.matrix).multiply(inverse(glyph.ctm))
        writer.writeToken(Operator.getOperator("ET"))
        writer.writeToken(Operator.getOperator("q"))
        writer.writeTokens(matrixTokens(local) + Operator.getOperator("cm"))
        writer.writeTokens(listOf(name, Operator.getOperator("Do")))
        writer.writeToken(Operator.getOperator("Q"))
        writer.writeToken(Operator.getOperator("BT"))
        // Tm sets both matrices. Restore the line matrix first, then reproduce just
        // its inline advance with TJ so following T* / apostrophe operators keep
        // their original baseline instead of inheriting the insertion position.
        writer.writeTokens(matrixTokens(glyph.lineMatrix) + Operator.getOperator("Tm"))
        val delta = glyph.textMatrix.multiply(inverse(glyph.lineMatrix))
        val shift = if (glyph.vertical) delta.translateY / glyph.fontSize
            else delta.translateX / (glyph.fontSize * glyph.horizontalScale)
        require(shift.isFinite()) { "Invalid text restoration transform" }
        writer.writeTokens(listOf(COSArray().apply { add(COSFloat(-1000f * shift)) }, Operator.getOperator("TJ")))
    }

    private fun inverse(matrix: Matrix): Matrix {
        val a = matrix.getValue(0, 0); val b = matrix.getValue(0, 1)
        val c = matrix.getValue(1, 0); val d = matrix.getValue(1, 1)
        val x = matrix.translateX; val y = matrix.translateY
        val determinant = a * d - b * c
        require(abs(determinant) > .000001f) { "Singular content transform" }
        return Matrix(d / determinant, -b / determinant, -c / determinant, a / determinant,
            (c * y - d * x) / determinant, (b * x - a * y) / determinant)
    }

    private fun copyResources(original: PDResources?): PDResources {
        val copy = original?.cosObject?.let(::COSDictionary) ?: COSDictionary()
        for (key in copy.keySet().toList()) (copy.getDictionaryObject(key) as? COSDictionary)?.let { copy.setItem(key, COSDictionary(it)) }
        return PDResources(copy)
    }
}
