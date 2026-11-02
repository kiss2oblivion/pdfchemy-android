package com.pdfchemy.app.logic

/** Bounded chronological snapshots; callers commit a drag as one operation. */
class SessionHistory<T>(initial: T, private val limit: Int = 50) {
    var current: T = initial
        private set
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun replace(value: T) {
        if (value == current) return
        past.addLast(current)
        while (past.size > limit) past.removeFirst()
        future.clear()
        current = value
    }
    fun undo(): T? {
        if (past.isEmpty()) return null
        future.addLast(current); current = past.removeLast(); return current
    }
    fun redo(): T? {
        if (future.isEmpty()) return null
        past.addLast(current); current = future.removeLast(); return current
    }
    fun reset(value: T) { past.clear(); future.clear(); current = value }
}
