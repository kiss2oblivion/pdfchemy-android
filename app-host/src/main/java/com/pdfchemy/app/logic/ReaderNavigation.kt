package com.pdfchemy.app.logic

object ReaderNavigation {
    data class OutlineRow(val bookmark: OutlineBookmark, val depth: Int)
    data class Occurrence(val section: Int, val paragraph: Int, val start: Int)

    fun flattenOutline(bookmarks: List<OutlineBookmark>): List<OutlineRow> = buildList {
        fun visit(nodes: List<OutlineBookmark>, depth: Int) {
            if (depth > 32) return
            for (node in nodes) {
                if (size >= 2000) return
                add(OutlineRow(node, depth))
                visit(node.children, depth + 1)
            }
        }
        visit(bookmarks, 0)
    }

    fun occurrences(sections: List<ReflowSection>, query: String): List<Occurrence> = buildList {
        val needle = query.trim()
        if (needle.isEmpty()) return@buildList
        sections.forEachIndexed { section, item ->
            item.paragraphs.forEachIndexed { paragraph, text ->
                var offset = 0
                while (offset <= text.length - needle.length && size < 10_000) {
                    val start = text.indexOf(needle, offset, ignoreCase = true)
                    if (start < 0) break
                    add(Occurrence(section, paragraph, start))
                    offset = start + needle.length
                }
            }
        }
    }
}
