package com.pdfchemy.app.logic

import android.graphics.RectF

data class RedactionBox(
    val pageIndex: Int,
    val xRatio: Float,
    val yRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val normalizedRect: RectF = RectF(xRatio, yRatio, xRatio + widthRatio, yRatio + heightRatio),
    val overlayLabel: String = "REDACTED"
) {
    constructor(pageIndex: Int, normalizedRect: RectF, overlayLabel: String? = null) : this(
        pageIndex = pageIndex,
        xRatio = normalizedRect.left,
        yRatio = normalizedRect.top,
        widthRatio = normalizedRect.width(),
        heightRatio = normalizedRect.height(),
        normalizedRect = normalizedRect,
        overlayLabel = overlayLabel ?: "REDACTED"
    )
}

data class RedactionConfig(
    val isBlackout: Boolean = true,
    val defaultOverlayText: String = "",
    val forensicSanitize: Boolean = true
)

enum class FormFieldType {
    TEXT, CHECKBOX, RADIO, CHOICE, SIGNATURE, OTHER
}

data class FormFieldInfo(
    val name: String,
    val fullyQualifiedName: String,
    val type: FormFieldType,
    val value: String,
    val possibleOptions: List<String> = emptyList(),
    val isReadOnly: Boolean = false,
    val isRequired: Boolean = false
)

data class InteractiveFieldSpec(
    val pageIndex: Int,
    val name: String,
    val type: FormFieldType = FormFieldType.TEXT,
    val xRatio: Float = 0.1f,
    val yRatio: Float = 0.1f,
    val widthRatio: Float = 0.4f,
    val heightRatio: Float = 0.05f,
    val defaultValue: String = "",
    val options: List<String> = emptyList()
)

enum class OfficeFormat(val displayName: String, val extension: String, val mimeType: String) {
    WORD("Microsoft Word (.docx)", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    EXCEL("Microsoft Excel (.xlsx)", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POWERPOINT("Microsoft PowerPoint (.pptx)", "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
}

data class OfficeExportReport(
    val format: OfficeFormat,
    val pageCount: Int,
    val outputSizeBytes: Long,
    val itemsExtracted: Int
)
