package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import java.util.Collections
import java.util.IdentityHashMap

/** Removes original resources instead of merely hiding them behind replacement content. */
internal object RedactionResources {
    /** A used stream can contain invisible data outside another page's clipping area. */
    class RasterizationPlan internal constructor(
        val pages: Set<Int>,
        private val originals: Set<COSStream>
    ) {
        fun requireOriginalsDetached(document: PDDocument) {
            visitGraph(document.document.trailer) {
                require(it !is COSStream || it !in originals) {
                    "Original redacted stream remains reachable outside replaced pages"
                }
            }
        }
    }

    fun rasterizationPlan(document: PDDocument, redacted: Set<Int>, deleted: Set<Int>): RasterizationPlan {
        // Remove unused inherited resources first: sharing an unused resource does
        // not require destroying an otherwise independent page's vector content.
        pruneUnused(document)
        val observed = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        var streamReferences = 0
        val streams = document.pages.map { page ->
            Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>()).apply {
                visitGraph(page.cosObject, page.cosObject) {
                    observed.add(it)
                    require(observed.size <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive page resource graph" }
                    if (it is COSStream) add(it)
                }
                streamReferences += size
                require(streamReferences <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive shared stream graph" }
            }
        }
        val selected = redacted.filterTo(mutableSetOf()) { it in streams.indices && it !in deleted }
        val originals = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
        selected.forEach { originals.addAll(streams[it]) }
        // Expand to a fixed point: rasterizing a dependent page also replaces its
        // other original streams, which must not survive through a third page.
        do {
            var changed = false
            for (index in streams.indices) {
                if (index !in selected && index !in deleted && streams[index].any { it in originals }) {
                    selected.add(index)
                    originals.addAll(streams[index])
                    changed = true
                }
            }
        } while (changed)
        return RasterizationPlan(selected, originals)
    }

    private fun visitGraph(root: COSBase, pageRoot: COSDictionary? = null, visit: (COSBase) -> Unit) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun walk(raw: COSBase?, depth: Int) {
            val node = dereference(raw) ?: return
            if (node !is COSDictionary && node !is COSArray || !seen.add(node)) return
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && seen.size <= SecurityLimits.MAX_GRAPH_NODES) {
                "Document graph exceeds redaction limit"
            }
            // A page's Parent/P/destination references must not pull in the entire
            // page tree when computing its own stream dependencies.
            if (pageRoot != null && node is COSDictionary && node !== pageRoot &&
                node.getCOSName(COSName.TYPE) in listOf(COSName.PAGE, COSName.PAGES)) return
            visit(node)
            when (node) {
                is COSDictionary -> {
                    require(node.size() <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive dictionary" }
                    for (key in node.keySet()) {
                        if (pageRoot == null || key != COSName.PARENT) walk(node.getItem(key), depth + 1)
                    }
                }
                is COSArray -> {
                    require(node.size() <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive array" }
                    for (index in 0 until node.size()) walk(node.get(index), depth + 1)
                }
            }
        }
        walk(root, 0)
    }

    /** Form values live outside page resources; preserve their visible appearances, then detach data. */
    fun prepareForRedaction(document: PDDocument) {
        val catalog = document.documentCatalog
        val form = catalog.getAcroForm(null) ?: return
        require(!form.hasXFA()) { "Secure redaction does not support XFA forms" }
        val removed = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun collect(raw: COSBase?, depth: Int) {
            val field = dereference(raw) as? COSDictionary
                ?: throw IllegalArgumentException("Invalid form field")
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && removed.add(field) &&
                removed.size <= SecurityLimits.MAX_GRAPH_NODES) { "Invalid or excessive form hierarchy" }
            val children = field.getDictionaryObject(COSName.KIDS)
            require(children == null || children is COSArray) { "Invalid form children" }
            if (children is COSArray) for (index in 0 until children.size()) collect(children.get(index), depth + 1)
        }
        val roots = form.cosObject.getDictionaryObject(COSName.FIELDS)
        require(roots == null || roots is COSArray) { "Invalid form roots" }
        if (roots is COSArray) for (index in 0 until roots.size()) collect(roots.get(index), 0)
        removed.add(form.cosObject)

        // Flatten before replacing any page: doing it afterward could paint a private
        // widget back over the redaction. Other pages keep static, selectable values.
        val fields = form.fieldTree.toList()
        // PDFBox flattening checks Hidden/Invisible but not NoView. Never bake a
        // screen-hidden field's private appearance into visible page content.
        for (field in fields) if (field is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField) {
            for (widget in field.widgets) if (widget.isNoView) widget.isHidden = true
        }
        val missing = fields.filter { field ->
            field is com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField && field.widgets.any {
                !it.isHidden && !it.isInvisible && it.normalAppearanceStream == null
            }
        }
        if (form.needAppearances) form.refreshAppearances(fields)
        else if (missing.isNotEmpty()) form.refreshAppearances(missing)
        form.flatten(fields, false)
        catalog.cosObject.removeItem(COSName.ACRO_FORM)

        // Actions/calculation arrays can reference detached fields independently of
        // AcroForm. Remove those references throughout the reachable document graph.
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        fun detach(raw: COSBase?, depth: Int) {
            val node = dereference(raw) ?: return
            if (node !is COSDictionary && node !is COSArray) return
            if (!seen.add(node)) return
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && seen.size <= SecurityLimits.MAX_GRAPH_NODES) {
                "Document graph exceeds form-sanitization limit"
            }
            when (node) {
                is COSDictionary -> for (key in node.keySet().toList()) {
                    val child = dereference(node.getItem(key))
                    if (child != null && child in removed) node.removeItem(key)
                    else detach(child, depth + 1)
                }
                is COSArray -> for (index in node.size() - 1 downTo 0) {
                    val child = dereference(node.get(index))
                    if (child != null && child in removed) node.remove(index)
                    else detach(child, depth + 1)
                }
            }
        }
        detach(document.document.trailer, 0)
    }

    private fun dereference(raw: COSBase?): COSBase? {
        if (raw !is COSObject) return raw
        var value = raw
        val seen = Collections.newSetFromMap(IdentityHashMap<COSObject, Boolean>())
        while (value is COSObject) {
            require(seen.add(value) && seen.size <= SecurityLimits.MAX_GRAPH_DEPTH) { "Invalid object reference" }
            value = value.`object`
        }
        return value
    }

    fun replacePageContent(document: PDDocument, page: PDPage, draw: (PDPageContentStream) -> Unit) {
        // Do not reuse the original (possibly inherited/shared) resource dictionary.
        page.resources = PDResources()
        for (key in listOf("Annots", "Thumb", "Metadata", "PieceInfo", "AA", "AF")) {
            page.cosObject.removeItem(COSName.getPDFName(key))
        }
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.OVERWRITE, false, false).use(draw)
    }

    /** Each page gets explicit, used-only resources before inherited originals are removed. */
    fun pruneUnused(document: PDDocument) {
        val visited = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
        var visitedStreams = 0
        fun filtered(content: PDContentStream, resources: PDResources?, depth: Int = 0): PDResources {
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH) { "Resource graph exceeds depth limit" }
            val result = COSDictionary()
            if (resources == null) return PDResources(result)
            val used = mutableMapOf<String, MutableSet<COSName>>()
            val inheritedForms = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
            fun use(category: String, name: COSName?) {
                if (name != null) used.getOrPut(category) { mutableSetOf() }.add(name)
            }
            fun collect(content: PDContentStream, inheritedDepth: Int) {
                require(inheritedDepth <= SecurityLimits.MAX_GRAPH_DEPTH) { "Resource graph exceeds depth limit" }
                val parser = PDFStreamParser(content)
                parser.parse()
                val operands = mutableListOf<COSBase>()
                for (token in parser.tokens) {
                    if (token is Operator) {
                        when (token.name) {
                            "Do" -> {
                                val name = operands.lastOrNull() as? COSName
                                use("XObject", name)
                                val form = name?.let { resources.getXObject(it) } as? PDFormXObject
                                // Resource-less Forms resolve names in their invocation's resource scope.
                                if (form != null && form.resources == null && inheritedForms.add(form.cosObject)) {
                                    require(++visitedStreams <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph exceeds limit" }
                                    collect(form, inheritedDepth + 1)
                                }
                            }
                            "Tf" -> use("Font", operands.firstOrNull() as? COSName)
                            "gs" -> use("ExtGState", operands.lastOrNull() as? COSName)
                            "CS", "cs" -> use("ColorSpace", operands.lastOrNull() as? COSName)
                            "SCN", "scn" -> use("Pattern", operands.lastOrNull() as? COSName)
                            "sh" -> use("Shading", operands.lastOrNull() as? COSName)
                            "BDC", "DP" -> use("Properties", operands.lastOrNull() as? COSName)
                            "BI" -> use("ColorSpace", token.imageParameters?.getDictionaryObject(
                                COSName.getPDFName("CS"), COSName.COLORSPACE) as? COSName)
                        }
                        operands.clear()
                    } else if (token is COSBase) operands.add(token)
                }
            }
            collect(content, depth)
            // Default device-space substitutions can be used implicitly by paint/image operators.
            for (name in listOf("DefaultGray", "DefaultRGB", "DefaultCMYK")) {
                use("ColorSpace", COSName.getPDFName(name))
            }
            if (content is PDPage) for (annotation in content.annotations) {
                val ap = annotation.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary ?: continue
                for (key in ap.keySet()) {
                    val entry = ap.getDictionaryObject(key)
                    val streams = when (entry) {
                        is COSStream -> listOf(entry)
                        is COSDictionary -> entry.keySet().mapNotNull { entry.getDictionaryObject(it) as? COSStream }
                        else -> emptyList()
                    }
                    for (stream in streams) {
                        val form = PDFormXObject(stream)
                        val own = form.resources
                        if (own == null && inheritedForms.add(stream)) collect(form, depth + 1)
                        else if (own != null && visited.add(stream)) {
                            require(++visitedStreams <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph exceeds limit" }
                            form.resources = filtered(form, own, depth + 1)
                        }
                    }
                }
            }
            val processedStates = mutableSetOf<COSName>()
            while (true) {
                val pending = used["ExtGState"].orEmpty().filter { processedStates.add(it) }
                if (pending.isEmpty()) break
                val states = resources.cosObject.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary ?: break
                for (name in pending) {
                    val state = states.getDictionaryObject(name) as? COSDictionary ?: continue
                    val mask = state.getDictionaryObject(COSName.SMASK) as? COSDictionary ?: continue
                    val group = mask.getDictionaryObject(COSName.G) as? COSStream ?: continue
                    val form = PDFormXObject(group)
                    val own = form.resources
                    if (own == null && inheritedForms.add(group)) collect(form, depth + 1)
                    else if (own != null && visited.add(group)) {
                        require(++visitedStreams <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph exceeds limit" }
                        form.resources = filtered(form, own, depth + 1)
                    }
                }
            }
            for ((category, names) in used) {
                val key = COSName.getPDFName(category)
                val original = resources.cosObject.getDictionaryObject(key) as? COSDictionary ?: continue
                val entries = COSDictionary()
                for (name in names) original.getItem(name)?.let { entries.setItem(name, it) }
                if (entries.size() > 0) result.setItem(key, entries)
            }
            val xobjects = result.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
            for (name in xobjects?.keySet().orEmpty()) {
                val stream = xobjects?.getDictionaryObject(name) as? COSStream ?: continue
                if (stream.getCOSName(COSName.SUBTYPE) == COSName.FORM && visited.add(stream)) {
                    require(++visitedStreams <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph exceeds limit" }
                    val form = PDFormXObject(stream)
                    form.resources?.let { form.resources = filtered(form, it, depth + 1) }
                }
            }
            val patterns = result.getDictionaryObject(COSName.PATTERN) as? COSDictionary
            for (name in patterns?.keySet().orEmpty()) {
                val stream = patterns?.getDictionaryObject(name) as? COSStream ?: continue
                if (stream.getInt(COSName.PATTERN_TYPE) == 1 && visited.add(stream)) {
                    require(++visitedStreams <= SecurityLimits.MAX_GRAPH_NODES) { "Resource graph exceeds limit" }
                    val pattern = com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern(stream)
                    pattern.resources = filtered(pattern, requireNotNull(pattern.resources) { "Missing tiling-pattern resources" }, depth + 1)
                }
            }
            return PDResources(result)
        }
        for (page in document.pages) page.resources = filtered(page, page.resources)
        // Explicit resources above prevent inherited dictionaries retaining unused originals.
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun clearInherited(node: COSDictionary, depth: Int = 0) {
            if (!seen.add(node)) return
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && seen.size <= SecurityLimits.MAX_GRAPH_NODES) { "Page tree exceeds limit" }
            val kids = node.getDictionaryObject(COSName.KIDS) as? COSArray ?: return
            node.removeItem(COSName.RESOURCES)
            for (i in 0 until kids.size()) {
                (kids.getObject(i) as? COSDictionary)?.let { clearInherited(it, depth + 1) }
            }
        }
        clearInherited(document.pages.cosObject)
    }
}
