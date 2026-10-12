package com.pdfchemy.app.logic

data class OcrProgress(val completed: Int, val total: Int, val saving: Boolean)

/** Progress is informational; only the existing terminal result can accept output. */
class OcrProgressTracker(private val operationId: Long, private val maxPages: Int) {
    private var closed = false
    private var last: OcrProgress? = null

    @Synchronized fun accept(token: Long, completed: Int, total: Int, saving: Boolean): OcrProgress? {
        if (closed) return null
        require(token == operationId && operationId > 0)
        require(total in 1..maxPages && completed in 0..total)
        val previous = last
        if (previous == null) require(completed == 0 && !saving)
        else {
            require(total == previous.total && !previous.saving)
            if (saving) require(completed == total && previous.completed == total)
            else require(completed == previous.completed + 1)
        }
        return OcrProgress(completed, total, saving).also { last = it }
    }

    @Synchronized fun close() { closed = true }
}
