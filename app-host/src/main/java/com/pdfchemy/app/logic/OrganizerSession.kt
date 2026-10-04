package com.pdfchemy.app.logic

import android.graphics.Bitmap
import java.util.UUID

enum class PageThumbnailState {
    LOADING,
    READY,
    FAILED
}

data class OrganizerPageItem(
    val id: String = UUID.randomUUID().toString(),
    val originalIndex: Int? = null,
    val rotation: Int = 0,
    val isBlank: Boolean = false,
    val thumbnail: Bitmap? = null,
    val previewState: PageThumbnailState = if (isBlank) PageThumbnailState.READY else if (thumbnail != null) PageThumbnailState.READY else PageThumbnailState.LOADING
)

class OrganizerSession(initialPages: List<OrganizerPageItem> = emptyList()) {
    private val undoStack = ArrayDeque<List<OrganizerPageItem>>()
    private val redoStack = ArrayDeque<List<OrganizerPageItem>>()

    var pages: List<OrganizerPageItem> = initialPages
        private set

    var baselinePages: List<OrganizerPageItem> = initialPages
        private set

    var selectedIndex: Int = if (initialPages.isNotEmpty()) 0 else -1
        private set

    fun initialize(pageCount: Int) {
        val items = (0 until pageCount).map { i ->
            OrganizerPageItem(
                originalIndex = i,
                rotation = 0,
                isBlank = false,
                thumbnail = null,
                previewState = PageThumbnailState.LOADING
            )
        }
        pages = items
        baselinePages = items
        selectedIndex = if (items.isNotEmpty()) 0 else -1
        undoStack.clear()
        redoStack.clear()
    }

    fun updateThumbnail(originalIndex: Int, bitmap: Bitmap?, state: PageThumbnailState) {
        val current = pages.toMutableList()
        var changed = false
        for (i in current.indices) {
            val item = current[i]
            if (item.originalIndex == originalIndex && !item.isBlank) {
                current[i] = item.copy(thumbnail = bitmap, previewState = state)
                changed = true
            }
        }
        if (changed) {
            pages = current
            val baseline = baselinePages.toMutableList()
            for (i in baseline.indices) {
                val item = baseline[i]
                if (item.originalIndex == originalIndex && !item.isBlank) {
                    baseline[i] = item.copy(thumbnail = bitmap, previewState = state)
                }
            }
            baselinePages = baseline
        }
    }

    fun selectPage(index: Int) {
        selectedIndex = if (index in pages.indices) {
            index
        } else if (pages.isEmpty()) {
            -1
        } else {
            pages.size - 1
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun canReset(): Boolean = pages != baselinePages

    private fun pushState() {
        undoStack.addLast(pages)
        redoStack.clear()
    }

    fun undo(): Boolean {
        if (!canUndo()) return false
        val previous = undoStack.removeLast()
        redoStack.addLast(pages)
        pages = previous
        if (selectedIndex >= pages.size) {
            selectedIndex = pages.size - 1
        }
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        val next = redoStack.removeLast()
        undoStack.addLast(pages)
        pages = next
        if (selectedIndex >= pages.size) {
            selectedIndex = pages.size - 1
        }
        return true
    }

    fun reset() {
        if (!canReset()) return
        pushState()
        pages = baselinePages.map { it.copy(rotation = 0) }
        selectedIndex = if (pages.isNotEmpty()) 0 else -1
    }

    fun reverse() {
        if (pages.size <= 1) return
        pushState()
        pages = pages.reversed()
        if (selectedIndex != -1) {
            selectedIndex = pages.size - 1 - selectedIndex
        }
    }

    fun rotateAll(degrees: Int = 90) {
        if (pages.isEmpty()) return
        pushState()
        pages = pages.map { it.copy(rotation = (it.rotation + degrees) % 360) }
    }

    fun rotateSelected(degrees: Int = 90) {
        if (selectedIndex !in pages.indices) return
        pushState()
        val list = pages.toMutableList()
        val item = list[selectedIndex]
        list[selectedIndex] = item.copy(rotation = (item.rotation + degrees) % 360)
        pages = list
    }

    fun moveSelectedLeft() {
        if (selectedIndex <= 0 || selectedIndex >= pages.size) return
        pushState()
        val list = pages.toMutableList()
        val item = list.removeAt(selectedIndex)
        list.add(selectedIndex - 1, item)
        pages = list
        selectedIndex--
    }

    fun moveSelectedRight() {
        if (selectedIndex < 0 || selectedIndex >= pages.size - 1) return
        pushState()
        val list = pages.toMutableList()
        val item = list.removeAt(selectedIndex)
        list.add(selectedIndex + 1, item)
        pages = list
        selectedIndex++
    }

    fun duplicateSelected() {
        if (selectedIndex !in pages.indices) return
        pushState()
        val list = pages.toMutableList()
        val item = list[selectedIndex]
        list.add(selectedIndex + 1, item.copy(id = UUID.randomUUID().toString()))
        pages = list
        selectedIndex++
    }

    fun insertBlank(atIndex: Int = if (selectedIndex in pages.indices) selectedIndex + 1 else pages.size) {
        pushState()
        val insertIdx = atIndex.coerceIn(0, pages.size)
        val list = pages.toMutableList()
        list.add(insertIdx, OrganizerPageItem(isBlank = true, previewState = PageThumbnailState.READY))
        pages = list
        selectedIndex = insertIdx
    }

    fun deleteSelected(): Boolean {
        if (selectedIndex !in pages.indices || pages.size <= 1) return false
        pushState()
        val list = pages.toMutableList()
        list.removeAt(selectedIndex)
        pages = list
        selectedIndex = selectedIndex.coerceAtMost(pages.size - 1)
        return true
    }

    fun toPageActions(): List<PageAction> {
        return pages.map {
            PageAction(
                originalPageIndex = it.originalIndex,
                rotationDegrees = it.rotation,
                isBlank = it.isBlank
            )
        }
    }
}
