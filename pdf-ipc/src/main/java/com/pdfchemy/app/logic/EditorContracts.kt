package com.pdfchemy.app.logic

import java.util.UUID

enum class EditorTool { VIEW, PEN, HIGHLIGHTER, TEXT, STAMP, REDACT }
enum class StampType(val text: String, val colorHex: Long) {
    APPROVED("APPROVED", 0xFF2E7D32),
    CONFIDENTIAL("CONFIDENTIAL", 0xFFC62828),
    DRAFT("DRAFT", 0xFFEF6C00),
    PAID("PAID", 0xFF1565C0),
    REJECTED("REJECTED", 0xFFB71C1C),
    FINAL("FINAL", 0xFF4527A0),
    URGENT("URGENT", 0xFFD84315)
}
data class DrawingPoint(val x: Float, val y: Float)
data class DrawingPath(val id: String = UUID.randomUUID().toString(), val points: List<DrawingPoint>, val color: Int, val strokeWidth: Float, val isHighlighter: Boolean = false)
data class TextAnnotation(val id: String = UUID.randomUUID().toString(), val text: String, val xRatio: Float, val yRatio: Float, val fontSize: Float = 16f, val textColor: Int = android.graphics.Color.BLACK, val backgroundColor: Int = android.graphics.Color.TRANSPARENT)
data class StampAnnotation(val id: String = UUID.randomUUID().toString(), val type: StampType, val xRatio: Float, val yRatio: Float, val scale: Float = 1.0f, val rotation: Float = -15f)
data class PageModification(val pageIndex: Int, val rotationDegrees: Int = 0, val isDeleted: Boolean = false, val drawings: List<DrawingPath> = emptyList(), val textAnnotations: List<TextAnnotation> = emptyList(), val stamps: List<StampAnnotation> = emptyList(), val redactions: List<com.pdfchemy.app.logic.RedactionBox> = emptyList()) {
    val hasChanges: Boolean get() = rotationDegrees != 0 || isDeleted || drawings.isNotEmpty() || textAnnotations.isNotEmpty() || stamps.isNotEmpty() || redactions.isNotEmpty()
}
