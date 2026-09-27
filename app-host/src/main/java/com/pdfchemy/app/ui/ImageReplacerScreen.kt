package com.pdfchemy.app.ui

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfchemy.app.R
import com.pdfchemy.app.logic.EmbeddedImageInfo
import com.pdfchemy.app.logic.PdfImageReplacerEngine
import com.pdfchemy.app.utils.FileUtils
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageReplacerScreen(
    viewModel: MainViewModel,
    initialPdfUri: Uri? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler { onBack() }

    var selectedPdfUri by remember { mutableStateOf<Uri?>(initialPdfUri) }
    var embeddedImages by remember { mutableStateOf<List<EmbeddedImageInfo>>(emptyList()) }
    var isScanningImages by remember { mutableStateOf(false) }

    var selectedTargetImage by remember { mutableStateOf<EmbeddedImageInfo?>(null) }
    var replacementImageUri by remember { mutableStateOf<Uri?>(null) }
    var replacementBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showReplaceDialog by remember { mutableStateOf(false) }

    // Launcher for PDF selection
    val pdfPickerLauncher = rememberVanguardPdfPicker { uri ->
        selectedPdfUri = uri
    }

    // Launcher for replacement image selection
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            replacementImageUri = uri
            scope.launch(Dispatchers.IO) {
                try {
                    val loader = coil.Coil.imageLoader(context)
                    val req = coil.request.ImageRequest.Builder(context)
                        .data(uri)
                        .allowHardware(false)
                        .size(2048)
                        .build()
                    val result = loader.execute(req)
                    val bmp = (result as? coil.request.SuccessResult)?.drawable?.let {
                        (it as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    }
                    withContext(Dispatchers.Main) {
                        replacementBitmap = bmp
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        replacementBitmap = null
                    }
                }
            }
        }
    }

    // Save updated PDF launcher
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { destUri ->
        if (destUri != null && selectedPdfUri != null && selectedTargetImage != null && replacementBitmap != null) {
            viewModel.replaceEmbeddedImage(
                context = context,
                sourcePdfUri = selectedPdfUri!!,
                destPdfUri = destUri,
                pageIndex = selectedTargetImage!!.pageIndex,
                resourceName = selectedTargetImage!!.resourceName,
                replacementBitmap = replacementBitmap!!,
                isLossless = true
            ) { success ->
                if (success) {
                    showReplaceDialog = false
                    selectedTargetImage = null
                    replacementImageUri = null
                    replacementBitmap = null
                }
            }
        }
    }

    // Scan for embedded images when PDF changes
    LaunchedEffect(selectedPdfUri) {
        selectedPdfUri?.let { uri ->
            isScanningImages = true
            embeddedImages = PdfImageReplacerEngine.listEmbeddedImages(context, uri)
            isScanningImages = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.menu_replace_image),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        Icons.Rounded.FindReplace,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.banner_replace_image_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.banner_replace_image_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // PDF Selector Card
            OutlinedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.PictureAsPdf,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: stringResource(R.string.btn_choose_pdf_file),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (selectedPdfUri != null) {
                                    stringResource(R.string.tap_to_change_file)
                                } else {
                                    stringResource(R.string.tap_to_browse)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Images Grid / Status
            if (isScanningImages) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(R.string.scanning_embedded_images),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (selectedPdfUri != null && embeddedImages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ImageNotSupported,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = stringResource(R.string.no_embedded_images_found),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else if (embeddedImages.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.embedded_images_count, embeddedImages.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(embeddedImages, key = { it.id }) { imageInfo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTargetImage = imageInfo
                                    replacementImageUri = null
                                    replacementBitmap = null
                                    showReplaceDialog = true
                                },
                            shape = RoundedCornerShape(12.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(110.dp)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val tbmp = imageInfo.thumbnailBitmap
                                    if (tbmp != null) {
                                        Image(
                                            bitmap = tbmp.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(
                                            Icons.Rounded.Image,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }

                                    // Page Badge
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(bottomStart = 8.dp),
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.page_badge_num, imageInfo.pageIndex + 1),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "${imageInfo.width} × ${imageInfo.height} px",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = imageInfo.resourceName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }

    // Replacement Modal Dialog
    if (showReplaceDialog && selectedTargetImage != null) {
        AlertDialog(
            onDismissRequest = { showReplaceDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.dialog_replace_image_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.dialog_replace_image_desc,
                            selectedTargetImage!!.pageIndex + 1,
                            selectedTargetImage!!.resourceName
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Before vs After Preview Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Current Image
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.label_current_image),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedTargetImage!!.thumbnailBitmap != null) {
                                    Image(
                                        bitmap = selectedTargetImage!!.thumbnailBitmap!!.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // New Replacement Image
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.label_new_image),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { imagePickerLauncher.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (replacementBitmap != null) {
                                    Image(
                                        bitmap = replacementBitmap!!.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.AddPhotoAlternate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = stringResource(R.string.btn_pick_photo),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val baseName = selectedPdfUri?.let { FileUtils.getFileName(context, it) } ?: "document.pdf"
                        val suggestedName = "image_swapped_$baseName"
                        savePdfLauncher.launch(suggestedName)
                    },
                    enabled = replacementBitmap != null
                ) {
                    Text(stringResource(R.string.btn_apply_and_save_pdf))
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
