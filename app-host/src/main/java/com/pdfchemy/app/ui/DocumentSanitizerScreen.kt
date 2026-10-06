package com.pdfchemy.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.FileUtil

import com.pdfchemy.app.logic.SanitizerAuditReport
import com.pdfchemy.app.logic.SanitizerResult
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentSanitizerScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    SecureScreenContent()
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var auditReport by remember { mutableStateOf<SanitizerAuditReport?>(null) }
    var isAuditing by remember { mutableStateOf(false) }
    var isSanitizing by remember { mutableStateOf(false) }
    var sanitizeResult by remember { mutableStateOf<SanitizerResult?>(null) }

    var purgeJs by remember { mutableStateOf(true) }
    var purgeActions by remember { mutableStateOf(true) }
    var purgeMetadata by remember { mutableStateOf(true) }
    var purgeAttachments by remember { mutableStateOf(true) }

    fun runAudit(uri: Uri) {
        selectedPdfUri = uri
        isAuditing = true
        auditReport = null
        sanitizeResult = null
        scope.launch {
            try {
                val report = com.pdfchemy.app.logic.PdfSanitizerEngine.auditDocumentThreats(context, uri)
                auditReport = report
            } catch (e: Exception) {
                AppLogger.e("Audit failed", e)
            } finally {
                isAuditing = false
            }
        }
    }

    LaunchedEffect(initialPdfUri) {
        if (initialPdfUri != null) {
            runAudit(initialPdfUri)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runAudit(uri)
        }
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        val srcUri = selectedPdfUri
        if (destUri != null && srcUri != null) {
            isSanitizing = true
            scope.launch {
                try {
                    val result = com.pdfchemy.app.logic.PdfSanitizerEngine.sanitizeDocument(
                        context, srcUri, destUri,
                        purgeJs, purgeActions, purgeMetadata
                    )
                    sanitizeResult = result
                    if (result != null && result.isSuccess) {
                        Toast.makeText(context, context.getString(R.string.sanitizer_success), Toast.LENGTH_SHORT).show()
                    } else if (result == null) {
                        Toast.makeText(context, "Sanitize failed (Timeout or Crash)", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    AppLogger.e("Sanitizing failed", e)
                } finally {
                    isSanitizing = false
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sanitizer_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.select_pdf))
            }

            selectedPdfUri?.let { uri ->
                val fileName = FileUtils.getFileName(context, uri) ?: "Document.pdf"
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            if (isAuditing) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (auditReport != null) {
                val report = auditReport!!

                // Audit Summary Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (report.isClean)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (report.isClean) Icons.Rounded.VerifiedUser else Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = if (report.isClean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                if (report.isClean) stringResource(R.string.sanitizer_all_clean)
                                else stringResource(R.string.sanitizer_threats_detected, report.threatsFound),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Spacer(modifier = Modifier.height(12.dp))

                        if (report.jsCount > 0) Text(stringResource(R.string.sanitizer_js_threats, report.jsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.launchActionsCount > 0) Text(stringResource(R.string.sanitizer_action_threats, report.launchActionsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.otherActionsCount > 0) Text(stringResource(R.string.sanitizer_other_action_threats, report.otherActionsCount), style = MaterialTheme.typography.bodyMedium)
                        if (report.attachmentCount > 0) Text(stringResource(R.string.sanitizer_attachments, report.attachmentCount), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Sterilization Options
                Text(
                    stringResource(R.string.sanitizer_audit_summary),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Purge JavaScript Payload Triggers")
                            Switch(checked = purgeJs, onCheckedChange = { purgeJs = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Purge Launch & URL Actions")
                            Switch(checked = purgeActions, onCheckedChange = { purgeActions = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Purge Hidden Embedded Files")
                            Switch(checked = purgeAttachments, onCheckedChange = { purgeAttachments = it })
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Purge Tracking Metadata & IDs")
                            Switch(checked = purgeMetadata, onCheckedChange = { purgeMetadata = it })
                        }
                    }
                }

                sanitizeResult?.let { res ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.sanitizer_success), fontWeight = FontWeight.Bold)
                            }
                            Text(
                                stringResource(R.string.sanitizer_success_desc),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        val suggestedName = FileUtil.generateSuggestedName(selectedPdfUri, "sanitized")
                        saveFileLauncher.launch(suggestedName)
                    },
                    enabled = !isSanitizing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    if (isSanitizing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onError)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.sanitizer_action_sanitize), color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}
