package com.pdfchemy.app.jail.engines

import com.pdfchemy.app.security.SecurityLimits
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Inspect the actual ordered Kids hierarchy, independently of cached /Count.
 * Only correct /Count; never invent missing pages, guess parentage, reorder children
 * or infer objects from byte-pattern matches. All traversal stays in the jail.
 */
internal object PageTreeRecovery {
    private data class CountCorrection(val dictionary: COSDictionary, val count: Int)
    data class Result(val pages: Int, val correctedCounts: Int)

    private fun resolve(raw: COSBase?): COSBase? {
        var value = raw
        var depth = 0
        while (value is COSObject) {
            requireInspectionGraphLimit(++depth <= SecurityLimits.MAX_GRAPH_DEPTH)
            value = value.`object`
        }
        return value
    }

    fun inspect(document: PDDocument, repairCounts: Boolean = false): Result {
        val root = resolve(document.documentCatalog.cosObject.getItem(COSName.PAGES)) as? COSDictionary
            ?: error("Page tree root is missing or invalid")
        require(resolve(root.getItem(COSName.TYPE)) == COSName.PAGES) { "Invalid page tree root type" }
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val corrections = mutableListOf<CountCorrection>()
        var leaves = 0

        fun visit(node: COSDictionary, parent: COSDictionary?, depth: Int): Int {
            requireInspectionGraphLimit(depth <= SecurityLimits.MAX_GRAPH_DEPTH && seen.size < SecurityLimits.MAX_GRAPH_NODES)
            require(seen.add(node)) { "Page tree contains a cycle or repeated child" }
            require(node !is COSStream) { "Page tree node must be a dictionary" }
            val actualParent = resolve(node.getItem(COSName.PARENT))
            if (parent != null) require(actualParent === parent) { "Page tree parent reference is missing or inconsistent" }
            else require(actualParent == null || actualParent is COSNull) { "Page tree root has a parent" }

            return when (resolve(node.getItem(COSName.TYPE))) {
                COSName.PAGE -> {
                    require(!node.containsKey(COSName.KIDS)) { "Page leaf unexpectedly has children" }
                    require(++leaves <= SecurityLimits.MAX_PAGE_COUNT) { "Page count exceeds recovery budget" }
                    1
                }
                COSName.PAGES -> {
                    val kids = resolve(node.getItem(COSName.KIDS)) as? COSArray
                        ?: error("Page tree branch has missing or invalid children")
                    requireInspectionGraphLimit(kids.size() <= SecurityLimits.MAX_GRAPH_NODES)
                    var count = 0
                    for (index in 0 until kids.size()) {
                        val child = resolve(kids.get(index)) as? COSDictionary
                            ?: error("Page tree child is missing or invalid")
                        count += visit(child, node, depth + 1)
                    }
                    val declared = resolve(node.getItem(COSName.COUNT)) as? COSInteger
                    if (declared?.longValue() != count.toLong()) corrections.add(CountCorrection(node, count))
                    count
                }
                else -> error("Unrecognized page tree node type")
            }
        }

        val count = visit(root, null, 0)
        require(count in 1..SecurityLimits.MAX_PAGE_COUNT && count == leaves) { "No supported pages recovered" }
        if (!repairCounts) require(corrections.isEmpty()) { "Page tree count mismatch" }
        // Commit mutations only after the entire hierarchy has been validated.
        if (repairCounts) corrections.forEach { it.dictionary.setInt(COSName.COUNT, it.count) }
        return Result(count, corrections.size)
    }
}
