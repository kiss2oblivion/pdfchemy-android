package com.pdfchemy.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

import java.io.File
import androidx.core.content.FileProvider
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.pdfchemy.app.R

fun createTempImageUri(context: android.content.Context): Uri {
    val tempFile = File(context.cacheDir, "scans/scan_${System.currentTimeMillis()}.jpg")
    tempFile.parentFile?.mkdirs()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImagesToPdfScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var currentPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val uiState by viewModel.uiState.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages = selectedImages + uris
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val photoUri = currentPhotoUri
        if (success && photoUri != null) {
            selectedImages = selectedImages + photoUri
        }
    }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null && selectedImages.isNotEmpty()) {
            viewModel.convertImagesToPdf(context, selectedImages, uri)
        }
    }

    androidx.activity.compose.BackHandler { onBack() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.images_to_pdf)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedImages.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { createPdfLauncher.launch(com.pdfchemy.app.logic.FileUtil.generateSuggestedName(null, "images")) },
                    icon = { Icon(Icons.Rounded.PictureAsPdf, contentDescription = "Convert") },
                    text = { Text(stringResource(R.string.convert_to_pdf)) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        pickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "Gallery", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.gallery),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                
                Button(
                    onClick = {
                        val uri = createTempImageUri(context)
                        currentPhotoUri = uri
                        cameraLauncher.launch(uri)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = "Take Photo", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.camera),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (selectedImages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_images_selected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(selectedImages, key = { it.toString() }, contentType = { "imageItem" }) { uri ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Selected Image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.1f))
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = uri.lastPathSegment ?: "Image",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                IconButton(onClick = {
                                    selectedImages = selectedImages.filter { it != uri }
                                }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Remove Image", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    // Spacer for FAB
                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }
}
