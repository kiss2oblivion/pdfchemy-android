package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

enum class HeaderFooterPosition {
    HEADER_LEFT,
    HEADER_CENTER,
    HEADER_RIGHT,
    FOOTER_LEFT,
    FOOTER_CENTER,
    FOOTER_RIGHT
}

data class BatesConfig(
    val enabled: Boolean = false,
    val prefix: String = "DOC-",
    val suffix: String = "",
    val startNumber: Int = 1,
    val digits: Int = 6
)

data class StampConfig(
    val templateText: String = "{page} / {total}",
    val position: HeaderFooterPosition = HeaderFooterPosition.FOOTER_CENTER,
    val fontSize: Float = 10f,
    val marginPt: Float = 30f,
    val startFromPage: Int = 1,
    val batesConfig: BatesConfig = BatesConfig()
)

