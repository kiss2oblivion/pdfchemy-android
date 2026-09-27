package com.pdfchemy.app.jail.engines

object JailQuotas {
    const val MAX_TEXT_BYTES = 2 * 1024 * 1024 // 2 MB text limit
    const val MAX_BOOKMARKS = 2000
    const val MAX_FORM_FIELDS = 1000
    const val MAX_JSON_RESPONSE_SIZE = 1 * 1024 * 1024 // 1 MB IPC limit
    const val MAX_ARCHIVE_FILE_COUNT = 5000 // For CBZ extraction
    const val MAX_ARCHIVE_BYTES_READ = 250L * 1024 * 1024 // 250 MB total extracted size
    const val MAX_OUTPUT_FILES = 500
    const val MAX_BATCH_FDS = 100
    const val MAX_BATCH_INPUT_BYTES = 5L * 1024 * 1024 * 1024 // 5 GB
    const val MAX_RENDER_DIMENSION = 5000
    const val MAX_RENDER_PIXELS = 16000000L // 16 million pixels
    const val MAX_PDF_FILESIZE = 1000L * 1024 * 1024 // 1 GB max input filesize

    fun enforceFileSize(fd: android.os.ParcelFileDescriptor?, name: String = "File") {
        if (fd != null && fd.statSize > MAX_PDF_FILESIZE) {
            throw SecurityException("$name size of ${fd.statSize} exceeded quota limit of $MAX_PDF_FILESIZE bytes.")
        }
    }

    fun enforceStringLength(value: String, limit: Int = MAX_TEXT_BYTES, name: String = "Text") {
        if (value.length > limit) {
            throw SecurityException("$name exceeded quota limit of $limit characters.")
        }
    }

    fun enforceItemCount(count: Int, limit: Int, name: String = "Items") {
        if (count > limit) {
            throw SecurityException("$name count of $count exceeded quota limit of $limit.")
        }
    }

    fun enforceResultSize(resultJson: String): String {
        if (resultJson.toByteArray(Charsets.UTF_8).size > MAX_JSON_RESPONSE_SIZE) {
            throw SecurityException("Result payload exceeded IPC quota limit of 1MB.")
        }
        return resultJson
    }
}
