package com.pdfchemy.app.logic

object VisualPageRanges {
    fun parse(value: String, pageCount: Int): Set<Int> = buildSet {
        value.split(',').forEach { token ->
            val ends = token.trim().split('-').map { it.trim().toIntOrNull() }
            val first = ends.firstOrNull() ?: return@forEach
            val last = if (ends.size == 1) first else if (ends.size == 2) ends[1] ?: return@forEach else return@forEach
            if (first in 1..pageCount && last in first..pageCount) addAll(first..last)
        }
    }
    fun format(pages: Set<Int>): String = pages.sorted().joinToString(",")
}
