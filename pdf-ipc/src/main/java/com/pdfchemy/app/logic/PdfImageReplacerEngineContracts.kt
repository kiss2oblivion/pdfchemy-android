package com.pdfchemy.app.logic

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri

data class EmbeddedImageInfo(
    val id: String,
    val pageIndex: Int,
    val resourceName: String,
    val width: Int,
    val height: Int,
    val format: String,
    val thumbnailBitmap: Bitmap?
)

