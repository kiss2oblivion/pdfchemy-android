package com.pdfchemy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.AcroFormEngine
import com.pdfchemy.app.logic.FormFieldInfo
import com.pdfchemy.app.logic.FormFieldType
import com.pdfchemy.app.logic.PdfEditor
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillFormScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val continuityUri by viewModel.continuityDocumentUri.collectAsState()
    var selectedPdfUri by remember { mutableStateOf<Uri?>(continuityUri) }
    var formFields by remember { mutableStateOf<List<FormFieldInfo>>(emptyList()) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var isLoadingFields by remember { mutableStateOf(false) }
    var flattenForm by remember { mutableStateOf(false) }
    var fillMode by remember { mutableStateOf(FormFillMode.LIST) }
    var currentPage by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(1) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoadingPage by remember { mutableStateOf(false) }

    LaunchedEffect(selectedPdfUri, currentPage, fillMode) {
        val uri = selectedPdfUri
        if (uri != null && fillMode == FormFillMode.IN_SITU_OVERLAY) {
            isLoadingPage = true
            val bmp = withContext(Dispatchers.IO) {
                PdfEditor.renderPageBitmap(context, uri, currentPage, 1080)
            }
            pageBitmap = bmp
            isLoadingPage = false
        }
    }

    fun loadFormFields(uri: Uri) {
        selectedPdfUri = uri
        isLoadingFields = true
        scope.launch {
            try {
                pageCount = PdfEditor.getPageCount(context, uri).coerceAtLeast(1)
            } catch (_: Exception) {}
            currentPage = 0
            val fields = AcroFormEngine.extractFields(context, uri)
            formFields = fields
            fieldValues.clear()
            fields.forEach { field ->
                fieldValues[field.fullyQualifiedName] = field.value
            }
            isLoadingFields = false
        }
    }

    LaunchedEffect(continuityUri) {
        if (continuityUri != null && selectedPdfUri == continuityUri && formFields.isEmpty() && !isLoadingFields) {
            loadFormFields(continuityUri!!)
        }
    }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        loadFormFields(uri)
    }

    val saveDocLauncher = rememberPreferredDocumentCreator("application/pdf") { destUri ->
        if (destUri != null && selectedPdfUri != null) {
            viewModel.fillAndSaveForm(
                context = context,
                sourceUri = selectedPdfUri!!,
                destUri = destUri,
                fieldValues = fieldValues.toMap(),
                flatten = flattenForm
            ) { success ->
                if (success) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_fill_form)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.DynamicForm,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = stringResource(R.string.fill_form_headline),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(R.string.fill_form_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Document Picker Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (selectedPdfUri != null) {
                                    FileUtils.getFileName(context, selectedPdfUri!!) ?: stringResource(R.string.select_form_pdf)
                                } else {
                                    stringResource(R.string.select_form_pdf)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (selectedPdfUri != null) {
                                Text(
                                    text = if (isLoadingFields) "Scanning form fields…" else "${formFields.size} form fields detected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Button(onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }) {
                            Text(if (selectedPdfUri == null) "Select" else "Change")
                        }
                    }
                }

                if (isLoadingFields) {
                    CircularProgressIndicator(modifier = Modifier.padding(24.dp))
                } else if (selectedPdfUri != null && formFields.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(
                                text = stringResource(R.string.no_acroform_fields_found),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                } else if (formFields.isNotEmpty()) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = fillMode == FormFillMode.LIST,
                            onClick = { fillMode = FormFillMode.LIST },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Icon(Icons.Rounded.ViewList, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.form_fill_mode_list))
                        }
                        SegmentedButton(
                            selected = fillMode == FormFillMode.IN_SITU_OVERLAY,
                            onClick = { fillMode = FormFillMode.IN_SITU_OVERLAY },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Icon(Icons.Rounded.Layers, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.form_fill_mode_insitu))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.form_fields_count, formFields.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                formFields.forEach { field ->
                                    fieldValues[field.fullyQualifiedName] = if (field.type == FormFieldType.CHECKBOX) "Off" else ""
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(stringResource(R.string.action_reset_fields), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (fillMode == FormFillMode.LIST) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(formFields, key = { it.fullyQualifiedName }, contentType = { "formField" }) { field ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = field.name,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                        when (field.type) {
                                            FormFieldType.CHECKBOX -> {
                                                val isChecked = fieldValues[field.fullyQualifiedName]?.equals("Yes", ignoreCase = true) == true
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(if (isChecked) "Checked" else "Unchecked", style = MaterialTheme.typography.bodyMedium)
                                                    Switch(
                                                        checked = isChecked,
                                                        onCheckedChange = { checked ->
                                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                            fieldValues[field.fullyQualifiedName] = if (checked) "Yes" else "Off"
                                                        }
                                                    )
                                                }
                                            }
                                            FormFieldType.CHOICE -> {
                                                var expanded by remember { mutableStateOf(false) }
                                                val currentVal = fieldValues[field.fullyQualifiedName] ?: ""
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                    OutlinedButton(
                                                        onClick = { expanded = true },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(if (currentVal.isBlank()) "Choose option…" else currentVal)
                                                        Spacer(modifier = Modifier.weight(1f))
                                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                    }
                                                    DropdownMenu(
                                                        expanded = expanded,
                                                        onDismissRequest = { expanded = false }
                                                    ) {
                                                        field.possibleOptions.forEach { option ->
                                                            DropdownMenuItem(
                                                                text = { Text(option) },
                                                                onClick = {
                                                                    fieldValues[field.fullyQualifiedName] = option
                                                                    expanded = false
                                                                }
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            else -> {
                                                OutlinedTextField(
                                                    value = fieldValues[field.fullyQualifiedName] ?: "",
                                                    onValueChange = { fieldValues[field.fullyQualifiedName] = it },
                                                    placeholder = { Text(stringResource(R.string.hint_enter_value)) },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val pageFields = formFields.filter { it.pageIndex == currentPage }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BoxWithConstraints(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isLoadingPage) {
                                    CircularProgressIndicator()
                                } else if (pageBitmap != null) {
                                    val bmp = pageBitmap!!
                                    val containerW = maxWidth
                                    val containerH = maxHeight
                                    val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
                                    val containerAspect = containerW.value / containerH.value.coerceAtLeast(1f)

                                    val renderedW = if (bmpAspect > containerAspect) containerW else containerH * bmpAspect
                                    val renderedH = if (bmpAspect > containerAspect) containerW / bmpAspect else containerH

                                    Box(modifier = Modifier.size(renderedW, renderedH)) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = stringResource(R.string.reader_page_content_desc, currentPage + 1),
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.FillBounds
                                        )

                                        for (field in pageFields) {
                                            val fieldX = renderedW * field.xRatio
                                            val fieldY = renderedH * field.yRatio
                                            val fieldW = (renderedW * field.widthRatio).coerceAtLeast(28.dp)
                                            val fieldH = (renderedH * field.heightRatio).coerceAtLeast(20.dp)

                                            Box(
                                                modifier = Modifier
                                                    .offset(x = fieldX, y = fieldY)
                                                    .size(width = fieldW, height = fieldH)
                                            ) {
                                                InSituFieldWidget(
                                                    field = field,
                                                    value = fieldValues[field.fullyQualifiedName] ?: "",
                                                    onValueChange = { newVal ->
                                                        fieldValues[field.fullyQualifiedName] = newVal
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Page Navigation
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (currentPage > 0) currentPage-- },
                                    enabled = currentPage > 0
                                ) {
                                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null)
                                }

                                Text(
                                    "Page ${currentPage + 1} of $pageCount • ${pageFields.size} field(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )

                                IconButton(
                                    onClick = { if (currentPage < pageCount - 1) currentPage++ },
                                    enabled = currentPage < pageCount - 1
                                ) {
                                    Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                }
                            }
                        }
                    }

                    // Save and Flatten Option
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(stringResource(R.string.flatten_form_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.flatten_form_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = flattenForm,
                            onCheckedChange = { flattenForm = it }
                        )
                    }

                    Button(
                        onClick = {
                            val originalName = FileUtils.getFileName(context, selectedPdfUri!!) ?: "Form.pdf"
                            val defaultName = "Filled_${originalName}"
                            saveDocLauncher.launch(defaultName)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 52.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.action_save_filled_pdf),
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
}

enum class FormFillMode {
    LIST,
    IN_SITU_OVERLAY
}

@Composable
fun InSituFieldWidget(
    field: FormFieldInfo,
    value: String,
    onValueChange: (String) -> Unit
) {
    val isReadOnly = field.isReadOnly
    val isRequired = field.isRequired

    when (field.type) {
        FormFieldType.CHECKBOX -> {
            val isChecked = value.equals("Yes", ignoreCase = true) ||
                    value.equals("True", ignoreCase = true) ||
                    value.equals("On", ignoreCase = true) ||
                    value == "1"
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(3.dp)
                    )
                    .clickable(enabled = !isReadOnly) {
                        onValueChange(if (isChecked) "Off" else "Yes")
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isChecked) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.desc_checked),
                        modifier = Modifier.fillMaxSize(0.85f),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        FormFieldType.CHOICE -> {
            var expanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f))
                    .border(
                        width = 1.dp,
                        color = if (isRequired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(3.dp)
                    )
                    .clickable(enabled = !isReadOnly) {
                        expanded = true
                    }
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = value.ifBlank { field.possibleOptions.firstOrNull() ?: "..." },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    field.possibleOptions.forEach { opt ->
                        DropdownMenuItem(
                            text = { Text(opt) },
                            onClick = {
                                onValueChange(opt)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        FormFieldType.SIGNATURE -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.secondary,
                        shape = RoundedCornerShape(3.dp)
                    )
                    .padding(2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✍ " + (value.ifBlank { "Sign" }),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        else -> {
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (isFocused) MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    )
                    .border(
                        width = if (isFocused) 1.5.dp else 1.dp,
                        color = when {
                            isRequired && value.isBlank() -> MaterialTheme.colorScheme.error
                            isFocused -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                        },
                        shape = RoundedCornerShape(3.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = !isReadOnly,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (value.isEmpty()) {
                    Text(
                        text = field.name.ifBlank { field.fullyQualifiedName },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
