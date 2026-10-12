package com.pdfchemy.app.logic

import java.util.concurrent.atomic.AtomicLong

/** Publication identity for asynchronous requests, including repeated inputs. */
class LatestRequest {
    private val generation = AtomicLong()
    fun begin(): Long = generation.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = generation.get() == ticket
    fun invalidate() { generation.incrementAndGet() }
}
