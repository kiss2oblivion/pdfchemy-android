package com.pdfchemy.app.ui

import android.content.Context
import com.pdfchemy.app.R

object ErrorPresentation {
    enum class Recovery { PASSWORD, STORAGE, ACCESS, DAMAGED, OPERATION }
    fun classify(detail: String): Recovery {
        val text = detail.lowercase()
        return when {
            listOf("password", "encrypted", "invalidpassword").any(text::contains) -> Recovery.PASSWORD
            listOf("enospc", "no space", "disk full", "storage full").any(text::contains) -> Recovery.STORAGE
            listOf("permission denied", "filenotfound", "file not found", "grant revoked", "eacces").any(text::contains) -> Recovery.ACCESS
            listOf("malformed", "invalid pdf", "corrupt", "parsefailed", "pdf header").any(text::contains) -> Recovery.DAMAGED
            else -> Recovery.OPERATION
        }
    }
    fun message(context: Context, detail: String): String {
        val recoveryMessages = listOf(R.string.error_password_recovery, R.string.error_storage_recovery,
            R.string.document_load_recovery, R.string.document_damaged_recovery, R.string.error_operation_recovery)
        if (recoveryMessages.any { context.getString(it) == detail } || detail.startsWith("No app can open") || detail.startsWith("No sharing app")) return detail
        return context.getString(when (classify(detail)) {
        Recovery.PASSWORD -> R.string.error_password_recovery
        Recovery.STORAGE -> R.string.error_storage_recovery
        Recovery.ACCESS -> R.string.document_load_recovery
        Recovery.DAMAGED -> R.string.document_damaged_recovery
        Recovery.OPERATION -> R.string.error_operation_recovery
        })
    }
}
