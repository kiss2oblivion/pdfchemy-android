package com.pdfchemy.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Recovery remains explicit; only a new, inspected document can resume a picker. */
@Composable
internal fun VanguardProtectedRecoveryDialog(
    sourceUri: Uri, encrypted: Boolean, blockedReasonResId: Int,
    onDismiss: () -> Unit, onContinue: (Uri) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val continueLatest by rememberUpdatedState(onContinue)
    var password by remember(sourceUri) { mutableStateOf("") }
    var consent by remember(sourceUri) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var passwordRejected by remember { mutableStateOf(false) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    val busy = saving || running
    val save = rememberPreferredDocumentDestinationCreator("application/pdf") { target ->
        saving = false
        if (target != null) {
            val entered = password
            password = ""
            running = true
            operationJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    check(target.isFreshCopy && consent)
                    if (encrypted) {
                        val params = JSONObject().put("password", entered)
                            .put("requireVanguardClean", true).put("allowActiveContentRemoval", true)
                        PdfGateway.executeEngineTyped<StandardOutputContract>(context, "UNLOCK",
                            sourceUri, target.uri, params.toString())
                    } else {
                        val params = JSONObject().put("purgeJs", true).put("purgeActions", true)
                            .put("purgeMetadata", false).put("purgeAttachments", false).put("requireVanguardClean", true)
                        val result = PdfGateway.executeEngineTyped<SanitizerCleanContract>(context, "SANITIZE_CLEAN",
                            sourceUri, target.uri, params.toString())
                        check(result.isSuccess)
                    }
                    currentCoroutineContext().ensureActive()
                    continueLatest(target.uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    passwordRejected = encrypted && error is PdfGateway.WorkerFailure && error.errorCode == 401
                    failed = true
                }
                finally {
                    withContext(NonCancellable + Dispatchers.IO) { OutputPolicy.deleteIfFresh(context, target.uri) }
                    running = false
                }
            }
        }
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = if (encrypted) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (encrypted) R.string.vanguard_encrypted_title else R.string.vanguard_blocked_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (encrypted) R.string.vanguard_encrypted_message else blockedReasonResId))
                Text(stringResource(R.string.vanguard_protected_copy_help))
                if (encrypted) OutlinedTextField(password, { password = it.take(1024) },
                    label = { Text(stringResource(R.string.enter_current_password_label)) },
                    singleLine = true, enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
                    Text(stringResource(R.string.vanguard_protected_consent), Modifier.padding(top = 12.dp))
                }
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) Text(stringResource(when {
                    passwordRejected -> R.string.vanguard_password_rejected
                    encrypted -> R.string.vanguard_unlock_failed
                    else -> R.string.vanguard_sanitize_failed
                }),
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = consent && !busy, onClick = {
                failed = false; passwordRejected = false; saving = true
                save.launch(if (encrypted) "unlocked.pdf" else "sanitized.pdf")
            }) { Text(stringResource(if (encrypted) R.string.vanguard_unlock_continue else R.string.vanguard_sanitize_continue)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = {
                if (running) operationJob?.cancel() else onDismiss()
            }) { Text(stringResource(R.string.cancel)) }
        }
    )
}
