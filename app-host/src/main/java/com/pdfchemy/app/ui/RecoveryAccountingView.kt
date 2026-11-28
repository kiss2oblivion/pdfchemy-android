package com.pdfchemy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.RecoveryAccounting
import com.pdfchemy.app.logic.RecoveryMethod

@Composable
internal fun RecoveryAccountingView(report: RecoveryAccounting) {
    val context = LocalContext.current
    Text(stringResource(R.string.vanguard_copy_saved))
    Text(stringResource(when (report.method) {
        RecoveryMethod.RESERIALIZE -> R.string.vanguard_method_reserialize
        RecoveryMethod.NORMALIZE_CONTAINER -> R.string.vanguard_method_normalize
        RecoveryMethod.SANITIZE -> R.string.vanguard_method_sanitize
        RecoveryMethod.UNLOCK -> R.string.vanguard_method_unlock
    }))
    Text(stringResource(R.string.vanguard_page_parity, report.readableInputPages, report.verifiedOutputPages))
    if (report.headerInserted) Text(stringResource(R.string.vanguard_header_inserted))
    if (report.prefixBytesRemoved > 0) Text(stringResource(R.string.vanguard_prefix_removed, report.prefixBytesRemoved))
    if (report.eofAppended) Text(stringResource(R.string.vanguard_eof_appended))
    if (report.pageTreeCountsCorrected > 0)
        Text(stringResource(R.string.vanguard_page_counts_corrected, report.pageTreeCountsCorrected))
    report.activeFindingsBefore?.let {
        Text(stringResource(R.string.vanguard_findings_parity, it, report.activeFindingsAfter))
    } ?: Text(stringResource(R.string.vanguard_no_active_after))
    Text(stringResource(R.string.vanguard_recovery_uncertainty), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        val text = buildString {
            appendLine("PDFchemy verified recovery")
            appendLine("Method: " + report.method.name)
            appendLine("Readable input pages: " + report.readableInputPages)
            appendLine("Verified output pages: " + report.verifiedOutputPages)
            appendLine("Fallback header inserted: " + report.headerInserted)
            appendLine("Fallback prefix bytes removed: " + report.prefixBytesRemoved)
            appendLine("Fallback EOF appended: " + report.eofAppended)
            appendLine("Page-tree count entries corrected: " + report.pageTreeCountsCorrected)
            appendLine("Active findings before: " + (report.activeFindingsBefore?.toString() ?: "Not measured"))
            appendLine("Active findings after: " + report.activeFindingsAfter)
            appendLine("Full content, visual and signature preservation not verified")
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("PDFchemy recovery", text))
    }) { Text(stringResource(R.string.desc_copy_diagnostic_report)) }
}
