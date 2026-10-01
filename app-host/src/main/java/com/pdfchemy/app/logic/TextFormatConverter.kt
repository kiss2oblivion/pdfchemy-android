package com.pdfchemy.app.logic

import android.content.Context
object TextFormatConverter {
    enum class Format { TXT, MD, HTML, CSV, TSV, JSON, YAML, XML }
    suspend fun convert(context: Context, inputContent: String, from: Format, to: Format): Result<String> = runCatching {
        com.google.gson.Gson().fromJson(JailEngineBridge.call(context, "TEXT_FORMAT", null, null,
            mapOf("text" to inputContent, "from" to from.name, "to" to to.name)), String::class.java)
    }
}
