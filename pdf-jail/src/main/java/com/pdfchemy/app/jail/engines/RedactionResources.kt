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
