package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

enum class TargetPaperSize(val displayName: String, val widthPts: Float, val heightPts: Float) {
    A4("A4 (210 x 297 mm)", 595.28f, 841.89f),
    LETTER("US Letter (8.5 x 11 in)", 612.0f, 792.0f),
    LEGAL("US Legal (8.5 x 14 in)", 612.0f, 1008.0f),
    A3("A3 (297 x 420 mm)", 841.89f, 1190.55f),
    A5("A5 (148 x 210 mm)", 419.53f, 595.28f)
}

enum class NUpMode(val pagesPerSheet: Int, val cols: Int, val rows: Int) {
    TWO_UP(2, 1, 2),
    FOUR_UP(4, 2, 2),
    SIX_UP(6, 2, 3)
}

