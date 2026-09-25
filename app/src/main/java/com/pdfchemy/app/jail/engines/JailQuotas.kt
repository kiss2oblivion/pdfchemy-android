package com.pdfchemy.app.jail.engines

object JailQuotas {
    const val MAX_TEXT_BYTES = 2 * 1024 * 1024 // 2 MB text limit
    const val MAX_BOOKMARKS = 2000
    const val MAX_FORM_FIELDS = 1000
    const val MAX_JSON_RESPONSE_SIZE = 1 * 1024 * 1024 // 1 MB IPC limit
    const val MAX_ARCHIVE_FILE_COUNT = 5000 // For CBZ extraction
    const val MAX_ARCHIVE_BYTES_READ = 250L * 1024 * 1024 // 250 MB total extracted size

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
