// =================================================================================================
// [FEATURE: Interactive Form Builder / AcroForm Authoring] (FEATURES_REGISTRY Android §4: Form Filling)
// Converts flat PDFs into interactive forms with draggable text fields, checkboxes, and dropdowns.
// =================================================================================================

package com.pdfchemy.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.AcroFormEngine
import com.pdfchemy.app.logic.FormFieldType
import com.pdfchemy.app.logic.InteractiveFieldSpec
import com.pdfchemy.app.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FormBuilderToolType {
    TEXT,
    CHECKBOX,
    DROPDOWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormBuilderScreen(
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var totalPages by remember { mutableIntStateOf(0) }
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var currentPageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isRenderingPage by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    var selectedTool by remember { mutableStateOf(FormBuilderToolType.TEXT) }
    val placedFields = remember { mutableStateListOf<InteractiveFieldSpec>() }

    // Dialog state for creating / editing field
    var pendingTapRatio by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var editingField by remember { mutableStateOf<InteractiveFieldSpec?>(null) }
    var fieldNameInput by remember { mutableStateOf("") }
    var defaultValueInput by remember { mutableStateOf("") }
    var dropdownOptionsInput by remember { mutableStateOf("") }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun renderCurrentPage(uri: Uri, pageIdx: Int) {
        isRenderingPage = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var pfd: ParcelFileDescriptor? = null
                    try {
                        pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            totalPages = com.pdfchemy.app.sandbox.NativeRendererCoordinator.getPageCount(context, pfd) ?: 0
                            if (pageIdx in 0 until totalPages) {
                                val bmp = com.pdfchemy.app.sandbox.NativeRendererCoordinator.renderPageToBitmap(context, pfd, pageIdx, 720)
                                if (bmp != null) {
                                    withContext(Dispatchers.Main) {
                                        currentPageBitmap?.recycle()
                                        currentPageBitmap = bmp
                                    }
                                }
                            }
                        }
                    } finally {
                        pfd?.close()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("FormBuilder: Failed to render page $pageIdx", e)
            } finally {
                isRenderingPage = false
            }
        }
    }

    LaunchedEffect(selectedPdfUri, currentPageIndex) {
        selectedPdfUri?.let { renderCurrentPage(it, currentPageIndex) }
    }

    val currentBmp by rememberUpdatedState(currentPageBitmap)
    DisposableEffect(Unit) {
        onDispose {
            currentBmp?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    val filePickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
        currentPageIndex = 0
        placedFields.clear()
    }

    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        val srcUri = selectedPdfUri
        if (destUri != null && srcUri != null) {
            isSaving = true
            scope.launch {
                val success = AcroFormEngine.createAcroFormWithFields(
                    context = context,
                    sourceUri = srcUri,
                    destUri = destUri,
                    fields = placedFields.toList()
                )
                isSaving = false
                if (success) {
                    Toast.makeText(context, context.getString(R.string.form_builder_success), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.error), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.form_builder_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back)
                        )
                    }
                },
                actions = {
                    if (selectedPdfUri != null) {
                        IconButton(onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.select_pdf))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (selectedPdfUri != null) {
                Surface(
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.form_builder_fields_count, placedFields.count { it.pageIndex == currentPageIndex }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Total: ${placedFields.size}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (placedFields.isEmpty()) {
                                    Toast.makeText(context, context.getString(R.string.form_builder_no_fields_warn), Toast.LENGTH_SHORT).show()
                                } else {
                                    saveFileLauncher.launch("fillable_form.pdf")
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSaving
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Rounded.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = stringResource(R.string.form_builder_action_save),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (selectedPdfUri == null) {
            // Document Selection Screen
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.EditNote,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = stringResource(R.string.menu_form_builder),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.menu_form_builder_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.select_pdf), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            // Main Canvas & Tool Selection
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Page Navigator Bar
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                            enabled = currentPageIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        }
                        Text(
                            text = "${currentPageIndex + 1} / ${totalPages.coerceAtLeast(1)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        IconButton(
                            onClick = { if (currentPageIndex < totalPages - 1) currentPageIndex++ },
                            enabled = currentPageIndex < totalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null)
                        }
                    }
                }

                // Tool selector tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedTool == FormBuilderToolType.TEXT,
                        onClick = { selectedTool = FormBuilderToolType.TEXT },
                        label = { Text(stringResource(R.string.form_builder_tool_text)) },
                        leadingIcon = { Icon(Icons.Rounded.TextFields, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTool == FormBuilderToolType.CHECKBOX,
                        onClick = { selectedTool = FormBuilderToolType.CHECKBOX },
                        label = { Text(stringResource(R.string.form_builder_tool_checkbox)) },
                        leadingIcon = { Icon(Icons.Rounded.CheckBox, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedTool == FormBuilderToolType.DROPDOWN,
                        onClick = { selectedTool = FormBuilderToolType.DROPDOWN },
                        label = { Text(stringResource(R.string.form_builder_tool_dropdown)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowDropDownCircle, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.weight(1.1f)
                    )
                }

                // Hint
                Text(
                    text = stringResource(R.string.form_builder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )

                // PDF Viewer Canvas with Interactive Field Overlays
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRenderingPage) {
                        CircularProgressIndicator()
                    } else {
                        currentPageBitmap?.let { bmp ->
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .onSizeChanged { canvasSize = it }
                                    .pointerInput(bmp, currentPageIndex, selectedTool) {
                                        detectTapGestures { offset ->
                                            if (canvasSize.width > 0 && canvasSize.height > 0) {
                                                val tapXRatio = offset.x / canvasSize.width.toFloat()
                                                val tapYRatio = offset.y / canvasSize.height.toFloat()

                                                // Check if tapped inside an existing field
                                                val hitField = placedFields.firstOrNull { spec ->
                                                    spec.pageIndex == currentPageIndex &&
                                                    tapXRatio >= spec.xRatio && tapXRatio <= (spec.xRatio + spec.widthRatio) &&
                                                    tapYRatio >= spec.yRatio && tapYRatio <= (spec.yRatio + spec.heightRatio)
                                                }

                                                if (hitField != null) {
                                                    editingField = hitField
                                                    fieldNameInput = hitField.name
                                                    defaultValueInput = hitField.defaultValue
                                                    dropdownOptionsInput = hitField.options.joinToString(", ")
                                                } else {
                                                    // New field creation
                                                    val fieldNum = placedFields.size + 1
                                                    val defaultName = when (selectedTool) {
                                                        FormBuilderToolType.TEXT -> "text_$fieldNum"
                                                        FormBuilderToolType.CHECKBOX -> "check_$fieldNum"
                                                        FormBuilderToolType.DROPDOWN -> "choice_$fieldNum"
                                                    }
                                                    fieldNameInput = defaultName
                                                    defaultValueInput = ""
                                                    dropdownOptionsInput = if (selectedTool == FormBuilderToolType.DROPDOWN) "Option 1, Option 2, Option 3" else ""
                                                    pendingTapRatio = Pair(tapXRatio, tapYRatio)
                                                }
                                            }
                                        }
                                    }
                            ) {
                                // Draw PDF Bitmap
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val imageBitmap = bmp.asImageBitmap()
                                    drawImage(
                                        image = imageBitmap,
                                        dstSize = IntSize(size.width.toInt(), size.height.toInt())
                                    )

                                    // Draw Overlays for Fields on this page
                                    val pageFields = placedFields.filter { it.pageIndex == currentPageIndex }
                                    for (field in pageFields) {
                                        val fx = field.xRatio * size.width
                                        val fy = field.yRatio * size.height
                                        val fw = field.widthRatio * size.width
                                        val fh = field.heightRatio * size.height

                                        // Background Tint
                                        drawRect(
                                            color = Color(0xFF1976D2).copy(alpha = 0.25f),
                                            topLeft = Offset(fx, fy),
                                            size = Size(fw, fh)
                                        )

                                        // Outline
                                        drawRect(
                                            color = Color(0xFF1976D2),
                                            topLeft = Offset(fx, fy),
                                            size = Size(fw, fh),
                                            style = Stroke(width = 2.dp.toPx())
                                        )
                                    }
                                }

                                // Interactive Badges overlay
                                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                    val pageFields = placedFields.filter { it.pageIndex == currentPageIndex }
                                    for (field in pageFields) {
                                        val fx = maxWidth * field.xRatio
                                        val fy = maxHeight * field.yRatio
                                        val fw = maxWidth * field.widthRatio
                                        val fh = maxHeight * field.heightRatio

                                        Box(
                                            modifier = Modifier
                                                .offset(x = fx, y = fy)
                                                .size(width = fw, height = fh)
                                                .padding(2.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFE3F2FD).copy(alpha = 0.85f))
                                                .border(1.dp, Color(0xFF1976D2), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                when (field.type) {
                                                    FormFieldType.TEXT -> Icon(
                                                        Icons.Rounded.TextFields,
                                                        contentDescription = null,
                                                        tint = Color(0xFF1976D2),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    FormFieldType.CHECKBOX -> Icon(
                                                        Icons.Rounded.CheckBox,
                                                        contentDescription = null,
                                                        tint = Color(0xFF2E7D32),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    FormFieldType.CHOICE -> Icon(
                                                        Icons.Rounded.ArrowDropDownCircle,
                                                        contentDescription = null,
                                                        tint = Color(0xFFE65100),
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    else -> {}
                                                }
                                                Text(
                                                    text = field.name,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF0D47A1),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // New Field Placement Dialog
    pendingTapRatio?.let { tap ->
        AlertDialog(
            onDismissRequest = { pendingTapRatio = null },
            title = {
                Text(
                    text = when (selectedTool) {
                        FormBuilderToolType.TEXT -> stringResource(R.string.form_builder_tool_text)
                        FormBuilderToolType.CHECKBOX -> stringResource(R.string.form_builder_tool_checkbox)
                        FormBuilderToolType.DROPDOWN -> stringResource(R.string.form_builder_tool_dropdown)
                    }
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = fieldNameInput,
                        onValueChange = { fieldNameInput = it },
                        label = { Text(stringResource(R.string.form_builder_field_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = defaultValueInput,
                        onValueChange = { defaultValueInput = it },
                        label = { Text(stringResource(R.string.form_builder_default_value_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (selectedTool == FormBuilderToolType.DROPDOWN) {
                        OutlinedTextField(
                            value = dropdownOptionsInput,
                            onValueChange = { dropdownOptionsInput = it },
                            label = { Text(stringResource(R.string.form_builder_options_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = fieldNameInput.trim().ifEmpty { "field_${placedFields.size + 1}" }
                        val (xRatio, yRatio) = tap
                        val (wRatio, hRatio) = when (selectedTool) {
                            FormBuilderToolType.TEXT -> Pair(0.35f, 0.045f)
                            FormBuilderToolType.CHECKBOX -> Pair(0.06f, 0.038f)
                            FormBuilderToolType.DROPDOWN -> Pair(0.35f, 0.045f)
                        }
                        val options = if (selectedTool == FormBuilderToolType.DROPDOWN) {
                            dropdownOptionsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        } else emptyList()

                        placedFields.add(
                            InteractiveFieldSpec(
                                pageIndex = currentPageIndex,
                                name = name,
                                type = when (selectedTool) {
                                    FormBuilderToolType.TEXT -> FormFieldType.TEXT
                                    FormBuilderToolType.CHECKBOX -> FormFieldType.CHECKBOX
                                    FormBuilderToolType.DROPDOWN -> FormFieldType.CHOICE
                                },
                                xRatio = xRatio.coerceIn(0f, 1f - wRatio),
                                yRatio = yRatio.coerceIn(0f, 1f - hRatio),
                                widthRatio = wRatio,
                                heightRatio = hRatio,
                                defaultValue = defaultValueInput.trim(),
                                options = options
                            )
                        )
                        pendingTapRatio = null
                    }
                ) {
                    Text(stringResource(R.string.form_builder_action_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTapRatio = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Existing Field Edit / Delete Dialog
    editingField?.let { targetField ->
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text(targetField.name) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = fieldNameInput,
                        onValueChange = { fieldNameInput = it },
                        label = { Text(stringResource(R.string.form_builder_field_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = defaultValueInput,
                        onValueChange = { defaultValueInput = it },
                        label = { Text(stringResource(R.string.form_builder_default_value_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (targetField.type == FormFieldType.CHOICE) {
                        OutlinedTextField(
                            value = dropdownOptionsInput,
                            onValueChange = { dropdownOptionsInput = it },
                            label = { Text(stringResource(R.string.form_builder_options_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            placedFields.remove(targetField)
                            editingField = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.form_builder_delete_field))
                    }
                    Button(
                        onClick = {
                            val newName = fieldNameInput.trim().ifEmpty { targetField.name }
                            val newOptions = if (targetField.type == FormFieldType.CHOICE) {
                                dropdownOptionsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            } else targetField.options

                            val idx = placedFields.indexOf(targetField)
                            if (idx >= 0) {
                                placedFields[idx] = targetField.copy(
                                    name = newName,
                                    defaultValue = defaultValueInput.trim(),
                                    options = newOptions
                                )
                            }
                            editingField = null
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingField = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
