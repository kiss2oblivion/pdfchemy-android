package com.pdfchemy.app.jail

import com.pdfchemy.app.security.SecurityLimits
import org.json.JSONArray
import org.json.JSONObject

object RequestValidator {
    fun validate(json: String) {
        SecurityLimits.enforceStringLength(json, SecurityLimits.MAX_PARAMS_JSON_BYTES, "Parameters")
        var nesting = 0
        var quoted = false
        var escaped = false
        for (character in json) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> require(++nesting <= SecurityLimits.MAX_REQUEST_DEPTH + 1) { "Parameter nesting quota exceeded" }
                '}', ']' -> require(--nesting >= 0) { "Invalid parameter nesting" }
            }
        }
        require(nesting == 0 && !quoted) { "Invalid parameter JSON" }
        var nodes = 0
        fun walk(value: Any?, depth: Int, key: String = "") {
            require(depth <= SecurityLimits.MAX_REQUEST_DEPTH && ++nodes <= SecurityLimits.MAX_REQUEST_NODES) { "Parameter traversal quota exceeded" }
            when (value) {
                is JSONObject -> value.keys().forEach { walk(value.get(it), depth + 1, it) }
                is JSONArray -> {
                    val limit = when (key) {
                        "signatures" -> SecurityLimits.MAX_SIGNATURES
                        "fields", "options" -> SecurityLimits.MAX_FORM_FIELDS
                        "bookmarks" -> SecurityLimits.MAX_BOOKMARKS
                        else -> SecurityLimits.MAX_REQUEST_ITEMS
                    }
                    require(value.length() <= limit) { "Parameter item quota exceeded" }
                    for (i in 0 until value.length()) walk(value[i], depth + 1, key)
                }
                is String -> SecurityLimits.enforceStringLength(value,
                    if (key in setOf("text", "markdownText")) SecurityLimits.MAX_PARAMS_JSON_BYTES else SecurityLimits.MAX_METADATA_LENGTH, key)
                is Number -> require(value.toDouble().isFinite()) { "Non-finite parameter" }
            }
        }
        val tokenizer = org.json.JSONTokener(json)
        val root = tokenizer.nextValue()
        require(root is JSONObject || root is JSONArray) { "Parameters must be an object or array" }
        require(tokenizer.nextClean() == '\u0000') { "Trailing parameter JSON" }
        walk(root, 0)
    }
}
