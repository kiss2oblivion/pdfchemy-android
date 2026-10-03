package com.pdfchemy.app.jail

import android.net.Uri

data class StagedPdf(
    val uri: Uri,
    val sha256: String,
    val size: Long
)
