package com.pdfchemy.app.ui.textconverter

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfchemy.app.logic.TextFormatConverter
import com.pdfchemy.app.logic.TextOutputPublisher
import com.pdfchemy.app.logic.OutputPolicy
import com.pdfchemy.app.logic.LatestRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import com.pdfchemy.app.R

class TextConverterViewModel : ViewModel() {
    private var activeOutput: Uri? = null
    private val imports = LatestRequest()
    private var importJob: Job? = null

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _inputFormat = MutableStateFlow(TextFormatConverter.Format.TXT)
    val inputFormat: StateFlow<TextFormatConverter.Format> = _inputFormat.asStateFlow()

    private val _outputFormat = MutableStateFlow(TextFormatConverter.Format.MD)
    val outputFormat: StateFlow<TextFormatConverter.Format> = _outputFormat.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    sealed class UiState {
        object Idle : UiState()
        object Processing : UiState()
        data class Success(val title: String, val message: String, val outputUri: Uri? = null) : UiState()
        data class Error(val message: String) : UiState()
    }

    fun setInputText(text: String) {
        _inputText.value = text
    }

    fun setInputFormat(format: TextFormatConverter.Format) {
        _inputFormat.value = format
        // Auto-select a valid output format if needed
        val validOutputs = getValidOutputFormats(format)
        if (!validOutputs.contains(_outputFormat.value)) {
            _outputFormat.value = validOutputs.firstOrNull() ?: TextFormatConverter.Format.TXT
        }
    }

    fun setOutputFormat(format: TextFormatConverter.Format) {
        _outputFormat.value = format
    }

    fun getValidOutputFormats(input: TextFormatConverter.Format): List<TextFormatConverter.Format> {
        return TextFormatConverter.Format.values().filter { it != input }
    }

    fun loadFile(context: Context, uri: Uri) {
        if (activeOutput != null) return
        val ticket = imports.begin()
        importJob?.cancel()
        _uiState.value = UiState.Processing
        importJob = viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open input document" }.bufferedReader(Charsets.UTF_8).use { reader ->
                        val stringBuilder = java.lang.StringBuilder()
                        val maxChars = 2_000_000
                        val buffer = CharArray(8192)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(stringBuilder.length <= maxChars - count) { "Input text exceeds size limit" }
                            stringBuilder.append(buffer, 0, count)
                        }
                        stringBuilder.toString()
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!imports.isCurrent(ticket)) return@launch
                _inputText.value = text
                
                // Auto-detect format based on extension
                val path = uri.path?.lowercase() ?: ""
                when {
                    path.endsWith(".md") -> setInputFormat(TextFormatConverter.Format.MD)
                    path.endsWith(".html") || path.endsWith(".htm") -> setInputFormat(TextFormatConverter.Format.HTML)
                    path.endsWith(".csv") -> setInputFormat(TextFormatConverter.Format.CSV)
                    path.endsWith(".tsv") -> setInputFormat(TextFormatConverter.Format.TSV)
                    path.endsWith(".json") -> setInputFormat(TextFormatConverter.Format.JSON)
                    path.endsWith(".yaml") || path.endsWith(".yml") -> setInputFormat(TextFormatConverter.Format.YAML)
                    path.endsWith(".xml") -> setInputFormat(TextFormatConverter.Format.XML)
                    else -> setInputFormat(TextFormatConverter.Format.TXT)
                }
                
                _uiState.value = UiState.Idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (imports.isCurrent(ticket)) _uiState.value = UiState.Error("Failed to read file: ${e.message}")
            } finally {
                if (imports.isCurrent(ticket) && _uiState.value is UiState.Processing) _uiState.value = UiState.Idle
            }
        }
    }

    fun convertAndSave(context: Context, destUri: Uri) {
        if (_uiState.value is UiState.Processing) {
            if (destUri != activeOutput) viewModelScope.launch(Dispatchers.IO) { OutputPolicy.deleteIfFresh(context, destUri) }
            return
        }
        val text = _inputText.value
        val from = _inputFormat.value
        val to = _outputFormat.value
        _uiState.value = UiState.Processing
        activeOutput = destUri

        viewModelScope.launch {
            var outcome: UiState = UiState.Idle
            try {
                require(OutputPolicy.isFreshCopy(destUri)) { "Saving requires a newly created output copy" }
                TextOutputPublisher.publish(context, destUri) {
                    TextFormatConverter.convert(context, text, from, to).getOrThrow()
                }
                outcome = UiState.Success(context.getString(R.string.success_title), context.getString(R.string.success_file_converted), destUri)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                outcome = UiState.Error(error.message ?: "Conversion failed.")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    // Published/claimed/unknown destinations cannot be deleted by this cleanup.
                    OutputPolicy.deleteIfFresh(context, destUri)
                }
                activeOutput = null
                _uiState.value = outcome
            }
        }
    }

    fun dismissMessage() {
        _uiState.value = UiState.Idle
    }

    override fun onCleared() {
        imports.invalidate()
        importJob?.cancel()
        super.onCleared()
    }
}
