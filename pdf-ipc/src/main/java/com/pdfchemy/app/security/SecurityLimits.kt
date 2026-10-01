package com.pdfchemy.app.security

import kotlin.math.floor
import kotlin.math.sqrt

/** Limits apply before staging, dispatch, allocation, and output. */
object SecurityLimits {
    const val MAX_PDF_FILESIZE = 100L * 1024 * 1024
    const val MAX_BATCH_INPUT_BYTES = 250L * 1024 * 1024
    const val MAX_BATCH_FDS = 32
    const val MAX_SCRATCH_FDS = 64
    const val MAX_OPERATION_WRITE_BYTES = 500L * 1024 * 1024
    const val MAX_PARSER_MEMORY_BYTES = 32L * 1024 * 1024
    const val MAX_GRAPH_NODES = 100_000
    const val MAX_GRAPH_DEPTH = 128
    const val MAX_STAGED_DOCUMENTS = 64
    const val MAX_OUTPUT_BYTES = 250L * 1024 * 1024
    const val MAX_TEXT_BYTES = 2 * 1024 * 1024
    const val MAX_BOOKMARKS = 2000
    const val MAX_FORM_FIELDS = 1000
    const val MAX_JSON_RESPONSE_SIZE = 1024 * 1024
    const val MAX_PARAMS_JSON_BYTES = 256 * 1024
    const val MAX_REQUEST_ITEMS = 2000
    const val MAX_REQUEST_DEPTH = 32
    const val MAX_REQUEST_NODES = 20_000
    const val MAX_QUERY_LENGTH = 4096
    const val MAX_METADATA_LENGTH = 4096
    const val MAX_ARCHIVE_FILE_COUNT = 5000
    const val MAX_ARCHIVE_BYTES_READ = 250L * 1024 * 1024
    const val MAX_OUTPUT_FILES = 500
    const val MAX_RENDER_DIMENSION = 2048
    const val MAX_RENDER_PIXELS = 4_194_304L
    const val MAX_SIGNATURE_BYTES = 2 * 1024 * 1024
    const val MAX_SIGNATURES = 100
    const val WORKER_DEADLINE_MS = 120_000L
    const val RENDER_DEADLINE_MS = 30_000L

    fun requireIdentity(hash: String?, size: Long) {
        require(size in 1..MAX_PDF_FILESIZE) { "Missing or excessive staged size" }
        require(hash != null && hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid SHA-256" }
    }

    fun requireBatch(count: Int, targets: Int, hashes: Array<String>?, sizes: LongArray?) {
        require(count in 1..MAX_BATCH_FDS && targets in 1..MAX_BATCH_FDS) { "Invalid batch descriptor count" }
        require(hashes != null && sizes != null && hashes.size == count && sizes.size == count) { "Batch identity metadata must match every descriptor" }
        sizes.indices.forEach { requireIdentity(hashes[it], sizes[it]) }
        require(sizes.sum() <= MAX_BATCH_INPUT_BYTES) { "Batch input quota exceeded" }
    }

    fun requirePixels(width: Int, height: Int) {
        require(width in 1..MAX_RENDER_DIMENSION && height in 1..MAX_RENDER_DIMENSION && width.toLong() * height <= MAX_RENDER_PIXELS) { "Raster quota exceeded" }
    }

    fun safeRenderSize(width: Double, height: Double, requestedScale: Double = 2.0): Pair<Int, Int> {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0 && requestedScale.isFinite() && requestedScale > 0) { "Invalid page dimensions" }
        val scale = minOf(requestedScale, MAX_RENDER_DIMENSION / maxOf(width, height), sqrt(MAX_RENDER_PIXELS / width / height))
        val w = floor(width * scale).toInt().coerceAtLeast(1)
        val h = floor(height * scale).toInt().coerceAtLeast(1)
        requirePixels(w, h)
        return w to h
    }

    fun enforceFileSize(fd: android.os.ParcelFileDescriptor?, name: String = "File") {
        require(fd == null || fd.statSize in 1..MAX_PDF_FILESIZE) { "$name must be a bounded regular staged file" }
    }
    fun enforceStringLength(value: String, limit: Int = MAX_TEXT_BYTES, name: String = "Text") {
        require(value.length <= limit && value.toByteArray(Charsets.UTF_8).size <= limit) { "$name quota exceeded" }
    }
    fun enforceItemCount(count: Int, limit: Int, name: String = "Items") {
        require(count in 0..limit) { "$name quota exceeded" }
    }
    fun enforceResultSize(resultJson: String): String {
        enforceStringLength(resultJson, MAX_JSON_RESPONSE_SIZE, "IPC result")
        return resultJson
    }
}
