package com.pdfchemy.app.logic

data class PdfAttachment(
    val attachmentId: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String
)
