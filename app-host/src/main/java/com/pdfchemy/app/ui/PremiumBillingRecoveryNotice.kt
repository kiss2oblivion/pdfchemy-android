package com.pdfchemy.app.ui

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pdfchemy.app.R
import com.pdfchemy.app.billing.BillingUiState

/** Also rendered in the Premium branch, where purchase controls are hidden. */
@Composable
internal fun PremiumBillingRecoveryNotice(state: BillingUiState, onRetry: () -> Unit) {
    if (!state.entitlementStorageFailed) return
    Text(state.message)
    if (state.loading) CircularProgressIndicator()
    if (state.canRetry) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
}
