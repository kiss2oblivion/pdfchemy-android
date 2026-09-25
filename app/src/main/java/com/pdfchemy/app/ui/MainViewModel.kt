package com.pdfchemy.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.pdfchemy.app.logic.PdfCompressor
import com.pdfchemy.app.logic.PdfAnalysis
import com.pdfchemy.app.logic.PdfScenario
import com.pdfchemy.app.logic.PdfManipulator
import com.pdfchemy.app.logic.PdfMetadata
import com.pdfchemy.app.logic.PdfMetadataManager
import com.pdfchemy.app.logic.PdfTextExtractor
import com.pdfchemy.app.logic.TextToPdfConverter
import com.pdfchemy.app.billing.BillingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import com.pdfchemy.app.utils.AppLogger
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import androidx.compose.ui.res.stringResource
import androidx.documentfile.provider.DocumentFile
import com.pdfchemy.app.logic.ImageCompressor
import com.pdfchemy.app.logic.ImageOutputFormat
import com.pdfchemy.app.logic.ImageCompressionResult
import com.pdfchemy.app.logic.BatchImageCompressionResult
import com.pdfchemy.app.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("shrinkpdf_settings", Context.MODE_PRIVATE)

    private val historyRepository = com.pdfchemy.app.logic.HistoryRepository(application)
    private val _historyList = MutableStateFlow(historyRepository.getHistory())
    val historyList: StateFlow<List<com.pdfchemy.app.logic.HistoryItem>> = _historyList.asStateFlow()

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = historyRepository.getHistory()
            _historyList.value = items
        }
    }
    private val _isHapticEnabled = MutableStateFlow(prefs.getBoolean("haptic", true))
    val isHapticEnabled: StateFlow<Boolean> = _isHapticEnabled.asStateFlow()

    private val _isSfxEnabled = MutableStateFlow(prefs.getBoolean("sfx", true))
    val isSfxEnabled: StateFlow<Boolean> = _isSfxEnabled.asStateFlow()

    fun setHapticEnabled(enabled: Boolean) {
        _isHapticEnabled.value = enabled
        prefs.edit().putBoolean("haptic", enabled).apply()
    }

    fun setSfxEnabled(enabled: Boolean) {
        _isSfxEnabled.value = enabled
        prefs.edit().putBoolean("sfx", enabled).apply()
    }

    private val _isHistoryEnabled = MutableStateFlow(prefs.getBoolean("history_enabled", true))
    val isHistoryEnabled: StateFlow<Boolean> = _isHistoryEnabled.asStateFlow()

    fun setHistoryEnabled(enabled: Boolean) {
        _isHistoryEnabled.value = enabled
        prefs.edit().putBoolean("history_enabled", enabled).apply()
        if (!enabled) {
            clearHistory()
        }
    }

    fun clearHistory() {
        historyRepository.clearHistory()
        refreshHistory()
    }

    private val _isVanguardEnabled = MutableStateFlow(prefs.getBoolean("vanguard_enabled", true))
    val isVanguardEnabled: StateFlow<Boolean> = _isVanguardEnabled.asStateFlow()

    fun setVanguardEnabled(enabled: Boolean) {
        _isVanguardEnabled.value = enabled
        prefs.edit().putBoolean("vanguard_enabled", enabled).apply()
    }

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private val _safeguardAssessment = MutableStateFlow<com.pdfchemy.app.logic.DeviceGuard.CapacityAssessment?>(null)
    val safeguardAssessment: StateFlow<com.pdfchemy.app.logic.DeviceGuard.CapacityAssessment?> = _safeguardAssessment.asStateFlow()

    fun dismissSafeguardAssessment() {
        _safeguardAssessment.value = null
    }

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    fun setPremiumForScreenshot() {
        _isPremium.value = true
    }

    private val _premiumPrice = MutableStateFlow("\$1.99")
    val premiumPrice: StateFlow<String> = _premiumPrice.asStateFlow()

    private var billingManager: BillingManager? = null

    fun initBilling(context: Context) {
        if (billingManager == null) {
            billingManager = BillingManager(context.applicationContext, viewModelScope)
            viewModelScope.launch {
                billingManager?.isPremium?.collect { _isPremium.value = it }
            }
            viewModelScope.launch {
                billingManager?.premiumPrice?.collect { _premiumPrice.value = it }
            }
        }
    }
    private val _compressionQuality = MutableStateFlow(0.50f)
    val compressionQuality: StateFlow<Float> = _compressionQuality.asStateFlow()

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<Long>(-1L)
    val selectedFileSize: StateFlow<Long> = _selectedFileSize.asStateFlow()

    private val _pdfAnalysis = MutableStateFlow<PdfAnalysis?>(null)
    val pdfAnalysis: StateFlow<PdfAnalysis?> = _pdfAnalysis.asStateFlow()

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _useGrayscale = MutableStateFlow(false)
    val useGrayscale: StateFlow<Boolean> = _useGrayscale.asStateFlow()

    private val _useLossless = MutableStateFlow(false)
    val useLossless: StateFlow<Boolean> = _useLossless.asStateFlow()

    private val _stripMetadata = MutableStateFlow(false)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata.asStateFlow()

    private val metadataManager = PdfMetadataManager()

    private val _currentMetadata = MutableStateFlow<PdfMetadata?>(null)
    val currentMetadata: StateFlow<PdfMetadata?> = _currentMetadata.asStateFlow()

    sealed class UiState {
        object Idle : UiState()
        object Processing : UiState()
        data class BatchProcessing(val current: Int, val total: Int, val currentFileName: String) : UiState()
        data class Success(val title: String, val message: String, val outputUris: List<Uri> = emptyList()) : UiState()
        data class Warning(val title: String, val message: String, val outputUris: List<Uri> = emptyList()) : UiState()
        data class Error(val message: String, val technicalDetails: String? = null) : UiState()
    }

    data class SelectedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val analysis: PdfAnalysis? = null,
        val isAnalyzing: Boolean = false
    )

    fun setQuality(quality: Float) {
        _compressionQuality.value = quality
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun purchasePremium(activity: android.app.Activity) {
        billingManager?.launchPurchaseFlow(activity)
    }

    fun setUseGrayscale(value: Boolean) {
        _useGrayscale.value = value
    }

    fun setUseLossless(value: Boolean) {
        _useLossless.value = value
    }

    fun setStripMetadata(value: Boolean) {
        _stripMetadata.value = value
    }

    fun onFileSelected(context: Context, uri: Uri) {
        _pdfAnalysis.value = null
        _selectedFileSize.value = -1L
        _useGrayscale.value = false
        _useLossless.value = false
        _stripMetadata.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val size = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            withContext(Dispatchers.Main) {
                _selectedFileSize.value = size
                _isAnalyzing.value = true
            }

            val analysisResult = PdfCompressor.analyzePdf(context, uri)
            withContext(Dispatchers.Main) {
                _isAnalyzing.value = false
                analysisResult.onSuccess { analysis ->
                    _pdfAnalysis.value = analysis
                    _compressionQuality.value = analysis.recommendedQuality
                    
                    // Smart auto-toggles recommendation based on scenario
                    applyScenarioDefaults(analysis.scenario)

                    // Pre-flight Device Capacity Assessment
                    val guardCheck = com.pdfchemy.app.logic.DeviceGuard.assessTask(
                        context = context,
                        pageCount = analysis.pageCount,
                        fileSizeBytes = size,
                        isImageHeavy = analysis.imageCount > 10
                    )
                    if (guardCheck.status != com.pdfchemy.app.logic.DeviceGuard.CapacityStatus.SAFE) {
                        _safeguardAssessment.value = guardCheck
                    }
                }.onFailure {
                    _compressionQuality.value = 0.50f
                    _useGrayscale.value = false
                    _useLossless.value = false
                    _stripMetadata.value = false
                }
            }
        }
    }

    fun loadTextFromFile(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val stringBuilder = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        stringBuilder.append(line).append("\n")
                    }
                    _inputText.value = stringBuilder.toString()
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Failed to read text file: ${e.message}")
            }
        }
    }

    private val _targetMb = MutableStateFlow<Float?>(null)
    val targetMb: StateFlow<Float?> = _targetMb.asStateFlow()

    fun setTargetMb(mb: Float?) {
        _targetMb.value = mb
    }

    // =============================================================================================
    // [FEATURE: PDF Compressor] (FEATURES_REGISTRY Android §1: Compression & Optimization)
    // =============================================================================================
    fun compressPdf(context: Context, sourceUri: Uri, destUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            
            val result = PdfCompressor.compressPdf(
                context = context,
                sourceUri = sourceUri,
                destUri = destUri,
                quality = _compressionQuality.value,
                useGrayscale = _useGrayscale.value,
                useLossless = _useLossless.value,
                stripMetadata = _stripMetadata.value,
                targetMb = _targetMb.value
            )
            
            result.onSuccess { report ->
                if (report.hasSignatures) {
                    _warning.value = context.getString(R.string.msg_compress_warning_signature)
                }

                val compressedSize = try {
                    context.contentResolver.openFileDescriptor(destUri, "r")?.use { it.statSize } ?: -1L
                } catch (e: Exception) {
                    -1L
                }

                val originalSize = report.originalSize
                
                val reductionDetails = StringBuilder().apply {
                    if (originalSize > 0 && compressedSize > 0) {
                        val reductionPercent = ((originalSize - compressedSize).toFloat() / originalSize * 100).toInt()
                        val saved = formatSize(originalSize - compressedSize)
                        if (compressedSize > originalSize) {
                            append(context.getString(R.string.msg_compress_slightly_larger))
                        } else {
                            append(context.getString(R.string.msg_compress_reduced, reductionPercent, saved))
                        }
                        
                        val verdict = when {
                            compressedSize > originalSize -> context.getString(R.string.msg_compress_verdict_no_benefit)
                            reductionPercent >= 50 -> context.getString(R.string.msg_compress_verdict_excellent)
                            reductionPercent >= 20 -> context.getString(R.string.msg_compress_verdict_good)
                            reductionPercent >= 5 -> context.getString(R.string.msg_compress_verdict_minor)
                            else -> context.getString(R.string.msg_compress_verdict_minimal)
                        }
                        append(context.getString(R.string.msg_compress_verdict_format, verdict))
                        append(context.getString(R.string.msg_compress_original_size, formatSize(originalSize)))
                        append(context.getString(R.string.msg_compress_compressed_size, formatSize(compressedSize)))
                        append(context.getString(R.string.msg_compress_settings_used))
                        if (_targetMb.value != null) {
                            append(context.getString(R.string.msg_compress_target_size, _targetMb.value.toString()))
                            append(context.getString(R.string.msg_compress_auto_optimized))
                        } else {
                            append(context.getString(R.string.msg_compress_quality_preset, (compressionQuality.value * 100).toInt()))
                        }
                        append(context.getString(R.string.msg_compress_grayscale, if (useGrayscale.value) context.getString(R.string.msg_compress_enabled) else context.getString(R.string.msg_compress_disabled)))
                        append(context.getString(R.string.msg_compress_lossless_zip, if (useLossless.value) context.getString(R.string.msg_compress_enabled) else context.getString(R.string.msg_compress_disabled)))
                        append(context.getString(R.string.msg_compress_metadata_removed, if (stripMetadata.value) context.getString(R.string.value_yes) else context.getString(R.string.value_no)))
                    } else {
                        append(context.getString(R.string.msg_compress_success))
                    }
                }.toString()

                if (report.targetMissed) {
                    _uiState.value = UiState.Warning(
                        context.getString(R.string.title_target_size_unreachable),
                        context.getString(R.string.msg_compress_limit_reached, _targetMb.value.toString()) + reductionDetails,
                        listOf(destUri)
                    )
                } else {
                    _uiState.value = UiState.Success(context.getString(R.string.title_compress_result), reductionDetails, listOf(destUri))
                }
                historyRepository.addHistoryItem(destUri, context.getString(R.string.label_compressed_pdf), context.getString(R.string.action_compress))
                refreshHistory()
                
            }.onFailure { error ->
                val userFriendlyMessage = when {
                    error.message?.contains("password", ignoreCase = true) == true || error.message?.contains("encrypt", ignoreCase = true) == true ->
                        context.getString(R.string.error_user_encrypted)
                    error.message?.contains("memory", ignoreCase = true) == true || error.message?.contains("dimension", ignoreCase = true) == true || error.message?.contains("bitmap", ignoreCase = true) == true ->
                        context.getString(R.string.error_user_memory)
                    error.message?.contains("damaged", ignoreCase = true) == true || error.message?.contains("corrupt", ignoreCase = true) == true || error.message?.contains("structure", ignoreCase = true) == true ->
                        context.getString(R.string.error_user_corrupt)
                    error.message?.contains("destination", ignoreCase = true) == true || error.message?.contains("space", ignoreCase = true) == true ->
                        context.getString(R.string.error_user_storage)
                    else -> error.message ?: context.getString(R.string.msg_error_unknown)
                }

                val techDetails = "${error.javaClass.name}: ${error.message}\n" +
                        (error.cause?.let { "Caused by: ${it.javaClass.name}: ${it.message}\n" } ?: "") +
                        error.stackTrace.take(8).joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }

                _uiState.value = UiState.Error(userFriendlyMessage, techDetails)
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Text to PDF Converter] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun convertTextToPdf(context: Context, destUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            
            val result = TextToPdfConverter.convert(context, _inputText.value, destUri)
            
            result.onSuccess {
                historyRepository.addHistoryItem(destUri, context.getString(R.string.history_text_to_pdf), context.getString(R.string.history_convert))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_pdf_created), context.getString(R.string.success_doc_saved), listOf(destUri))
            }.onFailure { error ->
                _uiState.value = UiState.Error(error.message ?: "Failed to create PDF.")
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Images to PDF] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun convertImagesToPdf(context: Context, imageUris: List<Uri>, destUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                withContext(Dispatchers.IO) {
                    val document = com.tom_roush.pdfbox.pdmodel.PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                    try {
                        for (uri in imageUris) {
                            // Decode bounds first to compute sample size and prevent OOM
                            val boundsOptions = BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                BitmapFactory.decodeStream(inputStream, null, boundsOptions)
                            }
                            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) continue

                            // Cap maximum dimension to 2048px for standard high-res document output
                            val maxDim = 2048
                            var sampleSize = 1
                            val maxOriginalDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
                            while ((maxOriginalDim / (sampleSize * 2)) >= maxDim) {
                                sampleSize *= 2
                            }

                            val decodeOptions = BitmapFactory.Options().apply {
                                inSampleSize = sampleSize
                                inPreferredConfig = Bitmap.Config.RGB_565 // Low RAM footprint
                            }

                            val bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
                            }

                            if (bitmap != null) {
                                try {
                                    val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4)
                                    document.addPage(page)

                                    val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(document, bitmap, 0.85f)
                                    val contentStream = com.tom_roush.pdfbox.pdmodel.PDPageContentStream(document, page)
                                    try {
                                        val pageWidth = page.mediaBox.width
                                        val pageHeight = page.mediaBox.height
                                        val margin = 20f
                                        val maxWidth = pageWidth - margin * 2
                                        val maxHeight = pageHeight - margin * 2

                                        val imgWidth = bitmap.width.toFloat()
                                        val imgHeight = bitmap.height.toFloat()

                                        val scale = minOf(maxWidth / imgWidth, maxHeight / imgHeight)
                                        val drawWidth = imgWidth * scale
                                        val drawHeight = imgHeight * scale

                                        val startX = (pageWidth - drawWidth) / 2
                                        val startY = (pageHeight - drawHeight) / 2

                                        contentStream.drawImage(pdImage, startX, startY, drawWidth, drawHeight)
                                    } finally {
                                        contentStream.close()
                                    }
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }

                        context.contentResolver.openOutputStream(destUri)?.use { out ->
                            document.save(out)
                        }
                    } finally {
                        document.close()
                    }
                }
                _uiState.value = UiState.Success(context.getString(R.string.success_pdf_created), context.getString(R.string.success_images_converted))
            } catch (e: Exception) {
                AppLogger.e("Exception in MainViewModel", e)
                _uiState.value = UiState.Error(e.message ?: "Failed to create PDF from images.")
            }
        }
    }

    private val _selectedFiles = MutableStateFlow<List<SelectedFile>>(emptyList())
    val selectedFiles: StateFlow<List<SelectedFile>> = _selectedFiles.asStateFlow()

    fun clearSelectedFiles() {
        _selectedFiles.value = emptyList()
    }

    fun removeSelectedFile(uri: Uri) {
        _selectedFiles.value = _selectedFiles.value.filter { it.uri != uri }
    }

    fun onFilesSelected(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val existingUris = _selectedFiles.value.map { it.uri }.toSet()
            val newUris = uris.filter { it !in existingUris }
            if (newUris.isEmpty()) return@launch

            val newList = newUris.map { uri ->
                val size = try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
                val name = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: "unknown_file.pdf"
                SelectedFile(uri, name, size, isAnalyzing = true)
            }
            _selectedFiles.value = _selectedFiles.value + newList

            // Analyze them concurrently
            withContext(Dispatchers.IO) {
                newList.map { selectedFile ->
                    async {
                        val analysisResult = PdfCompressor.analyzePdf(context, selectedFile.uri)
                        withContext(Dispatchers.Main) {
                            analysisResult.onSuccess { analysis ->
                                _selectedFiles.update { currentFiles ->
                                    currentFiles.map { item ->
                                        if (item.uri == selectedFile.uri) item.copy(analysis = analysis, isAnalyzing = false) else item
                                    }
                                }
                                if (_pdfAnalysis.value == null && _selectedFiles.value.firstOrNull()?.uri == selectedFile.uri) {
                                    _pdfAnalysis.value = analysis
                                    _compressionQuality.value = analysis.recommendedQuality
                                    applyScenarioDefaults(analysis.scenario)
                                }
                            }.onFailure {
                                _selectedFiles.update { currentFiles ->
                                    currentFiles.map { item ->
                                        if (item.uri == selectedFile.uri) item.copy(isAnalyzing = false) else item
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Batch PDF Compressor] (FEATURES_REGISTRY Android §1: Compression & Optimization)
    // =============================================================================================
    fun compressBatch(context: Context, destTreeUri: Uri) {
        if (_uiState.value is UiState.Processing || _uiState.value is UiState.BatchProcessing) return

        viewModelScope.launch(Dispatchers.IO) {
            val files = _selectedFiles.value
            if (files.isEmpty()) {
                _uiState.value = UiState.Error(context.getString(R.string.msg_no_files_batch))
                return@launch
            }

            val directory = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destTreeUri)
            if (directory == null || !directory.exists()) {
                _uiState.value = UiState.Error(context.getString(R.string.msg_invalid_folder))
                return@launch
            }

            val total = files.size
            var successCount = 0
            val sizeSavedMap = mutableMapOf<Int, Long>()
            val outputUris = mutableListOf<Uri>()

            files.forEachIndexed { index, selectedFile ->
                _uiState.value = UiState.BatchProcessing(index + 1, total, selectedFile.name)

                val outputName = "compressed_${selectedFile.name}"
                val outputDoc = directory.createFile("application/pdf", outputName)
                if (outputDoc == null) {
                    return@forEachIndexed
                }

                val compressResult = PdfCompressor.compressPdf(
                    context = context,
                    sourceUri = selectedFile.uri,
                    destUri = outputDoc.uri,
                    quality = _compressionQuality.value,
                    useGrayscale = _useGrayscale.value,
                    useLossless = _useLossless.value,
                    stripMetadata = _stripMetadata.value,
                    targetMb = _targetMb.value
                )

                compressResult.onSuccess { report ->
                    outputUris.add(outputDoc.uri)
                    val compressedSize = try {
                        context.contentResolver.openFileDescriptor(outputDoc.uri, "r")?.use { it.statSize } ?: -1L
                    } catch (e: Exception) { -1L }
                    if (report.originalSize > 0 && compressedSize > 0 && compressedSize < report.originalSize) {
                        sizeSavedMap[index] = report.originalSize - compressedSize
                    }
                    successCount++
                }
            }

            if (successCount == total) {
                historyRepository.addHistoryItem(destTreeUri, context.getString(R.string.label_batch_compress_folder), context.getString(R.string.action_compress_batch))
                refreshHistory()
                val totalSavedBytes = sizeSavedMap.values.sum()
                val totalSavedStr = formatSize(totalSavedBytes)
                _uiState.value = UiState.Success(
                    context.getString(R.string.title_batch_compress_result),
                    context.getString(R.string.msg_batch_compress_success_all, total, totalSavedStr),
                    outputUris
                )
            } else if (successCount > 0) {
                historyRepository.addHistoryItem(destTreeUri, context.getString(R.string.label_batch_compress_folder), context.getString(R.string.action_compress_batch))
                refreshHistory()
                val totalSavedBytes = sizeSavedMap.values.sum()
                val totalSavedStr = formatSize(totalSavedBytes)
                _uiState.value = UiState.Success(
                    context.getString(R.string.title_batch_compress_result),
                    context.getString(R.string.msg_batch_compress_success_partial, successCount, total, totalSavedStr),
                    outputUris
                )
            } else {
                _uiState.value = UiState.Error(context.getString(R.string.msg_batch_compress_fail))
            }
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
        _warning.value = null
        _pdfAnalysis.value = null
        _isAnalyzing.value = false
        _useGrayscale.value = false
        _useLossless.value = false
        _stripMetadata.value = false
        _selectedFiles.value = emptyList()
    }

    private fun applyScenarioDefaults(scenario: com.pdfchemy.app.logic.PdfScenario) {
        when (scenario) {
            com.pdfchemy.app.logic.PdfScenario.SIGNED_OFFICIAL -> {
                _useGrayscale.value = false
                _useLossless.value = true
                _stripMetadata.value = false
            }
            com.pdfchemy.app.logic.PdfScenario.SCANNED_IMAGE_HEAVY -> {
                _useGrayscale.value = true
                _useLossless.value = false
                _stripMetadata.value = true
            }
            com.pdfchemy.app.logic.PdfScenario.TEXT_VECTOR -> {
                _useGrayscale.value = false
                _useLossless.value = true
                _stripMetadata.value = true
            }
            com.pdfchemy.app.logic.PdfScenario.MIXED -> {
                _useGrayscale.value = false
                _useLossless.value = false
                _stripMetadata.value = true
            }
        }
    }

    fun dismissWarning() {
        _warning.value = null
    }

    fun estimateCompressedSize(originalSize: Long, quality: Float, useLossless: Boolean, useGrayscale: Boolean, scenario: com.pdfchemy.app.logic.PdfScenario?): Long {
        if (originalSize <= 0) return 0L
        if (useLossless) return (originalSize * 1.05).toLong()
        
        // Use continuous ratio estimation instead of fixed breakpoints
        var ratio = 0.10f + (quality * 0.85f) // Maps 0.25 -> ~0.31, 0.50 -> ~0.52, 0.75 -> ~0.74
        if (scenario == com.pdfchemy.app.logic.PdfScenario.TEXT_VECTOR) {
            ratio = 0.80f + (quality * 0.20f) // Maps 0.25 -> 0.85, 0.50 -> 0.90, 0.75 -> 0.95
        }
        
        val grayscaleDiscount = if (useGrayscale) 0.85f else 1.0f
        return (originalSize * ratio * grayscaleDiscount).toLong()
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 0) return "-${formatSize(-bytes)}"
        if (bytes == 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format("%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    // =============================================================================================
    // [FEATURE: Merge PDFs] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun mergePdfs(context: Context, sourceUris: List<Uri>, destUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                PdfManipulator.mergePdfs(context, sourceUris, destUri)
                historyRepository.addHistoryItem(destUri, context.getString(R.string.history_merged_pdf), context.getString(R.string.desc_merge))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_merge_complete), context.getString(R.string.success_merged_docs, sourceUris.size), listOf(destUri))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to merge PDFs.")
            }
        }
    }

    fun resetMetadata() {
        _currentMetadata.value = null
        _pdfAnalysis.value = null
    }

    // =============================================================================================
    // [FEATURE: Metadata Sanitizer & Inspector] (FEATURES_REGISTRY Android §5: Security, Privacy & Compliance)
    // =============================================================================================
    fun loadMetadata(context: Context, uri: Uri) {
        viewModelScope.launch {
            _isAnalyzing.value = true
            val metadataResult = metadataManager.getMetadata(context, uri)
            val analysisResult = PdfCompressor.analyzePdf(context, uri)

            if (metadataResult.isSuccess && analysisResult.isSuccess) {
                _currentMetadata.value = metadataResult.getOrNull()
                _pdfAnalysis.value = analysisResult.getOrNull()
            } else {
                _uiState.value = UiState.Error(context.getString(R.string.msg_error_unknown))
            }
            _isAnalyzing.value = false
        }
    }

    fun updateMetadata(context: Context, sourceUri: Uri, destUri: Uri, newMetadata: PdfMetadata) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing
            val result = metadataManager.updateMetadata(context, sourceUri, destUri, newMetadata)
            if (result.isSuccess) {
                historyRepository.addHistoryItem(destUri, context.getString(R.string.history_updated_metadata), context.getString(R.string.history_metadata))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_metadata_updated), context.getString(R.string.success_metadata_updated), listOf(destUri))
            } else {
                _uiState.value = UiState.Error(result.exceptionOrNull()?.message ?: "Failed to update metadata.")
            }
        }
    }

    fun clearMetadata(context: Context, sourceUri: Uri, destUri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing
            val result = metadataManager.clearMetadata(context, sourceUri, destUri)
            if (result.isSuccess) {
                historyRepository.addHistoryItem(destUri, context.getString(R.string.history_cleared_metadata), context.getString(R.string.history_metadata))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_metadata_removed), context.getString(R.string.success_metadata_stripped), listOf(destUri))
            } else {
                _uiState.value = UiState.Error(result.exceptionOrNull()?.message ?: "Failed to remove metadata.")
            }
        }
    }

    fun clearMetadataOverwrite(context: Context, sourceUri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Processing
            val result = metadataManager.clearMetadataOverwrite(context, sourceUri)
            if (result.isSuccess) {
                historyRepository.addHistoryItem(sourceUri, context.getString(R.string.history_cleared_metadata), context.getString(R.string.history_metadata))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_metadata_removed), context.getString(R.string.success_metadata_stripped_overwrite), listOf(sourceUri))
            } else {
                _uiState.value = UiState.Error(result.exceptionOrNull()?.message ?: "Failed to overwrite metadata.")
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Split PDFs (Range / All)] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun splitPdf(context: Context, sourceUri: Uri, destTreeUri: Uri, pageRange: String? = null) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                val directory = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destTreeUri)
                if (directory == null || !directory.exists()) {
                    _uiState.value = UiState.Error(context.getString(R.string.msg_invalid_folder))
                    return@launch
                }
                
                val baseName = com.pdfchemy.app.utils.FileUtils.getFileName(context, sourceUri)?.substringBeforeLast(".") ?: "split_doc"
                
                PdfManipulator.splitPdf(context, sourceUri, directory, baseName, pageRange)
                historyRepository.addHistoryItem(destTreeUri, context.getString(R.string.history_split_pdf_folder), context.getString(R.string.desc_split))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_split_complete), context.getString(R.string.success_split_success), listOf(destTreeUri))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to split PDF.")
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Split by Blank Pages] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun splitByBlankPages(context: Context, sourceUri: Uri, destTreeUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                val directory = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destTreeUri)
                if (directory == null || !directory.exists()) {
                    _uiState.value = UiState.Error(context.getString(R.string.msg_invalid_folder))
                    return@launch
                }
                val baseName = com.pdfchemy.app.utils.FileUtils.getFileName(context, sourceUri)?.substringBeforeLast(".") ?: "split_doc"
                val results = PdfManipulator.splitByBlankPages(context, sourceUri, directory, baseName)
                if (results.isEmpty()) {
                    _uiState.value = UiState.Error("No blank divider pages detected.")
                    return@launch
                }
                historyRepository.addHistoryItem(destTreeUri, context.getString(R.string.history_split_pdf_folder), context.getString(R.string.desc_split))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_split_complete), context.getString(R.string.split_blank_pages_found, results.size), listOf(destTreeUri))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to split PDF by blank pages.")
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Split by Bookmarks / Chapters] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun splitByBookmarks(context: Context, sourceUri: Uri, destTreeUri: Uri) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                val directory = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, destTreeUri)
                if (directory == null || !directory.exists()) {
                    _uiState.value = UiState.Error(context.getString(R.string.msg_invalid_folder))
                    return@launch
                }
                val baseName = com.pdfchemy.app.utils.FileUtils.getFileName(context, sourceUri)?.substringBeforeLast(".") ?: "split_doc"
                val results = PdfManipulator.splitByBookmarks(context, sourceUri, directory, baseName)
                if (results.isEmpty()) {
                    _uiState.value = UiState.Error(context.getString(R.string.split_no_bookmarks))
                    return@launch
                }
                historyRepository.addHistoryItem(destTreeUri, context.getString(R.string.history_split_pdf_folder), context.getString(R.string.desc_split))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_split_complete), context.getString(R.string.split_bookmarks_found, results.size), listOf(destTreeUri))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to split PDF by bookmarks.")
            }
        }
    }

    
    // =============================================================================================
    // [FEATURE: Delete Pages] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun deletePages(context: Context, sourceUri: Uri, destUri: Uri, pageRange: String) {
        if (_uiState.value is UiState.Processing) return

        viewModelScope.launch {
            _uiState.value = UiState.Processing
            try {
                PdfManipulator.deletePages(context, sourceUri, destUri, pageRange)
                historyRepository.addHistoryItem(destUri, com.pdfchemy.app.utils.FileUtils.getFileName(context, destUri) ?: context.getString(R.string.history_unknown), context.getString(R.string.history_organize))
                refreshHistory()
                _uiState.value = UiState.Success(context.getString(R.string.success_pages_deleted), context.getString(R.string.success_pages_removed), listOf(destUri))
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to delete pages.")
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Extract Images from PDF] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun extractImagesFromPdf(pdfUri: Uri, outputDirectory: androidx.documentfile.provider.DocumentFile, context: Context, onComplete: (Int, Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.Processing
            var extractedCount = 0
            var errorCount = 0
            try {
                context.contentResolver.openInputStream(pdfUri)?.use { inputStream ->
                    val document = PDDocument.load(inputStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                    for (pageIndex in 0 until document.numberOfPages) {
                        val page = document.getPage(pageIndex)
                        val resources = page.resources
                        if (resources != null) {
                            val xObjectNames = resources.xObjectNames
                            for (xObjectName in xObjectNames) {
                                val xObject = resources.getXObject(xObjectName)
                                if (xObject is PDImageXObject) {
                                    try {
                                        val bitmap = xObject.image
                                        if (bitmap != null) {
                                            val newFile = outputDirectory.createFile("image/jpeg", "extracted_image_${System.currentTimeMillis()}.jpg")
                                            newFile?.uri?.let { newUri ->
                                                context.contentResolver.openOutputStream(newUri)?.use { out ->
                                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                                                    extractedCount++
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        AppLogger.e("Exception in MainViewModel", e)
                                        errorCount++
                                    }
                                }
                            }
                        }
                    }
                    document.close()
                }
                
                withContext(Dispatchers.Main) {
                    historyRepository.addHistoryItem(outputDirectory.uri, context.getString(R.string.history_extracted_images_folder), context.getString(R.string.history_extract_images))
                    refreshHistory()
                    onComplete(extractedCount, errorCount)
                    _uiState.value = UiState.Idle
                }
            } catch (e: Exception) {
                AppLogger.e("Exception in MainViewModel", e)
                withContext(Dispatchers.Main) {
                    onComplete(extractedCount, errorCount)
                    _uiState.value = UiState.Idle
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Rotate Pages] (FEATURES_REGISTRY Android §2: Page Studio & Organization)
    // =============================================================================================
    fun rotatePdf(context: Context, sourceUri: Uri, destUri: Uri, degrees: Int, pageRange: String = "") {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                PdfManipulator.rotatePdf(context, sourceUri, destUri, degrees, pageRange)
                withContext(Dispatchers.Main) {
                    historyRepository.addHistoryItem(destUri, context.getString(R.string.history_rotated_pdf), context.getString(R.string.history_rotate_pdf))
                    refreshHistory()
                    _uiState.value = UiState.Success(context.getString(R.string.success_title), context.getString(R.string.success_doc_saved), listOf(destUri))
                }
            } catch (e: Exception) {
                AppLogger.e("Exception in MainViewModel", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Extract Plain Text from PDF] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun extractTextFromPdf(context: Context, sourceUri: Uri, destUri: Uri) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val success = PdfTextExtractor.extractText(context, sourceUri, destUri)
                withContext(Dispatchers.Main) {
                    if (success) {
                        historyRepository.addHistoryItem(destUri, context.getString(R.string.history_extracted_text), context.getString(R.string.history_pdf_to_text))
                        refreshHistory()
                        _uiState.value = UiState.Success(context.getString(R.string.success_title), context.getString(R.string.success_doc_saved), listOf(destUri))
                    } else {
                        _uiState.value = UiState.Error(context.getString(R.string.msg_error_unknown))
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("Exception in MainViewModel", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Image Compressor & Batch Image Studio] (FEATURES_REGISTRY Android §1: Compression)
    // =============================================================================================
    fun compressImage(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        quality: Int,
        format: ImageOutputFormat,
        maxDimension: Int,
        stripExif: Boolean,
        onResult: (ImageCompressionResult) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val result = ImageCompressor.compressImage(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    quality = quality,
                    targetFormat = format,
                    maxDimension = maxDimension,
                    stripExif = stripExif
                )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_compressed_image),
                            context.getString(R.string.menu_compress_image)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Idle
                    } else {
                        _uiState.value = UiState.Error(result.error ?: context.getString(R.string.error))
                    }
                    onResult(result)
                }
            } catch (e: Exception) {
                AppLogger.e("Exception in compressImage", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: "Image compression failed")
                    onResult(
                        ImageCompressionResult(
                            success = false,
                            originalSize = 0L,
                            compressedSize = 0L,
                            width = 0,
                            height = 0,
                            format = "",
                            error = e.message
                        )
                    )
                }
            }
        }
    }

    fun compressImageToTargetSize(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        targetSizeBytes: Long,
        format: ImageOutputFormat,
        stripExif: Boolean,
        onResult: (ImageCompressionResult) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val result = ImageCompressor.compressToTargetSize(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    targetSizeBytes = targetSizeBytes,
                    targetFormat = format,
                    stripExif = stripExif
                )
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_compressed_image),
                            context.getString(R.string.menu_compress_image)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Idle
                    } else {
                        _uiState.value = UiState.Error(result.error ?: context.getString(R.string.error))
                    }
                    onResult(result)
                }
            } catch (e: Exception) {
                AppLogger.e("Exception in compressImageToTargetSize", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: "Target size compression failed")
                    onResult(
                        ImageCompressionResult(
                            success = false,
                            originalSize = 0L,
                            compressedSize = 0L,
                            width = 0,
                            height = 0,
                            format = "",
                            error = e.message
                        )
                    )
                }
            }
        }
    }

    fun compressBatchImages(
        context: Context,
        sourceUris: List<Uri>,
        outputDirectory: DocumentFile,
        quality: Int,
        format: ImageOutputFormat,
        maxDimension: Int,
        stripExif: Boolean,
        onComplete: (BatchImageCompressionResult) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val outputUris = Collections.synchronizedList(mutableListOf<Uri>())
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val totalOriginalBytes = AtomicLong(0L)
            val totalCompressedBytes = AtomicLong(0L)
            val successCount = AtomicInteger(0)
            val failureCount = AtomicInteger(0)
            val completedCount = AtomicInteger(0)
            val semaphore = Semaphore(1)

            coroutineScope {
                sourceUris.mapIndexed { index, uri ->
                    launch(Dispatchers.IO) {
                        semaphore.withPermit {
                            val fileName = com.pdfchemy.app.utils.FileUtils.getFileName(context, uri) ?: "image_${index + 1}.jpg"
                            val baseName = fileName.substringBeforeLast(".")
                            val ext = if (format == ImageOutputFormat.ORIGINAL) {
                                val origExt = fileName.substringAfterLast(".", "jpg").lowercase()
                                if (origExt in listOf("jpg", "jpeg", "png", "webp")) origExt else "jpg"
                            } else {
                                format.extension
                            }
                            val mime = when (ext) {
                                "png" -> "image/png"
                                "webp" -> "image/webp"
                                else -> "image/jpeg"
                            }

                            val originalSize = ImageCompressor.getUriFileSize(context, uri)
                            totalOriginalBytes.addAndGet(originalSize)

                            val targetFile = outputDirectory.createFile(mime, "${baseName}_compressed.$ext")
                            if (targetFile != null) {
                                val result = ImageCompressor.compressImage(
                                    context = context,
                                    sourceUri = uri,
                                    destUri = targetFile.uri,
                                    quality = quality,
                                    targetFormat = format,
                                    maxDimension = maxDimension,
                                    stripExif = stripExif
                                )

                                if (result.success) {
                                    successCount.incrementAndGet()
                                    totalCompressedBytes.addAndGet(result.compressedSize)
                                    outputUris.add(targetFile.uri)
                                } else {
                                    failureCount.incrementAndGet()
                                    errors.add("$fileName: ${result.error ?: "Compression failed"}")
                                    targetFile.delete()
                                }
                            } else {
                                failureCount.incrementAndGet()
                                errors.add("$fileName: Failed to create output file")
                            }

                            val done = completedCount.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                _uiState.value = UiState.BatchProcessing(done, sourceUris.size, fileName)
                            }
                        }
                    }
                }.joinAll()
            }

            val batchResult = BatchImageCompressionResult(
                totalCount = sourceUris.size,
                successCount = successCount.get(),
                failureCount = failureCount.get(),
                totalOriginalBytes = totalOriginalBytes.get(),
                totalCompressedBytes = totalCompressedBytes.get(),
                outputUris = ArrayList(outputUris),
                errors = ArrayList(errors)
            )

            withContext(Dispatchers.Main) {
                if (outputUris.isNotEmpty()) {
                    historyRepository.addHistoryItem(
                        outputDirectory.uri,
                        context.getString(R.string.history_compressed_images_folder),
                        context.getString(R.string.menu_compress_image)
                    )
                    refreshHistory()
                }
                _uiState.value = UiState.Idle
                onComplete(batchResult)
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Visual PDF Editor] (FEATURES_REGISTRY Android §4: Form Filling & Document Editing)
    // =============================================================================================
    fun exportEditedPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        modifications: Map<Int, com.pdfchemy.app.logic.PageModification>,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            val result = com.pdfchemy.app.logic.PdfEditor.exportModifiedPdf(
                context = context,
                sourceUri = sourceUri,
                destUri = destUri,
                modifications = modifications
            )
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    historyRepository.addHistoryItem(
                        destUri,
                        context.getString(R.string.history_edited_pdf),
                        context.getString(R.string.menu_edit_pdf)
                    )
                    refreshHistory()
                    _uiState.value = UiState.Idle
                    onComplete(true)
                } else {
                    val errorMsg = result.exceptionOrNull()?.message ?: context.getString(R.string.error)
                    _uiState.value = UiState.Error(errorMsg)
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Encrypt / Password Protect] (FEATURES_REGISTRY Android §5: Security, Privacy & Compliance)
    // =============================================================================================
    fun protectPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        userPassword: String,
        ownerPassword: String = userPassword,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                com.pdfchemy.app.logic.PdfManipulator.protectPdf(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    userPassword = userPassword,
                    ownerPassword = ownerPassword
                )
                withContext(Dispatchers.Main) {
                    historyRepository.addHistoryItem(
                        destUri,
                        context.getString(R.string.history_protected_pdf),
                        context.getString(R.string.menu_protect_pdf)
                    )
                    refreshHistory()
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_protect_success),
                        context.getString(R.string.desc_protect_success),
                        listOf(destUri)
                    )
                    onComplete(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Decrypt / Unlock PDF] (FEATURES_REGISTRY Android §5: Security, Privacy & Compliance)
    // =============================================================================================
    fun unlockPdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        password: String,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                com.pdfchemy.app.logic.PdfManipulator.unlockPdf(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    password = password
                )
                withContext(Dispatchers.Main) {
                    historyRepository.addHistoryItem(
                        destUri,
                        context.getString(R.string.history_unlocked_pdf),
                        context.getString(R.string.menu_unlock_pdf)
                    )
                    refreshHistory()
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_unlock_success),
                        context.getString(R.string.desc_unlock_success),
                        listOf(destUri)
                    )
                    onComplete(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.error_invalid_password))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: PDF to High-Res Images] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun convertPdfToImages(
        context: Context,
        sourceUri: Uri,
        outputDirectory: androidx.documentfile.provider.DocumentFile,
        baseName: String,
        formatName: String = "JPEG",
        quality: Int = 90,
        targetWidth: Int = 1440,
        onComplete: (List<Uri>) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val uris = com.pdfchemy.app.logic.PdfManipulator.convertPdfToImages(
                    context = context,
                    sourceUri = sourceUri,
                    outputDirectory = outputDirectory,
                    baseName = baseName,
                    formatName = formatName,
                    quality = quality,
                    targetWidth = targetWidth
                )
                withContext(Dispatchers.Main) {
                    if (uris.isNotEmpty()) {
                        historyRepository.addHistoryItem(
                            outputDirectory.uri,
                            context.getString(R.string.history_pdf_to_images_folder),
                            context.getString(R.string.menu_pdf_to_images)
                        )
                        refreshHistory()
                    }
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_pdf_to_images_success),
                        context.getString(R.string.desc_pdf_to_images_success, uris.size),
                        uris
                    )
                    onComplete(uris)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(emptyList())
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: AcroForm Interactive Filler] (FEATURES_REGISTRY Android §4: Form Filling & Editing)
    // =============================================================================================
    fun fillAndSaveForm(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        fieldValues: Map<String, String>,
        flatten: Boolean = false,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val success = com.pdfchemy.app.logic.AcroFormEngine.fillAndSaveForm(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    fieldData = fieldValues,
                    flatten = flatten
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_filled_form),
                            context.getString(R.string.menu_fill_form)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Success(
                            context.getString(R.string.title_form_save_success),
                            context.getString(R.string.desc_form_save_success),
                            listOf(destUri)
                        )
                    } else {
                        _uiState.value = UiState.Error(context.getString(R.string.error_form_save_failed))
                    }
                    onComplete(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: On-Device OCR] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun createSearchablePdf(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val success = com.pdfchemy.app.logic.PdfOcrEngine.createSearchablePdf(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    onProgress = { current, total ->
                        viewModelScope.launch(Dispatchers.Main) {
                            onProgress(current, total)
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_searchable_ocr_pdf),
                            context.getString(R.string.menu_ocr_pdf)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Success(
                            context.getString(R.string.title_ocr_success),
                            context.getString(R.string.desc_ocr_success),
                            listOf(destUri)
                        )
                    } else {
                        _uiState.value = UiState.Error(context.getString(R.string.error_ocr_failed))
                    }
                    onComplete(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Permanent Smart Redaction] (FEATURES_REGISTRY Android §5: Security, Privacy & Compliance)
    // =============================================================================================
    fun applyRedactions(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        redactions: List<com.pdfchemy.app.logic.RedactionBox>,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val success = com.pdfchemy.app.logic.PdfRedactionEngine.applyRedactions(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    redactions = redactions
                ).isSuccess
                withContext(Dispatchers.Main) {
                    if (success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_redacted_pdf),
                            context.getString(R.string.menu_redact_pdf)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Success(
                            context.getString(R.string.title_redact_success),
                            context.getString(R.string.desc_redact_success),
                            listOf(destUri)
                        )
                    } else {
                        _uiState.value = UiState.Error(context.getString(R.string.error_redact_failed))
                    }
                    onComplete(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Visual Signer] (FEATURES_REGISTRY Android §4: Form Filling & Document Editing)
    // =============================================================================================
    fun applySignatures(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        signatures: List<com.pdfchemy.app.logic.PlacedSignature>,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            try {
                val success = com.pdfchemy.app.logic.SignatureEngine.applySignatures(
                    context = context,
                    sourceUri = sourceUri,
                    destUri = destUri,
                    signatures = signatures
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        historyRepository.addHistoryItem(
                            destUri,
                            context.getString(R.string.history_signed_pdf),
                            context.getString(R.string.menu_sign_pdf)
                        )
                        refreshHistory()
                        _uiState.value = UiState.Success(
                            context.getString(R.string.title_sign_success),
                            context.getString(R.string.desc_sign_success),
                            listOf(destUri)
                        )
                    } else {
                        _uiState.value = UiState.Error(context.getString(R.string.error_sign_failed))
                    }
                    onComplete(success)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Office Export (Word / Excel / PPTX)] (FEATURES_REGISTRY Android §3: Creation & Conversion)
    // =============================================================================================
    fun exportPdfToOffice(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        format: com.pdfchemy.app.logic.OfficeFormat,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            val result = when (format) {
                com.pdfchemy.app.logic.OfficeFormat.WORD -> com.pdfchemy.app.logic.OfficeExportEngine.exportToWord(context, sourceUri, destUri)
                com.pdfchemy.app.logic.OfficeFormat.EXCEL -> com.pdfchemy.app.logic.OfficeExportEngine.exportToExcel(context, sourceUri, destUri)
                com.pdfchemy.app.logic.OfficeFormat.POWERPOINT -> com.pdfchemy.app.logic.OfficeExportEngine.exportToPowerPoint(context, sourceUri, destUri)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { report ->
                    historyRepository.addHistoryItem(
                        destUri,
                        report.format.displayName,
                        context.getString(R.string.desc_office_export_success, report.pageCount)
                    )
                    refreshHistory()
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_office_export_success),
                        context.getString(R.string.desc_office_export_success, report.pageCount),
                        listOf(destUri)
                    )
                    onComplete(true)
                }.onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Image Replacer] (FEATURES_REGISTRY Android §4: Form Filling & Document Editing)
    // =============================================================================================
    fun replaceEmbeddedImage(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        pageIndex: Int,
        resourceName: String,
        replacementBitmap: Bitmap,
        isLossless: Boolean = true,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            val result = com.pdfchemy.app.logic.PdfImageReplacerEngine.replaceEmbeddedImage(
                context,
                sourcePdfUri,
                destPdfUri,
                pageIndex,
                resourceName,
                replacementBitmap,
                isLossless
            )
            withContext(Dispatchers.Main) {
                result.onSuccess {
                    refreshHistory()
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_image_replace_success),
                        context.getString(R.string.desc_image_replace_success),
                        listOf(destPdfUri)
                    )
                    onComplete(true)
                }.onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    // =============================================================================================
    // [FEATURE: Find & Replace Text] (FEATURES_REGISTRY Android §4: Form Filling & Document Editing)
    // =============================================================================================
    fun replaceTextOccurrences(
        context: Context,
        sourcePdfUri: Uri,
        destPdfUri: Uri,
        findText: String,
        replaceText: String,
        matchCase: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        _uiState.value = UiState.Processing
        viewModelScope.launch {
            val result = com.pdfchemy.app.logic.PdfFindAndReplaceEngine.replaceAll(
                context,
                sourcePdfUri,
                destPdfUri,
                findText,
                replaceText,
                matchCase
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { count ->
                    refreshHistory()
                    _uiState.value = UiState.Success(
                        context.getString(R.string.title_find_replace_success),
                        context.getString(R.string.desc_find_replace_success, count),
                        listOf(destPdfUri)
                    )
                    onComplete(true)
                }.onFailure { e ->
                    _uiState.value = UiState.Error(e.message ?: context.getString(R.string.msg_error_unknown))
                    onComplete(false)
                }
            }
        }
    }

    fun notifySuccess(title: String, message: String, outputUri: Uri) {
        historyRepository.addHistoryItem(outputUri, title, message)
        refreshHistory()
        _uiState.value = UiState.Success(title, message, listOf(outputUri))
    }

    fun notifyError(message: String, technicalDetails: String? = null) {
        _uiState.value = UiState.Error(message, technicalDetails)
    }

    fun showSuccessToast(title: String, message: String) {
        _uiState.value = UiState.Success(title, message, emptyList())
    }

    fun showErrorToast(title: String, message: String, technicalDetails: String? = null) {
        _uiState.value = UiState.Error(if (message.isBlank()) title else "$title: $message", technicalDetails)
    }
}


