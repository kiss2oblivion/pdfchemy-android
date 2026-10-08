package com.pdfchemy.app.logic

import java.util.UUID

/**
 * Snapshot of the editor session state for chronological undo/redo.
 */
data class EditorSessionSnapshot(
    val modifications: Map<Int, PageModification>,
    val targetPageIndex: Int
)

/**
 * Chronological editor session manager that tracks all user modifications
 * (drawings, text annotations, stamps, redactions, page rotations, deletions)
 * across pages with full Undo/Redo capability and dirty state detection.
 */
class EditorSession(
    initialModifications: Map<Int, PageModification> = emptyMap()
) {
    companion object {
        const val MAX_HISTORY_STEPS = 50
    }

    private val undoStack = ArrayDeque<EditorSessionSnapshot>()
    private val redoStack = ArrayDeque<EditorSessionSnapshot>()

    private var currentModifications: Map<Int, PageModification> = initialModifications
    private var baselineModifications: Map<Int, PageModification> = initialModifications

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * True if the document has modifications compared to its baseline state.
     */
    val isDirty: Boolean
        get() = currentModifications != baselineModifications && currentModifications.values.any { it.hasChanges }

    fun getModifications(): Map<Int, PageModification> = currentModifications

    fun getModification(pageIndex: Int): PageModification {
        return currentModifications[pageIndex] ?: PageModification(pageIndex = pageIndex)
    }

    private fun pushAction(targetPageIndex: Int, newModifications: Map<Int, PageModification>) {
        undoStack.addLast(EditorSessionSnapshot(currentModifications, targetPageIndex))
        currentModifications = newModifications
        redoStack.clear()
        if (undoStack.size > MAX_HISTORY_STEPS) {
            undoStack.removeFirst()
        }
    }

    fun addDrawing(pageIndex: Int, drawing: DrawingPath) {
        val current = getModification(pageIndex)
        val updated = current.copy(drawings = current.drawings + drawing)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun addTextAnnotation(pageIndex: Int, textAnnotation: TextAnnotation) {
        val current = getModification(pageIndex)
        val updated = current.copy(textAnnotations = current.textAnnotations + textAnnotation)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun removeTextAnnotation(pageIndex: Int, annotationId: String) {
        val current = getModification(pageIndex)
        val updated = current.copy(textAnnotations = current.textAnnotations.filter { it.id != annotationId })
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun addStamp(pageIndex: Int, stamp: StampAnnotation) {
        val current = getModification(pageIndex)
        val updated = current.copy(stamps = current.stamps + stamp)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun addRedaction(pageIndex: Int, redaction: RedactionBox) {
        val current = getModification(pageIndex)
        val updated = current.copy(redactions = current.redactions + redaction)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun rotatePage(pageIndex: Int, degreesDelta: Int = 90) {
        val current = getModification(pageIndex)
        val newRotation = (current.rotationDegrees + degreesDelta) % 360
        val updated = current.copy(rotationDegrees = if (newRotation < 0) newRotation + 360 else newRotation)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    fun deletePage(pageIndex: Int, isDeleted: Boolean = true) {
        val current = getModification(pageIndex)
        val updated = current.copy(isDeleted = isDeleted)
        pushAction(pageIndex, currentModifications + (pageIndex to updated))
    }

    /**
     * Undoes the last chronological action.
     * Returns the page index affected by the action so UI can update appropriately.
     */
    fun undo(): Int? {
        if (undoStack.isEmpty()) return null
        val snapshot = undoStack.removeLast()
        redoStack.addLast(EditorSessionSnapshot(currentModifications, snapshot.targetPageIndex))
        currentModifications = snapshot.modifications
        return snapshot.targetPageIndex
    }

    /**
     * Redoes the last undone chronological action.
     * Returns the page index affected by the action so UI can update appropriately.
     */
    fun redo(): Int? {
        if (redoStack.isEmpty()) return null
        val snapshot = redoStack.removeLast()
        undoStack.addLast(EditorSessionSnapshot(currentModifications, snapshot.targetPageIndex))
        currentModifications = snapshot.modifications
        return snapshot.targetPageIndex
    }

    fun markSaved() { baselineModifications = currentModifications }
    fun clear() {
        baselineModifications = emptyMap()
        reset()
    }
    fun reset() {
        undoStack.clear()
        redoStack.clear()
        currentModifications = baselineModifications
    }
}
