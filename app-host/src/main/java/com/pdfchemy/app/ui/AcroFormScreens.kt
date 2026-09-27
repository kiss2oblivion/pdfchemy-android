package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.AcroFormEngine
import com.pdfchemy.app.logic.FormFieldInfo
import com.pdfchemy.app.logic.FormFieldType
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillFormScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var formFields by remember { mutableStateOf<List<FormFieldInfo>>(emptyList()) }
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    var isLoadingFields by remember { mutableStateOf(false) }
    var flattenForm by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        selectedPdfUri = uri
        isLoadingFields = true
        scope.launch {
            val fields = AcroFormEngine.extractFields(context, uri)
            formFields = fields
            fieldValues.clear()
            fields.forEach { field ->
                fieldValues[field.fullyQualifiedName] = field.value
            }
            isLoadingFields = false
        }
    }

    val saveDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
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
                                                placeholder = { Text("Enter value…") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
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
