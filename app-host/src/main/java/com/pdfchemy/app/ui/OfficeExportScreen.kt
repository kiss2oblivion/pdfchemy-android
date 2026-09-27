package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.OfficeFormat
import com.pdfchemy.app.utils.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfficeExportScreen(
    initialFormat: OfficeFormat = OfficeFormat.WORD,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFormat by remember { mutableStateOf(initialFormat) }
    var fileName by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedUri = uri
        fileName = FileUtils.getFileName(context, uri) ?: "document.pdf"
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(selectedFormat.mimeType)
    ) { destUri: Uri? ->
        if (destUri != null && selectedUri != null) {
            isProcessing = true
            viewModel.exportPdfToOffice(
                context = context,
                sourceUri = selectedUri!!,
                destUri = destUri,
                format = selectedFormat
            ) {
                isProcessing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.title_office_exporter),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.banner_office_exporter_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.banner_office_exporter_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Step 1: Select PDF File
            Text(
                text = stringResource(R.string.step_select_pdf),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedUri != null) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    if (selectedUri != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (selectedUri != null) Icons.Rounded.PictureAsPdf else Icons.Rounded.UploadFile,
                        contentDescription = null,
                        tint = if (selectedUri != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (selectedUri != null) fileName else stringResource(R.string.btn_choose_pdf_file),
                            fontWeight = if (selectedUri != null) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (selectedUri != null) stringResource(R.string.tap_to_change_file) else stringResource(R.string.tap_to_browse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Step 2: Choose Target Format
            Text(
                text = stringResource(R.string.step_choose_format),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FormatSelectionCard(
                    title = "Word",
                    subtitle = ".docx",
                    icon = Icons.Rounded.Article,
                    color = Color(0xFF2B579A),
                    isSelected = selectedFormat == OfficeFormat.WORD,
                    modifier = Modifier.weight(1f)
                ) {
                    selectedFormat = OfficeFormat.WORD
                }

                FormatSelectionCard(
                    title = "Excel",
                    subtitle = ".xlsx",
                    icon = Icons.Rounded.TableChart,
                    color = Color(0xFF217346),
                    isSelected = selectedFormat == OfficeFormat.EXCEL,
                    modifier = Modifier.weight(1f)
                ) {
                    selectedFormat = OfficeFormat.EXCEL
                }

                FormatSelectionCard(
                    title = "PowerPoint",
                    subtitle = ".pptx",
                    icon = Icons.Rounded.Slideshow,
                    color = Color(0xFFD24726),
                    isSelected = selectedFormat == OfficeFormat.POWERPOINT,
                    modifier = Modifier.weight(1f)
                ) {
                    selectedFormat = OfficeFormat.POWERPOINT
                }
            }

            // Format Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = when (selectedFormat) {
                            OfficeFormat.WORD -> stringResource(R.string.info_word_export)
                            OfficeFormat.EXCEL -> stringResource(R.string.info_excel_export)
                            OfficeFormat.POWERPOINT -> stringResource(R.string.info_pptx_export)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f, fill = false))

            // Export Action Button
            Button(
                onClick = {
                    if (selectedUri != null) {
                        val baseName = fileName.substringBeforeLast(".")
                        val suggestedName = "${baseName}.${selectedFormat.extension}"
                        saveFileLauncher.launch(suggestedName)
                    }
                },
                enabled = selectedUri != null && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (selectedFormat) {
                        OfficeFormat.WORD -> Color(0xFF2B579A)
                        OfficeFormat.EXCEL -> Color(0xFF217346)
                        OfficeFormat.POWERPOINT -> Color(0xFFD24726)
                    },
                    contentColor = Color.White
                )
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.converting_to_office), color = Color.White)
                } else {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.btn_export_office_file, selectedFormat.extension.uppercase()),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun FormatSelectionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) color.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) color else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) color else color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) Color.White else color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) color else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
