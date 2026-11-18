package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Collections
import java.util.IdentityHashMap

/** Identity-based cleanup: removing a standard key alone does not remove alternate references. */
internal object PdfPrivacyCleanup {
    private fun name(value: String) = COSName.getPDFName(value)
    private val metadataKeys = setOf(COSName.METADATA, name("PieceInfo"), COSName.INFO)
    private fun identities(): MutableSet<COSBase> = Collections.newSetFromMap(IdentityHashMap())

    private fun value(raw: COSBase?): COSBase? {
        var node = raw
        val references = identities()
        while (node is COSObject) {
            require(references.add(node) && references.size <= SecurityLimits.MAX_GRAPH_DEPTH) { "Invalid private object reference" }
            node = node.`object`
        }
        return node
    }

    private fun children(node: COSBase): List<Pair<COSName?, COSBase?>> = when (node) {
        is COSDictionary -> {
            require(node.size() <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive private dictionary" }
            node.keySet().map { it to node.getItem(it) }
        }
        is COSArray -> {
            require(node.size() <= SecurityLimits.MAX_GRAPH_NODES) { "Excessive private array" }
            (0 until node.size()).map { null to node.get(it) }
        }
        else -> emptyList()
    }

    private fun walk(roots: Iterable<COSBase?>, skip: Set<COSName> = emptySet(),
                     boundary: Set<COSBase> = emptySet()): Set<COSBase> {
        val seen = identities()
        fun visit(raw: COSBase?, depth: Int) {
            val node = value(raw) ?: return
            if (node in boundary || node !is COSDictionary && node !is COSArray && node !is COSString || !seen.add(node)) return
            require(depth <= SecurityLimits.MAX_GRAPH_DEPTH && seen.size <= SecurityLimits.MAX_GRAPH_NODES) { "Private graph quota exceeded" }
            for ((key, child) in children(node)) if (key !in skip) visit(child, depth + 1)
        }
        roots.forEach { visit(it, 0) }
        return seen
    }

    private fun graph(document: PDDocument): Set<COSBase> {
        require(document.document.objects.size <= SecurityLimits.MAX_GRAPH_NODES) { "PDF object quota exceeded" }
        return walk(listOf(document.document.trailer) + document.document.objects)
    }

    fun hasMetadata(document: PDDocument): Boolean = graph(document).any { node ->
        node is COSDictionary && (metadataKeys.any { key ->
            val payload = value(node.getItem(key))
            payload != null && payload !is COSNull && !(key == COSName.INFO && payload is COSDictionary && payload.size() == 0)
        } || node is COSStream && node.getCOSName(COSName.TYPE) == COSName.METADATA)
    }

    fun removeMetadata(document: PDDocument) {
        removePayloads(document, metadataKeys, setOf(COSName.METADATA))
        // PDFBox generates a fresh identifier on save; never retain the source identity.
        document.document.trailer.removeItem(COSName.ID)
    }

    private fun removePayloads(document: PDDocument, keys: Set<COSName>, streamTypes: Set<COSName>) {
        val nodes = graph(document)
        val seeds = identities()
        for (node in nodes) if (node is COSDictionary) {
            for (key in keys) value(node.getItem(key))?.let { seeds.add(it) }
            if (node is COSStream && node.getCOSName(COSName.TYPE) in streamTypes) seeds.add(node)
        }
        // Real structural identities are protected; a claimed /Type in opaque payload is not.
        val anchors = identities().apply { add(document.document.trailer); add(document.documentCatalog.cosObject) }
        for (page in document.pages) {
            var parent: COSDictionary? = page.cosObject
            var depth = 0
            while (parent != null && anchors.add(parent)) {
                require(++depth <= SecurityLimits.MAX_GRAPH_DEPTH && anchors.size <= SecurityLimits.MAX_GRAPH_NODES) { "Invalid page hierarchy" }
                if (parent.getCOSName(COSName.TYPE) == COSName.PAGES) value(parent.getItem(COSName.KIDS))?.let(anchors::add)
                parent = value(parent.getItem(COSName.PARENT)) as? COSDictionary
            }
        }
        // Validate before mutation, including private descendants that point at real pages.
        val removed = walk(seeds)
        require(removed.none { it in anchors }) { "Private payload references document hierarchy" }
        val retainedRoots = mutableListOf<COSBase?>()
        val pageKeys = listOf("Contents", "Resources", "Annots", "Thumb", "Group", "MediaBox", "CropBox", "BleedBox", "TrimBox", "ArtBox", "VP")
        for (anchor in anchors) if (anchor is COSDictionary && anchor !== document.documentCatalog.cosObject && anchor !== document.document.trailer) {
            pageKeys.forEach { retainedRoots.add(anchor.getItem(name(it))) }
        }
        for (key in listOf("Names", "AcroForm", "Outlines", "Dests", "OpenAction", "PageLabels", "OCProperties", "OutputIntents", "ViewerPreferences", "StructTreeRoot")) {
            retainedRoots.add(document.documentCatalog.cosObject.getItem(name(key)))
        }
        val retained = walk(retainedRoots, keys, anchors)
        require(removed.none { it in retained }) { "Private payload aliases retained document content" }
        for (node in nodes) when (node) {
            is COSDictionary -> for (key in node.keySet().toList()) {
                if (key in keys || value(node.getItem(key)) in removed) node.removeItem(key)
            }
            is COSArray -> for (index in node.size() - 1 downTo 0) {
                if (value(node.get(index)) in removed) node.remove(index)
            }
        }
    }
}
