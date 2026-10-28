package com.pdfchemy.app.ui

import android.graphics.Color as AndroidColor
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.NonCancellable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.FileUtil
import com.pdfchemy.app.logic.PdfDeskewEngine
import com.pdfchemy.app.logic.DeskewPreviewLoader
import com.pdfchemy.app.logic.DeskewPreviewOwner
import com.pdfchemy.app.logic.defaultDeskewPreviewLoader
import com.pdfchemy.app.utils.AppLogger
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val LocalDeskewPreviewLoader = staticCompositionLocalOf<DeskewPreviewLoader?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeskewScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val suppliedPreviewLoader = LocalDeskewPreviewLoader.current
    val appContext = context.applicationContext
    val previewLoader = remember(appContext, suppliedPreviewLoader) { suppliedPreviewLoader ?: defaultDeskewPreviewLoader(appContext) }
    val previewOwner = remember(scope, previewLoader) { DeskewPreviewOwner(scope, previewLoader) }
    val preview by previewOwner.state.collectAsState()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    val detectedAngle = preview.angle
    val isAnalyzing = preview.loading
    var isProcessing by remember { mutableStateOf(false) }
    val previewBitmap = preview.bitmap
    var straightenedCount by remember { mutableIntStateOf(-1) }

    fun analyzeDocument(uri: Uri) {
        selectedPdfUri = uri
        straightenedCount = -1
        previewOwner.analyze(uri)
    }

    LaunchedEffect(initialPdfUri, previewOwner) {
        if (initialPdfUri != null) {
            analyzeDocument(initialPdfUri)
        } else {
            selectedPdfUri = null
            straightenedCount = -1
            previewOwner.clear()
        }
    }

    DisposableEffect(previewOwner) {
        onDispose { previewOwner.close() }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        analyzeDocument(uri)
    }

    var requestedOutputName by remember { mutableStateOf("straightened.pdf") }
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderUri ->
        val srcUri = selectedPdfUri
        if (folderUri != null && srcUri != null) {
            straightenedCount = -1
            isProcessing = true
            scope.launch(Dispatchers.Main.immediate) {
                var createdUri: Uri? = null
                try {
                    val destUri = withContext(Dispatchers.IO) {
                        val directory = checkNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri))
                        com.pdfchemy.app.logic.OutputPolicy.createCopy(directory, "application/pdf", requestedOutputName).uri
                            .also { createdUri = it }
                    }
                    val count = PdfDeskewEngine.deskewDocument(context, srcUri, destUri)
                    straightenedCount = count
                    com.pdfchemy.app.logic.OutputPolicy.markPublished(destUri)
                    Toast.makeText(context, context.getString(if (count == 0) R.string.deskew_no_tilt else R.string.deskew_success), Toast.LENGTH_SHORT).show()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    AppLogger.e("Deskew processing failed", e)
                    Toast.makeText(context, "Error straightening document", Toast.LENGTH_SHORT).show()
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        createdUri?.let { com.pdfchemy.app.logic.OutputPolicy.deleteIfFresh(context, it) }
                    }
                    isProcessing = false
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.deskew_title)) },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Button(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.UploadFile, contentDescription = null)
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
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(fileName, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }

            if (isAnalyzing) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                Text(
                    stringResource(R.string.deskew_auto_label) + "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (selectedPdfUri != null) {
                if (preview.failed) Text("Unable to analyze this document", color = MaterialTheme.colorScheme.error)
                // Detected Tilt Badge
                if (previewBitmap != null) Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (abs(detectedAngle) >= 0.5f)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            if (abs(detectedAngle) >= 0.5f) Icons.Rounded.RotateRight else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = if (abs(detectedAngle) >= 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (abs(detectedAngle) >= 0.5f)
                                stringResource(R.string.deskew_detected_angle, detectedAngle)
                            else
                                stringResource(R.string.deskew_no_tilt),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.menu_deskew_desc),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Page Preview
                previewBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Page Preview",
                            modifier = Modifier.padding(8.dp).semantics {
                                stateDescription = String.format(java.util.Locale.getDefault(), "%.1f°", detectedAngle)
                            }
                        )
                    }
                }

                if (straightenedCount >= 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(stringResource(if (straightenedCount == 0) R.string.deskew_no_tilt else R.string.deskew_success), fontWeight = FontWeight.Bold)
                                if (straightenedCount > 0) {
                                    Text(stringResource(R.string.deskew_success_desc), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        val suggestedName = FileUtil.generateSuggestedName(context,
                            selectedPdfUri,
                            "straightened"
                        )
                        requestedOutputName = suggestedName
                        saveFileLauncher.launch(null)
                    },
                    enabled = !isProcessing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.deskew_action_straighten))
                }
                Text("A new copy is saved in the folder you choose.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
