package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

data class TextMatchOccurrence(
    val pageIndex: Int,
    val matchedText: String,
    val snippet: String,
    val bounds: RectF, // In PDF page coordinate space (origin at bottom-left)
    val fontSize: Float
)

data class FindReplaceSummary(
    val totalMatches: Int,
    val pagesAffected: Int,
    val occurrences: List<TextMatchOccurrence>
)

